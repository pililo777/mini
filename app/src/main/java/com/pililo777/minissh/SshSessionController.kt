package com.pililo777.minissh

import android.util.Log
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Logger
import com.jcraft.jsch.Session
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.Properties
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

data class SshConnectionConfig(
    val host: String,
    val port: Int,
    val user: String,
    val password: CharArray,
    val expectedFingerprint: String?
)

sealed interface SshConnectionState {
    data object Disconnected : SshConnectionState
    data object Connecting : SshConnectionState
    data object Connected : SshConnectionState
    data object Disconnecting : SshConnectionState
    data class Error(val message: String, val cause: Throwable? = null) : SshConnectionState
}

class SshSessionController(private val listener: Listener) : AutoCloseable {
    interface Listener {
        fun onStateChanged(state: SshConnectionState)
        fun onOutput(bytes: ByteArray)
        fun onError(message: String, throwable: Throwable?)
        fun onEof()
    }

    companion object {
        private const val TAG = "SshSessionController"
    }

    private val generation = AtomicLong(0)
    private val writeExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    @Volatile private var closed = false
    @Volatile private var state: SshConnectionState = SshConnectionState.Disconnected
    @Volatile private var session: Session? = null
    @Volatile private var shell: ChannelShell? = null
    @Volatile private var output: OutputStream? = null

    init {
        JSch.setLogger(object : Logger {
            override fun isEnabled(level: Int): Boolean = true
            override fun log(level: Int, message: String?) {
                Log.d(TAG, "JSCH level=$level ${message ?: ""}")
            }
        })
    }

    fun connect(config: SshConnectionConfig) {
        if (closed) return
        disconnect("new_connection", notify = false)
        val id = generation.incrementAndGet()
        val passwordCopy = config.password.copyOf()
        val ownedConfig = config.copy(password = passwordCopy)
        setState(SshConnectionState.Connecting)
        Thread({ connectAndRead(ownedConfig, id) }, "MiniSSH-SSH-$id").start()
    }

    private fun connectAndRead(config: SshConnectionConfig, id: Long) {
        var localSession: Session? = null
        var localShell: ChannelShell? = null
        val repository = PinnedHostKeyRepository(config.expectedFingerprint ?: "")
        try {
            localSession = JSch().getSession(config.user, config.host, config.port).apply {
                setHostKeyRepository(repository)
                setPassword(String(config.password))
                setConfig(Properties().apply {
                    put("StrictHostKeyChecking", "yes")
                    put("PreferredAuthentications", "password,keyboard-interactive")
                })
                setConfig("cipher.c2s", "aes128-ctr")
                setConfig("cipher.s2c", "aes128-ctr")
                setConfig("mac.c2s", "hmac-sha2-256")
                setConfig("mac.s2c", "hmac-sha2-256")
                setConfig("compression.c2s", "none")
                setConfig("compression.s2c", "none")
                setServerAliveInterval(30_000)
                setServerAliveCountMax(3)
                connect(15_000)
            }
            log(id, "session connected id=${System.identityHashCode(localSession)}")
            localShell = localSession.openChannel("shell") as ChannelShell
            // Dynamic setPtySize() calls corrupted JSch/Android transport; keep PTY sizing disabled.
            localShell.setPty(true)
            localShell.setPtyType("xterm")
            val input = localShell.inputStream
            val localOutput = localShell.outputStream
            localShell.connect(8_000)
            if (!isCurrent(id)) return
            session = localSession
            shell = localShell
            output = localOutput
            log(id, "shell connected sessionConnected=${localSession.isConnected} channelConnected=${localShell.isConnected}")
            setState(SshConnectionState.Connected)
            log(id, "read loop started")
            val buffer = ByteArray(4096)
            while (isCurrent(id) && localShell.isConnected) {
                val count = input.read(buffer)
                if (count < 0) {
                    log(id, "read eof state=${channelState(localSession, localShell)}")
                    listener.onEof()
                    break
                }
                if (count > 0) {
                    log(id, "read bytes=$count")
                    listener.onOutput(buffer.copyOf(count))
                }
            }
            log(id, "read loop finished state=${channelState(localSession, localShell)}")
        } catch (e: Exception) {
            if (isCurrent(id)) {
                log(id, "connect/read exception ${e.javaClass.name}: ${e.message}\n${Log.getStackTraceString(e)}")
                val received = repository.lastReceivedFingerprint
                val message = if (received != null && config.expectedFingerprint != null &&
                    !PinnedHostKeyRepository.same(config.expectedFingerprint, received)) {
                    "BLOQUEADO: huella SSH distinta. Esperada: ${config.expectedFingerprint} Recibida: $received"
                } else e.message ?: e.javaClass.simpleName
                setState(SshConnectionState.Error(message, e))
                listener.onError(message, e)
            }
        } finally {
            config.password.fill('\u0000')
            log(id, "cleanup state=${channelState(localSession, localShell)}")
            try { localShell?.disconnect() } catch (e: Exception) { log(id, "shell cleanup exception ${e.message}") }
            try { localSession?.disconnect() } catch (e: Exception) { log(id, "session cleanup exception ${e.message}") }
            if (isCurrent(id)) {
                session = null
                shell = null
                output = null
                setState(SshConnectionState.Disconnected)
            }
        }
    }

    fun send(bytes: ByteArray, reason: String) {
        val copy = bytes.copyOf()
        val id = generation.get()
        val stream = output
        if (!isConnected() || stream == null) {
            log(id, "send rejected reason=$reason size=${copy.size}")
            listener.onError("No hay terminal SSH conectada.", null)
            return
        }
        log(id, "send queued reason=$reason bytes=${hex(copy)}")
        writeExecutor.execute {
            if (!isCurrent(id) || output !== stream) return@execute
            try {
                synchronized(stream) {
                    stream.write(copy)
                    log(id, "send write_ok reason=$reason")
                    stream.flush()
                    log(id, "send flush_ok reason=$reason")
                }
            } catch (e: Exception) {
                log(id, "send exception ${Log.getStackTraceString(e)}")
                listener.onError("Error enviando bytes: ${e.message ?: e.javaClass.simpleName}", e)
            }
        }
    }

    fun sendCommand(command: String) {
        val payload = command.toByteArray(StandardCharsets.UTF_8) + byteArrayOf('\r'.code.toByte())
        log(generation.get(), "sendEnter commandLength=${command.length} bytes=${hex(payload)} terminator=CR")
        send(payload, "command")
    }

    fun sendControl(byte: Byte) = send(byteArrayOf(byte), "control")

    fun disconnect(reason: String = "user", notify: Boolean = true) {
        val id = generation.incrementAndGet()
        setState(SshConnectionState.Disconnecting)
        log(id, "disconnect requested reason=$reason")
        try { shell?.disconnect() } catch (e: Exception) { log(id, "disconnect shell exception ${e.message}") }
        try { session?.disconnect() } catch (e: Exception) { log(id, "disconnect session exception ${e.message}") }
        shell = null
        session = null
        output = null
        if (notify) setState(SshConnectionState.Disconnected)
    }

    fun currentState(): SshConnectionState = state
    fun isConnected(): Boolean = state == SshConnectionState.Connected && shell?.isConnected == true

    override fun close() {
        if (closed) return
        closed = true
        disconnect("close")
        writeExecutor.shutdownNow()
        listener.onStateChanged(SshConnectionState.Disconnected)
    }

    private fun isCurrent(id: Long): Boolean = !closed && generation.get() == id
    private fun setState(newState: SshConnectionState) { state = newState; if (!closed) listener.onStateChanged(newState) }
    private fun log(id: Long, message: String) = Log.d(TAG, "conn=$id $message")
    private fun hex(bytes: ByteArray) = bytes.joinToString(" ") { String.format("%02X", it) }
    private fun channelState(s: Session?, c: ChannelShell?) =
        "session=${System.identityHashCode(s)} sessionConnected=${s?.isConnected} channel=${System.identityHashCode(c)} channelConnected=${c?.isConnected} channelClosed=${c?.isClosed} eof=${c?.isEOF}"
}

package com.pililo777.minissh

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.Logger
import java.nio.charset.StandardCharsets
import java.util.Properties
import java.util.concurrent.Executors

class MainActivity : Activity() {
    companion object {
        private const val VPN_REQUEST = 2201
        private const val TAG = "MiniSSH"
    }

    private lateinit var hostField: EditText
    private lateinit var portField: EditText
    private lateinit var userField: EditText
    private lateinit var passwordField: EditText
    private lateinit var fingerprintField: EditText
    private lateinit var connectButton: Button
    private lateinit var vpnButton: Button
    private lateinit var disconnectAllButton: Button
    private lateinit var vpnStatusView: TextView
    private lateinit var terminalView: TextView
    private lateinit var terminalScroll: ScrollView
    private lateinit var bottomInsetSpacer: View
    private lateinit var commandField: EditText
    private lateinit var ctrlButton: Button
    private lateinit var sendButton: Button
    private val terminalBridge = TerminalBridge()
    private val sshWriteExecutor = Executors.newSingleThreadExecutor()

    @Volatile private var session: Session? = null
    @Volatile private var shell: ChannelShell? = null
    @Volatile private var shellOutput: java.io.OutputStream? = null
    @Volatile private var terminalConnected = false
    @Volatile private var connectionGeneration = 0L
    @Volatile private var disconnectReason = "none"

    private val statusHandler = Handler(Looper.getMainLooper())
    private var pendingVpnConfig: VpnConfig? = null
    private var ctrlArmed = false
    private var internalCommandEdit = false
    private var lastSendTriggerAt = 0L
    private lateinit var rootView: LinearLayout

    private val statusPoll = object : Runnable {
        override fun run() {
            refreshVpnStatus()
            statusHandler.postDelayed(this, 1_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        JSch.setLogger(object : Logger {
            override fun isEnabled(level: Int): Boolean = true
            override fun log(level: Int, message: String?) {
                debug("JSCH level=$level ${message ?: ""}")
            }
        })
        debug("onCreate")
        buildUi()
        restoreConnectionData()
        appendTerminal("Mini SSH 0.4 listo. SSH y VPN requieren huella SHA-256.\n")
        statusHandler.post(statusPoll)
    }

    private fun buildUi() {
        WindowCompat.setDecorFitsSystemWindows(window, false)

        rootView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(Color.rgb(20, 20, 20))
        }

        val basePaddingLeft = rootView.paddingLeft
        val basePaddingTop = rootView.paddingTop
        val basePaddingRight = rootView.paddingRight
        val basePaddingBottom = rootView.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val bottomInset = maxOf(bars.bottom, ime.bottom)

            view.setPadding(
                basePaddingLeft + bars.left,
                basePaddingTop + bars.top,
                basePaddingRight + bars.right,
                basePaddingBottom + bottomInset
            )
            bottomInsetSpacer.layoutParams = (bottomInsetSpacer.layoutParams as LinearLayout.LayoutParams).apply {
                height = bottomInset
            }
            bottomInsetSpacer.requestLayout()
            insets
        }

        rootView.addView(TextView(this).apply {
            text = "Mini SSH"
            textSize = 24f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, dp(8))
        })

        val hostRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        hostField = field("Servidor / IP")
        portField = field("Puerto", InputType.TYPE_CLASS_NUMBER).apply { setText("22") }
        hostRow.addView(hostField, LinearLayout.LayoutParams(0, dp(52), 1f))
        hostRow.addView(portField, LinearLayout.LayoutParams(dp(92), dp(52)).apply { marginStart = dp(8) })
        rootView.addView(hostRow)

        userField = field("Usuario")
        passwordField = field("Contraseña", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        fingerprintField = field("Huella SHA256:...")
        rootView.addView(userField, fullWidth52())
        rootView.addView(passwordField, fullWidth52())
        rootView.addView(fingerprintField, fullWidth52())

        connectButton = Button(this).apply {
            text = "CONECTAR TERMINAL"
            setOnClickListener {
                debug("UI_ACTION terminal_${if (shell?.isConnected == true) "disconnect" else "connect"}_button thread=${Thread.currentThread().name} ${channelState(session, shell)}")
                if (shell?.isConnected == true) disconnectTerminal() else connectTerminal()
            }
        }
        rootView.addView(connectButton)

        vpnStatusView = TextView(this).apply {
            text = "VPN: desconectada"
            setTextColor(Color.LTGRAY)
            setPadding(dp(4), dp(8), dp(4), dp(4))
        }
        rootView.addView(vpnStatusView)

        val vpnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        vpnButton = Button(this).apply {
            text = "ACTIVAR VPN"
            setOnClickListener {
                val state = getSharedPreferences("vpn", MODE_PRIVATE).getString("state", "off")
                if (state == "on" || state == "connecting") disconnectVpn() else requestVpn()
            }
        }
        disconnectAllButton = Button(this).apply {
            text = "DESCONECTAR TODO"
            setOnClickListener {
                disconnectTerminal()
                disconnectVpn()
                appendTerminal("\nTerminal y VPN desconectadas. Android vuelve a su conexión normal.\n")
            }
        }
        vpnRow.addView(vpnButton, LinearLayout.LayoutParams(0, dp(52), 1f))
        vpnRow.addView(disconnectAllButton, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginStart = dp(8) })
        rootView.addView(vpnRow)

        terminalView = TextView(this).apply {
            textSize = 14f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(Color.rgb(90, 255, 120))
            setBackgroundColor(Color.BLACK)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setTextIsSelectable(true)
            setOnClickListener {
                if (shell?.isConnected == true) focusCommandInput()
            }
        }
        terminalScroll = ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(terminalView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        rootView.addView(terminalScroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ).apply { topMargin = dp(8) })

        bottomInsetSpacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0
            )
        }
        rootView.addView(bottomInsetSpacer)

        val commandRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        ctrlButton = Button(this).apply {
            text = "CTRL"
            isEnabled = false
            setOnClickListener {
                setCtrlArmed(!ctrlArmed)
                focusCommandInput()
            }
        }
        commandField = field("Comando").apply {
            imeOptions = EditorInfo.IME_ACTION_SEND
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

                override fun afterTextChanged(s: Editable?) {
                    if (!ctrlArmed || internalCommandEdit || s.isNullOrEmpty()) return
                    val controlCode = controlCodeFor(s.last()) ?: return

                    internalCommandEdit = true
                    try {
                        s.delete(s.length - 1, s.length)
                    } finally {
                        internalCommandEdit = false
                    }
                    setCtrlArmed(false)
                    sendRawControl(controlCode)
                }
            })
            setOnEditorActionListener { _, actionId, event ->
                val enterPressed = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
                when {
                    actionId == EditorInfo.IME_ACTION_SEND -> {
                        sendCommand()
                        true
                    }
                    enterPressed -> {
                        sendCommand()
                        true
                    }
                    else -> false
                }
            }
        }
        sendButton = Button(this).apply {
            text = "ENVIAR"
            isEnabled = false
            setOnClickListener {
                debug("UI_ACTION send_button thread=${Thread.currentThread().name} ${channelState(session, shell)}")
                sendCommand()
            }
        }
        commandRow.addView(ctrlButton, LinearLayout.LayoutParams(dp(78), dp(52)))
        commandRow.addView(commandField, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginStart = dp(6) })
        commandRow.addView(sendButton, LinearLayout.LayoutParams(dp(104), dp(52)).apply { marginStart = dp(6) })
        rootView.addView(commandRow)

        setContentView(rootView)
        rootView.post { ViewCompat.requestApplyInsets(rootView) }
    }

    private fun fullWidth52() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52))

    private fun field(hintText: String, inputTypeValue: Int = InputType.TYPE_CLASS_TEXT): EditText =
        EditText(this).apply {
            hint = hintText
            inputType = inputTypeValue
            isSingleLine = true
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setBackgroundColor(Color.rgb(45, 45, 45))
            setPadding(dp(10), 0, dp(10), 0)
        }

    private fun readConfig(requirePassword: Boolean = true): VpnConfig? {
        val host = hostField.text.toString().trim()
        val user = userField.text.toString().trim()
        val password = passwordField.text.toString()
        val port = portField.text.toString().toIntOrNull()
        val fingerprint = PinnedHostKeyRepository.normalize(fingerprintField.text.toString())
        if (host.isBlank() || user.isBlank() || port == null || port !in 1..65535 || fingerprint == null) return null
        if (requirePassword && password.isEmpty()) return null
        return VpnConfig(host, port, user, password, fingerprint)
    }

    private fun connectTerminal() {
        val cfg = readConfig() ?: run {
            appendTerminal("\nCompleta servidor, puerto, usuario, contraseña y huella SHA256.\n")
            return
        }
        debug("connectTerminal host=${cfg.host} port=${cfg.port} user=${cfg.user}")
        connectButton.isEnabled = false
        appendTerminal("\nVerificando ${cfg.host} y abriendo terminal SSH...\n")

        val generation = synchronized(this) { ++connectionGeneration }
        disconnectReason = "none"
        Thread {
            var localSession: Session? = null
            var localShell: ChannelShell? = null
            val repository = PinnedHostKeyRepository(cfg.fingerprint)
            try {
                localSession = JSch().getSession(cfg.user, cfg.host, cfg.port).apply {
                    setHostKeyRepository(repository)
                    setPassword(cfg.password)
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
                debug("conn=$generation session connected id=${System.identityHashCode(localSession)} thread=${Thread.currentThread().name}")
                localShell = localSession.openChannel("shell") as ChannelShell
                // Dynamic setPtySize() calls corrupted JSch/Android transport; keep PTY sizing disabled until safely proven.
                localShell.setPty(true)
                localShell.setPtyType("xterm")
                val input = localShell.inputStream
                val output = localShell.outputStream
                localShell.connect(8_000)
                debug("conn=$generation shell connected id=${System.identityHashCode(localShell)} sessionConnected=${localSession.isConnected} channelConnected=${localShell.isConnected}")

                session = localSession
                shell = localShell
                shellOutput = output
                terminalConnected = true
                runOnUiThread {
                    saveConnectionData(cfg)
                    passwordField.text.clear()
                    updateTerminalControls(true)
                    appendTerminal("Huella verificada. Terminal conectada.\n\n")
                    focusCommandInput()
                }

                val buffer = ByteArray(4096)
                debug("conn=$generation read loop started thread=${Thread.currentThread().name}")
                while (localShell.isConnected && generation == connectionGeneration) {
                    val count = input.read(buffer)
                    if (count < 0) {
                        debug("conn=$generation read eof ${channelState(localSession, localShell)} reason=$disconnectReason")
                        break
                    }
                    if (count > 0) {
                        debug("read bytes=$count")
                        val preview = buffer.take(count.coerceAtMost(64)).joinToString(" ") {
                            String.format("%02X", it)
                        }
                        debug("read preview=$preview")
                        onRemoteBytes(buffer, count)
                    }
                }
                debug("conn=$generation read loop finished ${channelState(localSession, localShell)} reason=$disconnectReason")
            } catch (e: Exception) {
                debug("conn=$generation connect/read exception ${e.javaClass.name}: ${e.message}\n${Log.getStackTraceString(e)} state=${channelState(localSession, localShell)}")
                val received = repository.lastReceivedFingerprint
                if (received != null && !PinnedHostKeyRepository.same(cfg.fingerprint, received)) {
                    appendTerminal("\nBLOQUEADO: huella SSH distinta.\nEsperada: ${cfg.fingerprint}\nRecibida: $received\n")
                } else {
                    appendTerminal("\nError SSH: ${e.message ?: e.javaClass.simpleName}\n")
                }
            } finally {
                debug("conn=$generation finally reason=$disconnectReason localShellConnected=${localShell?.isConnected == true} sameShell=${shell === localShell}")
                try { localShell?.disconnect() } catch (e: Exception) { debug("conn=$generation shell disconnect exception ${Log.getStackTraceString(e)}") }
                try { localSession?.disconnect() } catch (e: Exception) { debug("conn=$generation session disconnect exception ${Log.getStackTraceString(e)}") }
                if (shell === localShell) {
                    shell = null
                    session = null
                    shellOutput = null
                    terminalConnected = false
                    runOnUiThread { showTerminalDisconnected() }
                } else {
                    runOnUiThread { connectButton.isEnabled = true }
                }
            }
        }.start()
    }

    private fun focusCommandInput() {
        commandField.requestFocus()
        commandField.setSelection(commandField.text.length)
        commandField.postDelayed({
            val keyboard = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
            keyboard?.showSoftInput(commandField, InputMethodManager.SHOW_IMPLICIT)
        }, 120)
    }

    private fun setCtrlArmed(armed: Boolean) {
        ctrlArmed = armed
        ctrlButton.text = if (armed) "CTRL*" else "CTRL"
    }

    private fun controlCodeFor(character: Char): Int? {
        val upper = character.uppercaseChar()
        return when {
            upper in 'A'..'Z' -> upper.code - 'A'.code + 1
            character == '@' -> 0
            character == '[' -> 27
            character == '\\' -> 28
            character == ']' -> 29
            character == '^' -> 30
            character == '_' -> 31
            character == '?' -> 127
            else -> null
        }
    }

    private fun sendRawControl(controlCode: Int) {
        val output = shellOutput
        if (output == null || shell?.isConnected != true) {
            debug("sendRawControl rejected controlCode=$controlCode shellConnected=${shell?.isConnected == true}")
            appendTerminal("\nNo hay terminal SSH conectada.\n")
            return
        }
        sshWriteExecutor.execute {
            try {
                debug("sendRawControl write controlCode=$controlCode")
                synchronized(output) {
                    output.write(byteArrayOf(controlCode.toByte()))
                    output.flush()
                }
            } catch (e: Exception) {
                debug("sendRawControl exception ${e.javaClass.simpleName}: ${e.message}")
                appendTerminal("\nError enviando CTRL: ${e.message ?: e.javaClass.simpleName}\n")
            }
        }
    }

    private fun sendBytes(bytes: ByteArray) {
        val output = shellOutput
        val generation = connectionGeneration
        val queuedAt = SystemClock.elapsedRealtime()
        if (output == null || shell?.isConnected != true) {
            debug("sendBytes rejected size=${bytes.size} shellConnected=${shell?.isConnected == true}")
            appendTerminal("\nNo hay terminal SSH conectada.\n")
            return
        }
        debug("sendBytes queued conn=$generation at=$queuedAt output=${System.identityHashCode(output)} hex=${bytes.joinToString(" ") { String.format("%02X", it) }} state=${channelState(session, shell)}")
        sshWriteExecutor.execute {
            try {
                val preview = bytes.take(8).joinToString(" ") { String.format("%02X", it) }
                debug("sendBytes write_begin conn=$generation at=${SystemClock.elapsedRealtime()} size=${bytes.size} hex=${bytes.joinToString(" ") { String.format("%02X", it) }} output=${System.identityHashCode(output)} thread=${Thread.currentThread().name} state=${channelState(session, shell)}")
                synchronized(output) {
                    output.write(bytes)
                    debug("sendBytes write_ok conn=$generation at=${SystemClock.elapsedRealtime()}")
                    output.flush()
                    debug("sendBytes flush_ok conn=$generation at=${SystemClock.elapsedRealtime()} state=${channelState(session, shell)}")
                }
            } catch (e: Exception) {
                debug("sendBytes exception conn=$generation ${e.javaClass.name}: ${e.message} at=${SystemClock.elapsedRealtime()} state=${channelState(session, shell)}\n${Log.getStackTraceString(e)}")
                appendTerminal("\nError enviando bytes: ${e.message ?: e.javaClass.simpleName}\n")
            }
        }
    }

    private fun sendEnter() {
        val command = commandField.text.toString()
        // A terminal Enter is carriage return; Windows PTYs otherwise may only display LF without executing the line.
        val payload = command.toByteArray(StandardCharsets.UTF_8) + byteArrayOf('\r'.code.toByte())
        debug("sendEnter commandLength=${command.length} commandPreview=${command.take(48).replace(Regex("[\\r\\n]"), "?")} bytes=${payload.joinToString(" ") { String.format("%02X", it) }} terminator=CR")
        internalCommandEdit = true
        try {
            commandField.text.clear()
        } finally {
            internalCommandEdit = false
        }
        sendBytes(payload)
        focusCommandInput()
    }

    private fun requestVpn() {
        val cfg = readConfig() ?: run {
            appendTerminal("\nPara activar VPN completa los datos SSH y la contraseña.\n")
            return
        }
        saveConnectionData(cfg)
        pendingVpnConfig = cfg
        val prepare = VpnService.prepare(this)
        if (prepare != null) {
            startActivityForResult(prepare, VPN_REQUEST)
        } else {
            startVpn(cfg)
            pendingVpnConfig = null
        }
    }

    @Deprecated("Deprecated in Android API; kept for minSdk compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST) {
            val cfg = pendingVpnConfig
            pendingVpnConfig = null
            if (resultCode == RESULT_OK && cfg != null) startVpn(cfg)
            else appendTerminal("\nAndroid no autorizó la VPN.\n")
        }
    }

    private fun startVpn(cfg: VpnConfig) {
        val intent = Intent(this, TProxyService::class.java)
            .setAction(TProxyService.ACTION_CONNECT)
            .putExtra(TProxyService.EXTRA_HOST, cfg.host)
            .putExtra(TProxyService.EXTRA_PORT, cfg.port)
            .putExtra(TProxyService.EXTRA_USER, cfg.user)
            .putExtra(TProxyService.EXTRA_PASSWORD, cfg.password)
            .putExtra(TProxyService.EXTRA_FINGERPRINT, cfg.fingerprint)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        passwordField.text.clear()
        appendTerminal("\nActivando VPN SSH. La VPN solo se establecerá cuando SSH esté listo.\n")
    }

    private fun disconnectVpn() {
        startService(Intent(this, TProxyService::class.java).setAction(TProxyService.ACTION_DISCONNECT))
    }

    private fun refreshVpnStatus() {
        val prefs = getSharedPreferences("vpn", MODE_PRIVATE)
        val state = prefs.getString("state", "off") ?: "off"
        val message = prefs.getString("message", "Desconectada") ?: "Desconectada"
        vpnStatusView.text = "VPN: $message"
        vpnButton.text = if (state == "on" || state == "connecting") "DESACTIVAR VPN" else "ACTIVAR VPN"
    }

    private fun sendCommand() {
        val now = SystemClock.uptimeMillis()
        if (now - lastSendTriggerAt < 250) return
        lastSendTriggerAt = now
        debug("sendCommand triggered conn=$connectionGeneration terminalConnected=$terminalConnected shellConnected=${shell?.isConnected == true} outputReady=${shellOutput != null} thread=${Thread.currentThread().name} state=${channelState(session, shell)}")
        if (!terminalConnected || shell?.isConnected != true || shellOutput == null) {
            appendTerminal("\nNo hay terminal SSH conectada.\n")
            return
        }
        setCtrlArmed(false)
        sendEnter()
    }

    private fun disconnectTerminal() {
        disconnectReason = "user_or_activity"
        synchronized(this) { ++connectionGeneration }
        debug("disconnectTerminal requested thread=${Thread.currentThread().name} conn=$connectionGeneration reason=$disconnectReason ${channelState(session, shell)}\n${Throwable().stackTraceToString()}")
        try { shell?.disconnect() } catch (e: Exception) { debug("disconnect shell exception ${Log.getStackTraceString(e)}") }
        try { session?.disconnect() } catch (e: Exception) { debug("disconnect session exception ${Log.getStackTraceString(e)}") }
        shell = null
        session = null
        shellOutput = null
        terminalConnected = false
        showTerminalDisconnected()
    }

    private fun showTerminalDisconnected() {
        updateTerminalControls(false)
    }

    private fun updateTerminalControls(connected: Boolean) {
        connectButton.isEnabled = true
        connectButton.text = if (connected) "DESCONECTAR TERMINAL" else "CONECTAR TERMINAL"
        setCtrlArmed(false)
        ctrlButton.isEnabled = connected
        sendButton.isEnabled = connected
    }

    private fun saveConnectionData(cfg: VpnConfig) {
        getSharedPreferences("connection", MODE_PRIVATE).edit()
            .putString("host", cfg.host)
            .putInt("port", cfg.port)
            .putString("user", cfg.user)
            .putString("fingerprint", cfg.fingerprint)
            .apply()
    }

    private fun restoreConnectionData() {
        val prefs = getSharedPreferences("connection", MODE_PRIVATE)
        hostField.setText(prefs.getString("host", ""))
        portField.setText(prefs.getInt("port", 22).toString())
        userField.setText(prefs.getString("user", ""))
        fingerprintField.setText(prefs.getString("fingerprint", ""))
    }

    private fun appendTerminal(text: String) {
        runOnUiThread {
            terminalView.text = text
            terminalScroll.post { terminalScroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun onRemoteBytes(bytes: ByteArray, length: Int) {
        terminalBridge.feed(bytes, length)
        appendTerminal(terminalBridge.render())
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        statusHandler.removeCallbacks(statusPoll)
        debug("onDestroy")
        disconnectTerminal()
        super.onDestroy()
    }

    private fun debug(message: String) {
        Log.d(TAG, message)
    }

    private fun channelState(s: Session?, c: ChannelShell?): String =
        "session=${System.identityHashCode(s)} sessionConnected=${s?.isConnected} channel=${System.identityHashCode(c)} channelConnected=${c?.isConnected} channelClosed=${c?.isClosed} eof=${c?.isEOF} exitStatus=${c?.exitStatus}"

    private data class VpnConfig(
        val host: String,
        val port: Int,
        val user: String,
        val password: String,
        val fingerprint: String
    )

}

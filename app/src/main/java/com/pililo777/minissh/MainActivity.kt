package com.pililo777.minissh

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.Properties

class MainActivity : Activity() {
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
    private lateinit var commandField: EditText
    private lateinit var sendButton: Button

    @Volatile private var session: Session? = null
    @Volatile private var shell: ChannelShell? = null
    @Volatile private var shellOutput: OutputStream? = null

    private val statusHandler = Handler(Looper.getMainLooper())
    private var pendingVpnConfig: VpnConfig? = null

    private val statusPoll = object : Runnable {
        override fun run() {
            refreshVpnStatus()
            statusHandler.postDelayed(this, 1_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        restoreConnectionData()
        appendTerminal("Mini SSH 0.4 listo. SSH y VPN requieren huella SHA-256.\n")
        statusHandler.post(statusPoll)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(Color.rgb(20, 20, 20))
        }

        root.addView(TextView(this).apply {
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
        root.addView(hostRow)

        userField = field("Usuario")
        passwordField = field("Contraseña", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        fingerprintField = field("Huella SHA256:...")
        root.addView(userField, fullWidth52())
        root.addView(passwordField, fullWidth52())
        root.addView(fingerprintField, fullWidth52())

        connectButton = Button(this).apply {
            text = "CONECTAR TERMINAL"
            setOnClickListener { if (shell?.isConnected == true) disconnectTerminal() else connectTerminal() }
        }
        root.addView(connectButton)

        vpnStatusView = TextView(this).apply {
            text = "VPN: desconectada"
            setTextColor(Color.LTGRAY)
            setPadding(dp(4), dp(8), dp(4), dp(4))
        }
        root.addView(vpnStatusView)

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
        root.addView(vpnRow)

        terminalView = TextView(this).apply {
            textSize = 14f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(Color.rgb(90, 255, 120))
            setBackgroundColor(Color.BLACK)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setTextIsSelectable(true)
        }
        terminalScroll = ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(terminalView, ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT
            ))
        }
        root.addView(terminalScroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ).apply { topMargin = dp(8) })

        val commandRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        commandField = field("Comando").apply {
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    sendCommand()
                    true
                } else false
            }
        }
        sendButton = Button(this).apply {
            text = "ENVIAR"
            isEnabled = false
            setOnClickListener { sendCommand() }
        }
        commandRow.addView(commandField, LinearLayout.LayoutParams(0, dp(52), 1f))
        commandRow.addView(sendButton, LinearLayout.LayoutParams(dp(110), dp(52)).apply { marginStart = dp(8) })
        root.addView(commandRow)

        setContentView(root)
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
        connectButton.isEnabled = false
        appendTerminal("\nVerificando ${cfg.host} y abriendo terminal SSH...\n")

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
                    connect(15_000)
                }
                localShell = localSession.openChannel("shell") as ChannelShell
                localShell.setPty(true)
                localShell.setPtyType("dumb")
                val input = localShell.inputStream
                val output = localShell.outputStream
                localShell.connect(8_000)

                session = localSession
                shell = localShell
                shellOutput = output
                runOnUiThread {
                    saveConnectionData(cfg)
                    passwordField.text.clear()
                    connectButton.isEnabled = true
                    connectButton.text = "DESCONECTAR TERMINAL"
                    sendButton.isEnabled = true
                    appendTerminal("Huella verificada. Terminal conectada.\n\n")
                }

                val buffer = ByteArray(4096)
                while (localShell.isConnected) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count > 0) appendTerminal(String(buffer, 0, count, StandardCharsets.UTF_8))
                }
            } catch (e: Exception) {
                val received = repository.lastReceivedFingerprint
                if (received != null && !PinnedHostKeyRepository.same(cfg.fingerprint, received)) {
                    appendTerminal("\nBLOQUEADO: huella SSH distinta.\nEsperada: ${cfg.fingerprint}\nRecibida: $received\n")
                } else {
                    appendTerminal("\nError SSH: ${e.message ?: e.javaClass.simpleName}\n")
                }
            } finally {
                try { localShell?.disconnect() } catch (_: Exception) { }
                try { localSession?.disconnect() } catch (_: Exception) { }
                if (shell === localShell) {
                    shell = null
                    session = null
                    shellOutput = null
                    runOnUiThread { showTerminalDisconnected() }
                } else {
                    runOnUiThread { connectButton.isEnabled = true }
                }
            }
        }.start()
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
        val command = commandField.text.toString()
        if (command.isEmpty()) return
        commandField.text.clear()
        val output = shellOutput
        if (output == null || shell?.isConnected != true) {
            appendTerminal("\nNo hay terminal SSH conectada.\n")
            return
        }
        Thread {
            try {
                output.write((command + "\n").toByteArray(StandardCharsets.UTF_8))
                output.flush()
            } catch (e: Exception) {
                appendTerminal("\nError enviando comando: ${e.message ?: e.javaClass.simpleName}\n")
            }
        }.start()
    }

    private fun disconnectTerminal() {
        try { shell?.disconnect() } catch (_: Exception) { }
        try { session?.disconnect() } catch (_: Exception) { }
        shell = null
        session = null
        shellOutput = null
        showTerminalDisconnected()
    }

    private fun showTerminalDisconnected() {
        connectButton.isEnabled = true
        connectButton.text = "CONECTAR TERMINAL"
        sendButton.isEnabled = false
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
            terminalView.append(text)
            if (terminalView.length() > 100_000) {
                val keepFrom = terminalView.length() - 80_000
                terminalView.text = terminalView.text.subSequence(keepFrom, terminalView.length())
            }
            terminalScroll.post { terminalScroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        statusHandler.removeCallbacks(statusPoll)
        disconnectTerminal()
        super.onDestroy()
    }

    private data class VpnConfig(
        val host: String,
        val port: Int,
        val user: String,
        val password: String,
        val fingerprint: String
    )

    companion object {
        private const val VPN_REQUEST = 2201
    }
}

package com.pililo777.minissh

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
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
    private lateinit var connectButton: Button
    private lateinit var terminalView: TextView
    private lateinit var terminalScroll: ScrollView
    private lateinit var commandField: EditText
    private lateinit var sendButton: Button

    @Volatile private var session: Session? = null
    @Volatile private var shell: ChannelShell? = null
    @Volatile private var shellOutput: OutputStream? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        appendTerminal("Mini SSH listo. Introduce los datos de tu servidor.\n")
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(Color.rgb(20, 20, 20))
        }

        val title = TextView(this).apply {
            text = "Mini SSH"
            textSize = 24f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, dp(8))
        }
        root.addView(title)

        val hostRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        hostField = field("Servidor / IP")
        portField = field("Puerto", InputType.TYPE_CLASS_NUMBER).apply { setText("22") }
        hostRow.addView(hostField, LinearLayout.LayoutParams(0, dp(52), 1f))
        hostRow.addView(portField, LinearLayout.LayoutParams(dp(92), dp(52)).apply { marginStart = dp(8) })
        root.addView(hostRow)

        userField = field("Usuario")
        passwordField = field(
            "Contraseña",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        )
        root.addView(userField, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)))
        root.addView(passwordField, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)))

        connectButton = Button(this).apply {
            text = "CONECTAR"
            setOnClickListener {
                if (shell?.isConnected == true) disconnect() else connect()
            }
        }
        root.addView(connectButton)

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
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
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

    private fun connect() {
        val host = hostField.text.toString().trim()
        val user = userField.text.toString().trim()
        val password = passwordField.text.toString()
        val port = portField.text.toString().toIntOrNull()

        if (host.isBlank() || user.isBlank() || password.isEmpty() || port == null || port !in 1..65535) {
            appendTerminal("\nDatos de conexión incompletos o puerto inválido.\n")
            return
        }

        connectButton.isEnabled = false
        appendTerminal("\nConectando a $user@$host:$port ...\n")

        Thread {
            var localSession: Session? = null
            var localShell: ChannelShell? = null
            try {
                val jsch = JSch()
                localSession = jsch.getSession(user, host, port).apply {
                    setPassword(password)
                    setConfig(Properties().apply {
                        put("StrictHostKeyChecking", "no")
                        put("PreferredAuthentications", "password,keyboard-interactive")
                    })
                    connect(12_000)
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
                    passwordField.text.clear()
                    connectButton.isEnabled = true
                    connectButton.text = "DESCONECTAR"
                    sendButton.isEnabled = true
                    appendTerminal("Conectado.\n")
                    appendTerminal("AVISO MVP: la huella del servidor todavía no se verifica.\n\n")
                }

                val buffer = ByteArray(4096)
                while (localShell.isConnected) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count > 0) {
                        appendTerminal(String(buffer, 0, count, StandardCharsets.UTF_8))
                    }
                }
            } catch (e: Exception) {
                appendTerminal("\nError SSH: ${e.message ?: e.javaClass.simpleName}\n")
            } finally {
                try { localShell?.disconnect() } catch (_: Exception) {}
                try { localSession?.disconnect() } catch (_: Exception) {}
                if (shell === localShell) {
                    shell = null
                    session = null
                    shellOutput = null
                    runOnUiThread { showDisconnected() }
                } else {
                    runOnUiThread { connectButton.isEnabled = true }
                }
            }
        }.start()
    }

    private fun sendCommand() {
        val command = commandField.text.toString()
        if (command.isEmpty()) return
        commandField.text.clear()

        val output = shellOutput
        if (output == null || shell?.isConnected != true) {
            appendTerminal("\nNo hay una sesión SSH conectada.\n")
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

    private fun disconnect() {
        appendTerminal("\nDesconectando...\n")
        try { shell?.disconnect() } catch (_: Exception) {}
        try { session?.disconnect() } catch (_: Exception) {}
        shell = null
        session = null
        shellOutput = null
        showDisconnected()
    }

    private fun showDisconnected() {
        connectButton.isEnabled = true
        connectButton.text = "CONECTAR"
        sendButton.isEnabled = false
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
        try { shell?.disconnect() } catch (_: Exception) {}
        try { session?.disconnect() } catch (_: Exception) {}
        super.onDestroy()
    }
}

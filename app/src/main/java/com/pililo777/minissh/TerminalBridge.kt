package com.pililo777.minissh

import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TerminalSessionClient

internal class TerminalBridge {
    private val output = object : TerminalOutput() {
        override fun write(data: ByteArray, offset: Int, count: Int) = Unit
        override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit
        override fun onCopyTextToClipboard(text: String?) = Unit
        override fun onPasteTextFromClipboard() = Unit
        override fun onBell() = Unit
        override fun onColorsChanged() = Unit
    }

    private val client = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: com.termux.terminal.TerminalSession) = Unit
        override fun onTitleChanged(changedSession: com.termux.terminal.TerminalSession) = Unit
        override fun onSessionFinished(finishedSession: com.termux.terminal.TerminalSession) = Unit
        override fun onCopyTextToClipboard(session: com.termux.terminal.TerminalSession, text: String) = Unit
        override fun onPasteTextFromClipboard(session: com.termux.terminal.TerminalSession) = Unit
        override fun onBell(session: com.termux.terminal.TerminalSession) = Unit
        override fun onColorsChanged(session: com.termux.terminal.TerminalSession) = Unit
        override fun onTerminalCursorStateChange(state: Boolean) = Unit
        override fun getTerminalCursorStyle(): Int? = null
        override fun logError(tag: String, message: String) = Unit
        override fun logWarn(tag: String, message: String) = Unit
        override fun logInfo(tag: String, message: String) = Unit
        override fun logDebug(tag: String, message: String) = Unit
        override fun logVerbose(tag: String, message: String) = Unit
        override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) = Unit
        override fun logStackTrace(tag: String, e: Exception) = Unit
    }

    private var emulator = TerminalEmulator(output, 80, 24, 2000, client)
    private var columns = 80
    private var rows = 24

    fun resize(columns: Int, rows: Int) {
        this.columns = columns.coerceAtLeast(2)
        this.rows = rows.coerceAtLeast(2)
        emulator.resize(this.columns, this.rows)
    }

    fun feed(bytes: ByteArray, length: Int = bytes.size) {
        emulator.append(bytes, length)
    }

    fun render(): String {
        val screen = emulator.screen
        val startRow = -screen.activeTranscriptRows
        return screen.getSelectedText(0, startRow, columns, rows - 1, true, true)
    }
}

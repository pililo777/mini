package com.pililo777.minissh

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

internal class TerminalEmulator {
    private val decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)

    private val pendingBytes = ByteArrayOutputStream()
    private val screen = ArrayList<StringBuilder>()
    private var cursorRow = 0
    private var cursorCol = 0
    private var savedRow = 0
    private var savedCol = 0
    private var escapeState = EscapeState.None
    private val csiParams = StringBuilder()
    private val oscBuffer = StringBuilder()

    init {
        screen.add(StringBuilder())
    }

    fun feed(bytes: ByteArray, length: Int = bytes.size): Boolean {
        pendingBytes.write(bytes, 0, length)
        val input = ByteBuffer.wrap(pendingBytes.toByteArray())
        val charBuffer = CharBuffer.allocate((input.remaining() + 8) * 2)
        val result = decoder.decode(input, charBuffer, false)
        val consumed = input.position()
        if (consumed > 0) {
            val remaining = pendingBytes.toByteArray().copyOfRange(consumed, pendingBytes.size())
            pendingBytes.reset()
            pendingBytes.write(remaining)
        }
        charBuffer.flip()
        while (charBuffer.hasRemaining()) {
            consumeChar(charBuffer.get())
        }
        return result.isUnderflow || result.isOverflow
    }

    fun render(): String {
        val lines = ArrayList<String>(screen.size)
        for (line in screen) lines.add(line.toString().trimEnd('\u0000'))
        return lines.joinToString("\n").trimEnd('\n')
    }

    private fun consumeChar(ch: Char) {
        when (escapeState) {
            EscapeState.None -> when (ch) {
                '\u001B' -> escapeState = EscapeState.Esc
                '\r' -> cursorCol = 0
                '\n' -> newline()
                '\b' -> backspace()
                '\u0007' -> Unit
                else -> appendPrintable(ch)
            }
            EscapeState.Esc -> when (ch) {
                '[' -> {
                    csiParams.setLength(0)
                    escapeState = EscapeState.Csi
                }
                ']' -> {
                    oscBuffer.setLength(0)
                    escapeState = EscapeState.Osc
                }
                '7' -> {
                    savedRow = cursorRow
                    savedCol = cursorCol
                    escapeState = EscapeState.None
                }
                '8' -> {
                    cursorRow = savedRow
                    cursorCol = savedCol
                    ensureCursor()
                    escapeState = EscapeState.None
                }
                else -> escapeState = EscapeState.None
            }
            EscapeState.Csi -> {
                if (ch in '@'..'~') {
                    handleCsi(ch, csiParams.toString())
                    escapeState = EscapeState.None
                } else {
                    csiParams.append(ch)
                }
            }
            EscapeState.Osc -> {
                if (ch == '\u0007') {
                    escapeState = EscapeState.None
                } else if (ch == '\u001B') {
                    escapeState = EscapeState.OscEsc
                } else {
                    oscBuffer.append(ch)
                }
            }
            EscapeState.OscEsc -> {
                escapeState = if (ch == '\\') EscapeState.None else EscapeState.None
            }
        }
    }

    private fun handleCsi(command: Char, params: String) {
        val values = if (params.isBlank()) listOf(0) else params.split(';').map { it.toIntOrNull() ?: 0 }
        when (command) {
            'A' -> moveCursorRow(-(values.firstOrNull() ?: 1))
            'B' -> moveCursorRow(values.firstOrNull() ?: 1)
            'C' -> moveCursorCol(values.firstOrNull() ?: 1)
            'D' -> moveCursorCol(-(values.firstOrNull() ?: 1))
            'H', 'f' -> {
                cursorRow = (values.getOrNull(0)?.takeIf { it > 0 } ?: 1) - 1
                cursorCol = (values.getOrNull(1)?.takeIf { it > 0 } ?: 1) - 1
                ensureCursor()
            }
            'J' -> if ((values.firstOrNull() ?: 0) == 2) clearScreen()
            'K' -> clearLine(values.firstOrNull() ?: 0)
            'X' -> eraseCharacters(values.firstOrNull() ?: 1)
            's' -> {
                savedRow = cursorRow
                savedCol = cursorCol
            }
            'u' -> {
                cursorRow = savedRow
                cursorCol = savedCol
                ensureCursor()
            }
            'm' -> Unit
        }
    }

    private fun appendPrintable(ch: Char) {
        ensureCursor()
        val line = screen[cursorRow]
        while (line.length < cursorCol) line.append(' ')
        if (cursorCol == line.length) line.append(ch) else line.setCharAt(cursorCol, ch)
        cursorCol += 1
    }

    private fun newline() {
        cursorRow += 1
        cursorCol = 0
        ensureCursor()
    }

    private fun backspace() {
        if (cursorCol > 0) cursorCol -= 1
        val line = screen.getOrNull(cursorRow) ?: return
        if (cursorCol < line.length && cursorCol >= 0) line.deleteCharAt(cursorCol)
    }

    private fun clearScreen() {
        screen.clear()
        screen.add(StringBuilder())
        cursorRow = 0
        cursorCol = 0
    }

    private fun clearLine(mode: Int) {
        val line = screen.getOrNull(cursorRow) ?: return
        when (mode) {
            0 -> if (cursorCol < line.length) line.delete(cursorCol, line.length)
            1 -> if (cursorCol > 0) line.delete(0, cursorCol)
            2 -> line.clear()
        }
    }

    private fun eraseCharacters(count: Int) {
        val line = screen.getOrNull(cursorRow) ?: return
        val end = (cursorCol + count).coerceAtMost(line.length)
        if (cursorCol < end) line.replace(cursorCol, end, " ".repeat(end - cursorCol))
    }

    private fun moveCursorRow(delta: Int) {
        cursorRow = (cursorRow + delta).coerceAtLeast(0)
        ensureCursor()
    }

    private fun moveCursorCol(delta: Int) {
        cursorCol = (cursorCol + delta).coerceAtLeast(0)
        ensureCursor()
    }

    private fun ensureCursor() {
        while (screen.size <= cursorRow) screen.add(StringBuilder())
        val line = screen[cursorRow]
        while (line.length < cursorCol) line.append(' ')
    }

    private enum class EscapeState {
        None,
        Esc,
        Csi,
        Osc,
        OscEsc
    }
}

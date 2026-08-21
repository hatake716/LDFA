package com.hatake716.linuxdesktop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LiveLogFormatterTest {
    @Test
    fun keepsLatestLinesAndNormalizesTerminalOutput() {
        val raw = buildString {
            append("old line\r")
            append("\u001B[32mgreen line\u001B[0m\n")
            append("latest line   \n")
        }

        val formatted = LiveLogFormatter.format(raw, maxLines = 2)

        assertEquals("green line\nlatest line", formatted)
        assertFalse(formatted.contains("\u001B"))
        assertFalse(formatted.contains('\r'))
    }

    @Test
    fun returnsEmptyTextForBlankOutput() {
        assertEquals("", LiveLogFormatter.format("\n\r\n", maxLines = 40))
    }
}

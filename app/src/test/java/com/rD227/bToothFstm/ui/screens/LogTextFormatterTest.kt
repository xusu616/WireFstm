package com.rD227.bToothFstm.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class LogTextFormatterTest {
    @Test
    fun cleanMode_removesOnlyTrailingLineTerminators() {
        assertEquals("PING", formatLogText("PING\r\n\n\r", LogCRLFDisplay.CLEAN))
        assertEquals("PING \t", formatLogText("PING \t", LogCRLFDisplay.CLEAN))
        assertEquals("A\r\nB", formatLogText("A\r\nB\r\n", LogCRLFDisplay.CLEAN))
    }

    @Test
    fun escapedMode_makesControlCharactersAndBackslashesVisible() {
        assertEquals(
            "PING\\r\\n\\t\\u0000",
            formatLogText("PING\r\n\t\u0000", LogCRLFDisplay.ESCAPED)
        )
        assertEquals(
            "literal \\\\r\\\\n",
            formatLogText("literal \\r\\n", LogCRLFDisplay.ESCAPED)
        )
    }

    @Test
    fun escapedMode_usesUnicodeEscapeForOtherControls() {
        assertEquals("A\\u001BB\\u007F", formatLogText("A\u001BB\u007F", LogCRLFDisplay.ESCAPED))
    }

    @Test
    fun formatter_preservesPlainUnicodeAndEmptyText() {
        assertEquals("温度=36.5℃", formatLogText("温度=36.5℃", LogCRLFDisplay.CLEAN))
        assertEquals("", formatLogText("", LogCRLFDisplay.CLEAN))
        assertEquals("", formatLogText("", LogCRLFDisplay.ESCAPED))
    }
}

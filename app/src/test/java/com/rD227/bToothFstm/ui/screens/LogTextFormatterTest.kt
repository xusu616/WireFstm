package com.rD227.bToothFstm.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class LogTextFormatterTest {
    @Test
    fun cleanMode_removesOnlyTrailingLineTerminators() {
        assertEquals("PING", formatLogText("PING\r\n\n\r", LogTextDisplay.CLEAN))
        assertEquals("PING \t", formatLogText("PING \t", LogTextDisplay.CLEAN))
        assertEquals("A\r\nB", formatLogText("A\r\nB\r\n", LogTextDisplay.CLEAN))
    }

    @Test
    fun escapedMode_makesControlCharactersAndBackslashesVisible() {
        assertEquals(
            "PING\\r\\n\\t\\u0000",
            formatLogText("PING\r\n\t\u0000", LogTextDisplay.ESCAPED)
        )
        assertEquals(
            "literal \\\\r\\\\n",
            formatLogText("literal \\r\\n", LogTextDisplay.ESCAPED)
        )
    }

    @Test
    fun escapedMode_usesUnicodeEscapeForOtherControls() {
        assertEquals("A\\u001BB\\u007F", formatLogText("A\u001BB\u007F", LogTextDisplay.ESCAPED))
    }

    @Test
    fun formatter_preservesPlainUnicodeAndEmptyText() {
        assertEquals("温度=36.5℃", formatLogText("温度=36.5℃", LogTextDisplay.CLEAN))
        assertEquals("", formatLogText("", LogTextDisplay.CLEAN))
        assertEquals("", formatLogText("", LogTextDisplay.ESCAPED))
    }
}

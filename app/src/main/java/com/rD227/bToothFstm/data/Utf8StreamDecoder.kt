package com.rD227.bToothFstm.data

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

class Utf8StreamDecoder {
    private val decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    private var remainder = ByteArray(0)
    private var finished = false

    fun decode(bytes: ByteArray): String {
        if (finished || bytes.isEmpty()) return ""
        val combined = ByteArray(remainder.size + bytes.size)
        remainder.copyInto(combined)
        bytes.copyInto(combined, remainder.size)
        val input = ByteBuffer.wrap(combined)
        val output = CharBuffer.allocate(maxOf(1, combined.size + 1))
        decoder.decode(input, output, false)
        remainder = ByteArray(input.remaining()).also { input.get(it) }
        output.flip()
        return output.toString()
    }

    fun finish(): String {
        if (finished) return ""
        finished = true
        val input = ByteBuffer.wrap(remainder)
        val output = CharBuffer.allocate(maxOf(2, remainder.size + 2))
        decoder.decode(input, output, true)
        decoder.flush(output)
        remainder = ByteArray(0)
        output.flip()
        return output.toString()
    }
}
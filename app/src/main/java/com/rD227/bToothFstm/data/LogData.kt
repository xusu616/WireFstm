package com.rD227.bToothFstm.data

import androidx.compose.runtime.mutableStateListOf
import java.util.concurrent.atomic.AtomicLong

enum class LogFilter { ALL, SENT, RECEIVED }

object LogData {
    private const val MAX_LOG_ENTRIES = 500

    private val nextLogId = AtomicLong(0L)
    private val _logs = mutableStateListOf<LogEntry>()
    val logs: List<LogEntry>
        get() = _logs

    fun displayedLogs(filter: LogFilter): List<LogEntry> = when (filter) {
        LogFilter.ALL -> _logs
        LogFilter.SENT -> _logs.filter { it.direction == LogDirection.SENT }
        LogFilter.RECEIVED -> _logs.filter { it.direction == LogDirection.RECEIVED }
    }

    fun appendLog(
        direction: LogDirection,
        text: String,
        type: ConnectionType,
        address: String
    ) {
        if (text.isEmpty()) return
        _logs.add(
            LogEntry(
                id = nextLogId.incrementAndGet(),
                timestamp = System.currentTimeMillis(),
                direction = direction,
                text = text,
                type = type,
                address = address
            )
        )
        while (_logs.size > MAX_LOG_ENTRIES) _logs.removeAt(0)
    }

    fun clearLogs() {
        _logs.clear()
    }
}
package com.rD227.bToothFstm.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit

data class AppSettingsState(
    val autoChecksum: Boolean = false,
    val appendCrlf: Boolean = false,
    val hexMode: Boolean = false,
    val sortByRssi: Boolean = true,
    val filterUnknownDevices: Boolean = false,
    val showCrlf: Boolean = false,
    val autoScrollLogs: Boolean = true,
    val showTimestamps: Boolean = true
)

class AppSettingsManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var settings by mutableStateOf(load())
        private set

    private fun load(): AppSettingsState {
        return AppSettingsState(
            autoChecksum = prefs.getBoolean(KEY_AUTO_CHECKSUM, false),
            appendCrlf = prefs.getBoolean(KEY_APPEND_CRLF, false),
            hexMode = prefs.getBoolean(KEY_HEX_MODE, false),
            sortByRssi = prefs.getBoolean(KEY_SORT_BY_RSSI, true),
            filterUnknownDevices = prefs.getBoolean(KEY_FILTER_UNKNOWN_DEVICES, false),
            showCrlf = prefs.getBoolean(KEY_SHOW_CRLF, false),
            autoScrollLogs = prefs.getBoolean(KEY_AUTO_SCROLL_LOGS, true),
            showTimestamps = prefs.getBoolean(KEY_SHOW_TIMESTAMPS, true)
        )
    }

    fun updateAutoChecksum(value: Boolean) {
        settings = settings.copy(autoChecksum = value)
        prefs.edit { putBoolean(KEY_AUTO_CHECKSUM, value) }
    }

    fun updateAppendCrlf(value: Boolean) {
        settings = settings.copy(appendCrlf = value)
        prefs.edit { putBoolean(KEY_APPEND_CRLF, value) }
    }

    fun updateHexMode(value: Boolean) {
        settings = settings.copy(hexMode = value)
        prefs.edit { putBoolean(KEY_HEX_MODE, value) }
    }

    fun updateSortByRssi(value: Boolean) {
        settings = settings.copy(sortByRssi = value)
        prefs.edit { putBoolean(KEY_SORT_BY_RSSI, value) }
    }

    fun updateFilterUnknownDevices(value: Boolean) {
        settings = settings.copy(filterUnknownDevices = value)
        prefs.edit { putBoolean(KEY_FILTER_UNKNOWN_DEVICES, value) }
    }

    fun updateShowCrlf(value: Boolean) {
        settings = settings.copy(showCrlf = value)
        prefs.edit { putBoolean(KEY_SHOW_CRLF, value) }
    }

    fun updateAutoScrollLogs(value: Boolean) {
        settings = settings.copy(autoScrollLogs = value)
        prefs.edit { putBoolean(KEY_AUTO_SCROLL_LOGS, value) }
    }

    fun updateShowTimestamps(value: Boolean) {
        settings = settings.copy(showTimestamps = value)
        prefs.edit { putBoolean(KEY_SHOW_TIMESTAMPS, value) }
    }

    companion object {
        private const val PREFS_NAME = "wire_fstm_settings"
        private const val KEY_AUTO_CHECKSUM = "key_auto_checksum"
        private const val KEY_APPEND_CRLF = "key_append_crlf"
        private const val KEY_HEX_MODE = "key_hex_mode"
        private const val KEY_SORT_BY_RSSI = "key_sort_by_rssi"
        private const val KEY_FILTER_UNKNOWN_DEVICES = "key_filter_unknown_devices"
        private const val KEY_SHOW_CRLF = "key_show_crlf"
        private const val KEY_AUTO_SCROLL_LOGS = "key_auto_scroll_logs"
        private const val KEY_SHOW_TIMESTAMPS = "key_show_timestamps"
    }
}

@Composable
fun rememberAppSettingsManager(): AppSettingsManager {
    val context = LocalContext.current.applicationContext
    return remember { AppSettingsManager(context) }
}

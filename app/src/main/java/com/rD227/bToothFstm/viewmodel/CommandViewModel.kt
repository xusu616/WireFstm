package com.rD227.bToothFstm.viewmodel

import android.app.Application
import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import com.rD227.bToothFstm.data.QuickCommand
import org.json.JSONArray
import org.json.JSONObject

class CommandViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val _quickCommands = mutableStateListOf<QuickCommand>()

    val quickCommands: List<QuickCommand>
        get() = _quickCommands

    init {
        loadQuickCommands()
    }

    fun addQuickCommand(command: QuickCommand) {
        _quickCommands.add(command)
        saveQuickCommands()
    }

    fun updateQuickCommand(index: Int, command: QuickCommand) {
        if (index !in _quickCommands.indices) return
        _quickCommands[index] = command
        saveQuickCommands()
    }

    fun deleteQuickCommand(index: Int) {
        if (index !in _quickCommands.indices) return
        _quickCommands.removeAt(index)
        saveQuickCommands()
    }

    private fun loadQuickCommands() {
        _quickCommands.clear()
        val storedCommands = preferences.getString(COMMANDS_KEY, null)
        if (storedCommands == null) {
            _quickCommands.addAll(defaultCommands())
            return
        }

        try {
            val array = JSONArray(storedCommands)
            repeat(array.length()) { index ->
                _quickCommands.add(commandFromJson(array.getJSONObject(index)))
            }
        } catch (_: Exception) {
            _quickCommands.clear()
            _quickCommands.addAll(defaultCommands())
            saveQuickCommands()
        }
    }

    private fun saveQuickCommands() {
        val array = JSONArray()
        _quickCommands.forEach { array.put(it.toJson()) }
        preferences.edit { putString(COMMANDS_KEY, array.toString()) }
    }

    private fun QuickCommand.toJson() = JSONObject().apply {
        put("label", label)
        put("command", command)
        put("iconName", iconName)
        put("isDestructive", isDestructive)
    }

    private fun commandFromJson(json: JSONObject) = QuickCommand(
        label = json.getString("label"),
        command = json.getString("command"),
        iconName = json.optString("iconName", "Refresh"),
        isDestructive = json.optBoolean("isDestructive", false)
    )

    private fun defaultCommands() = listOf(
        QuickCommand("READ", "READ", "Refresh"),
        QuickCommand("RESET", "RESET", "Refresh"),
        QuickCommand("STATUS", "STATUS", "Info")
    )

    private companion object {
        const val PREFERENCES_NAME = "quick_commands"
        const val COMMANDS_KEY = "commands"
    }
}

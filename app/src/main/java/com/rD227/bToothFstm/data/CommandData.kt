package com.rD227.bToothFstm.data

data class QuickCommand(
    val label: String,
    val command: String,
    val iconName: String = "Refresh",
    val isDestructive: Boolean = false
)

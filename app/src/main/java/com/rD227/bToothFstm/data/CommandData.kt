package com.rD227.bToothFstm.data

data class QuickCommand(
    val label: String,
    val command: String,
    val iconName: String = "Refresh",
    val isDestructive: Boolean = false
)

data class CommandScreenUiState(
    val connectionState: ConnectionUiState,
    val targetDevice: ScanData.BleDevice?,
    val pairedDevices: List<ScanData.BleDevice>,
    val customInput: String,
    val bleServiceUuid: String,
    val bleCharUuid: String,
    val sppUuid: String,
    val quickCommands: List<QuickCommand>
)

data class CommandScreenActions(
    val onCustomInputChange: (String) -> Unit,
    val onQuickSend: (String) -> Unit,
    val onCustomSend: (String) -> Unit,
    val onConnect: (ScanData.BleDevice) -> Unit,
    val onDisconnect: () -> Unit,
    val onLoadPairedDevices: () -> Unit,
    val onUuidChange: (String, String, String) -> UuidUpdateResult,
    val onAddCommand: (QuickCommand) -> Unit,
    val onUpdateCommand: (Int, QuickCommand) -> Unit,
    val onDeleteCommand: (Int) -> Unit
)


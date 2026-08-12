package com.rD227.bToothFstm.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.rD227.bToothFstm.R
import com.rD227.bToothFstm.data.CommandScreenActions
import com.rD227.bToothFstm.data.CommandScreenUiState
import com.rD227.bToothFstm.data.ConnectionPhase
import com.rD227.bToothFstm.data.ConnectionType
import com.rD227.bToothFstm.data.ConnectionUiState
import com.rD227.bToothFstm.data.QuickCommand
import com.rD227.bToothFstm.data.ScanData.BleDevice
import com.rD227.bToothFstm.data.SendFailureReason
import com.rD227.bToothFstm.data.SendResult
import com.rD227.bToothFstm.data.TransportError
import com.rD227.bToothFstm.data.TransportErrorKind
import com.rD227.bToothFstm.data.UuidField
import com.rD227.bToothFstm.data.UuidUpdateResult
import com.rD227.bToothFstm.ui.theme.AppTheme
import com.rD227.bToothFstm.viewmodel.CommandViewModel
import com.rD227.bToothFstm.viewmodel.ScanViewModel
import java.util.UUID

@Composable
fun getIconByName(name: String): ImageVector = when (name) {
    "Refresh" -> Icons.Default.Refresh
    "Info" -> Icons.Default.Info
    "FlashOn" -> Icons.Default.FlashOn
    "PowerSettingsNew" -> Icons.Default.PowerSettingsNew
    "Stop" -> Icons.Default.Stop
    "Add" -> Icons.Default.Add
    "Delete" -> Icons.Default.Delete
    else -> Icons.Default.Refresh
}

@Composable
fun CommandScreen(
    scanViewModel: ScanViewModel,
    commandViewModel: CommandViewModel,
    modifier: Modifier = Modifier
) {
    val connectionState = scanViewModel.connectionState
    val targetDevice = connectionState.address?.let { address ->
        scanViewModel.devices.firstOrNull { it.address == address }
            ?: scanViewModel.pairedDevices.firstOrNull { it.address == address }
    }
    var customInput by rememberSaveable { mutableStateOf("") }
    var pendingCustomRequestId by rememberSaveable { mutableStateOf<Long?>(null) }
    var pendingCustomText by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val sendResult = scanViewModel.lastSendResult
    val failureMessage = sendResultMessage(sendResult)

    LaunchedEffect(sendResult?.requestId) {
        val result = sendResult ?: return@LaunchedEffect
        val matchesPendingCustom = result.requestId == pendingCustomRequestId
        if (matchesPendingCustom) {
            if (result is SendResult.Sent && customInput == pendingCustomText) {
                customInput = ""
            }
            pendingCustomRequestId = null
            pendingCustomText = null
        }

        try {
            if (result is SendResult.Failed && failureMessage != null) {
                snackbarHostState.showSnackbar(failureMessage)
            }
        } finally {
            scanViewModel.consumeLastSendResult(result.requestId)
        }
    }

    CommandScreenContent(
        state = CommandScreenUiState(
            connectionState = connectionState,
            targetDevice = targetDevice,
            pairedDevices = scanViewModel.pairedDevices,
            customInput = customInput,
            bleServiceUuid = scanViewModel.transportConfig.bleServiceUuid.toString(),
            bleCharUuid = scanViewModel.transportConfig.bleCharacteristicUuid.toString(),
            sppUuid = scanViewModel.transportConfig.sppUuid.toString(),
            quickCommands = commandViewModel.quickCommands
        ),
        actions = CommandScreenActions(
            onCustomInputChange = { customInput = it },
            onQuickSend = { scanViewModel.sendCommand(it) },
            onCustomSend = { submittedText ->
                val requestId = scanViewModel.sendCommand(submittedText)
                pendingCustomRequestId = requestId
                pendingCustomText = submittedText
            },
            onConnect = scanViewModel::connectDevice,
            onDisconnect = scanViewModel::disconnectDevice,
            onLoadPairedDevices = scanViewModel::loadPairedDevices,
            onUuidChange = scanViewModel::updateTransportConfig,
            onAddCommand = commandViewModel::addQuickCommand,
            onUpdateCommand = commandViewModel::updateQuickCommand,
            onDeleteCommand = commandViewModel::deleteQuickCommand
        ),
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommandScreenContent(
    state: CommandScreenUiState,
    actions: CommandScreenActions,
    modifier: Modifier = Modifier,
    snackbarHost: @Composable () -> Unit = {}
) {
    var showDeviceSheet by remember { mutableStateOf(false) }
    var showUuidDialog by remember { mutableStateOf(false) }
    var editingCommandIndex by remember { mutableStateOf<Int?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    val operationInProgress = state.connectionState.phase.isOperationInProgress()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.control_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                actions = {
                    FilledTonalIconButton(
                        onClick = { showAddDialog = true },
                        modifier = Modifier.padding(end = 4.dp)
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = stringResource(R.string.command_add),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    FilledTonalIconButton(
                        onClick = { showUuidDialog = true },
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Icon(
                            Icons.Default.Tune,
                            contentDescription = stringResource(R.string.uuid_settings),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        snackbarHost = snackbarHost,
        modifier = modifier
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            DeviceStatusBanner(
                connectionState = state.connectionState,
                targetDevice = state.targetDevice,
                onSelectDevice = {
                    actions.onLoadPairedDevices()
                    showDeviceSheet = true
                },
                onDisconnect = actions.onDisconnect
            )
            QuickCommandSection(
                commands = state.quickCommands,
                onSend = actions.onQuickSend,
                onLongClick = { editingCommandIndex = it }
            )
            CustomCommandInput(
                value = state.customInput,
                onValueChange = actions.onCustomInputChange,
                onSend = { actions.onCustomSend(state.customInput) }
            )
        }

        DevicesSheet(
            devices = state.pairedDevices,
            showDeviceSheet = showDeviceSheet,
            operationInProgress = operationInProgress,
            activeAddress = state.connectionState.address,
            onDismiss = { showDeviceSheet = false },
            onConnect = actions.onConnect
        )

        if (showUuidDialog) {
            UuidConfigDialog(
                bleServiceUuid = state.bleServiceUuid,
                bleCharUuid = state.bleCharUuid,
                sppUuid = state.sppUuid,
                onConfirm = actions.onUuidChange,
                onDismiss = { showUuidDialog = false },
                onSuccess = { showUuidDialog = false }
            )
        }

        if (showAddDialog) {
            QuickCommandEditDialog(
                command = null,
                onConfirm = {
                    actions.onAddCommand(it)
                    showAddDialog = false
                },
                onDismiss = { showAddDialog = false }
            )
        }

        editingCommandIndex?.let { index ->
            state.quickCommands.getOrNull(index)?.let { command ->
                QuickCommandEditDialog(
                    command = command,
                    onConfirm = {
                        actions.onUpdateCommand(index, it)
                        editingCommandIndex = null
                    },
                    onDismiss = { editingCommandIndex = null },
                    onDelete = {
                        actions.onDeleteCommand(index)
                        editingCommandIndex = null
                    }
                )
            }
        }
    }
}

@Composable
private fun DeviceStatusBanner(
    connectionState: ConnectionUiState,
    targetDevice: BleDevice?,
    onSelectDevice: () -> Unit,
    onDisconnect: () -> Unit
) {
    val isConnected = connectionState.phase == ConnectionPhase.CONNECTED
    val isError = connectionState.phase == ConnectionPhase.ERROR
    val isBusy = connectionState.phase.isOperationInProgress()

    val containerColor = when {
        isConnected -> MaterialTheme.colorScheme.secondaryContainer
        isError -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = when {
        isConnected -> MaterialTheme.colorScheme.onSecondaryContainer
        isError -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = containerColor,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = when {
                    isConnected -> MaterialTheme.colorScheme.secondary
                    isError -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.outline
                },
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = if (isConnected) {
                        Icons.Default.BluetoothConnected
                    } else {
                        Icons.Default.BluetoothDisabled
                    },
                    contentDescription = null,
                    tint = when {
                        isConnected -> MaterialTheme.colorScheme.onSecondary
                        isError -> MaterialTheme.colorScheme.onError
                        else -> MaterialTheme.colorScheme.surface
                    },
                    modifier = Modifier.padding(8.dp)
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = connectionPhaseLabel(connectionState.phase),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = contentColor
                )
                val details = buildList {
                    targetDevice?.name?.let(::add)
                    connectionState.type?.let { add(connectionTypeLabel(it)) }
                    connectionState.address?.let(::add)
                }.joinToString(" · ")
                if (details.isNotEmpty()) {
                    Text(
                        text = details,
                        style = MaterialTheme.typography.bodySmall,
                        color = contentColor.copy(alpha = 0.75f)
                    )
                }
                connectionState.error?.let {
                    Text(
                        text = transportErrorLabel(it),
                        style = MaterialTheme.typography.bodySmall,
                        color = contentColor
                    )
                }
            }
            when {
                isBusy -> CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
                isConnected -> {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilledTonalIconButton(
                            onClick = onSelectDevice,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.Default.Devices,
                                contentDescription = stringResource(R.string.select_device),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        TextButton(
                            onClick = onDisconnect,
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text(stringResource(R.string.disconnect))
                        }
                    }
                }
                else -> FilledTonalIconButton(
                    onClick = onSelectDevice,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Default.Devices,
                        contentDescription = stringResource(R.string.select_device),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun DevicesSheet(
    devices: List<BleDevice>,
    showDeviceSheet: Boolean,
    operationInProgress: Boolean,
    activeAddress: String?,
    onDismiss: () -> Unit,
    onConnect: (BleDevice) -> Unit
) {
    AnimatedVisibility(
        visible = showDeviceSheet,
        enter = slideInVertically(
            initialOffsetY = { it },
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow
            )
        ) + fadeIn(),
        exit = slideOutVertically(
            targetOffsetY = { it },
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow
            )
        ) + fadeOut()
    ) {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.3f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss
                    )
            )

            val fraction = when {
                devices.isEmpty() -> 0.2f
                devices.size <= 5 -> 0.1f * devices.size
                else -> 0.5f
            }
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(fraction),
                shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .width(32.dp)
                                .padding(top = 4.dp)
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
                        )
                    }
                    if (devices.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                stringResource(R.string.NoDevicesWarning),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(devices, key = { it.address }) { device ->
                                ElevatedCard(
                                    shape = RoundedCornerShape(12.dp),
                                    colors = CardDefaults.elevatedCardColors(
                                        containerColor = if (device.isConnected) {
                                            MaterialTheme.colorScheme.primaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant
                                        }
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                device.name,
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.Medium
                                            )
                                            Text(
                                                "${device.address} · ${connectionTypeLabel(device.deviceType)}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        when {
                                            device.isConnected -> Text(
                                                stringResource(R.string.connected),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            operationInProgress && activeAddress == device.address ->
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(28.dp),
                                                    strokeWidth = 3.dp
                                                )
                                            else -> Button(
                                                onClick = {
                                                    onConnect(device)
                                                    onDismiss()
                                                },
                                                enabled = !operationInProgress
                                            ) {
                                                Text(stringResource(R.string.connect))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickCommandSection(
    commands: List<QuickCommand>,
    onSend: (String) -> Unit,
    onLongClick: (Int) -> Unit
) {
    Column {
        Text(
            text = stringResource(R.string.quick_commands),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 10.dp)
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(4.dp)
        ) {
            items(commands.size) { index ->
                QuickCommandCard(
                    command = commands[index],
                    onClick = { onSend(commands[index].command) },
                    onLongClick = { onLongClick(index) }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QuickCommandCard(
    command: QuickCommand,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    ElevatedCard(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (command.isDestructive) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        ),
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp, horizontal = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = getIconByName(command.iconName),
                contentDescription = null,
                tint = if (command.isDestructive) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
                modifier = Modifier.size(26.dp)
            )
            Text(
                text = command.label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = if (command.isDestructive) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

@Composable
private fun CustomCommandInput(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit
) {
    Column {
        Text(
            text = stringResource(R.string.custom_command),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 10.dp)
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                placeholder = { Text(stringResource(R.string.command_hint)) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (value.isNotBlank()) onSend() }),
                modifier = Modifier.weight(1f)
            )
            FilledIconButton(
                onClick = onSend,
                enabled = value.isNotBlank(),
                shape = RoundedCornerShape(14.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                ),
                modifier = Modifier.size(56.dp)
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = stringResource(R.string.send),
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

@Composable
private fun UuidConfigDialog(
    bleServiceUuid: String,
    bleCharUuid: String,
    sppUuid: String,
    onConfirm: (String, String, String) -> UuidUpdateResult,
    onDismiss: () -> Unit,
    onSuccess: () -> Unit
) {
    var service by remember(bleServiceUuid) { mutableStateOf(bleServiceUuid) }
    var characteristic by remember(bleCharUuid) { mutableStateOf(bleCharUuid) }
    var spp by remember(sppUuid) { mutableStateOf(sppUuid) }
    var authoritativeInvalidField by remember { mutableStateOf<UuidField?>(null) }
    val serviceInvalid = !service.isValidUuid() || authoritativeInvalidField == UuidField.BLE_SERVICE
    val characteristicInvalid =
        !characteristic.isValidUuid() || authoritativeInvalidField == UuidField.BLE_CHARACTERISTIC
    val sppInvalid = !spp.isValidUuid() || authoritativeInvalidField == UuidField.SPP
    val canConfirm = !serviceInvalid && !characteristicInvalid && !sppInvalid

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.uuid_settings)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                UuidField(
                    value = service,
                    onValueChange = {
                        service = it
                        if (authoritativeInvalidField == UuidField.BLE_SERVICE) {
                            authoritativeInvalidField = null
                        }
                    },
                    label = stringResource(R.string.uuid_ble_service),
                    isError = serviceInvalid
                )
                UuidField(
                    value = characteristic,
                    onValueChange = {
                        characteristic = it
                        if (authoritativeInvalidField == UuidField.BLE_CHARACTERISTIC) {
                            authoritativeInvalidField = null
                        }
                    },
                    label = stringResource(R.string.uuid_ble_characteristic),
                    isError = characteristicInvalid
                )
                UuidField(
                    value = spp,
                    onValueChange = {
                        spp = it
                        if (authoritativeInvalidField == UuidField.SPP) {
                            authoritativeInvalidField = null
                        }
                    },
                    label = stringResource(R.string.uuid_spp),
                    isError = sppInvalid
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    when (val result = onConfirm(
                        service.trim(),
                        characteristic.trim(),
                        spp.trim()
                    )) {
                        is UuidUpdateResult.Success -> onSuccess()
                        is UuidUpdateResult.Invalid -> authoritativeInvalidField = result.field
                    }
                },
                enabled = canConfirm
            ) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun UuidField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    isError: Boolean
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        supportingText = if (isError) {
            { Text(stringResource(R.string.uuid_invalid)) }
        } else {
            null
        },
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun QuickCommandEditDialog(
    command: QuickCommand?,
    onConfirm: (QuickCommand) -> Unit,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)? = null
) {
    var label by remember(command) { mutableStateOf(command?.label.orEmpty()) }
    var commandText by remember(command) { mutableStateOf(command?.command.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (command == null) R.string.command_add else R.string.command_edit
                )
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text(stringResource(R.string.command_display_name)) },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = commandText,
                    onValueChange = { commandText = it },
                    label = { Text(stringResource(R.string.command_content)) },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.command_example)) }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        QuickCommand(
                            label = label,
                            command = commandText,
                            iconName = command?.iconName ?: "Refresh",
                            isDestructive = command?.isDestructive ?: false
                        )
                    )
                },
                enabled = label.isNotBlank() && commandText.isNotBlank()
            ) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            Row {
                onDelete?.let { delete ->
                    TextButton(
                        onClick = delete,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.delete))
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    )
}

private fun String.isValidUuid(): Boolean = runCatching {
    UUID.fromString(trim())
}.isSuccess

private fun ConnectionPhase.isOperationInProgress(): Boolean = this in setOf(
    ConnectionPhase.PAIRING,
    ConnectionPhase.CONNECTING,
    ConnectionPhase.DISCOVERING_SERVICES,
    ConnectionPhase.ENABLING_NOTIFICATIONS,
    ConnectionPhase.DISCONNECTING
)

@Composable
private fun connectionPhaseLabel(phase: ConnectionPhase): String = stringResource(
    when (phase) {
        ConnectionPhase.DISCONNECTED -> R.string.status_disconnected
        ConnectionPhase.PAIRING -> R.string.status_pairing
        ConnectionPhase.CONNECTING -> R.string.status_connecting
        ConnectionPhase.DISCOVERING_SERVICES -> R.string.status_discovering_services
        ConnectionPhase.ENABLING_NOTIFICATIONS -> R.string.status_enabling_notifications
        ConnectionPhase.CONNECTED -> R.string.status_connected
        ConnectionPhase.DISCONNECTING -> R.string.status_disconnecting
        ConnectionPhase.ERROR -> R.string.status_error
    }
)

@Composable
private fun connectionTypeLabel(type: ConnectionType): String = stringResource(
    when (type) {
        ConnectionType.BLE -> R.string.transport_ble
        ConnectionType.CLASSIC -> R.string.transport_spp
    }
)

@Composable
private fun transportErrorLabel(error: TransportError): String = stringResource(
    when (error.kind) {
        TransportErrorKind.ADAPTER_UNAVAILABLE -> R.string.error_adapter_unavailable
        TransportErrorKind.PERMISSION_DENIED,
        TransportErrorKind.SECURITY_ERROR -> R.string.error_permission_denied
        TransportErrorKind.SCAN_FAILED -> R.string.error_scan_failed
        TransportErrorKind.INVALID_DEVICE -> R.string.error_invalid_device
        TransportErrorKind.PAIRING_FAILED -> R.string.error_pairing_failed
        TransportErrorKind.SERVICE_NOT_FOUND -> R.string.error_service_not_found
        TransportErrorKind.CHARACTERISTIC_NOT_FOUND,
        TransportErrorKind.CHARACTERISTIC_NOT_SUPPORTED -> R.string.error_characteristic_unavailable
        TransportErrorKind.NOTIFICATION_SETUP_FAILED -> R.string.error_notification_setup
        TransportErrorKind.SETUP_TIMEOUT -> R.string.error_setup_timeout
        TransportErrorKind.REMOTE_DISCONNECTED -> R.string.error_remote_disconnected
        TransportErrorKind.WRITE_FAILED,
        TransportErrorKind.WRITE_TIMEOUT -> R.string.error_write_failed
        TransportErrorKind.CONNECTION_FAILED,
        TransportErrorKind.GATT_ERROR,
        TransportErrorKind.IO_ERROR -> R.string.error_connection_failed
    }
)

@Composable
private fun sendResultMessage(result: SendResult?): String? = when (result) {
    null, is SendResult.Sent -> null
    is SendResult.Failed -> stringResource(
        when (result.reason) {
            SendFailureReason.EMPTY_COMMAND -> R.string.send_failed_empty
            SendFailureReason.COMMAND_TOO_LARGE -> R.string.send_failed_command_too_large
            SendFailureReason.QUEUE_FULL -> R.string.send_failed_queue_full
            SendFailureReason.NOT_CONNECTED,
            SendFailureReason.DISCONNECTED -> R.string.send_failed_disconnected
            SendFailureReason.TIMEOUT -> R.string.send_failed_timeout
            SendFailureReason.SECURITY_ERROR -> R.string.send_failed_permission
            SendFailureReason.WRITE_FAILED -> R.string.send_failed_write
        }
    )
}

@Preview(showBackground = true, name = "Control — No Device")
@Composable
private fun CommandScreenNoDevicePreview() {
    AppTheme(dynamicColor = false) {
        CommandScreenContent(
            state = CommandScreenUiState(
                connectionState = ConnectionUiState(),
                targetDevice = null,
                pairedDevices = emptyList(),
                customInput = "",
                bleServiceUuid = "0000ffe0-0000-1000-8000-00805f9b34fb",
                bleCharUuid = "0000ffe1-0000-1000-8000-00805f9b34fb",
                sppUuid = "00001101-0000-1000-8000-00805f9b34fb",
                quickCommands = listOf(
                    QuickCommand("READ", "READ", "Refresh"),
                    QuickCommand("RESET", "RESET", "Refresh"),
                    QuickCommand("STATUS", "STATUS", "Info")
                )
            ),
            actions = CommandScreenActions(
                onCustomInputChange = {},
                onQuickSend = {},
                onCustomSend = {},
                onConnect = {},
                onDisconnect = {},
                onLoadPairedDevices = {},
                onUuidChange = { service, characteristic, spp ->
                    UuidUpdateResult.Success(
                        com.rD227.bToothFstm.data.TransportConfig(
                            UUID.fromString(service),
                            UUID.fromString(characteristic),
                            UUID.fromString(spp)
                        )
                    )
                },
                onAddCommand = {},
                onUpdateCommand = { _, _ -> },
                onDeleteCommand = {}
            )
        )
    }
}

@Preview
@Composable
fun DeviceCardPreview(
    device: BleDevice = BleDevice(
        "MCU-STM32-01",
        "AA:BB:CC:DD:EE:01",
        -55,
        isConnected = true,
        isPaired = true,
        deviceType = ConnectionType.BLE
    )
) {
    AppTheme {
        DevicesSheet(
            devices = listOf(device),
            showDeviceSheet = true,
            operationInProgress = false,
            activeAddress = device.address,
            onDismiss = {},
            onConnect = {}
        )
    }
}

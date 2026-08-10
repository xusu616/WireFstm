package com.rD227.bToothFstm.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.automirrored.filled.Send
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rD227.bToothFstm.R
import com.rD227.bToothFstm.data.ScanData.BleDevice
import com.rD227.bToothFstm.data.ScanData.DeviceType
import com.rD227.bToothFstm.ui.theme.AppTheme
import com.rD227.bToothFstm.viewmodel.ScanViewModel

data class QuickCommand(
    val label: String,
    val command: String,
    val icon: ImageVector,
    val isDestructive: Boolean = false
)

// ── 有状态外壳 ────────────────────────────────────────────────────────────────
@Composable
fun CommandScreen(
    modifier: Modifier = Modifier,
    viewModel: ScanViewModel = viewModel()
) {
    val connectedDevice = viewModel.connectedAddress?.let { addr ->
        viewModel.devices.find { it.address == addr }
            ?: viewModel.pairedDevices.find { it.address == addr }
    }
    var customInput by remember { mutableStateOf("") }

    CommandScreenContent(
        modifier = modifier,
        connectedDevice = connectedDevice,
        pairedDevices = viewModel.pairedDevices,
        pairingAddress = viewModel.pairingAddress,
        customInput = customInput,
        onCustomInputChange = { customInput = it },
        onSend = { text -> viewModel.sendCommand(text); customInput = "" },
        onConnect = { viewModel.connectDevice(it) },
        onDisconnect = { viewModel.disconnectDevice() },
        onLoadPairedDevices = { viewModel.loadPairedDevices() },
        bleServiceUuid = viewModel.bleServiceUuid,
        bleCharUuid = viewModel.bleCharUuid,
        sppUuid = viewModel.sppUuid,
        onUuidChange = { svc, ch, spp ->
            viewModel.bleServiceUuid = svc
            viewModel.bleCharUuid = ch
            viewModel.sppUuid = spp
        }
    )
}

// ── 纯 UI ─────────────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommandScreenContent(
    modifier: Modifier = Modifier,
    connectedDevice: BleDevice?,
    pairedDevices: List<BleDevice>,
    pairingAddress: String?,
    customInput: String,
    onCustomInputChange: (String) -> Unit,
    onSend: (String) -> Unit,
    onConnect: (BleDevice) -> Unit,
    onDisconnect: () -> Unit,
    onLoadPairedDevices: () -> Unit,
    bleServiceUuid: String,
    bleCharUuid: String,
    sppUuid: String,
    onUuidChange: (String, String, String) -> Unit
) {
    val quickCommands = listOf(
        QuickCommand(stringResource(R.string.cmd_led_on),  "LED:ON",  Icons.Default.FlashOn),
        QuickCommand(stringResource(R.string.cmd_led_off), "LED:OFF", Icons.Default.PowerSettingsNew),
        QuickCommand(stringResource(R.string.cmd_status),  "STATUS",  Icons.Default.Info),
        QuickCommand(stringResource(R.string.cmd_read),    "READ",    Icons.Default.Refresh),
        QuickCommand(stringResource(R.string.cmd_reset),   "RESET",   Icons.Default.Refresh),
        QuickCommand(stringResource(R.string.cmd_stop),    "STOP",    Icons.Default.Stop, isDestructive = true),
    )
    var showDeviceSheet by remember { mutableStateOf(false) }
    var showUuidDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.control_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold)
                },
                actions = {
                    FilledTonalIconButton(onClick = { showUuidDialog = true },
                        modifier = Modifier.padding(end = 8.dp)) {
                        Icon(Icons.Default.Tune, contentDescription = "UUID 设置",
                            modifier = Modifier.size(20.dp))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        modifier = modifier
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(0.dp))
            DeviceStatusBanner(
                connectedDevice = connectedDevice,
                pairingAddress = pairingAddress,
                onSelectDevice = {
                    onLoadPairedDevices()
                    showDeviceSheet = true
                },
                onDisconnect = onDisconnect
            )
            QuickCommandSection(quickCommands, onSend = onSend)
            CustomCommandInput(
                value = customInput,
                onValueChange = onCustomInputChange,
                onSend = { onSend(customInput) }
            )
        }

        DevicesSheet(
            devices = pairedDevices,
            showDeviceSheet = showDeviceSheet,
            onDismiss = { showDeviceSheet = false },
            onConnect = onConnect
        )

        if (showUuidDialog) {
            UuidConfigDialog(
                bleServiceUuid = bleServiceUuid,
                bleCharUuid = bleCharUuid,
                sppUuid = sppUuid,
                onConfirm = { svc, ch, spp -> onUuidChange(svc, ch, spp); showUuidDialog = false },
                onDismiss = { showUuidDialog = false }
            )
        }
    }
}

// ── DeviceStatusBanner ────────────────────────────────────────────────────────
@Composable
private fun DeviceStatusBanner(
    connectedDevice: BleDevice?,
    pairingAddress: String?,
    onSelectDevice: () -> Unit,
    onDisconnect: () -> Unit
) {
    val isConnected = connectedDevice != null
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (isConnected) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = CircleShape,
                color = if (isConnected) MaterialTheme.colorScheme.secondary
                        else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = if (isConnected) Icons.Default.BluetoothConnected
                                  else Icons.Default.BluetoothDisabled,
                    contentDescription = null,
                    tint = if (isConnected) MaterialTheme.colorScheme.onSecondary
                           else MaterialTheme.colorScheme.onError,
                    modifier = Modifier
                        .padding(8.dp)
                        .size(24.dp)
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (isConnected) stringResource(R.string.connected)
                           else stringResource(R.string.no_device_connected),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isConnected) MaterialTheme.colorScheme.onSecondaryContainer
                            else MaterialTheme.colorScheme.onErrorContainer
                )
                connectedDevice?.let {
                    Text(text = it.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f))
                }
            }
            if (pairingAddress != null) {
                CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
            } else if (isConnected) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalIconButton(onClick = onSelectDevice, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Devices, contentDescription = "选择设备",
                            modifier = Modifier.size(18.dp))
                    }
                    TextButton(onClick = onDisconnect,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error)) {
                        Text(stringResource(R.string.disconnect),
                            style = MaterialTheme.typography.labelMedium)
                    }
                }
            } else {
                FilledTonalIconButton(onClick = onSelectDevice, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Devices, contentDescription = "选择设备",
                        modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

// ── DevicesSheet ──────────────────────────────────────────────────────────────
@Composable
fun DevicesSheet(
    devices: List<BleDevice>,
    showDeviceSheet: Boolean,
    onDismiss: () -> Unit,
    onConnect: (BleDevice) -> Unit
){
    AnimatedVisibility(
        showDeviceSheet,
        enter = slideInVertically(
            initialOffsetY = { it },
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow
            )
        ) + fadeIn(spring(dampingRatio = Spring.DampingRatioMediumBouncy))
        ,
        exit = slideOutVertically(
            targetOffsetY = { it },
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow
            )
        ) + fadeOut(spring(dampingRatio = Spring.DampingRatioMediumBouncy))

    ) {
        Box(Modifier.fillMaxSize()){
            Box(Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.3f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss
                    )
            )

            val fraction = if (devices.size <=5 && devices.isNotEmpty()) 0.1 * devices.size
                else if (devices.isEmpty()) 0.2
                else 0.5

            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(fraction.toFloat()),
                shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
                color = MaterialTheme.colorScheme.surface,
                //tonalElevation = 4.dp what's this
            ) {
                Column(Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 0.dp, top = 0.dp, end = 0.dp, bottom = 0.dp)
                            //.background(MaterialTheme.colorScheme.primaryContainer)
                            .clickable(onClick = onDismiss)
                        ,
                        contentAlignment = Alignment.Center
                    ){
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
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            Alignment.Center
                        ) {
                            Text(
                                stringResource(R.string.NoDevicesWarning),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                        }
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(devices) { device ->
                                ElevatedCard(
                                    shape = RoundedCornerShape(12.dp),
                                    colors = CardDefaults.elevatedCardColors(
                                        containerColor = if (device.isConnected)
                                            MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surfaceVariant
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
                                                device.address,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        if (device.isConnected) {
                                            Text(
                                                "已连接",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        } else {
                                            Button(onClick = { onConnect(device); onDismiss() }) {
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

// ── QuickCommandSection ───────────────────────────────────────────────────────
@Composable
private fun QuickCommandSection(commands: List<QuickCommand>, onSend: (String) -> Unit) {
    Column {
        Text(text = stringResource(R.string.quick_commands),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 10.dp))
        LazyVerticalGrid(columns = GridCells.Fixed(3),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(0.dp)) {
            items(commands) { cmd -> QuickCommandCard(cmd, onClick = { onSend(cmd.command) }) }
        }
    }
}

@Composable
private fun QuickCommandCard(cmd: QuickCommand, onClick: () -> Unit) {
    ElevatedCard(onClick = onClick, shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (cmd.isDestructive) MaterialTheme.colorScheme.errorContainer
                             else MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 14.dp, horizontal = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(imageVector = cmd.icon, contentDescription = null,
                tint = if (cmd.isDestructive) MaterialTheme.colorScheme.error
                       else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(26.dp))
            Text(text = cmd.label, style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = if (cmd.isDestructive) MaterialTheme.colorScheme.onErrorContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ── CustomCommandInput ────────────────────────────────────────────────────────
@Composable
private fun CustomCommandInput(value: String, onValueChange: (String) -> Unit, onSend: () -> Unit) {
    Column {
        Text(text = stringResource(R.string.custom_command),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 10.dp))
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(value = value, onValueChange = onValueChange,
                placeholder = { Text(stringResource(R.string.command_hint)) },
                singleLine = true, shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
                modifier = Modifier.weight(1f))
            FilledIconButton(onClick = onSend, enabled = value.isNotBlank(),
                shape = RoundedCornerShape(14.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier.size(56.dp)) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.send),
                    modifier = Modifier.size(22.dp))
            }
        }
    }
}

// ── UuidConfigDialog ──────────────────────────────────────────────────────────
@Composable
private fun UuidConfigDialog(
    bleServiceUuid: String, bleCharUuid: String, sppUuid: String,
    onConfirm: (String, String, String) -> Unit, onDismiss: () -> Unit
) {
    var svc by remember { mutableStateOf(bleServiceUuid) }
    var ch  by remember { mutableStateOf(bleCharUuid) }
    var spp by remember { mutableStateOf(sppUuid) }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("UUID 配置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = svc, onValueChange = { svc = it },
                    label = { Text("BLE Service UUID") }, singleLine = true,
                    shape = RoundedCornerShape(10.dp))
                OutlinedTextField(value = ch, onValueChange = { ch = it },
                    label = { Text("BLE Characteristic UUID") }, singleLine = true,
                    shape = RoundedCornerShape(10.dp))
                OutlinedTextField(value = spp, onValueChange = { spp = it },
                    label = { Text("SPP UUID (经典蓝牙)") }, singleLine = true,
                    shape = RoundedCornerShape(10.dp))
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(svc, ch, spp) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
/**
// ── Previews ──────────────────────────────────────────────────────────────────


@Preview(showBackground = true, name = "Control — Light")
@Preview(showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES, name = "Control — Dark")
@Composable
private fun CommandScreenPreview() {
    AppTheme(dynamicColor = false) {
        CommandScreenContent(
            connectedDevice = BleDevice("MCU-STM32-01", "AA:BB:CC:DD:EE:01", -55,
                isConnected = true, isPaired = true, deviceType = DeviceType.BLE),
            pairedDevices = listOf(
                BleDevice("MCU-STM32-01", "AA:BB:CC:DD:EE:01", -55, isConnected = true, isPaired = true),
                BleDevice("HC-05 Module",  "AA:BB:CC:DD:EE:02", -72, isPaired = true)
            ),
            pairingAddress = null,
            customInput = "",
            onCustomInputChange = {},
            onSend = {},
            onConnect = {},
            onDisconnect = {},
            onLoadPairedDevices = {},
            bleServiceUuid = "0000FFE0-0000-1000-8000-00805F9B34FB",
            bleCharUuid    = "0000FFE1-0000-1000-8000-00805F9B34FB",
            sppUuid        = "00001101-0000-1000-8000-00805F9B34FB",
            onUuidChange = { _, _, _ -> }
        )
    }
}
**/

@Preview(showBackground = true, name = "Control — No Device")
@Composable
private fun CommandScreenNoDevicePreview() {
    AppTheme(dynamicColor = false) {
        CommandScreenContent(
            connectedDevice = null,
            pairedDevices = emptyList(),
            pairingAddress = null,
            customInput = "",
            onCustomInputChange = {},
            onSend = {},
            onConnect = {},
            onDisconnect = {},
            onLoadPairedDevices = {},
            bleServiceUuid = "0000FFE0-0000-1000-8000-00805F9B34FB",
            bleCharUuid    = "0000FFE1-0000-1000-8000-00805F9B34FB",
            sppUuid        = "00001101-0000-1000-8000-00805F9B34FB",
            onUuidChange = { _, _, _ -> }
        )
    }
}

val emptyList = listOf<BleDevice>()

@Preview
@Composable
fun DeviceCardPreview(
    device: BleDevice = BleDevice("MCU-STM32-01", "AA:BB:CC:DD:EE:01", -55,
        isConnected = true, isPaired = true, deviceType = DeviceType.BLE),
) {
    AppTheme() {
        DevicesSheet(
            devices = listOf(device),
            showDeviceSheet = true,
            onDismiss = { },
            onConnect = { }
        )
    }
}
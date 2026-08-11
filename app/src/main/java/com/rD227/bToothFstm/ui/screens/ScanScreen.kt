package com.rD227.bToothFstm.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.rD227.bToothFstm.R
import com.rD227.bToothFstm.data.ConnectionPhase
import com.rD227.bToothFstm.data.ConnectionType
import com.rD227.bToothFstm.data.ConnectionUiState
import com.rD227.bToothFstm.data.ScanData.BleDevice
import com.rD227.bToothFstm.data.TransportErrorKind
import com.rD227.bToothFstm.ui.theme.AppTheme
import com.rD227.bToothFstm.viewmodel.ScanViewModel

@Composable
fun ScanScreen(
    scanViewModel: ScanViewModel,
    modifier: Modifier = Modifier
) {
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        scanViewModel.onPermissionResult(result.values.all { it })
    }

    ScanScreenContent(
        modifier = modifier,
        isScanning = scanViewModel.isScanning,
        devices = scanViewModel.devices,
        connectionState = scanViewModel.connectionState,
        onToggleScan = {
            if (scanViewModel.isScanning) {
                scanViewModel.stopScan()
            } else if (scanViewModel.hasPermission) {
                scanViewModel.startScan()
            } else {
                val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    arrayOf(
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_CONNECT
                    )
                } else {
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
                }
                permissionLauncher.launch(permissions)
            }
        },
        onConnect = scanViewModel::connectDevice,
        onDisconnect = scanViewModel::disconnectDevice
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreenContent(
    modifier: Modifier = Modifier,
    isScanning: Boolean,
    devices: List<BleDevice>,
    connectionState: ConnectionUiState,
    onToggleScan: () -> Unit,
    onConnect: (BleDevice) -> Unit,
    onDisconnect: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.scan_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        modifier = modifier
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            ScanControlCard(
                isScanning = isScanning,
                deviceCount = devices.size,
                onToggleScan = onToggleScan
            )
            ConnectionStatusStrip(connectionState)
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(devices, key = { it.address }) { device ->
                    DeviceCard(
                        device = device,
                        connectionState = connectionState,
                        onConnect = { onConnect(device) },
                        onDisconnect = onDisconnect
                    )
                }
            }
        }
    }
}

@Composable
private fun ScanControlCard(
    isScanning: Boolean,
    deviceCount: Int,
    onToggleScan: () -> Unit
) {
    val pulseTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by pulseTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "alpha"
    )

    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(48.dp)) {
                if (isScanning) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .alpha(pulseAlpha)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.25f), CircleShape)
                    )
                }
                Icon(
                    imageVector = if (isScanning) {
                        Icons.AutoMirrored.Filled.BluetoothSearching
                    } else {
                        Icons.Default.Bluetooth
                    },
                    contentDescription = stringResource(R.string.content_desc_bluetooth),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (isScanning) {
                        stringResource(R.string.scanning)
                    } else {
                        stringResource(R.string.devices_found, deviceCount)
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = if (isScanning) {
                        stringResource(R.string.scan_transport_types)
                    } else {
                        stringResource(R.string.scan_tap_to_start)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                )
            }
            val scanTransition = rememberInfiniteTransition(label = "scan_animation")
            val scanAlpha by scanTransition.animateFloat(
                initialValue = 0.9f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(500, easing = LinearEasing), RepeatMode.Reverse),
                label = "alpha"
            )
            FilledTonalButton(
                onClick = onToggleScan,
                modifier = Modifier
                    .alpha(if (isScanning) scanAlpha else 1f)
                    .sweepEffect(isScanning)
            ) {
                Icon(
                    imageVector = if (isScanning) {
                        Icons.Default.Close
                    } else {
                        Icons.AutoMirrored.Filled.BluetoothSearching
                    },
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (isScanning) {
                        stringResource(R.string.scan_stop)
                    } else {
                        stringResource(R.string.scan_start)
                    }
                )
            }
        }
    }
}

@Composable
private fun ConnectionStatusStrip(connectionState: ConnectionUiState) {
    if (connectionState.phase == ConnectionPhase.DISCONNECTED) return
    val busy = connectionState.phase.isScanOperationInProgress()
    val isError = connectionState.phase == ConnectionPhase.ERROR
    Surface(
        color = if (isError) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = scanConnectionPhaseLabel(connectionState.phase),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isError) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                val summary = buildList {
                    connectionState.type?.let { add(scanConnectionTypeLabel(it)) }
                    connectionState.address?.let(::add)
                }.joinToString(" · ")
                if (summary.isNotEmpty()) {
                    Text(summary, style = MaterialTheme.typography.bodySmall)
                }
                connectionState.error?.let { error ->
                    Text(
                        text = scanTransportErrorLabel(error.kind),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }
    }
}

fun Modifier.sweepEffect(active: Boolean): Modifier {
    if (!active) return this
    return composed {
        val transition = rememberInfiniteTransition(label = "sweep_effect")
        val sweepLight by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                tween(1000, easing = EaseInOut),
                RepeatMode.Reverse
            ),
            label = "sweep_light"
        )
        val primaryColor = Color.White
        val neutralColor = MaterialTheme.colorScheme.primaryContainer
        drawWithContent {
            drawContent()
            val bandWidth = size.width * 0.3f
            val centerX = (size.width - bandWidth) * sweepLight + bandWidth / 2
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.Transparent,
                        neutralColor,
                        primaryColor,
                        neutralColor,
                        Color.Transparent
                    ),
                    start = Offset(centerX - bandWidth, 0f),
                    end = Offset(centerX + bandWidth, 0f),
                    tileMode = TileMode.Clamp
                ),
                alpha = 0.2f
            )
        }
    }
}

@Composable
private fun DeviceCard(
    device: BleDevice,
    connectionState: ConnectionUiState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    val isTarget = connectionState.address == device.address
    val isConnected = isTarget && connectionState.phase == ConnectionPhase.CONNECTED
    val isBusy = connectionState.phase.isScanOperationInProgress()
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isConnected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isConnected) 2.dp else 0.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = if (isConnected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
                },
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isConnected) Icons.Default.BluetoothConnected else Icons.Default.Bluetooth,
                        contentDescription = null,
                        tint = if (isConnected) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = device.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (device.isPaired) {
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f)
                        ) {
                            Text(
                                text = stringResource(R.string.paired),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                Text(
                    text = "${device.address} · ${scanConnectionTypeLabel(device.deviceType)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.SignalCellularAlt,
                        contentDescription = stringResource(R.string.content_desc_signal),
                        tint = signalColor(device.rssi),
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "${device.rssi} dBm",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            when {
                isTarget && isBusy -> CircularProgressIndicator(
                    modifier = Modifier.size(36.dp),
                    strokeWidth = 3.dp
                )
                isConnected -> OutlinedButton(
                    onClick = onDisconnect,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.disconnect))
                }
                else -> Button(onClick = onConnect, enabled = !isBusy) {
                    Text(stringResource(R.string.connect))
                }
            }
        }
    }
}

private fun ConnectionPhase.isScanOperationInProgress(): Boolean = this in setOf(
    ConnectionPhase.PAIRING,
    ConnectionPhase.CONNECTING,
    ConnectionPhase.DISCOVERING_SERVICES,
    ConnectionPhase.ENABLING_NOTIFICATIONS,
    ConnectionPhase.DISCONNECTING
)

@Composable
private fun scanConnectionPhaseLabel(phase: ConnectionPhase): String = stringResource(
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
private fun scanConnectionTypeLabel(type: ConnectionType): String = stringResource(
    if (type == ConnectionType.BLE) R.string.transport_ble else R.string.transport_spp
)

@Composable
private fun scanTransportErrorLabel(kind: TransportErrorKind): String = stringResource(
    when (kind) {
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
private fun signalColor(rssi: Int) = when {
    rssi >= -60 -> MaterialTheme.colorScheme.secondary
    rssi >= -75 -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.error
}

@Preview(showBackground = true, name = "Scan — Light")
@Preview(
    showBackground = true,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
    name = "Scan — Dark"
)
@Composable
private fun ScanScreenPreview() {
    AppTheme(dynamicColor = false) {
        ScanScreenContent(
            isScanning = true,
            devices = listOf(
                BleDevice(
                    "MCU-STM32-01",
                    "AA:BB:CC:DD:EE:01",
                    -55,
                    isConnected = true,
                    isPaired = true,
                    deviceType = ConnectionType.BLE
                ),
                BleDevice(
                    "HC-05 Module",
                    "AA:BB:CC:DD:EE:02",
                    -72,
                    isPaired = true,
                    deviceType = ConnectionType.CLASSIC
                ),
                BleDevice(
                    "ESP32-DevKit",
                    "AA:BB:CC:DD:EE:03",
                    -88,
                    deviceType = ConnectionType.BLE
                )
            ),
            connectionState = ConnectionUiState(
                phase = ConnectionPhase.DISCOVERING_SERVICES,
                address = "AA:BB:CC:DD:EE:03",
                type = ConnectionType.BLE
            ),
            onToggleScan = {},
            onConnect = {},
            onDisconnect = {}
        )
    }
}

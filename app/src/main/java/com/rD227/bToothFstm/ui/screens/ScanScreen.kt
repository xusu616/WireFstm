package com.rD227.bToothFstm.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.rD227.bToothFstm.R
import com.rD227.bToothFstm.data.ScanData
import com.rD227.bToothFstm.data.ScanData.BleDevice
import com.rD227.bToothFstm.ui.theme.AppTheme
import com.rD227.bToothFstm.viewmodel.ScanViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(
    modifier: Modifier = Modifier,
    viewModel: ScanViewModel = viewModel()
) {
    // launcher 在 Composable 里声明，结果通知 ViewModel
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        viewModel.onPermissionResult(result.values.all { it })
    }

    // 扫描按钮的行为：有权限直接扫，没有权限先申请
    val onToggleScan = {
        if (viewModel.isScanning) {
            viewModel.stopScan()
        } else if (viewModel.hasPermission) {
            viewModel.startScan()
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
    }

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
                isScanning = viewModel.isScanning,
                deviceCount = viewModel.devices.size,
                onToggleScan = onToggleScan
            )
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(viewModel.devices) { device ->
                    DeviceCard(device = device)
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
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f, targetValue = 1f,
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
                    imageVector = if (isScanning) Icons.Default.BluetoothSearching else Icons.Default.Bluetooth,
                    contentDescription = stringResource(R.string.content_desc_bluetooth),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (isScanning) stringResource(R.string.scanning) else "发现 $deviceCount 台设备",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = if (isScanning) "BLE · 经典蓝牙" else "点击开始扫描附近设备",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                )
            }
            FilledTonalButton(onClick = onToggleScan) {
                Icon(
                    imageVector = if (isScanning) Icons.Default.Close else Icons.Default.BluetoothSearching,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(if (isScanning) stringResource(R.string.scan_stop) else stringResource(R.string.scan_start))
            }
        }
    }
}

@Composable
private fun DeviceCard(device: BleDevice) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (device.isConnected)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (device.isConnected) 2.dp else 0.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = if (device.isConnected)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (device.isConnected) Icons.Default.BluetoothConnected else Icons.Default.Bluetooth,
                        contentDescription = null,
                        tint = if (device.isConnected) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
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
                Spacer(Modifier.height(2.dp))
                Text(
                    text = device.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
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
            if (device.isConnected) {
                OutlinedButton(
                    onClick = {},
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.disconnect))
                }
            } else {
                Button(onClick = {}) {
                    Text(stringResource(R.string.connect))
                }
            }
        }
    }
}

@Composable
private fun signalColor(rssi: Int) = when {
    rssi >= -60 -> MaterialTheme.colorScheme.secondary
    rssi >= -75 -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.error
}

@Preview(showBackground = true, name = "Scan — Light")
@Preview(showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES, name = "Scan — Dark")
@Composable
private fun ScanScreenPreview() {
    AppTheme(dynamicColor = false) { ScanScreen() }
}

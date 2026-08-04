package com.rD227.bToothFstm.viewmodel

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rD227.bToothFstm.data.ScanData.BleDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

@SuppressLint("MissingPermission")
class ScanViewModel(application: Application) : AndroidViewModel(application) {

    private val bluetoothAdapter: BluetoothAdapter? =
        (application.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    // HC-05 / SPP 标准 UUID，BLE 设备连接时不用这个
    private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    var hasPermission by mutableStateOf(false)
        private set
    var isScanning by mutableStateOf(false)
        private set
    val devices = mutableStateListOf<BleDevice>()

    // 当前连接的设备 MAC，null 表示未连接
    var connectedAddress by mutableStateOf<String?>(null)
        private set

    // 正在配对中的设备 MAC，用于显示 loading 状态
    var pairingAddress by mutableStateOf<String?>(null)
        private set

    private var connectedSocket: BluetoothSocket? = null

    // ── BLE 扫描回调 ──────────────────────────────────────────────────────────
    private val leScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            upsertDevice(result.device.name, result.device.address, result.rssi)
        }
        override fun onScanFailed(errorCode: Int) { isScanning = false }
    }

    // ── 经典蓝牙广播接收器 ────────────────────────────────────────────────────
    private val classicReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                    device?.let { upsertDevice(it.name, it.address, rssi) }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    isScanning = false
                    unregisterClassicReceiver()
                }
            }
        }
    }
    private var classicReceiverRegistered = false

    // ── 配对状态广播接收器 ────────────────────────────────────────────────────
    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            } ?: return

            when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)) {
                BluetoothDevice.BOND_BONDED -> {
                    // 配对成功 → 建立连接
                    pairingAddress = null
                    upsertDevice(device.name, device.address, getRssi(device.address))
                    connectSocket(device)
                }
                BluetoothDevice.BOND_NONE -> {
                    // 配对失败或被拒绝
                    pairingAddress = null
                }
                // BOND_BONDING：配对进行中，不处理
            }
        }
    }
    private var bondReceiverRegistered = false

    // ── 公开方法 ──────────────────────────────────────────────────────────────
    fun onPermissionResult(granted: Boolean) {
        hasPermission = granted
        if (granted) startScan()
    }

    fun startScan() {
        if (!hasPermission) return
        devices.clear()
        isScanning = true

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        bluetoothAdapter?.bluetoothLeScanner?.startScan(null, settings, leScanCallback)

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        getApplication<Application>().registerReceiver(classicReceiver, filter)
        classicReceiverRegistered = true
        bluetoothAdapter?.startDiscovery()
    }

    fun stopScan() {
        isScanning = false
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(leScanCallback)
        bluetoothAdapter?.cancelDiscovery()
        unregisterClassicReceiver()
    }

    fun connectDevice(device: BleDevice) {
        val btDevice = bluetoothAdapter?.getRemoteDevice(device.address) ?: return
        // 扫描时连接容易失败，先停掉
        stopScan()
        when (btDevice.bondState) {
            BluetoothDevice.BOND_NONE -> {
                // 未配对：先发起配对，配对成功后在 bondReceiver 里连接
                registerBondReceiver()
                pairingAddress = device.address
                btDevice.createBond()
            }
            BluetoothDevice.BOND_BONDED -> {
                // 已配对：直接连接
                connectSocket(btDevice)
            }
            // BOND_BONDING：已经在配对中，忽略
        }
    }

    fun disconnectDevice() {
        viewModelScope.launch(Dispatchers.IO) {
            try { connectedSocket?.close() } catch (_: IOException) {}
            withContext(Dispatchers.Main) {
                connectedSocket = null
                val addr = connectedAddress
                connectedAddress = null
                // 刷新列表里该设备的状态
                addr?.let { a ->
                    val idx = devices.indexOfFirst { it.address == a }
                    if (idx != -1) devices[idx] = devices[idx].copy(isConnected = false)
                }
            }
        }
    }

    // ── 内部实现 ──────────────────────────────────────────────────────────────
    private fun connectSocket(btDevice: BluetoothDevice) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val socket = btDevice.createRfcommSocketToServiceRecord(SPP_UUID)
                socket.connect()           // 阻塞直到连接成功或抛异常
                withContext(Dispatchers.Main) {
                    connectedSocket = socket
                    connectedAddress = btDevice.address
                    val idx = devices.indexOfFirst { it.address == btDevice.address }
                    if (idx != -1) devices[idx] = devices[idx].copy(isConnected = true)
                }
            } catch (e: IOException) {
                withContext(Dispatchers.Main) {
                    connectedAddress = null
                }
            }
        }
    }

    private fun upsertDevice(name: String?, address: String, rssi: Int) {
        val bonded = bluetoothAdapter?.bondedDevices?.map { it.address }?.toSet() ?: emptySet()
        val device = BleDevice(
            name = name?.takeIf { it.isNotBlank() } ?: "未知设备",
            address = address,
            rssi = rssi,
            isConnected = address == connectedAddress,
            isPaired = address in bonded
        )
        val idx = devices.indexOfFirst { it.address == address }
        if (idx == -1) devices.add(device) else devices[idx] = device
    }

    private fun getRssi(address: String): Int =
        devices.firstOrNull { it.address == address }?.rssi ?: -80

    private fun registerBondReceiver() {
        if (bondReceiverRegistered) return
        getApplication<Application>().registerReceiver(
            bondReceiver,
            IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        )
        bondReceiverRegistered = true
    }

    private fun unregisterClassicReceiver() {
        if (!classicReceiverRegistered) return
        try { getApplication<Application>().unregisterReceiver(classicReceiver) }
        catch (_: IllegalArgumentException) {}
        classicReceiverRegistered = false
    }

    override fun onCleared() {
        super.onCleared()
        stopScan()
        disconnectDevice()
        if (bondReceiverRegistered) {
            try { getApplication<Application>().unregisterReceiver(bondReceiver) }
            catch (_: IllegalArgumentException) {}
        }
    }
}
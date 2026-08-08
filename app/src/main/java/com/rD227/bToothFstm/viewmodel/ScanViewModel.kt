package com.rD227.bToothFstm.viewmodel

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
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
import com.rD227.bToothFstm.data.ScanData.DeviceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

enum class ConnectionType { BLE, CLASSIC }

@SuppressLint("MissingPermission")
class ScanViewModel(application: Application) : AndroidViewModel(application) {

    // ── UUID 配置（可由 UI 层直接修改）────────────────────────────────────────
    var bleServiceUuid by mutableStateOf("0000FFE0-0000-1000-8000-00805F9B34FB")
    var bleCharUuid    by mutableStateOf("0000FFE1-0000-1000-8000-00805F9B34FB")
    var sppUuid        by mutableStateOf("00001101-0000-1000-8000-00805F9B34FB")

    private val bluetoothAdapter: BluetoothAdapter? =
        (application.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    var hasPermission by mutableStateOf(false)
        private set
    var isScanning by mutableStateOf(false)
        private set
    val devices = mutableStateListOf<BleDevice>()
    val pairedDevices = mutableStateListOf<BleDevice>()

    var connectedAddress by mutableStateOf<String?>(null)
        private set
    var pairingAddress by mutableStateOf<String?>(null)
        private set
    var connectionType by mutableStateOf<ConnectionType?>(null)
        private set

    private var connectedSocket: BluetoothSocket? = null
    private var bluetoothGatt: BluetoothGatt? = null

    // ── GATT 回调 ──────────────────────────────────────────────────────────────
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt.discoverServices()
            } else {
                viewModelScope.launch(Dispatchers.Main) {
                    val addr = connectedAddress
                    connectedAddress = null; connectionType = null; bluetoothGatt = null
                    addr?.let { a ->
                        val i = devices.indexOfFirst { it.address == a }
                        if (i != -1) devices[i] = devices[i].copy(isConnected = false)
                        val pi = pairedDevices.indexOfFirst { it.address == a }
                        if (pi != -1) pairedDevices[pi] = pairedDevices[pi].copy(isConnected = false)
                    }
                }
            }
        }
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            viewModelScope.launch(Dispatchers.Main) {
                connectedAddress = gatt.device.address
                connectionType = ConnectionType.BLE
                pairingAddress = null
                val i = devices.indexOfFirst { it.address == gatt.device.address }
                if (i != -1) devices[i] = devices[i].copy(isConnected = true)
                val pi = pairedDevices.indexOfFirst { it.address == gatt.device.address }
                if (pi != -1) pairedDevices[pi] = pairedDevices[pi].copy(isConnected = true)
            }
        }
    }

    // ── BLE 扫描回调 ───────────────────────────────────────────────────────────
    private val leScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            upsertDevice(result.device.name, result.device.address, result.rssi, DeviceType.BLE)
        }
        override fun onScanFailed(errorCode: Int) { isScanning = false }
    }

    // ── 经典蓝牙广播接收器 ─────────────────────────────────────────────────────
    private val classicReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    else @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                    device?.let { upsertDevice(it.name, it.address, rssi, DeviceType.CLASSIC) }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    isScanning = false; unregisterClassicReceiver()
                }
            }
        }
    }
    private var classicReceiverRegistered = false

    // ── 配对状态广播接收器 ─────────────────────────────────────────────────────
    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
            val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }
            if (device == null) return
            when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)) {
                BluetoothDevice.BOND_BONDED -> {
                    pairingAddress = null
                    upsertDevice(device.name, device.address, getRssi(device.address), DeviceType.CLASSIC)
                    connectSocket(device)
                }
                BluetoothDevice.BOND_NONE -> { pairingAddress = null }
            }
        }
    }
    private var bondReceiverRegistered = false

    // ── 公开方法 ───────────────────────────────────────────────────────────────
    fun onPermissionResult(granted: Boolean) {
        hasPermission = granted
        if (granted) { loadPairedDevices(); startScan() }
    }

    fun startScan() {
        if (!hasPermission) return
        devices.clear(); isScanning = true
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
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
        stopScan()
        when (btDevice.bondState) {
            BluetoothDevice.BOND_NONE -> {
                registerBondReceiver()
                pairingAddress = device.address
                btDevice.createBond()
            }
            BluetoothDevice.BOND_BONDED -> {
                if (device.deviceType == DeviceType.BLE) connectGatt(btDevice)
                else connectSocket(btDevice)
            }
        }
    }

    fun disconnectDevice() {
        bluetoothGatt?.disconnect(); bluetoothGatt?.close(); bluetoothGatt = null
        viewModelScope.launch(Dispatchers.IO) {
            try { connectedSocket?.close() } catch (_: IOException) {}
            withContext(Dispatchers.Main) {
                connectedSocket = null
                val addr = connectedAddress; connectedAddress = null; connectionType = null
                addr?.let { a ->
                    val i = devices.indexOfFirst { it.address == a }
                    if (i != -1) devices[i] = devices[i].copy(isConnected = false)
                    val pi = pairedDevices.indexOfFirst { it.address == a }
                    if (pi != -1) pairedDevices[pi] = pairedDevices[pi].copy(isConnected = false)
                }
            }
        }
    }

    fun sendCommand(text: String) {
        val bytes = (text + "\r\n").toByteArray(Charsets.UTF_8)
        when (connectionType) {
            ConnectionType.BLE -> sendBle(bytes)
            ConnectionType.CLASSIC -> sendClassic(bytes)
            null -> {}
        }
    }

    fun loadPairedDevices() {
        if (!hasPermission) return
        val bonded = bluetoothAdapter?.bondedDevices ?: return
        val rssiMap = devices.associate { it.address to it.rssi }
        pairedDevices.clear()
        bonded.forEach { bt ->
            pairedDevices.add(BleDevice(
                name = bt.name?.takeIf { it.isNotBlank() } ?: "未知设备",
                address = bt.address,
                rssi = rssiMap[bt.address] ?: -80,
                isConnected = bt.address == connectedAddress,
                isPaired = true
            ))
        }
    }

    // ── 内部实现 ───────────────────────────────────────────────────────────────
    private fun connectGatt(btDevice: BluetoothDevice) {
        pairingAddress = btDevice.address
        bluetoothGatt = btDevice.connectGatt(getApplication(), false, gattCallback)
    }

    private fun connectSocket(btDevice: BluetoothDevice) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val socket = btDevice.createRfcommSocketToServiceRecord(UUID.fromString(sppUuid))
                socket.connect()
                withContext(Dispatchers.Main) {
                    connectedSocket = socket
                    connectedAddress = btDevice.address
                    connectionType = ConnectionType.CLASSIC
                    pairingAddress = null
                    val i = devices.indexOfFirst { it.address == btDevice.address }
                    if (i != -1) devices[i] = devices[i].copy(isConnected = true)
                    val pi = pairedDevices.indexOfFirst { it.address == btDevice.address }
                    if (pi != -1) pairedDevices[pi] = pairedDevices[pi].copy(isConnected = true)
                }
            } catch (_: IOException) {
                withContext(Dispatchers.Main) { connectedAddress = null; pairingAddress = null }
            }
        }
    }

    private fun sendBle(bytes: ByteArray) {
        val gatt = bluetoothGatt ?: return
        val service = gatt.getService(UUID.fromString(bleServiceUuid)) ?: return
        val char = service.getCharacteristic(UUID.fromString(bleCharUuid)) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(char, bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        } else {
            @Suppress("DEPRECATION")
            char.value = bytes
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(char)
        }
    }

    private fun sendClassic(bytes: ByteArray) {
        viewModelScope.launch(Dispatchers.IO) {
            try { connectedSocket?.outputStream?.write(bytes) } catch (_: IOException) {}
        }
    }

    private fun upsertDevice(name: String?, address: String, rssi: Int, type: DeviceType) {
        val bonded = bluetoothAdapter?.bondedDevices?.map { it.address }?.toSet() ?: emptySet()
        val device = BleDevice(
            name = name?.takeIf { it.isNotBlank() } ?: "未知设备",
            address = address, rssi = rssi,
            isConnected = address == connectedAddress,
            isPaired = address in bonded,
            deviceType = type
        )
        val idx = devices.indexOfFirst { it.address == address }
        if (idx == -1) devices.add(device) else devices[idx] = device
    }

    private fun getRssi(address: String) = devices.firstOrNull { it.address == address }?.rssi ?: -80

    private fun registerBondReceiver() {
        if (bondReceiverRegistered) return
        getApplication<Application>().registerReceiver(
            bondReceiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
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
        stopScan(); disconnectDevice()
        if (bondReceiverRegistered) {
            try { getApplication<Application>().unregisterReceiver(bondReceiver) }
            catch (_: IllegalArgumentException) {}
        }
    }
}

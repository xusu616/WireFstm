package com.rD227.bToothFstm.viewmodel

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rD227.bToothFstm.data.ConnectionPhase
import com.rD227.bToothFstm.data.ConnectionType
import com.rD227.bToothFstm.data.ConnectionUiState
import com.rD227.bToothFstm.data.LogDirection
import com.rD227.bToothFstm.data.ScanData.BleDevice
import com.rD227.bToothFstm.data.SendFailureReason
import com.rD227.bToothFstm.data.SendResult
import com.rD227.bToothFstm.data.TransportConfig
import com.rD227.bToothFstm.data.TransportError
import com.rD227.bToothFstm.data.TransportErrorKind
import com.rD227.bToothFstm.util.BleConnectionManager
import com.rD227.bToothFstm.util.ClassicConnectionManager
import com.rD227.bToothFstm.data.LogData
import com.rD227.bToothFstm.data.PendingSend
import com.rD227.bToothFstm.data.UuidField
import com.rD227.bToothFstm.data.UuidUpdateResult
import com.rD227.bToothFstm.data.frameCommand
import com.rD227.bToothFstm.data.resolveDeviceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

@SuppressLint("MissingPermission")
class ScanViewModel(application: Application) : AndroidViewModel(application) {
    private val tag = ScanViewModel::class.java.simpleName
    private val app = getApplication<Application>()
    private val bluetoothAdapter: BluetoothAdapter? =
        (application.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private val preferences = application.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    var hasPermission by mutableStateOf(false)
        private set
    var isScanning by mutableStateOf(false)
        private set

    private val _devices = mutableStateListOf<BleDevice>()
    val devices: List<BleDevice>
        get() = _devices

    private val _pairedDevices = mutableStateListOf<BleDevice>()
    val pairedDevices: List<BleDevice>
        get() = _pairedDevices

    var connectionState by mutableStateOf(ConnectionUiState())
        private set

    val connectedAddress: String?
        get() = connectionState.address.takeIf { connectionState.phase == ConnectionPhase.CONNECTED }
    val pairingAddress: String?
        get() = connectionState.address.takeIf { connectionState.phase == ConnectionPhase.PAIRING }
    val connectionType: ConnectionType?
        get() = connectionState.type

    var transportConfig by mutableStateOf(loadTransportConfig())
        private set



    var lastSendResult by mutableStateOf<SendResult?>(null)
        private set

    private var bleScanActive = false
    private var classicScanActive = false
    private var classicReceiverRegistered = false
    private var bondReceiverRegistered = false
    private var scanTimeoutJob: Job? = null

    @Volatile
    private var connectionGeneration = 0L
    private var pendingBondAddress: String? = null
    private var pendingBondGeneration = 0L

    private var connectionTimeoutJob: Job? = null

    private val pendingSends = linkedMapOf<Long, PendingSend>()
    private val nextSendId = AtomicLong(0L)

    private val bleManager = BleConnectionManager(
        app = app,
        scope = viewModelScope,
        onState = { connectionState = it },
        onConnected = { address ->
            connectionTimeoutJob?.cancel()
            connectionTimeoutJob = null
            markConnected(address, true)
        },
        onSendComplete = ::completeSend,
        onLog = LogData::appendLog,
        onFailed = ::failConnection,
    )

    private val classicManager = ClassicConnectionManager(
        adapter = bluetoothAdapter,
        scope = viewModelScope,
        onState = { connectionState = it },
        onConnected = { address ->
            connectionTimeoutJob?.cancel()
            connectionTimeoutJob = null
            markConnected(address, true)
        },
        onSendComplete = ::completeSend,
        onLog = LogData::appendLog,
        isPending = { requestId -> pendingSends.containsKey(requestId) },
        onFailed = ::failConnection,
    )

    init {
        if (bluetoothAdapter == null) {
            Log.e(tag, "BluetoothAdapter not found")
            connectionState = ConnectionUiState(
                phase = ConnectionPhase.ERROR,
                error = TransportError(
                    TransportErrorKind.ADAPTER_UNAVAILABLE,
                    "Bluetooth adapter is unavailable"
                )
            )
        }
    }

    fun updateTransportConfig(
        bleServiceText: String,
        bleCharacteristicText: String,
        sppText: String
    ): UuidUpdateResult {
        val service = parseUuid(bleServiceText)
            ?: return UuidUpdateResult.Invalid(UuidField.BLE_SERVICE, bleServiceText)
        val characteristic = parseUuid(bleCharacteristicText)
            ?: return UuidUpdateResult.Invalid(UuidField.BLE_CHARACTERISTIC, bleCharacteristicText)
        val spp = parseUuid(sppText)
            ?: return UuidUpdateResult.Invalid(UuidField.SPP, sppText)
        val updated = TransportConfig(service, characteristic, spp)
        if (updated == transportConfig) return UuidUpdateResult.Success(updated)

        transportConfig = updated
        preferences.edit {
            putString(KEY_BLE_SERVICE_UUID, service.toString())
            putString(KEY_BLE_CHARACTERISTIC_UUID, characteristic.toString())
            putString(KEY_SPP_UUID, spp.toString())
        }

        if (hasActiveConnection()) disconnectDevice()
        return UuidUpdateResult.Success(updated)
    }

    fun onPermissionResult(granted: Boolean) {
        hasPermission = granted
        if (granted) {
            loadPairedDevices()
            startScan()
        } else {
            stopScan()
            disconnectWithError(
                TransportError(TransportErrorKind.PERMISSION_DENIED, "Bluetooth permission denied")
            )
        }
    }

    fun startScan() {
        if (!hasPermission) {
            setIdleError(TransportErrorKind.PERMISSION_DENIED, "Bluetooth permission is required")
            return
        }
        val adapter = bluetoothAdapter
        if (adapter == null) {
            setIdleError(TransportErrorKind.ADAPTER_UNAVAILABLE, "Bluetooth adapter is unavailable")
            return
        }
        val adapterEnabled = isAdapterEnabled(adapter) ?: return
        if (!adapterEnabled) {
            setIdleError(TransportErrorKind.ADAPTER_UNAVAILABLE, "Bluetooth adapter is disabled")
            return
        }

        stopScan()
        _devices.clear()
        var started = false

        try {
            val scanner = adapter.bluetoothLeScanner
            if (scanner != null) {
                val settings = ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build()
                scanner.startScan(null, settings, leScanCallback)
                bleScanActive = true
                started = true
            }
        } catch (error: SecurityException) {
            setIdleError(TransportErrorKind.SECURITY_ERROR, error.message ?: "BLE scan permission denied")
        } catch (error: IllegalStateException) {
            setIdleError(TransportErrorKind.SCAN_FAILED, error.message ?: "BLE scan could not start")
        }

        try {
            registerClassicReceiver()
            classicScanActive = adapter.startDiscovery()
            started = classicScanActive || started
            if (!classicScanActive) unregisterClassicReceiver()
        } catch (error: SecurityException) {
            classicScanActive = false
            unregisterClassicReceiver()
            setIdleError(TransportErrorKind.SECURITY_ERROR, error.message ?: "Classic scan permission denied")
        }

        updateScanningState()
        if (started) {
            scanTimeoutJob = viewModelScope.launch(Dispatchers.Main.immediate) {
                delay(SCAN_TIMEOUT_MS)
                stopScan()
            }
        } else {
            setIdleError(TransportErrorKind.SCAN_FAILED, "No Bluetooth scan could be started")
        }
    }

    fun stopScan() {
        scanTimeoutJob?.cancel()
        scanTimeoutJob = null
        val adapter = bluetoothAdapter
        if (bleScanActive) {
            try {
                adapter?.bluetoothLeScanner?.stopScan(leScanCallback)
            } catch (error: SecurityException) {
                setIdleError(TransportErrorKind.SECURITY_ERROR, error.message ?: "Could not stop BLE scan")
            } catch (_: IllegalStateException) {
                // The adapter may have been disabled while scanning.
            }
        }
        if (classicScanActive) {
            try {
                adapter?.cancelDiscovery()
            } catch (error: SecurityException) {
                setIdleError(TransportErrorKind.SECURITY_ERROR, error.message ?: "Could not stop Classic scan")
            }
        }
        bleScanActive = false
        classicScanActive = false
        unregisterClassicReceiver()
        updateScanningState()
    }

    fun connectDevice(device: BleDevice) {
        if (!hasPermission) {
            setIdleError(TransportErrorKind.PERMISSION_DENIED, "Bluetooth permission is required")
            return
        }
        val adapter = bluetoothAdapter
        if (adapter == null) {
            setIdleError(TransportErrorKind.ADAPTER_UNAVAILABLE, "Bluetooth adapter is unavailable")
            return
        }
        val adapterEnabled = isAdapterEnabled(adapter) ?: return
        if (!adapterEnabled) {
            setIdleError(TransportErrorKind.ADAPTER_UNAVAILABLE, "Bluetooth adapter is disabled")
            return
        }

        val remoteDevice = try {
            adapter.getRemoteDevice(device.address)
        } catch (error: IllegalArgumentException) {
            setIdleError(TransportErrorKind.INVALID_DEVICE, "Invalid Bluetooth address: ${device.address}")
            return
        } catch (error: SecurityException) {
            setIdleError(TransportErrorKind.SECURITY_ERROR, error.message ?: "Cannot access Bluetooth device")
            return
        }

        stopScan()
        val type = device.deviceType
        val generation = beginConnection(device.address, type)

        if (type == ConnectionType.BLE) {
            bleManager.connect(remoteDevice, generation, transportConfig)
            return
        }

        try {
            when (remoteDevice.bondState) {
                BluetoothDevice.BOND_BONDED -> connectClassic(remoteDevice, generation)
                BluetoothDevice.BOND_BONDING -> waitForClassicBond(remoteDevice, generation)
                else -> {
                    waitForClassicBond(remoteDevice, generation)
                    if (!remoteDevice.createBond()) {
                        failConnection(
                            generation,
                            TransportError(TransportErrorKind.PAIRING_FAILED, "Pairing could not be started")
                        )
                    }
                }
            }
        } catch (error: SecurityException) {
            failConnection(
                generation,
                TransportError(TransportErrorKind.SECURITY_ERROR, error.message ?: "Pairing permission denied")
            )
        }
    }

    fun disconnectDevice() {
        if (!hasActiveConnection() && pendingSends.isEmpty()) {
            if (connectionState.phase == ConnectionPhase.ERROR) {
                connectionState = ConnectionUiState()
            }
            return
        }
        val oldAddress = connectionState.address
        val oldType = connectionState.type
        connectionState = ConnectionUiState(ConnectionPhase.DISCONNECTING, oldAddress, oldType)
        connectionGeneration++
        closeTransportResources(disconnectGatt = true)
        failAllPending(SendFailureReason.DISCONNECTED, "Transport disconnected")
        markConnected(oldAddress, false)
        connectionState = ConnectionUiState()
    }

    fun loadPairedDevices() {
        if (!hasPermission) return
        val adapter = bluetoothAdapter ?: run {
            setIdleError(TransportErrorKind.ADAPTER_UNAVAILABLE, "Bluetooth adapter is unavailable")
            return
        }
        val bonded = try {
            adapter.bondedDevices
        } catch (error: SecurityException) {
            setIdleError(TransportErrorKind.SECURITY_ERROR, error.message ?: "Cannot read paired devices")
            return
        }
        val discoveredByAddress = _devices.associateBy { it.address }
        _pairedDevices.clear()
        bonded.sortedBy { it.address }.forEach { btDevice ->
            val discovered = discoveredByAddress[btDevice.address]
            val type = safeResolvedType(btDevice, discovered?.deviceType)
            _pairedDevices.add(
                BleDevice(
                    name = safeDeviceName(btDevice),
                    address = btDevice.address,
                    rssi = discovered?.rssi ?: DEFAULT_RSSI,
                    isConnected = btDevice.address == connectedAddress,
                    isPaired = true,
                    deviceType = type
                )
            )
        }
    }

    fun sendCommand(
        text: String,
        addChecksum: Boolean = false,
        onComplete: (SendResult) -> Unit = {}
    ): Long {
        val requestId = nextSendId.incrementAndGet()
        if (text.isBlank()) {
            dispatchImmediateResult(
                SendResult.Failed(requestId, SendFailureReason.EMPTY_COMMAND, "Command must not be blank"),
                onComplete
            )
            return requestId
        }

        val payload = frameCommand(text, addChecksum)
        if (payload.size > MAX_COMMAND_PAYLOAD_BYTES) {
            dispatchImmediateResult(
                SendResult.Failed(
                    requestId,
                    SendFailureReason.COMMAND_TOO_LARGE,
                    "Framed command exceeds $MAX_COMMAND_PAYLOAD_BYTES bytes"
                ),
                onComplete
            )
            return requestId
        }

        viewModelScope.launch(Dispatchers.Main.immediate) {
            val current = connectionState
            if (current.phase != ConnectionPhase.CONNECTED || current.address == null || current.type == null) {
                dispatchImmediateResult(
                    SendResult.Failed(
                        requestId,
                        SendFailureReason.NOT_CONNECTED,
                        "No transport is connected"
                    ),
                    onComplete
                )
                return@launch
            }
            if (pendingSends.size >= MAX_PENDING_SENDS) {
                dispatchImmediateResult(
                    SendResult.Failed(
                        requestId,
                        SendFailureReason.QUEUE_FULL,
                        "Send queue is full"
                    ),
                    onComplete
                )
                return@launch
            }

            val pending = PendingSend(
                requestId = requestId,
                text = text,
                payload = payload,
                type = current.type,
                address = current.address,
                onComplete = onComplete
            )
            pendingSends[requestId] = pending
            when (pending.type) {
                ConnectionType.BLE -> bleManager.enqueue(pending, connectionGeneration)
                ConnectionType.CLASSIC -> classicManager.enqueue(pending, connectionGeneration)
            }
        }
        return requestId
    }

    fun consumeLastSendResult(requestId: Long) {
        if (lastSendResult?.requestId == requestId) lastSendResult = null
    }


    private val leScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            viewModelScope.launch(Dispatchers.Main.immediate) {
                if (bleScanActive) upsertDevice(result.device, result.rssi, ConnectionType.BLE)
            }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            val copies = results.toList()
            viewModelScope.launch(Dispatchers.Main.immediate) {
                if (bleScanActive) {
                    copies.forEach { upsertDevice(it.device, it.rssi, ConnectionType.BLE) }
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            viewModelScope.launch(Dispatchers.Main.immediate) {
                bleScanActive = false
                updateScanningState()
                setIdleError(TransportErrorKind.SCAN_FAILED, "BLE scan failed ($errorCode)")
            }
        }
    }

    private val classicReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device = intent.bluetoothDeviceExtra() ?: return
                    val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                    upsertDevice(device, rssi, ConnectionType.CLASSIC)
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    classicScanActive = false
                    unregisterClassicReceiver()
                    updateScanningState()
                }
            }
        }
    }

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
            val device = intent.bluetoothDeviceExtra() ?: return
            val pendingAddress = pendingBondAddress ?: run {
                unregisterBondReceiver()
                return
            }
            val generation = pendingBondGeneration
            if (
                device.address != pendingAddress ||
                generation != connectionGeneration ||
                connectionState.type != ConnectionType.CLASSIC
            ) return

            when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)) {
                BluetoothDevice.BOND_BONDED -> {
                    pendingBondAddress = null
                    unregisterBondReceiver()
                    upsertDevice(device, rssiFor(device.address), ConnectionType.CLASSIC)
                    loadPairedDevices()
                    connectClassic(device, generation)
                }
                BluetoothDevice.BOND_NONE -> {
                    pendingBondAddress = null
                    unregisterBondReceiver()
                    failConnection(
                        generation,
                        TransportError(TransportErrorKind.PAIRING_FAILED, "Pairing failed or was cancelled")
                    )
                }
            }
        }
    }

    private fun waitForClassicBond(device: BluetoothDevice, generation: Long) {
        pendingBondAddress = device.address
        pendingBondGeneration = generation
        registerBondReceiver()
        connectionState = ConnectionUiState(
            ConnectionPhase.PAIRING,
            device.address,
            ConnectionType.CLASSIC
        )
    }

    private fun connectClassic(device: BluetoothDevice, generation: Long) {
        if (generation != connectionGeneration) return
        pendingBondAddress = null
        pendingBondGeneration = 0L
        unregisterBondReceiver()
        classicManager.connect(device, generation, transportConfig.sppUuid)
    }

    private fun beginConnection(address: String, type: ConnectionType): Long {
        val previousAddress = connectionState.address
        connectionGeneration++
        closeTransportResources(disconnectGatt = true)
        failAllPending(SendFailureReason.DISCONNECTED, "Superseded by a new connection")
        markConnected(previousAddress, false)
        connectionState = ConnectionUiState(ConnectionPhase.CONNECTING, address, type)
        val generation = connectionGeneration
        connectionTimeoutJob = viewModelScope.launch(Dispatchers.Main.immediate) {
            delay(CONNECTION_TIMEOUT_MS)
            if (generation == connectionGeneration && connectionState.phase != ConnectionPhase.CONNECTED) {
                failConnection(
                    generation,
                    TransportError(TransportErrorKind.CONNECTION_FAILED, "Connection timed out")
                )
            }
        }
        return generation
    }

    private fun failConnection(generation: Long, error: TransportError) {
        if (generation != connectionGeneration) return
        val address = connectionState.address
        val type = connectionState.type
        val bleTail = if (type == ConnectionType.BLE) bleManager.takeTail() else ""
        val classicTail = if (type == ConnectionType.CLASSIC) classicManager.takeTail() else ""
        if (address != null) {
            if (bleTail.isNotEmpty()) LogData.appendLog(LogDirection.RECEIVED, bleTail, ConnectionType.BLE, address)
            if (classicTail.isNotEmpty()) {
                LogData.appendLog(LogDirection.RECEIVED, classicTail, ConnectionType.CLASSIC, address)
            }
        }
        connectionGeneration++
        closeTransportResources(disconnectGatt = true)
        val failureReason = when (error.kind) {
            TransportErrorKind.SECURITY_ERROR -> SendFailureReason.SECURITY_ERROR
            TransportErrorKind.WRITE_TIMEOUT -> SendFailureReason.TIMEOUT
            TransportErrorKind.WRITE_FAILED -> SendFailureReason.WRITE_FAILED
            else -> SendFailureReason.DISCONNECTED
        }
        failAllPending(failureReason, error.message)
        markConnected(address, false)
        connectionState = ConnectionUiState(ConnectionPhase.ERROR, address, type, error)
    }

    private fun disconnectWithError(error: TransportError) {
        val address = connectionState.address
        val type = connectionState.type
        connectionGeneration++
        closeTransportResources(disconnectGatt = true)
        failAllPending(SendFailureReason.DISCONNECTED, error.message)
        markConnected(address, false)
        connectionState = ConnectionUiState(ConnectionPhase.ERROR, address, type, error)
    }

    private fun closeTransportResources(disconnectGatt: Boolean) {
        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = null
        bleManager.close(disconnectGatt)
        classicManager.close()

        pendingBondAddress = null
        pendingBondGeneration = 0L
        unregisterBondReceiver()
    }

    private fun completeSend(pending: PendingSend, result: SendResult) {
        if (pendingSends.remove(pending.requestId) == null) return
        lastSendResult = result
        runCatching { pending.onComplete(result) }
    }

    private fun finishUntracked(pending: PendingSend, result: SendResult) {
        lastSendResult = result
        runCatching { pending.onComplete(result) }
    }

    private fun dispatchImmediateResult(result: SendResult, callback: (SendResult) -> Unit) {
        viewModelScope.launch(Dispatchers.Main.immediate) {
            lastSendResult = result
            runCatching { callback(result) }
        }
    }

    private fun failAllPending(reason: SendFailureReason, message: String) {
        val pending = pendingSends.values.toList()
        pendingSends.clear()
        pending.forEach { request ->
            val result = SendResult.Failed(request.requestId, reason, message)
            lastSendResult = result
            runCatching { request.onComplete(result) }
        }
    }



    private fun upsertDevice(device: BluetoothDevice, rssi: Int, scanSource: ConnectionType) {
        val address = try {
            device.address
        } catch (_: SecurityException) {
            return
        }
        val existingIndex = _devices.indexOfFirst { it.address == address }
        val existing = _devices.getOrNull(existingIndex)
        val resolvedType = safeResolvedType(device, existing?.deviceType, scanSource)
        val isPaired = try {
            device.bondState == BluetoothDevice.BOND_BONDED
        } catch (_: SecurityException) {
            existing?.isPaired ?: false
        }
        val updated = BleDevice(
            name = safeDeviceName(device).takeUnless { it == UNKNOWN_DEVICE_NAME }
                ?: existing?.name
                ?: UNKNOWN_DEVICE_NAME,
            address = address,
            rssi = rssi,
            isConnected = address == connectedAddress,
            isPaired = isPaired,
            deviceType = resolvedType
        )
        if (existingIndex < 0 || existing == null) {
            if (_devices.size < MAX_DISCOVERED_DEVICES) _devices.add(updated)
            return
        }
        val materiallyChanged =
            existing.name != updated.name ||
                existing.deviceType != updated.deviceType ||
                existing.isPaired != updated.isPaired ||
                existing.isConnected != updated.isConnected ||
                kotlin.math.abs(existing.rssi - updated.rssi) >= MIN_RSSI_CHANGE_DBM
        if (materiallyChanged) _devices[existingIndex] = updated
    }

    private fun safeResolvedType(
        device: BluetoothDevice,
        discoveredType: ConnectionType?,
        scanSource: ConnectionType? = null
    ): ConnectionType = try {
        resolveDeviceType(device.type, discoveredType, scanSource)
    } catch (_: SecurityException) {
        discoveredType ?: scanSource ?: ConnectionType.CLASSIC
    }

    private fun safeDeviceName(device: BluetoothDevice): String = try {
        device.name?.takeIf { it.isNotBlank() } ?: UNKNOWN_DEVICE_NAME
    } catch (_: SecurityException) {
        UNKNOWN_DEVICE_NAME
    }

    private fun rssiFor(address: String): Int =
        _devices.firstOrNull { it.address == address }?.rssi ?: DEFAULT_RSSI

    private fun markConnected(address: String?, connected: Boolean) {
        if (address == null) return
        val deviceIndex = _devices.indexOfFirst { it.address == address }
        if (deviceIndex >= 0) _devices[deviceIndex] = _devices[deviceIndex].copy(isConnected = connected)
        val pairedIndex = _pairedDevices.indexOfFirst { it.address == address }
        if (pairedIndex >= 0) {
            _pairedDevices[pairedIndex] = _pairedDevices[pairedIndex].copy(isConnected = connected)
        }
    }

    private fun hasActiveConnection(): Boolean =
        connectionState.phase !in setOf(ConnectionPhase.DISCONNECTED, ConnectionPhase.ERROR) ||
            bleManager.hasGatt() || classicManager.hasSocket() || pendingBondAddress != null

    private fun setIdleError(kind: TransportErrorKind, message: String) {
        if (!hasActiveConnection()) {
            connectionState = ConnectionUiState(
                ConnectionPhase.ERROR,
                error = TransportError(kind, message)
            )
        }
    }

    private fun isAdapterEnabled(adapter: BluetoothAdapter): Boolean? = try {
        adapter.isEnabled
    } catch (error: SecurityException) {
        setIdleError(
            TransportErrorKind.SECURITY_ERROR,
            error.message ?: "Cannot access Bluetooth adapter state"
        )
        null
    }

    private fun updateScanningState() {
        isScanning = bleScanActive || classicScanActive
    }

    private fun stopScanForClear() {
        scanTimeoutJob?.cancel()
        scanTimeoutJob = null
        try {
            if (bleScanActive) bluetoothAdapter?.bluetoothLeScanner?.stopScan(leScanCallback)
        } catch (_: SecurityException) {
            // Cleanup must continue even if permission was revoked.
        } catch (_: IllegalStateException) {
            // The adapter may already be disabled.
        }
        try {
            if (classicScanActive) bluetoothAdapter?.cancelDiscovery()
        } catch (_: SecurityException) {
            // Cleanup must continue even if permission was revoked.
        }
        bleScanActive = false
        classicScanActive = false
        isScanning = false
    }

    private fun registerClassicReceiver() {
        if (classicReceiverRegistered) return
        app.registerReceiver(
            classicReceiver,
            IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            }
        )
        classicReceiverRegistered = true
    }

    private fun registerBondReceiver() {
        if (bondReceiverRegistered) return
        app.registerReceiver(
            bondReceiver,
            IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        )
        bondReceiverRegistered = true
    }

    private fun unregisterClassicReceiver() {
        if (!classicReceiverRegistered) return
        try {
            app.unregisterReceiver(classicReceiver)
        } catch (_: IllegalArgumentException) {
            // Already unregistered by the platform.
        }
        classicReceiverRegistered = false
    }

    private fun unregisterBondReceiver() {
        if (!bondReceiverRegistered) return
        try {
            app.unregisterReceiver(bondReceiver)
        } catch (_: IllegalArgumentException) {
            // Already unregistered by the platform.
        }
        bondReceiverRegistered = false
    }

    private fun loadTransportConfig(): TransportConfig = TransportConfig(
        bleServiceUuid = parseUuid(preferences.getString(KEY_BLE_SERVICE_UUID, null))
            ?: DEFAULT_BLE_SERVICE_UUID,
        bleCharacteristicUuid = parseUuid(preferences.getString(KEY_BLE_CHARACTERISTIC_UUID, null))
            ?: DEFAULT_BLE_CHARACTERISTIC_UUID,
        sppUuid = parseUuid(preferences.getString(KEY_SPP_UUID, null)) ?: DEFAULT_SPP_UUID
    )

    private fun parseUuid(text: String?): UUID? = try {
        text?.trim()?.takeIf { it.isNotEmpty() }?.let(UUID::fromString)
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun Intent.bluetoothDeviceExtra(): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    override fun onCleared() {
        connectionGeneration++
        stopScanForClear()
        closeTransportResources(disconnectGatt = true)
        failAllPending(SendFailureReason.DISCONNECTED, "ViewModel cleared")
        unregisterClassicReceiver()
        unregisterBondReceiver()
        super.onCleared()
    }

    private companion object {
        const val PREFERENCES_NAME = "transport_config"
        const val KEY_BLE_SERVICE_UUID = "ble_service_uuid"
        const val KEY_BLE_CHARACTERISTIC_UUID = "ble_characteristic_uuid"
        const val KEY_SPP_UUID = "spp_uuid"
        const val UNKNOWN_DEVICE_NAME = "未知设备"
        const val DEFAULT_RSSI = -80
        const val SCAN_TIMEOUT_MS = 15_000L
        const val CONNECTION_TIMEOUT_MS = 15_000L
        const val MAX_COMMAND_PAYLOAD_BYTES = 4096
        const val MAX_PENDING_SENDS = 32
        const val MAX_DISCOVERED_DEVICES = 100
        const val MIN_RSSI_CHANGE_DBM = 3


        val DEFAULT_BLE_SERVICE_UUID: UUID =
            UUID.fromString("0000FFE0-0000-1000-8000-00805F9B34FB")
        val DEFAULT_BLE_CHARACTERISTIC_UUID: UUID =
            UUID.fromString("0000FFE1-0000-1000-8000-00805F9B34FB")
        val DEFAULT_SPP_UUID: UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}

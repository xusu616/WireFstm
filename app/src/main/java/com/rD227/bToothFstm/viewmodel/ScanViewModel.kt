package com.rD227.bToothFstm.viewmodel

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.bluetooth.BluetoothStatusCodes
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
import com.rD227.bToothFstm.data.LogEntry
import com.rD227.bToothFstm.data.ScanData.BleDevice
import com.rD227.bToothFstm.data.SendFailureReason
import com.rD227.bToothFstm.data.SendResult
import com.rD227.bToothFstm.data.TransportConfig
import com.rD227.bToothFstm.data.TransportError
import com.rD227.bToothFstm.data.TransportErrorKind
import com.rD227.bToothFstm.data.Utf8StreamDecoder
import com.rD227.bToothFstm.data.LogData
import com.rD227.bToothFstm.data.UuidField
import com.rD227.bToothFstm.data.UuidUpdateResult
import com.rD227.bToothFstm.data.bleChunkSize
import com.rD227.bToothFstm.data.chunkPayload
import com.rD227.bToothFstm.data.frameCommand
import com.rD227.bToothFstm.data.resolveDeviceType
import com.rD227.bToothFstm.data.selectBleWriteType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
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

    private var bluetoothGatt: BluetoothGatt? = null
    private var bleCharacteristic: BluetoothGattCharacteristic? = null
    private var bleWriteType: Int? = null
    private var negotiatedMtu = DEFAULT_MTU
    private var bleDecoder: Utf8StreamDecoder? = null
    private var connectionTimeoutJob: Job? = null
    private var setupTimeoutJob: Job? = null
    private var bleChunkTimeoutJob: Job? = null
    private val bleSendQueue = ArrayDeque<PendingSend>()
    private var activeBleSend: ActiveBleSend? = null

    private var classicSocket: BluetoothSocket? = null
    private var classicSendChannel: Channel<PendingSend>? = null
    private var classicConnectJob: Job? = null
    private var classicWriterJob: Job? = null
    private var classicReaderJob: Job? = null
    private var classicSendTimeoutJob: Job? = null
    private var classicSendTimedOut = false
    private var classicDecoder: Utf8StreamDecoder? = null

    private val pendingSends = linkedMapOf<Long, PendingSend>()
    private val nextSendId = AtomicLong(0L)

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
            connectGatt(remoteDevice, generation)
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

    fun sendCommand(text: String, onComplete: (SendResult) -> Unit = {}): Long {
        val requestId = nextSendId.incrementAndGet()
        if (text.isBlank()) {
            dispatchImmediateResult(
                SendResult.Failed(requestId, SendFailureReason.EMPTY_COMMAND, "Command must not be blank"),
                onComplete
            )
            return requestId
        }

        val payload = frameCommand(text)
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
                ConnectionType.BLE -> {
                    bleSendQueue.addLast(pending)
                    startNextBleSend(connectionGeneration)
                }
                ConnectionType.CLASSIC -> {
                    val channel = classicSendChannel
                    val queued = channel?.trySend(pending)
                    if (queued == null || queued.isFailure) {
                        completeSend(
                            pending,
                            SendResult.Failed(
                                requestId,
                                if (channel == null || queued?.exceptionOrNull() != null) {
                                    SendFailureReason.DISCONNECTED
                                } else {
                                    SendFailureReason.QUEUE_FULL
                                },
                                if (channel == null || queued?.exceptionOrNull() != null) {
                                    "Classic writer is unavailable"
                                } else {
                                    "Send queue is full"
                                }
                            )
                        )
                    }
                }
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

    private fun connectGatt(device: BluetoothDevice, generation: Long) {
        connectionState = ConnectionUiState(
            ConnectionPhase.CONNECTING,
            device.address,
            ConnectionType.BLE
        )
        val callback = createGattCallback(generation)
        try {
            val gatt = device.connectGatt(app, false, callback, BluetoothDevice.TRANSPORT_LE)
            if (generation != connectionGeneration) {
                safeCloseGatt(gatt, disconnect = true)
            } else if (gatt == null) {
                failConnection(
                    generation,
                    TransportError(TransportErrorKind.CONNECTION_FAILED, "BLE connection could not be started")
                )
            } else {
                bluetoothGatt = gatt
                bleDecoder = Utf8StreamDecoder()
            }
        } catch (error: SecurityException) {
            failConnection(
                generation,
                TransportError(TransportErrorKind.SECURITY_ERROR, error.message ?: "BLE connection permission denied")
            )
        }
    }

    private fun createGattCallback(generation: Long) = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (generation != connectionGeneration) {
                safeCloseGatt(gatt, disconnect = false)
                return
            }
            viewModelScope.launch(Dispatchers.Main.immediate) {
                if (!isCurrentGatt(gatt, generation)) {
                    safeCloseGatt(gatt, disconnect = false)
                    return@launch
                }
                if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    remoteBleDisconnected(generation, status)
                    return@launch
                }
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    failConnection(
                        generation,
                        TransportError(TransportErrorKind.GATT_ERROR, "GATT connection failed ($status)")
                    )
                    return@launch
                }
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        connectionState = ConnectionUiState(
                            ConnectionPhase.DISCOVERING_SERVICES,
                            gatt.device.address,
                            ConnectionType.BLE
                        )
                        startSetupTimeout(generation)
                        val started = try {
                            gatt.discoverServices()
                        } catch (error: SecurityException) {
                            failConnection(
                                generation,
                                TransportError(
                                    TransportErrorKind.SECURITY_ERROR,
                                    error.message ?: "Service discovery permission denied"
                                )
                            )
                            return@launch
                        }
                        if (!started) {
                            failConnection(
                                generation,
                                TransportError(
                                    TransportErrorKind.GATT_ERROR,
                                    "GATT service discovery could not start"
                                )
                            )
                        }
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            viewModelScope.launch(Dispatchers.Main.immediate) {
                if (!isCurrentGatt(gatt, generation)) {
                    safeCloseGatt(gatt, disconnect = false)
                    return@launch
                }
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    failConnection(
                        generation,
                        TransportError(TransportErrorKind.GATT_ERROR, "Service discovery failed ($status)")
                    )
                    return@launch
                }
                val service = gatt.getService(transportConfig.bleServiceUuid)
                if (service == null) {
                    failConnection(
                        generation,
                        TransportError(
                            TransportErrorKind.SERVICE_NOT_FOUND,
                            "BLE service ${transportConfig.bleServiceUuid} was not found"
                        )
                    )
                    return@launch
                }
                val characteristic = service.getCharacteristic(transportConfig.bleCharacteristicUuid)
                if (characteristic == null) {
                    failConnection(
                        generation,
                        TransportError(
                            TransportErrorKind.CHARACTERISTIC_NOT_FOUND,
                            "BLE characteristic ${transportConfig.bleCharacteristicUuid} was not found"
                        )
                    )
                    return@launch
                }
                val writeType = selectBleWriteType(characteristic.properties)
                val canNotify = characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
                val canIndicate = characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
                if (writeType == null || (!canNotify && !canIndicate)) {
                    failConnection(
                        generation,
                        TransportError(
                            TransportErrorKind.CHARACTERISTIC_NOT_SUPPORTED,
                            "Characteristic must support write and notify or indicate"
                        )
                    )
                    return@launch
                }
                if (characteristic.getDescriptor(CCCD_UUID) == null) {
                    failConnection(
                        generation,
                        TransportError(
                            TransportErrorKind.NOTIFICATION_SETUP_FAILED,
                            "Characteristic is missing the standard CCCD"
                        )
                    )
                    return@launch
                }
                bleCharacteristic = characteristic
                bleWriteType = writeType
                negotiatedMtu = DEFAULT_MTU
                val mtuStarted = try {
                    gatt.requestMtu(REQUESTED_MTU)
                } catch (error: SecurityException) {
                    failConnection(
                        generation,
                        TransportError(
                            TransportErrorKind.SECURITY_ERROR,
                            error.message ?: "MTU request permission denied"
                        )
                    )
                    return@launch
                }
                if (!mtuStarted) enableBleNotifications(gatt, characteristic, generation)
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            viewModelScope.launch(Dispatchers.Main.immediate) {
                if (!isCurrentGatt(gatt, generation)) {
                    safeCloseGatt(gatt, disconnect = false)
                    return@launch
                }
                negotiatedMtu = if (status == BluetoothGatt.GATT_SUCCESS) {
                    maxOf(DEFAULT_MTU, mtu)
                } else {
                    DEFAULT_MTU
                }
                val characteristic = bleCharacteristic ?: return@launch
                enableBleNotifications(gatt, characteristic, generation)
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            viewModelScope.launch(Dispatchers.Main.immediate) {
                if (!isCurrentGatt(gatt, generation)) {
                    safeCloseGatt(gatt, disconnect = false)
                    return@launch
                }
                val characteristic = bleCharacteristic
                if (
                    descriptor.uuid != CCCD_UUID ||
                    characteristic == null ||
                    descriptor.characteristic.uuid != characteristic.uuid
                ) return@launch
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    failConnection(
                        generation,
                        TransportError(
                            TransportErrorKind.NOTIFICATION_SETUP_FAILED,
                            "CCCD write failed ($status)"
                        )
                    )
                    return@launch
                }
                setupTimeoutJob?.cancel()
                setupTimeoutJob = null
                connectionTimeoutJob?.cancel()
                connectionTimeoutJob = null
                connectionState = ConnectionUiState(
                    ConnectionPhase.CONNECTED,
                    gatt.device.address,
                    ConnectionType.BLE
                )
                markConnected(gatt.device.address, true)
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            viewModelScope.launch(Dispatchers.Main.immediate) {
                handleBleChunkWritten(gatt, characteristic, status, generation)
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val value = characteristic.value?.copyOf() ?: return
            receiveBleBytes(gatt, characteristic, value, generation)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            receiveBleBytes(gatt, characteristic, value.copyOf(), generation)
        }
    }

    private fun enableBleNotifications(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        generation: Long
    ) {
        if (!isCurrentGatt(gatt, generation)) return
        connectionState = ConnectionUiState(
            ConnectionPhase.ENABLING_NOTIFICATIONS,
            gatt.device.address,
            ConnectionType.BLE
        )
        val cccd = characteristic.getDescriptor(CCCD_UUID)
        if (cccd == null) {
            failConnection(
                generation,
                TransportError(TransportErrorKind.NOTIFICATION_SETUP_FAILED, "CCCD is unavailable")
            )
            return
        }
        val enableValue = if (
            characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
        ) {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        }
        try {
            if (!gatt.setCharacteristicNotification(characteristic, true)) {
                failConnection(
                    generation,
                    TransportError(
                        TransportErrorKind.NOTIFICATION_SETUP_FAILED,
                        "Local notification registration failed"
                    )
                )
                return
            }
            val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(cccd, enableValue) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                cccd.value = enableValue
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(cccd)
            }
            if (!started) {
                failConnection(
                    generation,
                    TransportError(
                        TransportErrorKind.NOTIFICATION_SETUP_FAILED,
                        "CCCD write could not start"
                    )
                )
            }
        } catch (error: SecurityException) {
            failConnection(
                generation,
                TransportError(
                    TransportErrorKind.SECURITY_ERROR,
                    error.message ?: "Notification setup permission denied"
                )
            )
        }
    }

    private fun receiveBleBytes(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        generation: Long
    ) {
        viewModelScope.launch(Dispatchers.Main.immediate) {
            if (!isCurrentGatt(gatt, generation)) {
                safeCloseGatt(gatt, disconnect = false)
                return@launch
            }
            if (characteristic.uuid != bleCharacteristic?.uuid) return@launch
            val text = bleDecoder?.decode(value).orEmpty()
            if (text.isNotEmpty()) {
                LogData.appendLog(LogDirection.RECEIVED, text, ConnectionType.BLE, gatt.device.address)
            }
        }
    }

    private fun startSetupTimeout(generation: Long) {
        setupTimeoutJob?.cancel()
        setupTimeoutJob = viewModelScope.launch(Dispatchers.Main.immediate) {
            delay(SETUP_TIMEOUT_MS)
            if (generation == connectionGeneration && connectionState.phase != ConnectionPhase.CONNECTED) {
                failConnection(
                    generation,
                    TransportError(TransportErrorKind.SETUP_TIMEOUT, "BLE setup timed out")
                )
            }
        }
    }

    private fun startNextBleSend(generation: Long) {
        if (generation != connectionGeneration || activeBleSend != null) return
        val next = bleSendQueue.pollFirst() ?: return
        val active = ActiveBleSend(
            pending = next,
            chunks = chunkPayload(next.payload, bleChunkSize(negotiatedMtu))
        )
        activeBleSend = active
        writeActiveBleChunk(generation)
    }

    private fun writeActiveBleChunk(generation: Long) {
        val active = activeBleSend ?: return
        val gatt = bluetoothGatt
        val characteristic = bleCharacteristic
        val writeType = bleWriteType
        if (
            generation != connectionGeneration ||
            gatt == null ||
            characteristic == null ||
            writeType == null ||
            connectionState.phase != ConnectionPhase.CONNECTED
        ) {
            failBleTransport(
                generation,
                TransportError(TransportErrorKind.WRITE_FAILED, "BLE transport is not writable")
            )
            return
        }
        val chunk = active.chunks.getOrNull(active.chunkIndex)
        if (chunk == null) {
            finishActiveBleSend(generation)
            return
        }
        val started = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(characteristic, chunk, writeType) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.writeType = writeType
                @Suppress("DEPRECATION")
                characteristic.value = chunk
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(characteristic)
            }
        } catch (error: SecurityException) {
            failBleTransport(
                generation,
                TransportError(TransportErrorKind.SECURITY_ERROR, error.message ?: "BLE write permission denied")
            )
            return
        } catch (error: IllegalArgumentException) {
            failBleTransport(
                generation,
                TransportError(TransportErrorKind.WRITE_FAILED, error.message ?: "BLE write was rejected")
            )
            return
        }
        if (!started) {
            failBleTransport(
                generation,
                TransportError(TransportErrorKind.WRITE_FAILED, "BLE write could not start")
            )
            return
        }
        bleChunkTimeoutJob?.cancel()
        bleChunkTimeoutJob = viewModelScope.launch(Dispatchers.Main.immediate) {
            delay(BLE_CHUNK_TIMEOUT_MS)
            if (generation == connectionGeneration && activeBleSend === active) {
                failBleTransport(
                    generation,
                    TransportError(TransportErrorKind.WRITE_TIMEOUT, "BLE write timed out")
                )
            }
        }
    }

    private fun handleBleChunkWritten(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        status: Int,
        generation: Long
    ) {
        if (!isCurrentGatt(gatt, generation)) {
            safeCloseGatt(gatt, disconnect = false)
            return
        }
        if (characteristic.uuid != bleCharacteristic?.uuid || activeBleSend == null) return
        bleChunkTimeoutJob?.cancel()
        bleChunkTimeoutJob = null
        if (status != BluetoothGatt.GATT_SUCCESS) {
            failBleTransport(
                generation,
                TransportError(TransportErrorKind.WRITE_FAILED, "BLE write failed ($status)")
            )
            return
        }
        val active = activeBleSend ?: return
        active.chunkIndex++
        if (active.chunkIndex >= active.chunks.size) {
            finishActiveBleSend(generation)
        } else {
            writeActiveBleChunk(generation)
        }
    }

    private fun finishActiveBleSend(generation: Long) {
        val active = activeBleSend ?: return
        activeBleSend = null
        LogData.appendLog(
            LogDirection.SENT,
            active.pending.text,
            active.pending.type,
            active.pending.address
        )
        completeSend(active.pending, SendResult.Sent(active.pending.requestId))
        startNextBleSend(generation)
    }

    private fun failBleTransport(generation: Long, error: TransportError) {
        if (generation != connectionGeneration) return
        failConnection(generation, error)
    }

    private fun connectClassic(device: BluetoothDevice, generation: Long) {
        if (generation != connectionGeneration) return
        pendingBondAddress = null
        pendingBondGeneration = 0L
        unregisterBondReceiver()
        connectionState = ConnectionUiState(
            ConnectionPhase.CONNECTING,
            device.address,
            ConnectionType.CLASSIC
        )
        try {
            bluetoothAdapter?.cancelDiscovery()
        } catch (error: SecurityException) {
            failConnection(
                generation,
                TransportError(TransportErrorKind.SECURITY_ERROR, error.message ?: "Cannot cancel discovery")
            )
            return
        }

        classicConnectJob?.cancel()
        classicConnectJob = viewModelScope.launch(Dispatchers.IO) {
            var socket: BluetoothSocket? = null
            try {
                socket = device.createRfcommSocketToServiceRecord(transportConfig.sppUuid)
                withContext(Dispatchers.Main.immediate) {
                    if (generation != connectionGeneration) {
                        safeCloseSocket(socket)
                        return@withContext
                    }
                    classicSocket = socket
                }
                if (generation != connectionGeneration) {
                    safeCloseSocket(socket)
                    return@launch
                }
                socket.connect()
                withContext(Dispatchers.Main.immediate) {
                    if (generation != connectionGeneration || classicSocket !== socket) {
                        safeCloseSocket(socket)
                        return@withContext
                    }
                    startClassicIo(socket, device.address, generation)
                }
            } catch (error: CancellationException) {
                safeCloseSocket(socket)
                throw error
            } catch (error: SecurityException) {
                safeCloseSocket(socket)
                withContext(Dispatchers.Main.immediate) {
                    if (generation == connectionGeneration) {
                        failConnection(
                            generation,
                            TransportError(
                                TransportErrorKind.SECURITY_ERROR,
                                error.message ?: "Classic connection permission denied"
                            )
                        )
                    }
                }
            } catch (error: IOException) {
                safeCloseSocket(socket)
                withContext(Dispatchers.Main.immediate) {
                    if (generation == connectionGeneration) {
                        failConnection(
                            generation,
                            TransportError(
                                TransportErrorKind.CONNECTION_FAILED,
                                error.message ?: "Classic connection failed"
                            )
                        )
                    }
                }
            }
        }
    }

    private fun startClassicIo(socket: BluetoothSocket, address: String, generation: Long) {
        val channel = Channel<PendingSend>(MAX_PENDING_SENDS)
        classicSendChannel = channel
        classicDecoder = Utf8StreamDecoder()
        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = null
        connectionState = ConnectionUiState(
            ConnectionPhase.CONNECTED,
            address,
            ConnectionType.CLASSIC
        )
        markConnected(address, true)

        classicWriterJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                for (pending in channel) {
                    withContext(Dispatchers.Main.immediate) {
                        startClassicSendTimeout(socket, generation)
                    }
                    socket.outputStream.write(pending.payload)
                    socket.outputStream.flush()
                    withContext(Dispatchers.Main.immediate) {
                        if (classicSendTimedOut) {
                            failConnection(
                                generation,
                                TransportError(TransportErrorKind.WRITE_TIMEOUT, "Classic write timed out")
                            )
                            return@withContext
                        }
                        cancelClassicSendTimeout()
                        if (generation == connectionGeneration && pendingSends.containsKey(pending.requestId)) {
                            LogData.appendLog(
                                LogDirection.SENT,
                                pending.text,
                                ConnectionType.CLASSIC,
                                address
                            )
                            completeSend(pending, SendResult.Sent(pending.requestId))
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: SecurityException) {
                withContext(Dispatchers.Main.immediate) {
                    cancelClassicSendTimeout()
                    if (generation == connectionGeneration) {
                        failConnection(
                            generation,
                            TransportError(
                                TransportErrorKind.SECURITY_ERROR,
                                error.message ?: "Classic write permission denied"
                            )
                        )
                    }
                }
            } catch (error: IOException) {
                withContext(Dispatchers.Main.immediate) {
                    val timedOut = classicSendTimedOut
                    cancelClassicSendTimeout()
                    if (generation == connectionGeneration) {
                        failConnection(
                            generation,
                            TransportError(
                                if (timedOut) TransportErrorKind.WRITE_TIMEOUT else TransportErrorKind.WRITE_FAILED,
                                error.message ?: if (timedOut) "Classic write timed out" else "Classic write failed"
                            )
                        )
                    }
                }
            }
        }

        classicReaderJob = viewModelScope.launch(Dispatchers.IO) {
            val buffer = ByteArray(CLASSIC_READ_BUFFER_SIZE)
            try {
                while (true) {
                    val count = socket.inputStream.read(buffer)
                    if (count < 0) {
                        withContext(Dispatchers.Main.immediate) {
                            if (generation == connectionGeneration) {
                                failConnection(
                                    generation,
                                    TransportError(
                                        TransportErrorKind.REMOTE_DISCONNECTED,
                                        "Classic device closed the connection"
                                    )
                                )
                            }
                        }
                        return@launch
                    }
                    if (count == 0) continue
                    val copy = buffer.copyOf(count)
                    withContext(Dispatchers.Main.immediate) {
                        if (generation == connectionGeneration) {
                            val text = classicDecoder?.decode(copy).orEmpty()
                            if (text.isNotEmpty()) {
                                LogData.appendLog(LogDirection.RECEIVED, text, ConnectionType.CLASSIC, address)
                            }
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: SecurityException) {
                withContext(Dispatchers.Main.immediate) {
                    if (generation == connectionGeneration) {
                        failConnection(
                            generation,
                            TransportError(
                                TransportErrorKind.SECURITY_ERROR,
                                error.message ?: "Classic read permission denied"
                            )
                        )
                    }
                }
            } catch (error: IOException) {
                withContext(Dispatchers.Main.immediate) {
                    val timedOut = classicSendTimedOut
                    if (generation == connectionGeneration) {
                        failConnection(
                            generation,
                            TransportError(
                                if (timedOut) TransportErrorKind.WRITE_TIMEOUT else TransportErrorKind.IO_ERROR,
                                error.message ?: if (timedOut) "Classic write timed out" else "Classic read failed"
                            )
                        )
                    }
                }
            }
        }
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
        val bleTail = if (type == ConnectionType.BLE) bleDecoder?.finish().orEmpty() else ""
        val classicTail = if (type == ConnectionType.CLASSIC) classicDecoder?.finish().orEmpty() else ""
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

    private fun remoteBleDisconnected(generation: Long, status: Int) {
        if (generation != connectionGeneration) return
        val message = if (status == BluetoothGatt.GATT_SUCCESS) {
            "BLE device disconnected"
        } else {
            "BLE device disconnected (GATT status $status)"
        }
        failConnection(
            generation,
            TransportError(TransportErrorKind.REMOTE_DISCONNECTED, message)
        )
    }

    private fun startClassicSendTimeout(socket: BluetoothSocket, generation: Long) {
        cancelClassicSendTimeout()
        classicSendTimedOut = false
        classicSendTimeoutJob = viewModelScope.launch(Dispatchers.Main.immediate) {
            delay(CLASSIC_SEND_TIMEOUT_MS)
            if (generation == connectionGeneration && classicSocket === socket) {
                classicSendTimedOut = true
                classicSendTimeoutJob = null
                classicSocket = null
                safeCloseSocket(socket)
            }
        }
    }

    private fun cancelClassicSendTimeout() {
        classicSendTimeoutJob?.cancel()
        classicSendTimeoutJob = null
    }

    private fun closeTransportResources(disconnectGatt: Boolean) {
        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = null
        setupTimeoutJob?.cancel()
        setupTimeoutJob = null
        bleChunkTimeoutJob?.cancel()
        bleChunkTimeoutJob = null

        val gatt = bluetoothGatt
        bluetoothGatt = null
        bleCharacteristic = null
        bleWriteType = null
        negotiatedMtu = DEFAULT_MTU
        bleDecoder = null
        activeBleSend = null
        bleSendQueue.clear()
        safeCloseGatt(gatt, disconnectGatt)

        classicSendChannel?.close()
        classicSendChannel = null
        classicConnectJob?.cancel()
        classicConnectJob = null
        classicWriterJob?.cancel()
        classicWriterJob = null
        classicReaderJob?.cancel()
        classicReaderJob = null
        cancelClassicSendTimeout()
        classicSendTimedOut = false
        val socket = classicSocket
        classicSocket = null
        safeCloseSocket(socket)
        classicDecoder = null

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
            bluetoothGatt != null || classicSocket != null || pendingBondAddress != null

    private fun isCurrentGatt(gatt: BluetoothGatt, generation: Long): Boolean =
        generation == connectionGeneration && bluetoothGatt === gatt

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

    private fun safeCloseGatt(gatt: BluetoothGatt?, disconnect: Boolean) {
        if (gatt == null) return
        try {
            if (disconnect) gatt.disconnect()
        } catch (_: SecurityException) {
            // Closing still releases the local GATT resource.
        } finally {
            gatt.close()
        }
    }

    private fun safeCloseSocket(socket: BluetoothSocket?) {
        try {
            socket?.close()
        } catch (_: IOException) {
            // The resource is already unusable.
        }
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

    private data class PendingSend(
        val requestId: Long,
        val text: String,
        val payload: ByteArray,
        val type: ConnectionType,
        val address: String,
        val onComplete: (SendResult) -> Unit
    )

    private data class ActiveBleSend(
        val pending: PendingSend,
        val chunks: List<ByteArray>,
        var chunkIndex: Int = 0
    )



    private companion object {
        const val PREFERENCES_NAME = "transport_config"
        const val KEY_BLE_SERVICE_UUID = "ble_service_uuid"
        const val KEY_BLE_CHARACTERISTIC_UUID = "ble_characteristic_uuid"
        const val KEY_SPP_UUID = "spp_uuid"
        const val UNKNOWN_DEVICE_NAME = "未知设备"
        const val DEFAULT_RSSI = -80
        const val DEFAULT_MTU = 23
        const val REQUESTED_MTU = 517
        const val SCAN_TIMEOUT_MS = 15_000L
        const val CONNECTION_TIMEOUT_MS = 15_000L
        const val SETUP_TIMEOUT_MS = 10_000L
        const val BLE_CHUNK_TIMEOUT_MS = 5_000L
        const val CLASSIC_SEND_TIMEOUT_MS = 5_000L
        const val CLASSIC_READ_BUFFER_SIZE = 1024
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
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
    }
}

package com.rD227.bToothFstm.util

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.os.Build
import com.rD227.bToothFstm.data.ConnectionPhase
import com.rD227.bToothFstm.data.ConnectionType
import com.rD227.bToothFstm.data.ConnectionUiState
import com.rD227.bToothFstm.data.LogData
import com.rD227.bToothFstm.data.LogDirection
import com.rD227.bToothFstm.data.PendingSend
import com.rD227.bToothFstm.data.SendResult
import com.rD227.bToothFstm.data.TransportConfig
import com.rD227.bToothFstm.data.TransportError
import com.rD227.bToothFstm.data.TransportErrorKind
import com.rD227.bToothFstm.data.bleChunkSize
import com.rD227.bToothFstm.data.chunkPayload
import com.rD227.bToothFstm.data.selectBleWriteType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.ArrayDeque
import java.util.UUID

/**
 * Owns the BLE connection lifecycle: connecting, service discovery, notification
 * setup, and chunked writes. It keeps all of its own state and reports back to the
 * caller (the ViewModel) through callbacks, so the ViewModel no longer needs to hold
 * any BLE-specific fields.
 */
@SuppressLint("MissingPermission")
class BleConnectionManager(
    private val app: Application,
    private val scope: CoroutineScope,
    private val onState: (ConnectionUiState) -> Unit,
    private val onConnected: (String) -> Unit,
    private val onSendComplete: (PendingSend, SendResult) -> Unit,
    private val onFailed: (Long, TransportError) -> Unit,
) {
    @Volatile
    private var activeGeneration = 0L

    private var bluetoothGatt: BluetoothGatt? = null
    private var bleCharacteristic: BluetoothGattCharacteristic? = null
    private var bleWriteType: Int? = null
    private var negotiatedMtu = DEFAULT_MTU
    private var bleDecoder: Utf8StreamDecoder? = null
    private var setupTimeoutJob: Job? = null
    private var bleChunkTimeoutJob: Job? = null
    private val bleSendQueue = ArrayDeque<PendingSend>()
    private var activeBleSend: ActiveBleSend? = null
    private var connected = false

    fun connect(device: BluetoothDevice, generation: Long, config: TransportConfig) {
        activeGeneration = generation
        connected = false
        onState(ConnectionUiState(ConnectionPhase.CONNECTING, device.address, ConnectionType.BLE))
        val callback = createGattCallback(generation, config)
        try {
            val gatt = device.connectGatt(app, false, callback, BluetoothDevice.TRANSPORT_LE)
            if (generation != activeGeneration) {
                safeCloseGatt(gatt, disconnect = true)
            } else if (gatt == null) {
                fail(
                    generation,
                    TransportError(TransportErrorKind.CONNECTION_FAILED, "BLE connection could not be started")
                )
            } else {
                bluetoothGatt = gatt
                bleDecoder = Utf8StreamDecoder()
            }
        } catch (error: SecurityException) {
            fail(
                generation,
                TransportError(TransportErrorKind.SECURITY_ERROR, error.message ?: "BLE connection permission denied")
            )
        }
    }

    fun enqueue(pending: PendingSend, generation: Long) {
        if (generation != activeGeneration) return
        bleSendQueue.addLast(pending)
        startNextBleSend(generation)
    }

    fun close(disconnectGatt: Boolean) {
        activeGeneration++
        connected = false
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
    }

    fun hasGatt(): Boolean = bluetoothGatt != null

    fun takeTail(): String = bleDecoder?.finish().orEmpty()

    private fun fail(generation: Long, error: TransportError) {
        if (generation != activeGeneration) return
        onFailed(generation, error)
    }

    private fun isCurrent(gatt: BluetoothGatt, generation: Long): Boolean =
        generation == activeGeneration && bluetoothGatt === gatt

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

    private fun createGattCallback(generation: Long, config: TransportConfig) = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (generation != activeGeneration) {
                safeCloseGatt(gatt, disconnect = false)
                return
            }
            scope.launch(Dispatchers.Main.immediate) {
                if (!isCurrent(gatt, generation)) {
                    safeCloseGatt(gatt, disconnect = false)
                    return@launch
                }
                if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    remoteDisconnected(generation, status)
                    return@launch
                }
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    fail(
                        generation,
                        TransportError(TransportErrorKind.GATT_ERROR, "GATT connection failed ($status)")
                    )
                    return@launch
                }
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        onState(
                            ConnectionUiState(
                                ConnectionPhase.DISCOVERING_SERVICES,
                                gatt.device.address,
                                ConnectionType.BLE
                            )
                        )
                        startSetupTimeout(generation)
                        val started = try {
                            gatt.discoverServices()
                        } catch (error: SecurityException) {
                            fail(
                                generation,
                                TransportError(
                                    TransportErrorKind.SECURITY_ERROR,
                                    error.message ?: "Service discovery permission denied"
                                )
                            )
                            return@launch
                        }
                        if (!started) {
                            fail(
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
            scope.launch(Dispatchers.Main.immediate) {
                if (!isCurrent(gatt, generation)) {
                    safeCloseGatt(gatt, disconnect = false)
                    return@launch
                }
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    fail(
                        generation,
                        TransportError(TransportErrorKind.GATT_ERROR, "Service discovery failed ($status)")
                    )
                    return@launch
                }
                val service = gatt.getService(config.bleServiceUuid)
                if (service == null) {
                    fail(
                        generation,
                        TransportError(
                            TransportErrorKind.SERVICE_NOT_FOUND,
                            "BLE service ${config.bleServiceUuid} was not found"
                        )
                    )
                    return@launch
                }
                val characteristic = service.getCharacteristic(config.bleCharacteristicUuid)
                if (characteristic == null) {
                    fail(
                        generation,
                        TransportError(
                            TransportErrorKind.CHARACTERISTIC_NOT_FOUND,
                            "BLE characteristic ${config.bleCharacteristicUuid} was not found"
                        )
                    )
                    return@launch
                }
                val writeType = selectBleWriteType(characteristic.properties)
                val canNotify = characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
                val canIndicate = characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
                if (writeType == null || (!canNotify && !canIndicate)) {
                    fail(
                        generation,
                        TransportError(
                            TransportErrorKind.CHARACTERISTIC_NOT_SUPPORTED,
                            "Characteristic must support write and notify or indicate"
                        )
                    )
                    return@launch
                }
                if (characteristic.getDescriptor(CCCD_UUID) == null) {
                    fail(
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
                    fail(
                        generation,
                        TransportError(
                            TransportErrorKind.SECURITY_ERROR,
                            error.message ?: "MTU request permission denied"
                        )
                    )
                    return@launch
                }
                if (!mtuStarted) enableNotifications(gatt, characteristic, generation)
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            scope.launch(Dispatchers.Main.immediate) {
                if (!isCurrent(gatt, generation)) {
                    safeCloseGatt(gatt, disconnect = false)
                    return@launch
                }
                negotiatedMtu = if (status == BluetoothGatt.GATT_SUCCESS) {
                    maxOf(DEFAULT_MTU, mtu)
                } else {
                    DEFAULT_MTU
                }
                val characteristic = bleCharacteristic ?: return@launch
                enableNotifications(gatt, characteristic, generation)
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            scope.launch(Dispatchers.Main.immediate) {
                if (!isCurrent(gatt, generation)) {
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
                    fail(
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
                connected = true
                onState(
                    ConnectionUiState(
                        ConnectionPhase.CONNECTED,
                        gatt.device.address,
                        ConnectionType.BLE
                    )
                )
                onConnected(gatt.device.address)
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            scope.launch(Dispatchers.Main.immediate) {
                handleChunkWritten(gatt, characteristic, status, generation)
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val value = characteristic.value?.copyOf() ?: return
            receiveBytes(gatt, characteristic, value, generation)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            receiveBytes(gatt, characteristic, value.copyOf(), generation)
        }
    }

    private fun enableNotifications(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        generation: Long
    ) {
        if (!isCurrent(gatt, generation)) return
        onState(
            ConnectionUiState(
                ConnectionPhase.ENABLING_NOTIFICATIONS,
                gatt.device.address,
                ConnectionType.BLE
            )
        )
        val cccd = characteristic.getDescriptor(CCCD_UUID)
        if (cccd == null) {
            fail(
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
                fail(
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
                fail(
                    generation,
                    TransportError(
                        TransportErrorKind.NOTIFICATION_SETUP_FAILED,
                        "CCCD write could not start"
                    )
                )
            }
        } catch (error: SecurityException) {
            fail(
                generation,
                TransportError(
                    TransportErrorKind.SECURITY_ERROR,
                    error.message ?: "Notification setup permission denied"
                )
            )
        }
    }

    private fun receiveBytes(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        generation: Long
    ) {
        scope.launch(Dispatchers.Main.immediate) {
            if (!isCurrent(gatt, generation)) {
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

    private fun remoteDisconnected(generation: Long, status: Int) {
        if (generation != activeGeneration) return
        val message = if (status == BluetoothGatt.GATT_SUCCESS) {
            "BLE device disconnected"
        } else {
            "BLE device disconnected (GATT status $status)"
        }
        fail(generation, TransportError(TransportErrorKind.REMOTE_DISCONNECTED, message))
    }

    private fun startSetupTimeout(generation: Long) {
        setupTimeoutJob?.cancel()
        setupTimeoutJob = scope.launch(Dispatchers.Main.immediate) {
            delay(SETUP_TIMEOUT_MS)
            if (generation == activeGeneration && !connected) {
                fail(
                    generation,
                    TransportError(TransportErrorKind.SETUP_TIMEOUT, "BLE setup timed out")
                )
            }
        }
    }

    private fun startNextBleSend(generation: Long) {
        if (generation != activeGeneration || activeBleSend != null) return
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
            generation != activeGeneration ||
            gatt == null ||
            characteristic == null ||
            writeType == null ||
            !connected
        ) {
            fail(
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
            fail(
                generation,
                TransportError(TransportErrorKind.SECURITY_ERROR, error.message ?: "BLE write permission denied")
            )
            return
        } catch (error: IllegalArgumentException) {
            fail(
                generation,
                TransportError(TransportErrorKind.WRITE_FAILED, error.message ?: "BLE write was rejected")
            )
            return
        }
        if (!started) {
            fail(
                generation,
                TransportError(TransportErrorKind.WRITE_FAILED, "BLE write could not start")
            )
            return
        }
        bleChunkTimeoutJob?.cancel()
        bleChunkTimeoutJob = scope.launch(Dispatchers.Main.immediate) {
            delay(BLE_CHUNK_TIMEOUT_MS)
            if (generation == activeGeneration && activeBleSend === active) {
                fail(
                    generation,
                    TransportError(TransportErrorKind.WRITE_TIMEOUT, "BLE write timed out")
                )
            }
        }
    }

    private fun handleChunkWritten(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        status: Int,
        generation: Long
    ) {
        if (!isCurrent(gatt, generation)) {
            safeCloseGatt(gatt, disconnect = false)
            return
        }
        if (characteristic.uuid != bleCharacteristic?.uuid || activeBleSend == null) return
        bleChunkTimeoutJob?.cancel()
        bleChunkTimeoutJob = null
        if (status != BluetoothGatt.GATT_SUCCESS) {
            fail(
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
        onSendComplete(active.pending, SendResult.Sent(active.pending.requestId))
        startNextBleSend(generation)
    }

    private data class ActiveBleSend(
        val pending: PendingSend,
        val chunks: List<ByteArray>,
        var chunkIndex: Int = 0
    )

    private companion object {
        const val DEFAULT_MTU = 23
        const val REQUESTED_MTU = 517
        const val SETUP_TIMEOUT_MS = 10_000L
        const val BLE_CHUNK_TIMEOUT_MS = 5_000L
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
    }
}
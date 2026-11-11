package com.rD227.bToothFstm.data

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import java.util.UUID

enum class ConnectionType { BLE, CLASSIC }

enum class ConnectionPhase {
    DISCONNECTED,
    PAIRING,
    CONNECTING,
    DISCOVERING_SERVICES,
    ENABLING_NOTIFICATIONS,
    CONNECTED,
    DISCONNECTING,
    ERROR
}

enum class TransportErrorKind {
    ADAPTER_UNAVAILABLE,
    PERMISSION_DENIED,
    SCAN_FAILED,
    INVALID_DEVICE,
    PAIRING_FAILED,
    CONNECTION_FAILED,
    GATT_ERROR,
    SERVICE_NOT_FOUND,
    CHARACTERISTIC_NOT_FOUND,
    CHARACTERISTIC_NOT_SUPPORTED,
    NOTIFICATION_SETUP_FAILED,
    SETUP_TIMEOUT,
    IO_ERROR,
    SECURITY_ERROR,
    REMOTE_DISCONNECTED,
    WRITE_FAILED,
    WRITE_TIMEOUT
}

data class TransportError(
    val kind: TransportErrorKind,
    val message: String
)

data class ConnectionUiState(
    val phase: ConnectionPhase = ConnectionPhase.DISCONNECTED,
    val address: String? = null,
    val type: ConnectionType? = null,
    val error: TransportError? = null
)

data class TransportConfig(
    val bleServiceUuid: UUID,
    val bleCharacteristicUuid: UUID,
    val sppUuid: UUID
)

enum class UuidField { BLE_SERVICE, BLE_CHARACTERISTIC, SPP }

sealed class UuidUpdateResult {
    data class Success(val config: TransportConfig) : UuidUpdateResult()
    data class Invalid(val field: UuidField, val value: String) : UuidUpdateResult()
}

enum class LogDirection { SENT, RECEIVED }

data class LogEntry(
    val id: Long,
    val timestamp: Long,
    val direction: LogDirection,
    val text: String,
    val type: ConnectionType,
    val address: String
)

enum class SendFailureReason {
    EMPTY_COMMAND,
    COMMAND_TOO_LARGE,
    QUEUE_FULL,
    NOT_CONNECTED,
    DISCONNECTED,
    WRITE_FAILED,
    TIMEOUT,
    SECURITY_ERROR
}

sealed class SendResult {
    abstract val requestId: Long

    data class Sent(override val requestId: Long) : SendResult()

    data class Failed(
        override val requestId: Long,
        val reason: SendFailureReason,
        val message: String
    ) : SendResult()
}

data class PendingSend(
    val requestId: Long,
    val text: String,
    val payload: ByteArray,
    val type: ConnectionType,
    val address: String,
    val onComplete: (SendResult) -> Unit
)

fun frameCommand(
    text: String,
    addChecksum: Boolean = false,
    appendCrlf: Boolean = true,
    hexMode: Boolean = false
): ByteArray {
    val trimmed = text.trimEnd('\r', '\n')
    val data = if (hexMode) {
        parseHexBytes(trimmed) ?: trimmed.toByteArray(Charsets.UTF_8)
    } else if (addChecksum) {
        parseHexBytes(trimmed) ?: trimmed.toByteArray(Charsets.UTF_8)
    } else {
        trimmed.toByteArray(Charsets.UTF_8)
    }
    val withChecksum = if (addChecksum) appendChecksum(data) else data
    return if (appendCrlf) withChecksum + "\r\n".toByteArray(Charsets.UTF_8) else withChecksum
}

/** Parses a hex string such as "12 AB 45" or "0x12 0xAB 0x45" into bytes, or null if not valid hex. */
fun parseHexBytes(text: String): ByteArray? {
    val cleaned = text.trim()
        .replace("0x", "")
        .replace("0X", "")
        .replace(" ", "")
        .replace("\t", "")
        .replace(",", "")
        .replace(":", "")
        .replace("-", "")
    if (cleaned.isEmpty() || cleaned.length % 2 != 0) return null
    return try {
        ByteArray(cleaned.length / 2) { index ->
            cleaned.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    } catch (_: NumberFormatException) {
        null
    }
}

/** Returns the low 8 bits of the sum of all bytes in [data]. */
fun checksum(data: ByteArray): Byte =
    (data.fold(0) { acc, byte -> (acc + (byte.toInt() and 0xFF)) and 0xFF }).toByte()

/** Returns [data] followed by its modulo-256 sum checksum byte. */
fun appendChecksum(data: ByteArray): ByteArray = data + checksum(data)

/** Alias for [appendChecksum]. */
fun addChecksum(data: ByteArray): ByteArray = appendChecksum(data)

fun bleChunkSize(negotiatedMtu: Int): Int =
    minOf(maxOf(1, negotiatedMtu - 3), 512)

fun chunkPayload(payload: ByteArray, maxChunkSize: Int): List<ByteArray> {
    if (payload.isEmpty()) return emptyList()
    val chunkSize = maxOf(1, maxChunkSize)
    return buildList((payload.size + chunkSize - 1) / chunkSize) {
        var offset = 0
        while (offset < payload.size) {
            val end = minOf(payload.size, offset + chunkSize)
            add(payload.copyOfRange(offset, end))
            offset = end
        }
    }
}

fun selectBleWriteType(properties: Int): Int? = when {
    properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 ->
        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
    properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0 ->
        BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
    else -> null
}

fun resolveDeviceType(
    deviceType: Int,
    discoveredType: ConnectionType? = null,
    scanSource: ConnectionType? = null
): ConnectionType = when (deviceType) {
    BluetoothDevice.DEVICE_TYPE_LE -> ConnectionType.BLE
    BluetoothDevice.DEVICE_TYPE_CLASSIC -> ConnectionType.CLASSIC
    BluetoothDevice.DEVICE_TYPE_DUAL -> discoveredType ?: scanSource ?: ConnectionType.BLE
    BluetoothDevice.DEVICE_TYPE_UNKNOWN -> discoveredType ?: scanSource ?: ConnectionType.CLASSIC
    else -> discoveredType ?: scanSource ?: ConnectionType.CLASSIC
}

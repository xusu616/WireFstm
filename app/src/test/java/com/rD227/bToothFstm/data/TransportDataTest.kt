package com.rD227.bToothFstm.data

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TransportDataTest {
    @Test
    fun frameCommand_replacesExistingLineEndingWithSingleCrLf() {
        assertArrayEquals("状态\r\n".toByteArray(Charsets.UTF_8), frameCommand("状态\n"))
        assertArrayEquals("READ\r\n".toByteArray(), frameCommand("READ\r\n"))
    }

    @Test
    fun appendChecksum_appendsLowEightBitsOfByteSum() {
        assertArrayEquals(
            byteArrayOf(0x12, 0xAB.toByte(), 0x45, 0x02),
            appendChecksum(byteArrayOf(0x12, 0xAB.toByte(), 0x45))
        )
        assertArrayEquals(
            byteArrayOf(0x01, 0x02.toByte(), 0x03),
            appendChecksum(byteArrayOf(0x01, 0x02.toByte()))
        )
    }

    @Test
    fun frameCommand_withChecksum_appendsChecksumWithoutCrLf() {
        assertArrayEquals(
            byteArrayOf('R'.code.toByte(), 'E'.code.toByte(), 'A'.code.toByte(), 'D'.code.toByte(), 0x1C),
            frameCommand("READ", addChecksum = true)
        )
        assertArrayEquals(
            byteArrayOf(0x12, 0xAB.toByte(), 0x45, 0x02),
            frameCommand("12 AB 45", addChecksum = true)
        )
    }

    @Test
    fun parseHexBytes_handlesSpacesAndPrefixes() {
        assertArrayEquals(
            byteArrayOf(0x12, 0xAB.toByte(), 0x45),
            parseHexBytes("0x12 0xAB 0x45")
        )
        assertNull(parseHexBytes("READ"))
    }

    @Test
    fun chunkPayload_usesConfiguredPayloadSizeAndReassembles() {
        val payload = frameCommand("1234567890123456789012345678901234567890")
        val chunks = chunkPayload(payload, 20)

        assertEquals(listOf(20, 20, 2), chunks.map(ByteArray::size))
        assertArrayEquals(payload, chunks.fold(ByteArray(0)) { result, chunk -> result + chunk })
    }

    @Test
    fun chunkPayload_clampsInvalidChunkSize() {
        assertEquals(3, chunkPayload(byteArrayOf(1, 2, 3), 0).size)
    }

    @Test
    fun bleChunkSize_capsMtu517PayloadAt512Bytes() {
        assertEquals(512, bleChunkSize(517))
        assertEquals(1, bleChunkSize(3))
    }

    @Test
    fun selectBleWriteType_prefersWriteWithResponse() {
        val both = BluetoothGattCharacteristic.PROPERTY_WRITE or
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE

        assertEquals(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT, selectBleWriteType(both))
        assertEquals(
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
            selectBleWriteType(BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)
        )
        assertNull(selectBleWriteType(BluetoothGattCharacteristic.PROPERTY_READ))
    }

    @Test
    fun resolveDeviceType_preservesDiscoveryAndScanOriginForAmbiguousDevices() {
        assertEquals(ConnectionType.BLE, resolveDeviceType(BluetoothDevice.DEVICE_TYPE_LE))
        assertEquals(ConnectionType.CLASSIC, resolveDeviceType(BluetoothDevice.DEVICE_TYPE_CLASSIC))
        assertEquals(
            ConnectionType.CLASSIC,
            resolveDeviceType(
                BluetoothDevice.DEVICE_TYPE_DUAL,
                ConnectionType.CLASSIC,
                ConnectionType.BLE
            )
        )
        assertEquals(
            ConnectionType.CLASSIC,
            resolveDeviceType(
                BluetoothDevice.DEVICE_TYPE_DUAL,
                scanSource = ConnectionType.CLASSIC
            )
        )
        assertEquals(ConnectionType.BLE, resolveDeviceType(BluetoothDevice.DEVICE_TYPE_DUAL))
        assertEquals(
            ConnectionType.BLE,
            resolveDeviceType(
                BluetoothDevice.DEVICE_TYPE_UNKNOWN,
                scanSource = ConnectionType.BLE
            )
        )
        assertEquals(ConnectionType.CLASSIC, resolveDeviceType(BluetoothDevice.DEVICE_TYPE_UNKNOWN))
    }
}

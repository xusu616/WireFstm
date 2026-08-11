package com.rD227.bToothFstm.data

class ScanData {
    data class BleDevice(
        val name: String,
        val address: String,
        val rssi: Int,
        val isConnected: Boolean = false,
        val isPaired: Boolean = false,
        val deviceType: ConnectionType
    )
}

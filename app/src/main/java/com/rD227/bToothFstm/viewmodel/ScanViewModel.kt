package com.rD227.bToothFstm.viewmodel

import android.app.Application
import android.os.Build
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.rD227.bToothFstm.data.ScanData.BleDevice

class ScanViewModel(application: Application) : AndroidViewModel(application) {

    // 是否已获得蓝牙权限，由 ScanScreen 的 launcher 回调写入
    var hasPermission by mutableStateOf(false)
        private set

    // 扫描开关状态
    var isScanning by mutableStateOf(false)
        private set

    // 扫描到的设备列表，UI 直接观察
    val devices = mutableStateListOf<BleDevice>()

    // ScanScreen 的 rememberLauncherForActivityResult 回调后调用此方法
    fun onPermissionResult(granted: Boolean) {
        hasPermission = granted
        if (granted) startScan()
    }

    fun startScan() {
        if (!hasPermission) return
        isScanning = true
        // TODO: 调用 BluetoothLeScanner 开始扫描，结果 add 到 devices
    }

    fun stopScan() {
        isScanning = false
        // TODO: 停止扫描
    }

    override fun onCleared() {
        super.onCleared()
        stopScan()
    }
}
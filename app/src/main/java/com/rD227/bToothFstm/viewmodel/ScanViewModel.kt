package com.rD227.bToothFstm.viewmodel

import androidx.lifecycle.MutableLiveData
import com.rD227.bToothFstm.data.ScanData

class ScanViewModel {
    private val _scannedDevices = MutableLiveData<List<ScanData.BleDevice>>(emptyList())
    //the previous list can be del now

}
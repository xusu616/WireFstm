package com.rD227.bToothFstm.viewmodel

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.rD227.bToothFstm.data.UuidField
import com.rD227.bToothFstm.data.UuidUpdateResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class ScanViewModelConfigTest {
    private lateinit var application: Application

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        preferences().edit().clear().commit()
    }

    @After
    fun tearDown() {
        preferences().edit().clear().commit()
    }

    @Test
    fun validConfig_isCanonicalizedAndPersists() {
        val first = ScanViewModel(application)
        val service = "12345678-1234-1234-1234-1234567890ab"
        val characteristic = "22345678-1234-1234-1234-1234567890ab"
        val spp = "32345678-1234-1234-1234-1234567890ab"

        val result = first.updateTransportConfig("  $service  ", characteristic, spp)
        val recreated = ScanViewModel(application)

        assertTrue(result is UuidUpdateResult.Success)
        assertEquals(UUID.fromString(service), recreated.transportConfig.bleServiceUuid)
        assertEquals(UUID.fromString(characteristic), recreated.transportConfig.bleCharacteristicUuid)
        assertEquals(UUID.fromString(spp), recreated.transportConfig.sppUuid)
    }

    @Test
    fun invalidConfig_doesNotPartiallyChangeOrPersist() {
        val first = ScanViewModel(application)
        val original = first.transportConfig
        val result = first.updateTransportConfig(
            "12345678-1234-1234-1234-1234567890ab",
            "invalid",
            "32345678-1234-1234-1234-1234567890ab"
        )
        val recreated = ScanViewModel(application)

        assertEquals(UuidField.BLE_CHARACTERISTIC, (result as UuidUpdateResult.Invalid).field)
        assertEquals(original, first.transportConfig)
        assertEquals(original, recreated.transportConfig)
    }

    @Test
    fun corruptStoredField_fallsBackOnlyThatField() {
        val validCharacteristic = "22345678-1234-1234-1234-1234567890ab"
        preferences().edit()
            .putString("ble_service_uuid", "invalid")
            .putString("ble_characteristic_uuid", validCharacteristic)
            .commit()

        val viewModel = ScanViewModel(application)

        assertEquals(
            UUID.fromString("0000FFE0-0000-1000-8000-00805F9B34FB"),
            viewModel.transportConfig.bleServiceUuid
        )
        assertEquals(UUID.fromString(validCharacteristic), viewModel.transportConfig.bleCharacteristicUuid)
    }

    private fun preferences() =
        application.getSharedPreferences("transport_config", Context.MODE_PRIVATE)
}

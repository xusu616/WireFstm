package com.rD227.bToothFstm.viewmodel

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.rD227.bToothFstm.data.QuickCommand
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CommandViewModelTest {
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
    fun noStoredCommands_loadsDefaults() {
        val viewModel = CommandViewModel(application)

        assertEquals(listOf("READ", "RESET", "STATUS"), viewModel.quickCommands.map { it.label })
    }

    @Test
    fun addUpdateDelete_surviveViewModelRecreation() {
        val first = CommandViewModel(application)
        first.addQuickCommand(QuickCommand("LED", "LED:ON", "FlashOn"))
        first.updateQuickCommand(0, QuickCommand("READ DATA", "READ", "Info"))
        first.deleteQuickCommand(1)

        val recreated = CommandViewModel(application)

        assertEquals(listOf("READ DATA", "STATUS", "LED"), recreated.quickCommands.map { it.label })
        assertEquals("FlashOn", recreated.quickCommands.last().iconName)
    }

    @Test
    fun corruptJson_recoversDefaultsAndRepairsStorage() {
        preferences().edit().putString("commands", "not-json").commit()

        val recovered = CommandViewModel(application)
        val recreated = CommandViewModel(application)

        assertEquals(listOf("READ", "RESET", "STATUS"), recovered.quickCommands.map { it.label })
        assertEquals(recovered.quickCommands.toList(), recreated.quickCommands.toList())
    }

    private fun preferences() =
        application.getSharedPreferences("quick_commands", Context.MODE_PRIVATE)
}

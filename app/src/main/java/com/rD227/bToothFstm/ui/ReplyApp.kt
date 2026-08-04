package com.rD227.bToothFstm.ui

import android.content.res.Configuration.UI_MODE_NIGHT_NO
import android.content.res.Configuration.UI_MODE_NIGHT_YES
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.BluetoothSearching
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import com.rD227.bToothFstm.R
import com.rD227.bToothFstm.ui.screens.CommandScreen
import com.rD227.bToothFstm.ui.screens.LogScreen
import com.rD227.bToothFstm.ui.screens.ScanScreen
import com.rD227.bToothFstm.ui.theme.AppTheme

sealed class AppScreen(
    val route: String,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    object Scan : AppScreen(
        route = "scan",
        labelRes = R.string.nav_scan,
        selectedIcon = Icons.Filled.BluetoothSearching,
        unselectedIcon = Icons.Outlined.BluetoothSearching
    )
    object Control : AppScreen(
        route = "control",
        labelRes = R.string.nav_control,
        selectedIcon = Icons.Filled.Terminal,
        unselectedIcon = Icons.Outlined.Terminal
    )
    object Log : AppScreen(
        route = "log",
        labelRes = R.string.nav_log,
        selectedIcon = Icons.Filled.History,
        unselectedIcon = Icons.Outlined.History
    )
}

val bottomNavScreens = listOf(AppScreen.Scan, AppScreen.Control, AppScreen.Log)

@Composable
fun McuLinkApp() {
    var currentScreen by rememberSaveable { mutableStateOf(AppScreen.Scan.route) }

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = androidx.compose.ui.unit.Dp(3f)
            ) {
                bottomNavScreens.forEach { screen ->
                    val selected = currentScreen == screen.route
                    NavigationBarItem(
                        selected = selected,
                        onClick = { currentScreen = screen.route },
                        icon = {
                            Icon(
                                imageVector = if (selected) screen.selectedIcon else screen.unselectedIcon,
                                contentDescription = stringResource(screen.labelRes)
                            )
                        },
                        label = {
                            Text(
                                text = stringResource(screen.labelRes),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }
        }
    ) { _ ->
        when (currentScreen) {
            AppScreen.Scan.route -> ScanScreen(modifier = Modifier.fillMaxSize())
            AppScreen.Control.route -> CommandScreen(connectedDevice = "MCU-STM32-01", modifier = Modifier.fillMaxSize())
            AppScreen.Log.route -> LogScreen(modifier = Modifier.fillMaxSize())
        }
    }
}

@Preview(uiMode = UI_MODE_NIGHT_NO, showBackground = true, name = "Light")
@Preview(uiMode = UI_MODE_NIGHT_YES, showBackground = true, name = "Dark")
@Composable
private fun McuLinkAppPreview() {
    AppTheme(dynamicColor = false) { McuLinkApp() }
}

package com.rD227.bToothFstm.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rD227.bToothFstm.R
import com.rD227.bToothFstm.ui.screens.CommandScreen
import com.rD227.bToothFstm.ui.screens.LogScreen
import com.rD227.bToothFstm.ui.screens.ScanScreen

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
    var selectedIndex by rememberSaveable { mutableStateOf(0) }
    Scaffold(
        bottomBar = {
            BoxWithConstraints() {
                val totalWidth = maxWidth
                val itemCount = bottomNavScreens.size
                val itemWidth = totalWidth/itemCount

                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 3.dp
                ) {
                    bottomNavScreens.forEachIndexed { index,screen ->
                        val selected = index == selectedIndex
                        val squishX = remember { Animatable(1f) }

                        LaunchedEffect(selected) {
                            if (selected) {
                                squishX.animateTo(1.2f, animationSpec = tween(durationMillis = 80))
                                squishX.animateTo(1f, animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                    stiffness = Spring.StiffnessLow
                                ))
                            }
                        }

                        val iconScale by animateFloatAsState(
                            targetValue = if (selected) 1.2f else 1f,
                            label = "iconScale",
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioHighBouncy,
                                stiffness = Spring.StiffnessMedium
                            )
                        )

                        NavigationBarItem(
                            selected = selected,
                            onClick = { selectedIndex = index },
                            icon = {
                                Icon(
                                    imageVector = if (selected) screen.selectedIcon else screen.unselectedIcon,
                                    contentDescription = stringResource(screen.labelRes),
                                    modifier = Modifier.graphicsLayer(
                                        scaleX = squishX.value * iconScale,
                                        scaleY = iconScale
                                    )
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
                                indicatorColor = Color.Transparent,
                                selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        val screenModifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
        when (currentScreen) {
            AppScreen.Scan.route -> ScanScreen(modifier = screenModifier)
            AppScreen.Control.route -> CommandScreen(connectedDevice = "MCU-STM32-01", modifier = screenModifier)
            AppScreen.Log.route -> LogScreen(modifier = screenModifier)
        }
    }
}



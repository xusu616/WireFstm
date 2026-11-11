package com.rD227.bToothFstm.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.automirrored.outlined.BluetoothSearching
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
//import androidx.compose.ui.tooling.preview.Preview
//import com.rD227.bToothFstm.ui.theme.AppTheme
//用于注释掉的preview，和preview都不要删掉
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rD227.bToothFstm.R
import com.rD227.bToothFstm.data.rememberAppSettingsManager
import com.rD227.bToothFstm.ui.components.GlassSidebarBox
import com.rD227.bToothFstm.ui.screens.AboutScreen
import com.rD227.bToothFstm.ui.screens.CommandScreen
import com.rD227.bToothFstm.ui.screens.LogScreen
import com.rD227.bToothFstm.ui.screens.ScanScreen
import com.rD227.bToothFstm.viewmodel.CommandViewModel
import com.rD227.bToothFstm.viewmodel.ScanViewModel

sealed class AppScreen(
    val route: String,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    object Scan : AppScreen(
        route = "scan",
        labelRes = R.string.nav_scan,
        selectedIcon = Icons.AutoMirrored.Filled.BluetoothSearching,
        unselectedIcon = Icons.AutoMirrored.Outlined.BluetoothSearching
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
    val scanViewModel: ScanViewModel = viewModel()
    val commandViewModel: CommandViewModel = viewModel()
    val settingsManager = rememberAppSettingsManager()
    val settings = settingsManager.settings

    // 同步设置至扫描状态
    LaunchedEffect(settings.sortByRssi, settings.filterUnknownDevices) {
        scanViewModel.sortByRssi = settings.sortByRssi
        scanViewModel.filterUnknownDevices = settings.filterUnknownDevices
    }

    var currentScreen by rememberSaveable { mutableStateOf(AppScreen.Scan.route) }
    var previousScreen by rememberSaveable { mutableStateOf(AppScreen.Scan.route) }
    var selectedIndex by rememberSaveable { mutableIntStateOf(0) }
    var isSidebarOpen by rememberSaveable { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            bottomBar = {
                if (currentScreen != "about") {
                    BoxWithConstraints {
                        val totalWidth = maxWidth
                        val itemCount = bottomNavScreens.size
                        val itemWidth = totalWidth / itemCount

                        val indicatorOffsetX by animateDpAsState(
                            targetValue = itemWidth * selectedIndex,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessLow
                            ),
                            label = "indicatorOffset"
                        )

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surface)
                                .navigationBarsPadding()
                        ) {
                            // 第一层：滑动指示器
                            Box(
                                modifier = Modifier
                                    .offset { androidx.compose.ui.unit.IntOffset(indicatorOffsetX.roundToPx(), 0) }
                                    .padding(horizontal = 8.dp)
                                    .size(width = itemWidth - 16.dp, height = 45.dp)
                                    .align(Alignment.CenterStart)
                                    .background(
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                        shape = RoundedCornerShape(16.dp)
                                    )
                            )
                            // 第二层：icon + label 行
                            Row(modifier = Modifier.fillMaxWidth()) {
                                bottomNavScreens.forEachIndexed { index, screen ->
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

                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(64.dp)
                                            .clickable(
                                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                                indication = null
                                            ) {
                                                selectedIndex = index
                                                currentScreen = screen.route
                                            },
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
                                    ) {
                                        Icon(
                                            imageVector = if (selected) screen.selectedIcon else screen.unselectedIcon,
                                            contentDescription = stringResource(screen.labelRes),
                                            tint = if (selected)
                                                MaterialTheme.colorScheme.onPrimaryContainer
                                            else
                                                MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.graphicsLayer(
                                                scaleX = squishX.value * iconScale,
                                                scaleY = iconScale
                                            )
                                        )
                                        Text(
                                            text = stringResource(screen.labelRes),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = if (selected)
                                                MaterialTheme.colorScheme.primary
                                            else
                                                MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        ) { innerPadding ->
            val screenModifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)

            when (currentScreen) {
                AppScreen.Scan.route -> ScanScreen(
                    scanViewModel = scanViewModel,
                    settings = settings,
                    onOpenSettings = { isSidebarOpen = true },
                    modifier = screenModifier
                )
                AppScreen.Control.route -> CommandScreen(
                    scanViewModel = scanViewModel,
                    commandViewModel = commandViewModel,
                    settings = settings,
                    onOpenSettings = { isSidebarOpen = true },
                    modifier = screenModifier
                )
                AppScreen.Log.route -> LogScreen(
                    settings = settings,
                    onOpenSettings = { isSidebarOpen = !isSidebarOpen },
                    isSettingsOpen = isSidebarOpen,
                    modifier = screenModifier
                )
                "about" -> AboutScreen(
                    onBack = { currentScreen = previousScreen },
                    modifier = screenModifier
                )
            }
        }

        // 全局侧边栏抽屉 (参考 shizuku-ftp GlassSidebarBox，展开多个 switch，底部提供关于页导航)
        GlassSidebarBox(
            isOpen = isSidebarOpen,
            onDismiss = { isSidebarOpen = false },
            settingsManager = settingsManager,
            onNavigateAbout = {
                previousScreen = currentScreen
                currentScreen = "about"
            }
        )
    }
}

/**
@Preview(showBackground = true)
@Composable
fun BottomBarPreview() {
    AppTheme {
        var selectedIndex by remember { mutableStateOf(0) }
        BoxWithConstraints {
            val totalWidth = maxWidth
            val itemCount = bottomNavScreens.size
            val itemWidth = totalWidth / itemCount

            //icon x bounce
            val indicatorOffsetX by animateDpAsState(
                targetValue = itemWidth * selectedIndex,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow
                ),
                label = "indicatorOffset"
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
            ) {
                Box(
                    modifier = Modifier
                        .offset { androidx.compose.ui.unit.IntOffset(indicatorOffsetX.roundToPx(), 0) }
                        .size(width = itemWidth - 16.dp, height = 45.dp)
                        .align(Alignment.CenterStart)
                        .padding(horizontal = 8.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(16.dp)
                        )
                )
                Row(modifier = Modifier.fillMaxWidth()) {
                    bottomNavScreens.forEachIndexed { index, screen ->
                        val selected = index == selectedIndex
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .height(64.dp)
                                //.padding(horizontal = 4.dp)
                                .clickable(
                                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                    indication = null
                                ) { selectedIndex = index },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
                        ) {
                            Icon(
                                imageVector = if (selected) screen.selectedIcon else screen.unselectedIcon,
                                contentDescription = stringResource(screen.labelRes),
                                tint = if (selected)
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = stringResource(screen.labelRes),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (selected)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                            )
                        }
                    }
                }
            }
        }
    }
}

 **/
package com.rD227.bToothFstm.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rD227.bToothFstm.R
import com.rD227.bToothFstm.data.AppSettingsManager

val GlassSideMenuWidth: Dp = 285.dp

@Composable
fun GlassSidebarBox(
    isOpen: Boolean,
    onDismiss: () -> Unit,
    settingsManager: AppSettingsManager,
    onNavigateAbout: () -> Unit
) {
    BackHandler(enabled = isOpen) {
        onDismiss()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Scrim (半透明遮罩，点击关闭)
        AnimatedVisibility(
            visible = isOpen,
            enter = fadeIn(tween(250)),
            exit = fadeOut(tween(200))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.42f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss
                    )
            )
        }

        // 侧边栏主体 (参考 shizuku-ftp GlassSidebarBox，无图片及模糊渲染，采用材质容器配合圆角与滑入动画)
        AnimatedVisibility(
            visible = isOpen,
            enter = slideInHorizontally(
                initialOffsetX = { it },
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessLow
                )
            ) + fadeIn(tween(200)),
            exit = slideOutHorizontally(
                targetOffsetX = { it },
                animationSpec = tween(220)
            ) + fadeOut(tween(180)),
            modifier = Modifier.align(Alignment.TopEnd)
        ) {
            val settings = settingsManager.settings
            val shape = RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp)

            Surface(
                modifier = Modifier
                    .width(GlassSideMenuWidth)
                    .fillMaxHeight()
                    .shadow(16.dp, shape = shape)
                    .clip(shape),
                color = MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = 6.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 20.dp)
                ) {
                    // 顶部标题栏
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.settings_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        IconButton(onClick = onDismiss) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(R.string.cancel),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )

                    // 1. 通信与协议设置组
                    SectionHeader(title = stringResource(R.string.settings_section_protocol))
                    SidebarSwitchItem(
                        title = stringResource(R.string.settings_auto_checksum),
                        description = stringResource(R.string.settings_auto_checksum_desc),
                        checked = settings.autoChecksum,
                        onCheckedChange = settingsManager::updateAutoChecksum
                    )
                    SidebarSwitchItem(
                        title = stringResource(R.string.settings_append_crlf),
                        description = stringResource(R.string.settings_append_crlf_desc),
                        checked = settings.appendCrlf,
                        onCheckedChange = settingsManager::updateAppendCrlf
                    )
                    SidebarSwitchItem(
                        title = stringResource(R.string.settings_hex_mode),
                        description = stringResource(R.string.settings_hex_mode_desc),
                        checked = settings.hexMode,
                        onCheckedChange = settingsManager::updateHexMode
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )

                    // 2. 蓝牙扫描与设备排序设置组
                    SectionHeader(title = stringResource(R.string.settings_section_scan))
                    SidebarSwitchItem(
                        title = stringResource(R.string.settings_sort_by_rssi),
                        description = stringResource(R.string.settings_sort_by_rssi_desc),
                        checked = settings.sortByRssi,
                        onCheckedChange = settingsManager::updateSortByRssi
                    )
                    SidebarSwitchItem(
                        title = stringResource(R.string.settings_filter_unknown),
                        description = stringResource(R.string.settings_filter_unknown_desc),
                        checked = settings.filterUnknownDevices,
                        onCheckedChange = settingsManager::updateFilterUnknownDevices
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )

                    // 3. 日志与界面设置组
                    SectionHeader(title = stringResource(R.string.settings_section_log))
                    SidebarSwitchItem(
                        title = stringResource(R.string.settings_show_crlf),
                        description = stringResource(R.string.settings_show_crlf_desc),
                        checked = settings.showCrlf,
                        onCheckedChange = settingsManager::updateShowCrlf
                    )
                    SidebarSwitchItem(
                        title = stringResource(R.string.settings_auto_scroll),
                        description = stringResource(R.string.settings_auto_scroll_desc),
                        checked = settings.autoScrollLogs,
                        onCheckedChange = settingsManager::updateAutoScrollLogs
                    )
                    SidebarSwitchItem(
                        title = stringResource(R.string.settings_show_timestamps),
                        description = stringResource(R.string.settings_show_timestamps_desc),
                        checked = settings.showTimestamps,
                        onCheckedChange = settingsManager::updateShowTimestamps
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )

                    // 4. 关于 / About 入口条目 (点击执行 Nav 跳转独立界面)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = {
                                onDismiss()
                                onNavigateAbout()
                            })
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Info,
                                contentDescription = stringResource(R.string.settings_about_entry),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Column(modifier = Modifier.padding(start = 14.dp)) {
                                Text(
                                    text = stringResource(R.string.settings_about_entry),
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = stringResource(R.string.settings_about_entry_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 4.dp)
    )
}

@Composable
private fun SidebarSwitchItem(
    title: String,
    description: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (!description.isNullOrBlank()) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

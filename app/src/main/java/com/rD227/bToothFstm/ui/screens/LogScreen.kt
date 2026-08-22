package com.rD227.bToothFstm.ui.screens

import android.content.res.Configuration
import android.text.style.BackgroundColorSpan
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.rD227.bToothFstm.R
import com.rD227.bToothFstm.data.ConnectionType
import com.rD227.bToothFstm.data.LogDirection
import com.rD227.bToothFstm.data.LogEntry
import com.rD227.bToothFstm.data.LogFilter
import com.rD227.bToothFstm.ui.theme.AppTheme
import com.rD227.bToothFstm.data.LogData
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LogCRLFDisplay { CLEAN, ESCAPED }

fun formatLogText(text: String, display: LogCRLFDisplay): String = when (display) {
    LogCRLFDisplay.CLEAN -> text.trimEnd('\r', '\n')
    LogCRLFDisplay.ESCAPED -> buildString {
        text.forEach { character ->
            when {
                character == '\\' -> append("\\\\")
                character == '\r' -> append("\\r")
                character == '\n' -> append("\\n")
                character == '\t' -> append("\\t")
                character == '\b' -> append("\\b")
                character == '\u000C' -> append("\\f")
                character.isISOControl() -> append("\\u%04X".format(character.code))
                else -> append(character)
            }
        }
    }
}

@Composable
fun LogScreen(
    modifier: Modifier = Modifier,
    autoChecksum: Boolean = false,
    onAutoChecksumChange: (Boolean) -> Unit = {}
) {
    var filter by remember { mutableStateOf(LogFilter.ALL) }
    var textDisplay by rememberSaveable { mutableStateOf(LogCRLFDisplay.CLEAN) }
    LogScreenContent(
        modifier = modifier,
        filter = filter,
        onFilterChange = { filter = it },
        textDisplay = textDisplay,
        onTextDisplayChange = { textDisplay = it },
        onClear = LogData::clearLogs,
        autoChecksum = autoChecksum,
        onAutoChecksumChange = onAutoChecksumChange
    )
}

@Composable
fun RoundIconButton(iconRes: Int, rotation: Float, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .clickable {
                onClick()
            }
            .padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = iconRes),
            contentDescription = null,
            modifier = Modifier
                .size(28.dp)
                .rotate(rotation),
            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreenContent(
    filter: LogFilter,
    onFilterChange: (LogFilter) -> Unit,
    textDisplay: LogCRLFDisplay,
    onTextDisplayChange: (LogCRLFDisplay) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    rightMenuInitVisible : Boolean = false,
    autoChecksum: Boolean = false,
    onAutoChecksumChange: (Boolean) -> Unit = {}
) {
    val displayedLogs = LogData.displayedLogs(filter)
    val timestampFormatter = remember {
        SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    }

    val rightMenuVisible = remember { mutableStateOf(rightMenuInitVisible) }
    val gearRotation by animateFloatAsState(
        targetValue = if (rightMenuVisible.value) 180f else 0f,
        label = "gear-rotation"
    )

    Box(modifier = Modifier.fillMaxSize(1f)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = stringResource(R.string.log_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                    },
                    actions = {
                        IconButton(onClick = onClear, enabled = LogData.logs.isNotEmpty()) {
                            Icon(
                                Icons.Default.DeleteOutline,
                                contentDescription = stringResource(R.string.log_clear),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                        RoundIconButton(
                            iconRes = R.drawable.gear,
                            rotation = gearRotation,
                            onClick = { rightMenuVisible.value = !rightMenuVisible.value }
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            },
            modifier = modifier
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                LogFilterRow(
                    filter = filter,
                    onFilterChange = onFilterChange
                )
                //LogTextDisplayRow(
                //    textDisplay = textDisplay,
                //    onTextDisplayChange = onTextDisplayChange
                //)
                if (displayedLogs.isEmpty()) {
                    EmptyLogPlaceholder(Modifier.weight(1f))
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(displayedLogs, key = { it.id }) { entry ->
                            LogEntryRow(
                                entry = entry,
                                formattedTimestamp = timestampFormatter.format(Date(entry.timestamp)),
                                textDisplay = textDisplay
                            )
                        }
                    }
                }
            }
        }

        AnimatedVisibility (
                visible = rightMenuVisible.value,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f))
                    .clickable(
                        onClick = {
                            rightMenuVisible.value = false
                        },
                        //enabled = rightMenuVisible.value,
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    )
            )
        }

        AnimatedVisibility(
            visible = rightMenuVisible.value,
            enter = slideInHorizontally(
                initialOffsetX = { it },
                //animationSpec = tween(300)
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessLow
                )
            ) + fadeIn(animationSpec = tween(300)),
            exit = slideOutHorizontally(
                targetOffsetX = { it },
                //animationSpec = tween(300)
            ) + fadeOut(animationSpec = tween(300)),
            modifier = Modifier.align(Alignment.TopEnd)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(270.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .graphicsLayer { clip = true } // 提示系统开启硬件加速
            ) {
                Column {
                    Spacer(modifier = Modifier.padding(10.dp))
                    RowSwitch(
                        name = stringResource(R.string.auto_checksum),
                        checked = autoChecksum,
                        onCheckedChange = onAutoChecksumChange
                    )
                    RowSwitch(
                        name = stringResource(R.string.log_crlf_switch),
                        checked = textDisplay == LogCRLFDisplay.ESCAPED,
                        onCheckedChange = { isChecked ->
                            onTextDisplayChange(
                                if (isChecked) LogCRLFDisplay.ESCAPED else LogCRLFDisplay.CLEAN
                            )
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun RowSwitch(
    name: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = name,
            fontWeight = FontWeight.Light,
            style = MaterialTheme.typography.titleLarge
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

/****/
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogFilterRow(
    filter: LogFilter,
    onFilterChange: (LogFilter) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        LogFilter.entries.forEach { candidate ->
            val label = when (candidate) {
                LogFilter.ALL -> stringResource(R.string.log_filter_all)
                LogFilter.SENT -> stringResource(R.string.log_sent)
                LogFilter.RECEIVED -> stringResource(R.string.log_received)
            }
            FilterChip(
                selected = filter == candidate,
                onClick = { onFilterChange(candidate) },
                label = { Text(label) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
        Spacer(modifier = Modifier.weight(1f))
    }
}

@Composable
private fun LogTextDisplayRow(
    textDisplay: LogCRLFDisplay,
    onTextDisplayChange: (LogCRLFDisplay) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        LogCRLFDisplay.entries.forEach { candidate ->
            FilterChip(
                selected = textDisplay == candidate,
                onClick = { onTextDisplayChange(candidate) },
                label = {
                    Text(
                        stringResource(
                            if (candidate == LogCRLFDisplay.CLEAN) {
                                R.string.log_display_clean
                            } else {
                                R.string.log_display_escaped
                            }
                        )
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    }
}

@Composable
fun LogEntryRow(
    entry: LogEntry,
    formattedTimestamp: String,
    textDisplay: LogCRLFDisplay
) {
    val isSent = entry.direction == LogDirection.SENT
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Surface(
            shape = CircleShape,
            color = if (isSent) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
            modifier = Modifier.size(34.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (isSent) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                    contentDescription = null,
                    tint = if (isSent) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.secondary
                    },
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = buildString {
                        append(
                            if (isSent) {
                                stringResource(R.string.log_sent)
                            } else {
                                stringResource(R.string.log_received)
                            }
                        )
                        append(" · ")
                        append(
                            stringResource(
                                if (entry.type == ConnectionType.BLE) {
                                    R.string.transport_ble
                                } else {
                                    R.string.transport_spp
                                }
                            )
                        )
                        append(" · ")
                        append(compactAddress(entry.address))
                    },
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isSent) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.secondary
                    }
                )
                Text(
                    text = formattedTimestamp,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Surface(
                shape = RoundedCornerShape(
                    topStart = 2.dp,
                    topEnd = 12.dp,
                    bottomStart = 12.dp,
                    bottomEnd = 12.dp
                ),
                color = if (isSent) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                modifier = Modifier.padding(top = 4.dp)
            ) {
                Text(
                    text = formatLogText(entry.text, textDisplay),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp
                    ),
                    color = if (isSent) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }
    }
}

@Composable
fun EmptyLogPlaceholder(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.History,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(56.dp)
            )
            Text(
                text = stringResource(R.string.log_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp)
            )
        }
    }
}

private fun compactAddress(address: String): String {
    val parts = address.split(':')
    return if (parts.size >= 2) parts.takeLast(2).joinToString(":") else address.takeLast(5)
}

@Preview(showBackground = true, name = "Log — Light")
@Preview(
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    name = "Log — Dark"
)
@Composable
private fun LogScreenPreview() {
    AppTheme(dynamicColor = false) {
        LogScreenContent(
            filter = LogFilter.ALL,
            onFilterChange = {},
            textDisplay = LogCRLFDisplay.CLEAN,
            onTextDisplayChange = {},
            onClear = {},
            rightMenuInitVisible = true
        )
    }
}

package com.hatake716.linuxdesktop.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hatake716.linuxdesktop.data.ContainerInfo
import com.hatake716.linuxdesktop.data.ContainerState
import com.hatake716.linuxdesktop.ui.theme.SuccessGreen
import com.hatake716.linuxdesktop.ui.theme.WarningOrange

@Composable
internal fun DesktopsScreen(
    state: MainUiState,
    modifier: Modifier,
    onAdd: () -> Unit,
    onRefresh: () -> Unit,
    onStart: (ContainerInfo) -> Unit,
    onStop: (ContainerInfo) -> Unit,
    onRepair: () -> Unit,
    onLogs: (ContainerInfo) -> Unit,
    onDelete: (ContainerInfo) -> Unit,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            LargeTitleRow(
                refreshing = state.refreshing,
                onRefresh = onRefresh,
            )
        }

        item {
            OverviewCard(state.containers)
        }

        if (state.containers.isEmpty()) {
            item { EmptyDesktopCard(onAdd = onAdd) }
        } else {
            items(state.containers, key = { it.id }) { container ->
                ContainerCard(
                    container = container,
                    liveInstallationLog = state.liveInstallationLogs[container.id].orEmpty(),
                    onStart = { onStart(container) },
                    onStop = { onStop(container) },
                    onRepair = onRepair,
                    onLogs = { onLogs(container) },
                    onDelete = { onDelete(container) },
                )
            }
        }
    }
}

@Composable
private fun LargeTitleRow(refreshing: Boolean, onRefresh: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                "LDFA",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Ubuntu XFCE デスクトップ",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        IconButton(onClick = onRefresh, enabled = !refreshing) {
            if (refreshing) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Rounded.Refresh, contentDescription = "状態を更新")
            }
        }
    }
}

@Composable
private fun OverviewCard(containers: List<ContainerInfo>) {
    val running = containers.count { it.state == ContainerState.RUNNING }
    val installing = containers.count {
        it.state == ContainerState.INSTALLING || it.state == ContainerState.QUEUED
    }

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(54.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.10f),
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Memory, null, modifier = Modifier.size(30.dp))
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "保存済み ${containers.size} 環境",
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    when {
                        running > 0 -> "$running 環境を実行中"
                        installing > 0 -> "$installing 環境を準備中"
                        else -> "AndroidからLinuxデスクトップを起動"
                    },
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.76f),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            StatusDot(active = running > 0)
        }
    }
}

@Composable
private fun StatusDot(active: Boolean) {
    Box(
        modifier = Modifier
            .size(12.dp)
            .clip(CircleShape)
            .background(
                if (active) SuccessGreen
                else MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.38f),
            ),
    )
}

@Composable
private fun EmptyDesktopCard(onAdd: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 34.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(
                modifier = Modifier.size(70.dp),
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Computer, null, modifier = Modifier.size(38.dp))
                }
            }
            Spacer(Modifier.height(18.dp))
            Text(
                "最初のUbuntu環境を作成",
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(7.dp))
            Text(
                "日本語入力とsudoを設定済みのUbuntu XFCEを、LDFAが自動でインストールします。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = onAdd,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Ubuntu環境を作成")
            }
        }
    }
}

@Composable
private fun ContainerCard(
    container: ContainerInfo,
    liveInstallationLog: String,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRepair: () -> Unit,
    onLogs: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val accent = stateColor(container.state)
    val installationActive = container.state == ContainerState.QUEUED ||
        container.state == ContainerState.INSTALLING

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(50.dp),
                    shape = RoundedCornerShape(15.dp),
                    color = accent.copy(alpha = 0.14f),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Computer, null, tint = accent, modifier = Modifier.size(29.dp))
                    }
                }
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        container.name,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 18.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(3.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(accent))
                        Spacer(Modifier.width(7.dp))
                        Text(
                            "Ubuntu · XFCE · ${stateLabel(container.state)}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = "メニュー")
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("ログを表示") },
                            leadingIcon = { Icon(Icons.Rounded.Description, null) },
                            onClick = {
                                menuExpanded = false
                                onLogs()
                            },
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("削除", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = {
                                Icon(
                                    Icons.Rounded.Delete,
                                    null,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onDelete()
                            },
                        )
                    }
                }
            }

            if (container.state.isBusy || container.state == ContainerState.INSTALLING) {
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LinearProgressIndicator(
                        progress = { container.progress / 100f },
                        modifier = Modifier
                            .weight(1f)
                            .height(7.dp)
                            .clip(CircleShape),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "${container.progress}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (installationActive) {
                Spacer(Modifier.height(10.dp))
                LiveInstallationLogPanel(
                    logs = liveInstallationLog,
                    onOpenFullLogs = onLogs,
                )
            }

            Spacer(Modifier.height(13.dp))
            Text(
                container.message.ifBlank { defaultStateMessage(container) },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(16.dp))

            when {
                container.canStop -> Button(
                    onClick = onStop,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Icon(Icons.Rounded.Stop, null)
                    Spacer(Modifier.width(8.dp))
                    Text("停止")
                }
                container.state == ContainerState.READY -> Button(
                    onClick = onStart,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(Icons.Rounded.PlayArrow, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Ubuntu XFCEを開く")
                }
                container.state == ContainerState.FAILED -> OutlinedButton(
                    onClick = onRepair,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(Icons.Rounded.Refresh, null)
                    Spacer(Modifier.width(8.dp))
                    Text("インストールを再開")
                }
                else -> FilledTonalButton(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text("処理中")
                }
            }
        }
    }
}

@Composable
private fun LiveInstallationLogPanel(
    logs: String,
    onOpenFullLogs: () -> Unit,
) {
    val scrollState = rememberScrollState()
    val displayedLogs = logs.ifBlank { "ログの出力を待っています…" }

    LaunchedEffect(displayedLogs) {
        withFrameNanos { }
        scrollState.scrollTo(scrollState.maxValue)
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
    ) {
        Column {
            Row(
                modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Rounded.Terminal,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.inversePrimary,
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    "リアルタイムログ",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                TextButton(onClick = onOpenFullLogs) {
                    Text("全体を表示", style = MaterialTheme.typography.labelSmall)
                }
            }
            Text(
                displayedLogs,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 92.dp, max = 156.dp)
                    .verticalScroll(scrollState)
                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.9f),
            )
        }
    }
}

private fun stateLabel(state: ContainerState): String = when (state) {
    ContainerState.QUEUED -> "待機中"
    ContainerState.INSTALLING -> "インストール中"
    ContainerState.READY -> "停止中"
    ContainerState.STARTING -> "起動中"
    ContainerState.RUNNING -> "実行中"
    ContainerState.STOPPING -> "停止処理中"
    ContainerState.FAILED -> "要修復"
    ContainerState.UNKNOWN -> "状態不明"
}

private fun defaultStateMessage(container: ContainerInfo): String = when (container.state) {
    ContainerState.QUEUED -> "Ubuntuのインストール開始を待っています"
    ContainerState.INSTALLING -> "日本語Ubuntu XFCE環境を準備しています"
    ContainerState.READY -> "ボタンを押すとLinuxデスクトップが開きます"
    ContainerState.STARTING -> "内蔵X11表示サーバーへ接続しています"
    ContainerState.RUNNING -> "LDFAがLinuxデスクトップを維持しています"
    ContainerState.STOPPING -> "Ubuntu XFCEを安全に停止しています"
    ContainerState.FAILED -> "ログを確認するか、インストールを再開してください"
    ContainerState.UNKNOWN -> "状態を更新してください"
}

@Composable
private fun stateColor(state: ContainerState): Color = when (state) {
    ContainerState.RUNNING, ContainerState.READY -> SuccessGreen
    ContainerState.QUEUED,
    ContainerState.INSTALLING,
    ContainerState.STARTING,
    ContainerState.STOPPING -> WarningOrange
    ContainerState.FAILED -> MaterialTheme.colorScheme.error
    ContainerState.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
}

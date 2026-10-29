package com.hatake716.linuxdesktop.ui

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import com.hatake716.linuxdesktop.data.*
import com.hatake716.linuxdesktop.ui.theme.*

@Composable
internal fun DesktopsScreen(
    state: MainUiState,
    modifier: Modifier,
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
                title = "Debian XFCE",
                refreshing = state.refreshing,
                onRefresh = onRefresh,
            )
        }

        item {
            OverviewCard(state.containers)
        }

        if (state.containers.isEmpty()) {
            item { EmptyDesktopCard() }
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
private fun LargeTitleRow(title: String, refreshing: Boolean, onRefresh: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            modifier = Modifier.weight(1f),
            fontSize = 32.sp,
            lineHeight = 38.sp,
            fontWeight = FontWeight.Bold,
        )
        IconButton(onClick = onRefresh, enabled = !refreshing) {
            if (refreshing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Icon(Icons.Rounded.Refresh, contentDescription = "更新")
        }
    }
}

@Composable
private fun OverviewCard(containers: List<ContainerInfo>) {
    val running = containers.count { it.state == ContainerState.RUNNING }
    val installing = containers.count {
        it.state == ContainerState.INSTALLING || it.state == ContainerState.QUEUED
    }

    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primary) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(54.dp),
                shape = RoundedCornerShape(16.dp),
                color = Color.White.copy(alpha = 0.18f),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Memory, null, tint = Color.White, modifier = Modifier.size(30.dp))
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("保存済み ${containers.size} 環境", color = Color.White, fontWeight = FontWeight.SemiBold)
                Text(
                    when {
                        running > 0 -> "$running 環境を実行中"
                        installing > 0 -> "$installing 環境を準備中"
                        else -> "Debian XFCEをアプリ内で起動"
                    },
                    color = Color.White.copy(alpha = 0.78f),
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
            .background(if (active) SuccessGreen else Color.White.copy(alpha = 0.45f)),
    )
}

@Composable
private fun EmptyDesktopCard() {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 34.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(
                modifier = Modifier.size(70.dp),
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Computer, null, modifier = Modifier.size(38.dp))
                }
            }
            Spacer(Modifier.height(18.dp))
            Text("最初のDebian環境を追加", fontWeight = FontWeight.SemiBold, fontSize = 19.sp)
            Spacer(Modifier.height(7.dp))
            Text(
                "右下の＋から、日本語入力とsudoを設定済みのDebian XFCE環境を作成します。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
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
                            "Debian · XFCE · ${stateLabel(container.state)}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = "メニュー")
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text("ログを表示") },
                            leadingIcon = { Icon(Icons.Rounded.Description, null) },
                            onClick = {
                                menuExpanded = false
                                onLogs()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("削除", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) },
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
                        modifier = Modifier.weight(1f).height(7.dp).clip(CircleShape),
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
                    colors = ButtonDefaults.buttonColors(containerColor = DangerRed),
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
                    Text("Debian XFCEを開く")
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
    ContainerState.QUEUED -> "Debianのインストール開始を待っています"
    ContainerState.INSTALLING -> "日本語Debian XFCE環境を準備しています"
    ContainerState.READY -> "タップしてDebian XFCEを起動できます"
    ContainerState.STARTING -> "内蔵X11表示サーバーへ接続しています"
    ContainerState.RUNNING -> "終了を検知した場合は自動的に再起動します"
    ContainerState.STOPPING -> "Debian XFCEを安全に停止しています"
    ContainerState.FAILED -> "ログを確認するか、インストールを再開してください"
    ContainerState.UNKNOWN -> "再読み込みしてください"
}

@Composable
private fun stateColor(state: ContainerState): Color = when (state) {
    ContainerState.RUNNING, ContainerState.READY -> SuccessGreen
    ContainerState.QUEUED, ContainerState.INSTALLING, ContainerState.STARTING, ContainerState.STOPPING -> WarningOrange
    ContainerState.FAILED -> DangerRed
    ContainerState.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
}

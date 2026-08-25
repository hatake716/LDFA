package com.hatake716.linuxdesktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hatake716.linuxdesktop.R
import com.hatake716.linuxdesktop.data.ContainerState
import com.hatake716.linuxdesktop.ui.theme.SuccessGreen

private enum class BackupOverlay { NONE, CREATE, RESTORE }

@Composable
internal fun ToolsScreen(
    state: MainUiState,
    contentPadding: PaddingValues,
    onOpenTerminal: () -> Unit,
    onOpenDisplay: () -> Unit,
    onRepair: () -> Unit,
    onRestored: () -> Unit,
) {
    val hasRunningDesktop = state.containers.any {
        it.state == ContainerState.RUNNING || it.sessionAlive
    }
    var overlay by rememberSaveable { mutableStateOf(BackupOverlay.NONE) }

    when (overlay) {
        BackupOverlay.CREATE -> {
            BackupScreen(
                containers = state.containers,
                onClose = { overlay = BackupOverlay.NONE },
            )
            return
        }
        BackupOverlay.RESTORE -> {
            RestoreScreen(
                existingNames = state.containers.map { it.name }.toSet(),
                onRestored = onRestored,
                onClose = { overlay = BackupOverlay.NONE },
            )
            return
        }
        BackupOverlay.NONE -> Unit
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .statusBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column {
                Text("ツール", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Linuxの画面とターミナルをここから開けます。",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            ToolActionCard(
                icon = Icons.Rounded.DesktopWindows,
                title = "Linux画面",
                description = if (hasRunningDesktop) {
                    "実行中のXFCEデスクトップを表示します"
                } else {
                    "ホームでLinuxを起動すると利用できます"
                },
                enabled = hasRunningDesktop,
                onClick = onOpenDisplay,
            )
        }

        item {
            ToolActionCard(
                icon = Icons.Rounded.Terminal,
                title = "内蔵ターミナル",
                description = "Linuxの管理やコマンド操作に使えます",
                enabled = state.setup.terminalReady,
                onClick = onOpenTerminal,
            )
        }

        item {
            Column {
                Text(
                    text = "バックアップ",
                    modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ToolActionCard(
                        icon = Icons.Rounded.Save,
                        title = stringResource(R.string.backup_menu_create),
                        description = "停止中の環境を1つのファイルに保存します",
                        enabled = state.setup.hostReady,
                        onClick = { overlay = BackupOverlay.CREATE },
                    )
                    ToolActionCard(
                        icon = Icons.Rounded.Restore,
                        title = stringResource(R.string.backup_menu_restore),
                        description = "バックアップから新しい環境として復元します",
                        enabled = state.setup.hostReady,
                        onClick = { overlay = BackupOverlay.RESTORE },
                    )
                }
            }
        }

        item {
            Column {
                Text(
                    text = "システムの状態",
                    modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                ) {
                    StatusRow(
                        icon = Icons.Rounded.Memory,
                        title = "アプリ内実行環境",
                        description = "ターミナルとX11サーバー",
                        ready = state.setup.terminalReady && state.setup.x11Ready,
                    )
                    HorizontalDivider(Modifier.padding(start = 64.dp))
                    StatusRow(
                        icon = Icons.Rounded.Folder,
                        title = "Androidファイル共有",
                        description = state.setup.doctor?.sharedDirectory
                            ?.takeIf { it.isNotBlank() }
                            ?: "/mnt/android",
                        ready = state.setup.storageReady,
                    )
                    HorizontalDivider(Modifier.padding(start = 64.dp))
                    StatusRow(
                        icon = Icons.Rounded.Build,
                        title = "Linux実行基盤",
                        description = "Debian・PRoot・XFCEの管理機能",
                        ready = state.setup.hostReady,
                    )
                }
            }
        }

        item {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "うまく動かないとき",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "中断されたインストールやLinuxの監視処理を確認し、安全に再開します。保存済みファイルは削除しません。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(14.dp))
                    FilledTonalButton(
                        onClick = onRepair,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("自動修復を実行")
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolActionCard(
    icon: ImageVector,
    title: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        enabled = enabled,
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(52.dp),
                shape = MaterialTheme.shapes.large,
                color = if (enabled) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.13f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        icon,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = if (enabled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatusRow(
    icon: ImageVector,
    title: String,
    description: String,
    ready: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(36.dp),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(21.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = if (ready) Icons.Rounded.CheckCircle else Icons.Rounded.Warning,
            contentDescription = if (ready) "準備完了" else "確認が必要",
            tint = if (ready) SuccessGreen else MaterialTheme.colorScheme.error,
        )
    }
}

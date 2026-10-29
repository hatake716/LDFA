package com.hatake716.linuxdesktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*

@Composable
internal fun SettingsScreen(
    state: MainUiState,
    modifier: Modifier,
    onOpenTerminal: () -> Unit,
    onOpenDisplay: () -> Unit,
    onBatterySettings: () -> Unit,
    onAppSettings: () -> Unit,
    onRepair: () -> Unit,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 90.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Text("設定", fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold)
        }

        item {
            SettingsSection(title = "内蔵環境") {
                SettingsRow(
                    icon = Icons.Rounded.Terminal,
                    title = "内蔵ターミナル",
                    subtitle = if (state.setup.terminalReady) {
                        "Termuxランタイムをアプリ内で利用"
                    } else {
                        "初期展開が必要です"
                    },
                    onClick = if (state.setup.terminalReady) onOpenTerminal else null,
                )
                HorizontalDivider(Modifier.padding(start = 58.dp))
                SettingsRow(
                    icon = Icons.Rounded.Computer,
                    title = "内蔵X11ディスプレイ",
                    subtitle = "Termux:X11表示機能をアプリ内で利用",
                    onClick = onOpenDisplay,
                )
                HorizontalDivider(Modifier.padding(start = 58.dp))
                SettingsRow(
                    icon = Icons.Rounded.Storage,
                    title = "Android共有フォルダ",
                    subtitle = state.setup.doctor?.sharedDirectory?.ifBlank { null }
                        ?: "/sdcard/LinuxDesktop",
                    onClick = null,
                )
            }
        }

        item {
            SettingsSection(title = "安定動作") {
                SettingsRow(
                    icon = Icons.Rounded.BatteryFull,
                    title = "バッテリー最適化",
                    subtitle = "このアプリを「制限なし」に設定",
                    onClick = onBatterySettings,
                )
                HorizontalDivider(Modifier.padding(start = 58.dp))
                SettingsRow(
                    icon = Icons.Rounded.Settings,
                    title = "Androidのアプリ設定",
                    subtitle = "ストレージ、通知、バックグラウンド動作",
                    onClick = onAppSettings,
                )
            }
        }

        item {
            SettingsSection(title = "保守") {
                SettingsRow(
                    icon = Icons.Rounded.Refresh,
                    title = "中断した処理を修復",
                    subtitle = "DebianインストールやXFCE監視を再開",
                    onClick = onRepair,
                )
            }
        }

        item {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Info, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Text("構成について", fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "ターミナル実行基盤とX11表示機能を同じAPKへ統合しています。作成できる環境はDebian + XFCEに限定し、日本語表示、Fcitx5/Mozc、sudoを初期設定します。複数のDebian環境を保存できますが、同時表示は1環境です。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        item {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Gavel, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Text("ライセンスとソースコード", fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "この統合版はGNU GPL version 3 onlyで提供され、明示・黙示を問わず無保証です。再配布と改変はGPLv3の条件に従って行えます。ライセンス全文、対応するソースコード、Termux／Termux:X11などの著作権表示は配布元リポジトリのLICENSEとTHIRD_PARTY_NOTICES.mdで確認できます。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column {
        Text(
            title,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelLarge,
        )
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
            Column(content = content)
        }
    }
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: (() -> Unit)?,
) {
    Surface(
        onClick = onClick ?: {},
        enabled = onClick != null,
        color = Color.Transparent,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(34.dp),
                shape = RoundedCornerShape(9.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.13f),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Medium)
                Text(
                    subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (onClick != null) {
                Icon(
                    Icons.Rounded.KeyboardArrowRight,
                    null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

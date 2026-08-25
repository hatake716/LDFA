package com.hatake716.linuxdesktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.KeyboardAlt
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hatake716.linuxdesktop.BuildConfig
import com.hatake716.linuxdesktop.data.KeyboardLayout
import com.hatake716.linuxdesktop.ui.theme.SuccessGreen

@Composable
internal fun SettingsScreen(
    state: MainUiState,
    modifier: Modifier,
    onOpenTerminal: () -> Unit,
    onBatterySettings: () -> Unit,
    onAppSettings: () -> Unit,
    onRepair: () -> Unit,
    onSelectDesktopScale: (Int) -> Unit,
    onToggleExtraKeys: (Boolean) -> Unit,
    onSelectKeyboardLayout: (KeyboardLayout) -> Unit,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 90.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Column {
                Text(
                    "LDFA",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "設定とシステム状態",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            SettingsSection(title = "準備状況") {
                StatusSettingsRow(
                    icon = Icons.Rounded.Terminal,
                    title = "内蔵ターミナル",
                    ready = state.setup.terminalReady,
                    readyText = "利用できます",
                    pendingText = "初期展開が必要です",
                )
                HorizontalDivider(Modifier.padding(start = 58.dp))
                StatusSettingsRow(
                    icon = Icons.Rounded.Computer,
                    title = "内蔵X11サーバー",
                    ready = state.setup.x11Ready,
                    readyText = "利用できます",
                    pendingText = "表示機能を確認してください",
                )
                HorizontalDivider(Modifier.padding(start = 58.dp))
                StatusSettingsRow(
                    icon = Icons.Rounded.Folder,
                    title = "Android共有ストレージ",
                    ready = state.setup.storageReady,
                    readyText = "接続済み",
                    pendingText = "アクセス許可が必要です",
                )
                HorizontalDivider(Modifier.padding(start = 58.dp))
                StatusSettingsRow(
                    icon = Icons.Rounded.CloudDone,
                    title = "Debian実行基盤",
                    ready = state.setup.hostReady,
                    readyText = "XFCEと日本語環境を構築済み",
                    pendingText = "セットアップが必要です",
                )
            }
        }

        item {
            SettingsSection(title = "表示と入力") {
                DesktopScaleRow(
                    current = state.setup.desktopScalePercent,
                    enabled = state.setup.hostReady && !state.operationInProgress,
                    onSelect = onSelectDesktopScale,
                )
                HorizontalDivider(Modifier.padding(start = 58.dp))
                ExtraKeysToggleRow(
                    checked = state.setup.extraKeysVisible,
                    onToggle = onToggleExtraKeys,
                )
                HorizontalDivider(Modifier.padding(start = 58.dp))
                KeyboardLayoutRow(
                    current = state.setup.keyboardLayout,
                    enabled = state.setup.hostReady && !state.operationInProgress,
                    onSelect = onSelectKeyboardLayout,
                )
            }
        }

        item {
            SettingsSection(title = "内蔵ツール") {
                SettingsRow(
                    icon = Icons.Rounded.Terminal,
                    title = "ターミナルを開く",
                    subtitle = "Debianの保守やコマンド操作に使用",
                    onClick = if (state.setup.terminalReady) onOpenTerminal else null,
                )
                HorizontalDivider(Modifier.padding(start = 58.dp))
                SettingsRow(
                    icon = Icons.Rounded.Storage,
                    title = "共有フォルダ",
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
                    subtitle = "LDFAを「制限なし」に設定",
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
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Info, contentDescription = null)
                        Spacer(Modifier.width(10.dp))
                        Text("LDFAについて", fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.size(10.dp))
                    Text(
                        "LDFA ${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(
                        "ターミナルとX11サーバーを同じAPKへ統合し、Debian + XFCE、日本語表示、Fcitx5/Mozc、Google Chrome、sudoを1つのアプリから構築します。複数のDebian環境を保存できますが、画面表示は1環境ずつです。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        item {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp,
            ) {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.Gavel,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("ライセンスとソースコード", fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.size(10.dp))
                    Text(
                        "LDFAはGNU GPL version 3 onlyで提供され、明示・黙示を問わず無保証です。再配布と改変はGPLv3の条件に従って行えます。ライセンス全文、対応するソースコード、Termux／Termux:X11などの著作権表示は配布元リポジトリのLICENSEとTHIRD_PARTY_NOTICES.mdで確認できます。",
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
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp,
        ) {
            Column(content = content)
        }
    }
}

@Composable
private fun StatusSettingsRow(
    icon: ImageVector,
    title: String,
    ready: Boolean,
    readyText: String,
    pendingText: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsIcon(icon)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(
                if (ready) readyText else pendingText,
                color = if (ready) SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            if (ready) Icons.Rounded.CheckCircle else Icons.Rounded.Schedule,
            contentDescription = if (ready) "準備完了" else "未完了",
            tint = if (ready) SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingsIcon(icon)
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
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SettingsIcon(icon: ImageVector) {
    Surface(
        modifier = Modifier.size(34.dp),
        shape = RoundedCornerShape(9.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DesktopScaleRow(
    current: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
) {
    val presets = listOf(100, 125, 150, 175, 200, 225, 250)
    Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SettingsIcon(Icons.Rounded.ZoomIn)
            Spacer(Modifier.size(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "表示倍率",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    "デスクトップ全体（文字・アイコン・パネル）の大きさ",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.size(12.dp))
        // Seven presets don't fit one row on a phone, so wrap them.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            presets.forEach { percent ->
                FilterChip(
                    selected = percent == current,
                    onClick = { if (enabled) onSelect(percent) },
                    enabled = enabled,
                    label = { Text("$percent%") },
                    leadingIcon = if (percent == current) {
                        {
                            Icon(
                                Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier.size(FilterChipDefaults.IconSize),
                            )
                        }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

@Composable
private fun ExtraKeysToggleRow(
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsIcon(Icons.Rounded.Keyboard)
        Spacer(Modifier.size(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "特殊キーバー",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                "画面下部の ESC / CTRL / ALT / 矢印キーなどの行を表示",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.size(12.dp))
        Switch(checked = checked, onCheckedChange = onToggle)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeyboardLayoutRow(
    current: KeyboardLayout,
    enabled: Boolean,
    onSelect: (KeyboardLayout) -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SettingsIcon(Icons.Rounded.KeyboardAlt)
            Spacer(Modifier.size(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "キーボード配列",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    "接続した物理キーボードの配列に合わせます",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.size(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KeyboardLayout.entries.forEach { layout ->
                val label = when (layout) {
                    KeyboardLayout.JIS -> "JIS（日本語）"
                    KeyboardLayout.US -> "US（英語）"
                }
                FilterChip(
                    selected = layout == current,
                    onClick = { if (enabled) onSelect(layout) },
                    enabled = enabled,
                    label = { Text(label) },
                    leadingIcon = if (layout == current) {
                        {
                            Icon(
                                Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier.size(FilterChipDefaults.IconSize),
                            )
                        }
                    } else {
                        null
                    },
                )
            }
        }
        Spacer(Modifier.size(8.dp))
        // The @ position is the easiest tell for "which layout is my keyboard".
        val detail = when (current) {
            KeyboardLayout.JIS -> "半角/全角キーあり。@ は 2 の右上のキー"
            KeyboardLayout.US -> "半角/全角キーなし。@ は Shift + 2。日本語入力の切り替えは Ctrl + Space"
        }
        Text(
            detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

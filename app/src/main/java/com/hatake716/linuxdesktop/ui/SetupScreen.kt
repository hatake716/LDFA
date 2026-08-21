package com.hatake716.linuxdesktop.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hatake716.linuxdesktop.ui.theme.SuccessGreen
import kotlinx.coroutines.delay
import java.io.File
import java.io.RandomAccessFile

@Composable
internal fun LoadingScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(
                modifier = Modifier.size(82.dp),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Rounded.Computer,
                        contentDescription = null,
                        modifier = Modifier.size(42.dp),
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
            CircularProgressIndicator(strokeWidth = 3.dp)
            Spacer(Modifier.height(12.dp))
            Text(
                "LDFAの内蔵環境を確認しています",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun SetupScreen(
    state: MainUiState,
    onPrepareRuntime: () -> Unit,
    onGrantStorageAccess: () -> Unit,
    onRefresh: () -> Unit,
    onBootstrap: () -> Unit,
) {
    val completed = listOf(
        state.setup.terminalReady,
        state.setup.x11Ready,
        state.setup.storageReady,
        state.setup.hostReady,
    ).count { it }
    var bootstrapLog by remember { mutableStateOf("") }

    LaunchedEffect(state.bootstrapping) {
        if (state.bootstrapping) {
            bootstrapLog = "インストールログの出力を待っています…"
            while (true) {
                val latest = readBootstrapLogTail()
                if (latest.isNotBlank()) bootstrapLog = latest
                delay(BOOTSTRAP_LOG_POLL_MILLIS)
            }
        } else if (bootstrapLog.isNotBlank()) {
            val finalLog = readBootstrapLogTail()
            if (finalLog.isNotBlank()) bootstrapLog = finalLog
        }
    }

    androidx.compose.foundation.lazy.LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 42.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "LDFA",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Linux Desktop for Android",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onRefresh, enabled = !state.refreshing && !state.bootstrapping) {
                    if (state.refreshing) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Rounded.Refresh, contentDescription = "状態を再確認")
                    }
                }
            }
        }

        item {
            SetupHero(completed = completed, total = 4)
        }

        item {
            SetupPrimaryAction(
                state = state,
                onPrepareRuntime = onPrepareRuntime,
                onGrantStorageAccess = onGrantStorageAccess,
                onRefresh = onRefresh,
                onBootstrap = onBootstrap,
            )
        }

        item {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Verified, contentDescription = null)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("外部アプリは不要", fontWeight = FontWeight.SemiBold)
                        Text(
                            "ターミナルとX11サーバーはLDFAに内蔵されています。",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }

        item {
            SetupStepCard(
                number = 1,
                title = "内蔵ターミナル",
                description = "Ubuntuを動かすTermuxランタイムを、LDFAの内部領域へ展開します。",
                complete = state.setup.terminalReady,
                icon = Icons.Rounded.Terminal,
            )
        }

        item {
            SetupStepCard(
                number = 2,
                title = "内蔵X11サーバー",
                description = "XFCEの画面・タッチ・マウス・キーボード入力をAndroidへ接続します。",
                complete = state.setup.x11Ready,
                icon = Icons.Rounded.Computer,
            )
        }

        item {
            SetupStepCard(
                number = 3,
                title = "Android共有ストレージ",
                description = "Android側のファイルをUbuntuの /mnt/android から読み書きできるようにします。",
                complete = state.setup.storageReady,
                icon = Icons.Rounded.Folder,
            )
        }

        item {
            SetupStepCard(
                number = 4,
                title = "Ubuntu XFCEと日本語環境",
                description = "Ubuntu、XFCE、Fcitx5、Mozc、日本語フォント、sudoをまとめて構築します。",
                complete = state.setup.hostReady,
                icon = Icons.Rounded.CloudDownload,
                showProgress = state.bootstrapping,
                logContent = bootstrapLog,
            )
        }

        item {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
                    Icon(
                        Icons.Rounded.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "セットアップ後はUbuntu環境を追加し、「Ubuntu XFCEを開く」を押すだけでLinuxデスクトップを起動できます。1環境につき3〜5GB以上の空き容量を推奨します。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SetupPrimaryAction(
    state: MainUiState,
    onPrepareRuntime: () -> Unit,
    onGrantStorageAccess: () -> Unit,
    onRefresh: () -> Unit,
    onBootstrap: () -> Unit,
) {
    val actionLabel = when {
        state.bootstrapping -> "Ubuntu環境をインストール中"
        !state.setup.terminalReady -> "セットアップを開始"
        !state.setup.x11Ready -> "内蔵X11を再確認"
        !state.setup.storageReady -> "ストレージアクセスを許可"
        !state.setup.hostReady -> "Ubuntu環境をインストール"
        else -> "セットアップ完了"
    }
    val supportingText = when {
        state.bootstrapping -> "画面を閉じても処理は継続します。下に現在のログを表示しています。"
        !state.setup.terminalReady -> "最初のボタンから、必要な準備を順番に案内します。"
        !state.setup.x11Ready -> "LDFAに組み込まれたX11表示機能を確認します。"
        !state.setup.storageReady -> "AndroidのファイルをLinuxから利用するための権限です。"
        !state.setup.hostReady -> "XFCEと日本語入力を自動で構築します。"
        else -> "LDFAを使用できます。"
    }
    val actionIcon = when {
        state.bootstrapping -> Icons.Rounded.Download
        !state.setup.terminalReady -> Icons.Rounded.Security
        !state.setup.x11Ready -> Icons.Rounded.Computer
        !state.setup.storageReady -> Icons.Rounded.Folder
        else -> Icons.Rounded.Download
    }
    val action: () -> Unit = when {
        !state.setup.terminalReady -> onPrepareRuntime
        !state.setup.x11Ready -> onRefresh
        !state.setup.storageReady -> onGrantStorageAccess
        !state.setup.hostReady -> onBootstrap
        else -> onRefresh
    }
    val enabled = !state.bootstrapping &&
        !(state.setup.terminalReady &&
            state.setup.x11Ready &&
            state.setup.storageReady &&
            state.setup.hostReady)

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(
                "かんたんセットアップ",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                supportingText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = action,
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                if (state.bootstrapping) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Icon(actionIcon, contentDescription = null)
                }
                Spacer(Modifier.width(10.dp))
                Text(actionLabel)
            }
        }
    }
}

@Composable
private fun SetupHero(completed: Int, total: Int) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Column(Modifier.padding(24.dp)) {
            Surface(
                modifier = Modifier.size(64.dp),
                shape = RoundedCornerShape(19.dp),
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.10f),
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Computer, null, Modifier.size(36.dp))
                }
            }
            Spacer(Modifier.height(22.dp))
            Text(
                "Androidを、\nLinux PCへ。",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                if (completed == total) "セットアップ完了" else "あと ${total - completed} ステップ",
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f),
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(18.dp))
            LinearProgressIndicator(
                progress = { completed.toFloat() / total.toFloat() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(7.dp)
                    .clip(CircleShape),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.16f),
            )
        }
    }
}

@Composable
private fun SetupStepCard(
    number: Int,
    title: String,
    description: String,
    complete: Boolean,
    icon: ImageVector,
    showProgress: Boolean = false,
    logContent: String = "",
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(
                            if (complete) SuccessGreen
                            else MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (complete) {
                        Icon(
                            Icons.Rounded.CheckCircle,
                            null,
                            tint = androidx.compose.ui.graphics.Color.White,
                            modifier = Modifier.size(23.dp),
                        )
                    } else {
                        Text(number.toString(), fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (complete) "準備完了" else if (showProgress) "処理中" else "未完了",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (complete) SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (showProgress) {
                Spacer(Modifier.height(14.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape))
            }

            if (logContent.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                SetupInstallLogPanel(logContent)
            }
        }
    }
}

@Composable
private fun SetupInstallLogPanel(logs: String) {
    val scrollState = rememberScrollState()

    LaunchedEffect(logs) {
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
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
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
                    "インストールログ",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                logs,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 220.dp)
                    .verticalScroll(scrollState)
                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.92f),
            )
        }
    }
}

private fun readBootstrapLogTail(): String = runCatching {
    val file = File(BOOTSTRAP_LOG_PATH)
    if (!file.isFile) return@runCatching ""

    RandomAccessFile(file, "r").use { raf ->
        val length = raf.length()
        val start = (length - BOOTSTRAP_LOG_MAX_BYTES).coerceAtLeast(0L)
        raf.seek(start)
        val bytes = ByteArray((length - start).toInt())
        raf.readFully(bytes)
        String(bytes, Charsets.UTF_8)
            .lines()
            .takeLast(BOOTSTRAP_LOG_MAX_LINES)
            .joinToString("\n")
            .trim()
    }
}.getOrDefault("")

private const val BOOTSTRAP_LOG_PATH =
    "/data/data/com.termux/files/home/.local/share/linux-desktop-for-android/logs/bootstrap.log"
private const val BOOTSTRAP_LOG_POLL_MILLIS = 750L
private const val BOOTSTRAP_LOG_MAX_LINES = 80
private const val BOOTSTRAP_LOG_MAX_BYTES = 64L * 1024L

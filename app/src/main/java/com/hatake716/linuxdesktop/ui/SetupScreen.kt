package com.hatake716.linuxdesktop.ui

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
import androidx.compose.ui.graphics.vector.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.*
import com.hatake716.linuxdesktop.ui.theme.*
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
                color = MaterialTheme.colorScheme.primary,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Rounded.Computer,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(42.dp),
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
            CircularProgressIndicator(strokeWidth = 3.dp)
            Spacer(Modifier.height(12.dp))
            Text("内蔵環境を確認しています", color = MaterialTheme.colorScheme.onSurfaceVariant)
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

    LazyColumn(
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
                horizontalArrangement = Arrangement.End,
            ) {
                IconButton(onClick = onRefresh, enabled = !state.refreshing && !state.bootstrapping) {
                    if (state.refreshing) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Rounded.Refresh, contentDescription = "再確認")
                    }
                }
            }
        }

        item {
            SetupHero(completed = completed, total = 3)
        }

        item {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.Verified,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("外部アプリは不要", fontWeight = FontWeight.SemiBold)
                        Text(
                            "ターミナル実行基盤とX11表示機能は、このAPKに統合されています。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        item {
            SetupStepCard(
                number = 1,
                title = "内蔵ターミナルを展開",
                description = "Debianコンテナを動かすTermuxランタイムを、アプリの内部領域へ安全に展開します。",
                complete = state.setup.terminalReady,
                icon = Icons.Rounded.Terminal,
                actionLabel = if (state.setup.terminalReady) "準備済み" else "内蔵ランタイムを準備",
                onAction = onPrepareRuntime,
                actionEnabled = !state.setup.terminalReady,
            )
        }

        item {
            SetupStepCard(
                number = 2,
                title = "共有ストレージを許可",
                description = "Download、DocumentsなどAndroid側のファイルをDebianの /mnt/android から読み書きできるようにします。",
                complete = state.setup.storageReady,
                icon = Icons.Rounded.Folder,
                actionLabel = if (state.setup.storageReady) "許可済み" else "ストレージを許可",
                onAction = onGrantStorageAccess,
                actionEnabled = state.setup.terminalReady && !state.setup.storageReady,
            )
        }

        item {
            SetupStepCard(
                number = 3,
                title = "Debian実行基盤を自動準備",
                description = "tmux、PRoot Distroなど、Debian XFCEの作成と監視に必要なパッケージをまとめて導入します。",
                complete = state.setup.hostReady,
                icon = Icons.Rounded.Download,
                actionLabel = when {
                    state.bootstrapping -> "準備中…"
                    state.setup.hostReady -> "準備完了"
                    else -> "自動インストール"
                },
                onAction = onBootstrap,
                actionEnabled = state.setup.terminalReady &&
                    state.setup.storageReady &&
                    !state.bootstrapping &&
                    !state.setup.hostReady,
                showProgress = state.bootstrapping,
                logContent = bootstrapLog,
            )
        }

        item {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
                    Icon(
                        Icons.Rounded.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "作成できるデスクトップはDebian + XFCEです。日本語表示、Fcitx5/Mozc、sudoを初期設定します。1環境につき3〜5GB以上の空き容量を推奨します。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SetupHero(completed: Int, total: Int) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.primary,
        contentColor = Color.White,
    ) {
        Column(Modifier.padding(24.dp)) {
            Surface(
                modifier = Modifier.size(64.dp),
                shape = RoundedCornerShape(19.dp),
                color = Color.White.copy(alpha = 0.18f),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Computer, null, Modifier.size(36.dp), Color.White)
                }
            }
            Spacer(Modifier.height(22.dp))
            Text(
                "このアプリだけで、\nDebian XFCEへ。",
                fontSize = 30.sp,
                lineHeight = 36.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                if (completed == total) "セットアップ完了" else "あと ${total - completed} ステップ",
                color = Color.White.copy(alpha = 0.82f),
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(18.dp))
            LinearProgressIndicator(
                progress = { completed.toFloat() / total.toFloat() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(7.dp)
                    .clip(CircleShape),
                color = Color.White,
                trackColor = Color.White.copy(alpha = 0.24f),
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
    actionLabel: String,
    onAction: () -> Unit,
    actionEnabled: Boolean = true,
    showProgress: Boolean = false,
    logContent: String = "",
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
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
                        Icon(Icons.Rounded.CheckCircle, null, tint = Color.White, modifier = Modifier.size(23.dp))
                    } else {
                        Text(number.toString(), fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(9.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            if (showProgress) {
                LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape))
            } else {
                FilledTonalButton(
                    onClick = onAction,
                    enabled = actionEnabled,
                    shape = RoundedCornerShape(13.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(actionLabel)
                }
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

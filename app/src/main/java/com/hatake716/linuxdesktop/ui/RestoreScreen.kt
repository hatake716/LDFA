package com.hatake716.linuxdesktop.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hatake716.linuxdesktop.R
import com.hatake716.linuxdesktop.backup.BackupManifest
import com.hatake716.linuxdesktop.backup.BackupReader
import com.hatake716.linuxdesktop.service.BackupService
import com.hatake716.linuxdesktop.service.BackupUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Full-screen restore flow. The user picks a `.ldfa` file (SAF), we copy it into
 * app cache and read its header to preview the source device / arch, block a
 * mismatched architecture, then hand the file to [BackupService] which restores
 * it as a brand-new environment. The caller refreshes the list on success.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RestoreScreen(
    existingNames: Set<String>,
    onRestored: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val serviceState by BackupService.state.collectAsStateWithLifecycle()

    var picked by remember { mutableStateOf<File?>(null) }
    var manifest by remember { mutableStateOf<BackupManifest?>(null) }
    var previewError by remember { mutableStateOf<String?>(null) }
    var archOk by remember { mutableStateOf(true) }
    var archMessage by remember { mutableStateOf<String?>(null) }

    val deviceAbi = remember { android.os.Build.SUPPORTED_ABIS.firstOrNull().orEmpty() }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        previewError = null
        manifest = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val cached = File(context.cacheDir, "restore-input.ldfa")
                    context.contentResolver.openInputStream(uri)?.use { ins ->
                        cached.outputStream().use { out -> ins.copyTo(out, 1 shl 16) }
                    } ?: error("開けませんでした")
                    val m = cached.inputStream().buffered().use { BackupReader().readManifest(it) }
                    cached to m
                }
            }
            result.onSuccess { (file, m) ->
                picked = file
                manifest = m
                val ok = archMatches(deviceAbi, m.container.guestArch)
                archOk = ok
                archMessage = if (ok) null else
                    context.getString(R.string.restore_arch_mismatch, deviceAbi, m.container.guestArch)
            }.onFailure {
                picked = null
                previewError = context.getString(R.string.restore_invalid_file)
            }
        }
    }

    val running = serviceState as? BackupUiState.Running
    val isRestoreRunning = running?.op == BackupUiState.Op.RESTORE
    val done = (serviceState as? BackupUiState.Done)?.takeIf { it.op == BackupUiState.Op.RESTORE }
    val failed = (serviceState as? BackupUiState.Failed)?.takeIf { it.op == BackupUiState.Op.RESTORE }
    val cancelled = (serviceState as? BackupUiState.Cancelled)?.takeIf { it.op == BackupUiState.Op.RESTORE }

    // Refresh the environment list once the restore reports success.
    LaunchedEffect(done) { if (done != null) onRestored() }

    BackHandler(enabled = !isRestoreRunning) { finishRestore(onClose) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.restore_title)) },
                navigationIcon = {
                    IconButton(onClick = { if (!isRestoreRunning) finishRestore(onClose) }) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = stringResource(R.string.close))
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.restore_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when {
                done != null -> ResultCard(
                    success = true,
                    title = stringResource(R.string.restore_done_title),
                    message = done.message,
                    detail = done.detail,
                )
                failed != null -> ResultCard(
                    success = false,
                    title = stringResource(R.string.restore_failed_title),
                    message = failed.message,
                    detail = null,
                )
                cancelled != null -> ResultCard(
                    success = false,
                    title = stringResource(R.string.restore_cancelled),
                    message = "",
                    detail = null,
                )
                isRestoreRunning && running != null -> RunningCard(
                    label = stringResource(R.string.restore_running),
                    indeterminate = running.indeterminate,
                    percent = running.percent,
                    sub = if (running.total > 0) {
                        stringResource(R.string.backup_running_files, running.processed, running.total)
                    } else null,
                )
                else -> {
                    OutlinedButton(
                        onClick = { picker.launch(arrayOf("*/*")) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Rounded.FileOpen, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.restore_pick_file))
                    }

                    previewError?.let { InfoCard(it) }

                    manifest?.let { m -> ManifestPreview(m) }
                    archMessage?.let { InfoCard(it) }
                }
            }

            Spacer(Modifier.height(4.dp))

            when {
                done != null || failed != null || cancelled != null -> {
                    Button(
                        onClick = { finishRestore(onClose) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.close)) }
                }
                isRestoreRunning -> {
                    OutlinedButton(
                        onClick = { BackupService.cancel(context) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.cancel)) }
                }
                manifest != null && picked != null -> {
                    Button(
                        onClick = {
                            BackupService.startRestore(context, picked!!, existingNames)
                        },
                        enabled = archOk,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Rounded.Restore, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.restore_start))
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ManifestPreview(m: BackupManifest) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(
                text = m.container.displayName.ifBlank { "Debian XFCE" },
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(10.dp))
            PreviewRow(stringResource(R.string.restore_header_from), "${m.sourceDevice.model} (Android ${m.sourceDevice.androidSdk})")
            PreviewRow(stringResource(R.string.restore_header_app), "${m.app.versionName} (${m.app.versionCode})")
            PreviewRow(stringResource(R.string.restore_header_arch), m.container.guestArch)
            PreviewRow(stringResource(R.string.restore_header_created), m.createdAt)
        }
    }
}

@Composable
private fun PreviewRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun archMatches(abi: String, arch: String): Boolean {
    val a = when {
        abi.startsWith("arm64") -> "arm64"
        abi.startsWith("x86_64") -> "x86_64"
        abi.startsWith("armeabi") -> "armhf"
        abi == "x86" -> "i386"
        else -> abi
    }
    return a == arch
}

private fun finishRestore(onClose: () -> Unit) {
    BackupService.acknowledge()
    onClose()
}

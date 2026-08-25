package com.hatake716.linuxdesktop.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hatake716.linuxdesktop.R
import com.hatake716.linuxdesktop.backup.BackupPaths
import com.hatake716.linuxdesktop.data.ContainerInfo
import com.hatake716.linuxdesktop.data.ContainerState
import com.hatake716.linuxdesktop.service.BackupService
import com.hatake716.linuxdesktop.service.BackupUiState

/**
 * Full-screen flow for creating a FULL backup of a stopped environment. It reads
 * live progress from [BackupService.state]; the actual work runs in that service
 * so it survives the screen turning off. On completion it invites the user back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BackupScreen(
    containers: List<ContainerInfo>,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val paths = remember { BackupPaths(context) }
    val serviceState by BackupService.state.collectAsStateWithLifecycle()

    // Only stopped, installed environments are safe to back up.
    val stoppable = remember(containers) {
        containers.filter { it.state == ContainerState.READY && !it.sessionAlive }
    }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(stoppable) {
        if (selectedId == null || stoppable.none { it.id == selectedId }) {
            selectedId = stoppable.firstOrNull()?.id
        }
    }

    val running = serviceState as? BackupUiState.Running
    val isBackupRunning = running?.op == BackupUiState.Op.BACKUP
    val done = (serviceState as? BackupUiState.Done)?.takeIf { it.op == BackupUiState.Op.BACKUP }
    val failed = (serviceState as? BackupUiState.Failed)?.takeIf { it.op == BackupUiState.Op.BACKUP }
    val cancelled = (serviceState as? BackupUiState.Cancelled)?.takeIf { it.op == BackupUiState.Op.BACKUP }

    // Closing the screen while idle is fine; while running we keep it open.
    BackHandler(enabled = !isBackupRunning) { finishAndAck(onClose) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.backup_title)) },
                navigationIcon = {
                    IconButton(onClick = { if (!isBackupRunning) finishAndAck(onClose) }) {
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
                text = stringResource(R.string.backup_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when {
                done != null -> ResultCard(
                    success = true,
                    title = stringResource(R.string.backup_done_title),
                    message = done.message,
                    detail = done.detail,
                )
                failed != null -> ResultCard(
                    success = false,
                    title = stringResource(R.string.backup_failed_title),
                    message = failed.message,
                    detail = null,
                )
                cancelled != null -> ResultCard(
                    success = false,
                    title = stringResource(R.string.backup_cancelled),
                    message = "",
                    detail = null,
                )
                isBackupRunning && running != null -> RunningCard(
                    label = stringResource(R.string.backup_running),
                    indeterminate = running.indeterminate,
                    percent = running.percent,
                    sub = if (running.total > 0) {
                        stringResource(R.string.backup_running_files, running.processed, running.total)
                    } else null,
                )
                stoppable.isEmpty() -> InfoCard(stringResource(R.string.backup_no_stopped_env))
                else -> {
                    Text(
                        text = stringResource(R.string.backup_pick_env),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        ),
                    ) {
                        Column {
                            stoppable.forEach { env ->
                                EnvRow(
                                    name = env.name,
                                    selected = env.id == selectedId,
                                    onSelect = { selectedId = env.id },
                                )
                            }
                        }
                    }
                    DestinationCard(paths.defaultBackupDir.absolutePath)
                }
            }

            Spacer(Modifier.height(4.dp))

            // Actions.
            when {
                done != null || failed != null || cancelled != null -> {
                    Button(
                        onClick = { finishAndAck(onClose) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.close)) }
                }
                isBackupRunning -> {
                    OutlinedButton(
                        onClick = { BackupService.cancel(context) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.cancel)) }
                }
                stoppable.isNotEmpty() -> {
                    Button(
                        onClick = {
                            val id = selectedId ?: return@Button
                            BackupService.startBackup(context, id, paths.defaultBackupDir)
                        },
                        enabled = selectedId != null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Rounded.Save, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.backup_start))
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun EnvRow(name: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(Modifier.width(8.dp))
        Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun DestinationCard(path: String) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.backup_dest),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(path, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
internal fun RunningCard(label: String, indeterminate: Boolean, percent: Int, sub: String?) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.width(22.dp).height(22.dp), strokeWidth = 2.5.dp)
                Spacer(Modifier.width(12.dp))
                Text(label, style = MaterialTheme.typography.titleMedium)
                if (!indeterminate) {
                    Spacer(Modifier.weight(1f))
                    Text("$percent%", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(14.dp))
            if (indeterminate) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
            }
            if (sub != null) {
                Spacer(Modifier.height(8.dp))
                Text(sub, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
internal fun ResultCard(success: Boolean, title: String, message: String, detail: String?) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (success) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.errorContainer
            },
        ),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (success) Icons.Rounded.CheckCircle else Icons.Rounded.Error,
                    contentDescription = null,
                )
                Spacer(Modifier.width(10.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            if (message.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(message, style = MaterialTheme.typography.bodyLarge)
            }
            if (detail != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun InfoCard(text: String) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(18.dp),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

private fun finishAndAck(onClose: () -> Unit) {
    BackupService.acknowledge()
    onClose()
}

package com.hatake716.linuxdesktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.*
import androidx.compose.ui.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.*
import com.hatake716.linuxdesktop.data.*

@Composable
fun LinuxDesktopRoot(
    viewModel: MainViewModel,
    onPrepareRuntime: () -> Unit,
    onGrantStorageAccess: () -> Unit,
    onStartContainer: (ContainerInfo) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showCreateDialog by rememberSaveable { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<ContainerInfo?>(null) }

    LaunchedEffect(state.errorMessage, state.noticeMessage) {
        val message = state.errorMessage ?: state.noticeMessage
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            viewModel.clearMessages()
        }
    }

    val setupComplete = state.setup.terminalReady &&
        state.setup.x11Ready &&
        state.setup.storageReady &&
        state.setup.hostReady

    Box(Modifier.fillMaxSize()) {
        if (state.initialLoading) {
            LoadingScreen()
        } else if (!setupComplete) {
            SetupScreen(
                state = state,
                onPrepareRuntime = onPrepareRuntime,
                onGrantStorageAccess = onGrantStorageAccess,
                onRefresh = { viewModel.refreshEnvironment() },
                onBootstrap = viewModel::bootstrapHost,
            )
        } else {
            MainShell(
                state = state,
                onTabSelected = viewModel::selectTab,
                onAdd = { showCreateDialog = true },
                onRefresh = { viewModel.refreshEnvironment() },
                onStart = onStartContainer,
                onStop = viewModel::stopContainer,
                onRepair = viewModel::repairInterruptedWork,
                onLogs = viewModel::loadLogs,
                onDelete = { deleteTarget = it },
                onOpenTerminal = viewModel::openTerminal,
                onOpenDisplay = viewModel::openDisplay,
                onBatterySettings = viewModel::openBatterySettings,
                onAppSettings = viewModel::openThisAppSettings,
            )
        }

        if (state.operationInProgress) {
            OperationOverlay(desktopStarting = state.desktopStartInProgress)
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(
                    start = 16.dp,
                    end = 16.dp,
                    bottom = if (setupComplete) 72.dp else 16.dp,
                ),
        )
    }

    if (showCreateDialog) {
        CreateContainerDialog(
            busy = state.operationInProgress,
            onDismiss = { showCreateDialog = false },
            onCreate = { name ->
                showCreateDialog = false
                viewModel.createContainer(name)
            },
        )
    }

    deleteTarget?.let { container ->
        DeleteContainerDialog(
            container = container,
            onDismiss = { deleteTarget = null },
            onDelete = { deleteSharedFiles ->
                deleteTarget = null
                viewModel.deleteContainer(container, deleteSharedFiles)
            },
        )
    }

    val logsTitle = state.logsTitle
    val logsContent = state.logsContent
    if (logsTitle != null && logsContent != null) {
        LogsDialog(
            title = logsTitle,
            logs = logsContent,
            onDismiss = viewModel::dismissLogs,
        )
    }
}

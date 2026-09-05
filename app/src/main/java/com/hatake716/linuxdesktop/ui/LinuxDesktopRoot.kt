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
    onStartContainer: (ContainerInfo) -> Unit,
    onInstall: (String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showRestore by rememberSaveable { mutableStateOf(false) }
    var showCreateDialog by rememberSaveable { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<ContainerInfo?>(null) }

    LaunchedEffect(state.errorMessage, state.noticeMessage) {
        val message = state.errorMessage ?: state.noticeMessage
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            viewModel.clearMessages()
        }
    }

    // A prepared host without a Linux environment stays on the introduction screen.
    val setupComplete = state.setup.terminalReady &&
        state.setup.x11Ready &&
        state.setup.hostReady && state.containers.isNotEmpty()

    Box(Modifier.fillMaxSize()) {
        if (showRestore && state.setup.hostReady) {
            RestoreScreen(
                existingNames = state.containers.map { it.name }.toSet(),
                onRestored = { viewModel.refreshEnvironment() },
                onClose = { showRestore = false },
            )
        } else if (state.initialLoading) {
            LoadingScreen()
        } else if (!setupComplete) {
            SetupScreen(
                state = state,
                onRefresh = { viewModel.refreshEnvironment() },
                onInstall = onInstall,
                onRestore = { showRestore = true; viewModel.prepareForRestore() },
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
                onSelectDesktopScale = viewModel::setDesktopScale,
                onToggleExtraKeys = viewModel::setExtraKeysVisible,
                onSelectKeyboardLayout = viewModel::setKeyboardLayout,
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
            busy = state.operationInProgress || state.installation.busy,
            onDismiss = { showCreateDialog = false },
            onCreate = { name ->
                showCreateDialog = false
                onInstall(name)
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

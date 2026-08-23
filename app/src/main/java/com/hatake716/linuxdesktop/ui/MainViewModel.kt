package com.hatake716.linuxdesktop.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hatake716.linuxdesktop.LinuxDesktopApplication
import com.hatake716.linuxdesktop.data.ContainerInfo
import com.hatake716.linuxdesktop.data.ContainerState
import com.hatake716.linuxdesktop.data.DoctorReport
import com.hatake716.linuxdesktop.data.LinuxDesktopRepository
import com.hatake716.linuxdesktop.data.RuntimeStatus
import com.hatake716.linuxdesktop.service.DesktopKeepAliveService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class MainTab { DESKTOPS, SETTINGS }

data class SetupSnapshot(
    val runtime: RuntimeStatus = RuntimeStatus(terminalReady = false),
    val doctor: DoctorReport? = null,
) {
    val terminalReady: Boolean get() = runtime.terminalReady
    val x11Ready: Boolean get() = runtime.x11Embedded
    val hostReady: Boolean get() = doctor?.hostReady == true
    val storageReady: Boolean get() = doctor?.storageReady == true
}

data class MainUiState(
    val initialLoading: Boolean = true,
    val refreshing: Boolean = false,
    val bootstrapping: Boolean = false,
    val operationInProgress: Boolean = false,
    val desktopStartInProgress: Boolean = false,
    val setup: SetupSnapshot = SetupSnapshot(),
    val containers: List<ContainerInfo> = emptyList(),
    val liveInstallationLogs: Map<String, String> = emptyMap(),
    val selectedTab: MainTab = MainTab.DESKTOPS,
    val errorMessage: String? = null,
    val noticeMessage: String? = null,
    val logsTitle: String? = null,
    val logsContent: String? = null,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val linuxDesktopApplication = application as LinuxDesktopApplication
    private val repository: LinuxDesktopRepository =
        linuxDesktopApplication.repository
    private val _state = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = _state.asStateFlow()
    private var pollingJob: Job? = null
    private var containerRefreshJob: Job? = null

    init {
        refreshEnvironment(showLoading = true)
    }

    /**
     * The Compose home screen is stopped while the native X11 activity is visible. Polling its
     * cards in that state only starts short-lived Termux/PRoot processes beside Chrome, so keep
     * the five-second refresh strictly scoped to the visible host activity.
     */
    fun setHostActivityVisible(visible: Boolean) {
        if (visible) {
            if (pollingJob?.isActive != true) startPolling()
            return
        }
        pollingJob?.cancel()
        pollingJob = null
        containerRefreshJob?.cancel()
        containerRefreshJob = null
    }

    fun refreshEnvironment(showLoading: Boolean = false) {
        viewModelScope.launch {
            _state.update {
                it.copy(
                    initialLoading = showLoading && it.containers.isEmpty(),
                    refreshing = !showLoading,
                )
            }

            val runtime = repository.runtimeStatus()
            val doctor = if (runtime.terminalReady) {
                runCatching { repository.doctor() }.getOrNull()
            } else {
                null
            }

            val containers = if (runtime.terminalReady) {
                runCatching { repository.listContainers() }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
            val liveLogs = if (runtime.terminalReady) {
                loadLiveInstallationLogs(containers)
            } else {
                emptyMap()
            }

            _state.update {
                it.copy(
                    initialLoading = false,
                    refreshing = false,
                    setup = SetupSnapshot(runtime = runtime, doctor = doctor),
                    containers = containers,
                    liveInstallationLogs = liveLogs,
                )
            }
        }
    }

    fun bootstrapHost() {
        viewModelScope.launch {
            _state.update { it.copy(bootstrapping = true, errorMessage = null) }
            runCatching { repository.bootstrapHost() }
                .onSuccess { doctor ->
                    _state.update {
                        it.copy(
                            bootstrapping = false,
                            setup = it.setup.copy(doctor = doctor),
                            noticeMessage = "Debian XFCEの実行基盤を準備しました。",
                        )
                    }
                    refreshContainers()
                }
                .onFailure(::showError)
        }
    }

    fun createContainer(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            _state.update {
                it.copy(
                    operationInProgress = true,
                    desktopStartInProgress = false,
                    errorMessage = null,
                )
            }
            runCatching {
                repository.createContainer(name)
                DesktopKeepAliveService.start(getApplication())
            }.onSuccess {
                _state.update {
                    it.copy(
                        operationInProgress = false,
                        desktopStartInProgress = false,
                        noticeMessage = "「${name.trim()}」のDebian XFCEインストールを開始しました。",
                    )
                }
                refreshContainers()
            }.onFailure(::showError)
        }
    }

    fun startContainer(container: ContainerInfo) {
        viewModelScope.launch {
            _state.update {
                it.copy(
                    operationInProgress = true,
                    desktopStartInProgress = true,
                    errorMessage = null,
                )
            }
            try {
                linuxDesktopApplication.startDesktopSession(container.id).await()
                _state.update {
                    it.copy(
                        operationInProgress = false,
                        desktopStartInProgress = false,
                        noticeMessage = "${container.name}を起動しました。",
                    )
                }
                refreshContainers()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Throwable) {
                showError(exception)
            }
        }
    }

    fun stopContainer(container: ContainerInfo) {
        viewModelScope.launch {
            _state.update {
                it.copy(
                    operationInProgress = true,
                    desktopStartInProgress = false,
                    errorMessage = null,
                )
            }
            runCatching { repository.stopContainer(container.id) }
                .onSuccess {
                    _state.update {
                        it.copy(
                            operationInProgress = false,
                            desktopStartInProgress = false,
                            noticeMessage = "${container.name}を停止しました。",
                        )
                    }
                    refreshContainers()
                }
                .onFailure(::showError)
        }
    }

    fun deleteContainer(container: ContainerInfo, deleteSharedFiles: Boolean) {
        viewModelScope.launch {
            _state.update {
                it.copy(
                    operationInProgress = true,
                    desktopStartInProgress = false,
                    errorMessage = null,
                )
            }
            runCatching { repository.deleteContainer(container.id, deleteSharedFiles) }
                .onSuccess {
                    _state.update {
                        it.copy(
                            operationInProgress = false,
                            desktopStartInProgress = false,
                            noticeMessage = "${container.name}を削除しました。",
                        )
                    }
                    refreshContainers()
                }
                .onFailure(::showError)
        }
    }

    fun repairInterruptedWork() {
        viewModelScope.launch {
            _state.update {
                it.copy(
                    operationInProgress = true,
                    desktopStartInProgress = false,
                    errorMessage = null,
                )
            }
            runCatching { repository.repairInterruptedWork() }
                .onSuccess {
                    _state.update {
                        it.copy(
                            operationInProgress = false,
                            desktopStartInProgress = false,
                            noticeMessage = "中断されたDebian処理を再開しました。",
                        )
                    }
                    refreshContainers()
                }
                .onFailure(::showError)
        }
    }

    fun loadLogs(container: ContainerInfo) {
        viewModelScope.launch {
            _state.update {
                it.copy(
                    operationInProgress = true,
                    desktopStartInProgress = false,
                    errorMessage = null,
                )
            }
            runCatching { repository.logs(container.id) }
                .onSuccess { logs ->
                    _state.update {
                        it.copy(
                            operationInProgress = false,
                            desktopStartInProgress = false,
                            logsTitle = "${container.name} のログ",
                            logsContent = logs.ifBlank { "ログはまだありません。" },
                        )
                    }
                }
                .onFailure(::showError)
        }
    }

    fun dismissLogs() {
        _state.update { it.copy(logsTitle = null, logsContent = null) }
    }

    fun selectTab(tab: MainTab) {
        _state.update { it.copy(selectedTab = tab) }
    }

    fun clearMessages() {
        _state.update { it.copy(errorMessage = null, noticeMessage = null) }
    }

    fun openTerminal() = repository.openTerminal()

    fun openDisplay() = repository.openDisplay()

    fun openBatterySettings() = repository.openBatteryOptimizationSettings()

    fun openThisAppSettings() = repository.openThisAppSettings()

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            while (isActive) {
                val interval = if (_state.value.containers.any { it.isInstalling() }) {
                    LIVE_LOG_POLL_INTERVAL_MILLIS
                } else {
                    DEFAULT_POLL_INTERVAL_MILLIS
                }
                delay(interval)
                if (_state.value.setup.terminalReady && !_state.value.operationInProgress) {
                    refreshContainersNow(silent = true)
                }
            }
        }
    }

    private fun refreshContainers(silent: Boolean = false) {
        if (containerRefreshJob?.isActive == true) return
        containerRefreshJob = viewModelScope.launch {
            refreshContainersNow(silent)
        }
    }

    private suspend fun refreshContainersNow(silent: Boolean) {
        if (!silent) _state.update { it.copy(refreshing = true) }
        runCatching {
            val containers = if (silent) {
                repository.listContainersFast()
            } else {
                repository.listContainers()
            }
            containers to loadLiveInstallationLogs(containers)
        }.onSuccess { (containers, liveLogs) ->
            _state.update {
                it.copy(
                    containers = containers,
                    liveInstallationLogs = liveLogs,
                    refreshing = if (silent) it.refreshing else false,
                )
            }
        }.onFailure {
            if (!silent) showError(it)
        }
    }

    private suspend fun loadLiveInstallationLogs(
        containers: List<ContainerInfo>,
    ): Map<String, String> = coroutineScope {
        val previousLogs = _state.value.liveInstallationLogs
        containers
            .filter { it.isInstalling() }
            .map { container ->
                async {
                    val latest = runCatching {
                        repository.liveInstallationLogs(container.id)
                    }.getOrElse {
                        previousLogs[container.id].orEmpty()
                    }
                    container.id to latest
                }
            }
            .map { it.await() }
            .toMap()
    }

    private fun showError(throwable: Throwable) {
        _state.update {
            it.copy(
                initialLoading = false,
                refreshing = false,
                bootstrapping = false,
                operationInProgress = false,
                desktopStartInProgress = false,
                errorMessage = throwable.message ?: "処理に失敗しました。",
            )
        }
    }

    private fun ContainerInfo.isInstalling(): Boolean =
        state == ContainerState.QUEUED || state == ContainerState.INSTALLING

    companion object {
        private const val LIVE_LOG_POLL_INTERVAL_MILLIS = 2_000L
        private const val DEFAULT_POLL_INTERVAL_MILLIS = 5_000L
    }
}

package com.hatake716.linuxdesktop.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hatake716.linuxdesktop.LinuxDesktopApplication
import com.hatake716.linuxdesktop.data.ContainerInfo
import com.hatake716.linuxdesktop.data.ContainerState
import com.hatake716.linuxdesktop.data.DoctorReport
import com.hatake716.linuxdesktop.data.KeyboardLayout
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
    val desktopScalePercent: Int = 100,
    val extraKeysVisible: Boolean = true,
    val keyboardLayout: KeyboardLayout = KeyboardLayout.DEFAULT,
) {
    val terminalReady: Boolean get() = runtime.terminalReady
    val x11Ready: Boolean get() = runtime.x11Embedded
    val hostReady: Boolean get() = doctor?.hostReady == true
    val storageReady: Boolean get() = doctor?.storageReady == true
}

data class MainUiState(
    val initialLoading: Boolean = true,
    val refreshing: Boolean = false,
    val environmentError: String? = null,
    val bootstrapping: Boolean = false,
    val installation: com.hatake716.linuxdesktop.InstallationProgress = com.hatake716.linuxdesktop.InstallationProgress(),
    val operationInProgress: Boolean = false,
    val desktopStartInProgress: Boolean = false,
    val desktopStartup: com.hatake716.linuxdesktop.data.DesktopStartupProgress = com.hatake716.linuxdesktop.data.DesktopStartupProgress(),
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
    private var environmentRefreshJob: Job? = null
    private var environmentRefreshRequested = false

    init {
        refreshEnvironment(showLoading = true)
        viewModelScope.launch {
            linuxDesktopApplication.desktopStartup.progress.collect { progress ->
                _state.update {
                    it.copy(
                        desktopStartup = progress,
                        desktopStartInProgress = progress.busy,
                        operationInProgress = progress.busy || (!it.desktopStartInProgress && it.operationInProgress),
                    )
                }
            }
        }
        viewModelScope.launch {
            linuxDesktopApplication.installation.collect { progress ->
                _state.update { it.copy(
                    installation = progress,
                    errorMessage = progress.error,
                    noticeMessage = progress.message.takeIf { !progress.busy && it.isNotBlank() } ?: it.noticeMessage,
                ) }
                if (progress.phase >= 2 || !progress.busy) refreshEnvironment()
            }
        }
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
        if (environmentRefreshJob?.isActive == true) {
            environmentRefreshRequested = true
            return
        }
        environmentRefreshJob = viewModelScope.launch {
            _state.update {
                it.copy(initialLoading = showLoading && it.containers.isEmpty(), refreshing = !showLoading)
            }
            val runtime = repository.runtimeStatus()
            try {
                val doctor = if (runtime.terminalReady) repository.doctor() else null
                val containers = if (runtime.terminalReady) repository.listContainers() else emptyList()
                val liveLogs = if (runtime.terminalReady) loadLiveInstallationLogs(containers) else emptyMap()
                _state.update {
                    it.copy(
                        initialLoading = false,
                        refreshing = false,
                        environmentError = null,
                        setup = SetupSnapshot(
                            runtime = runtime,
                            doctor = doctor,
                            desktopScalePercent = repository.desktopScalePercent(),
                            extraKeysVisible = repository.extraKeysVisible(),
                            keyboardLayout = repository.keyboardLayout(),
                        ),
                        containers = containers,
                        liveInstallationLogs = liveLogs,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // A failed probe is not evidence that the user's saved environments are gone.
                _state.update {
                    it.copy(
                        initialLoading = false,
                        refreshing = false,
                        setup = it.setup.copy(runtime = runtime),
                        environmentError = "Linux環境の状態を確認できませんでした。再確認してください。\n" +
                            (failure.message ?: "実行環境から応答がありません。"),
                    )
                }
            } finally {
                if (environmentRefreshRequested) {
                    environmentRefreshRequested = false
                    environmentRefreshJob = null
                    refreshEnvironment()
                }
            }
        }
    }

    fun prepareForRestore() = linuxDesktopApplication.installLinux("", prepareOnly = true)

    fun createContainer(name: String) = linuxDesktopApplication.installLinux(name)

    fun startContainer(container: ContainerInfo) {
        if (container.state == ContainerState.RUNNING) {
            repository.openDisplay()
            return
        }
        viewModelScope.launch {
            try {
                linuxDesktopApplication.startDesktopSession(container.id, container.name).await()
                _state.update { it.copy(noticeMessage = "${container.name}を起動しました。") }
                refreshContainers()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Throwable) {
                // The Application keeps the failed launch and its logs visible after recreation.
                if (!linuxDesktopApplication.desktopStartup.progress.value.visible) showError(exception)
            }
        }
    }

    fun dismissStartupFailure() = linuxDesktopApplication.desktopStartup.dismissFailure()

    fun stopContainer(container: ContainerInfo) {
        if (container.isInstalling()) {
            com.hatake716.linuxdesktop.service.DesktopKeepAliveService.requestInstallationStop(linuxDesktopApplication)
            return
        }
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
        linuxDesktopApplication.resumeInstallations(force = true)
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

    fun setDesktopScale(percent: Int) {
        if (percent == _state.value.setup.desktopScalePercent) return
        viewModelScope.launch {
            _state.update {
                it.copy(
                    setup = it.setup.copy(desktopScalePercent = percent),
                    operationInProgress = true,
                    errorMessage = null,
                )
            }
            runCatching { repository.setDesktopScale(percent) }
                .onSuccess {
                    _state.update {
                        it.copy(
                            operationInProgress = false,
                            noticeMessage = "表示倍率を${percent}%に設定しました。" +
                                "デスクトップ起動中は即時、停止中は次回起動時に反映されます。",
                        )
                    }
                }
                .onFailure(::showError)
        }
    }

    fun setExtraKeysVisible(visible: Boolean) {
        if (visible == _state.value.setup.extraKeysVisible) return
        viewModelScope.launch {
            _state.update { it.copy(setup = it.setup.copy(extraKeysVisible = visible)) }
            runCatching { repository.setExtraKeysVisible(visible) }.onFailure(::showError)
        }
    }

    fun setKeyboardLayout(layout: KeyboardLayout) {
        if (layout == _state.value.setup.keyboardLayout) return
        viewModelScope.launch {
            _state.update { it.copy(setup = it.setup.copy(keyboardLayout = layout)) }
            runCatching { repository.setKeyboardLayout(layout) }.onFailure(::showError)
        }
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
            maybeResumeInterruptedInstalls(containers)
        }.onFailure {
            if (!silent) showError(it)
        }
    }

    /**
     * An install whose worker died with a previous app process shows as
     * QUEUED/INSTALLING with no live session. Auto-relaunch it (once per id per
     * process — the repository guards against loops) so a killed app never
     * strands a half-installed environment.
     */
    private fun maybeResumeInterruptedInstalls(containers: List<ContainerInfo>) {
        val interrupted = containers.filter {
            (it.state == ContainerState.QUEUED || it.state == ContainerState.INSTALLING) &&
                !it.sessionAlive
        }
        if (interrupted.isEmpty()) return
        linuxDesktopApplication.resumeInstallations()
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

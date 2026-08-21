package com.hatake716.linuxdesktop.data

import android.app.Activity
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Base64
import com.termux.app.EmbeddedTermuxRuntime
import com.termux.app.RunCommandService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal data class TermuxCommandResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val internalErrorCode: Int,
    val internalErrorMessage: String,
)

class TermuxCommandException(message: String, cause: Throwable? = null) : Exception(message, cause)

internal object TermuxResultRegistry {
    private val nextId = AtomicInteger(2000)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<TermuxCommandResult>>()

    fun register(): Pair<Int, CompletableDeferred<TermuxCommandResult>> {
        val id = nextId.incrementAndGet()
        val deferred = CompletableDeferred<TermuxCommandResult>()
        pending[id] = deferred
        return id to deferred
    }

    fun complete(id: Int, result: TermuxCommandResult) {
        pending.remove(id)?.complete(result)
    }

    fun fail(id: Int, throwable: Throwable) {
        pending.remove(id)?.completeExceptionally(throwable)
    }

    fun remove(id: Int) {
        pending.remove(id)?.cancel()
    }
}

class TermuxResultService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val executionId = intent?.getIntExtra(EXTRA_EXECUTION_ID, -1) ?: -1
        if (executionId >= 0) {
            val resultBundle = intent?.getBundleExtra(RESULT_BUNDLE)
            if (resultBundle == null) {
                TermuxResultRegistry.fail(
                    executionId,
                    TermuxCommandException("内蔵ターミナルから結果を受け取れませんでした。"),
                )
            } else {
                TermuxResultRegistry.complete(executionId, resultBundle.toCommandResult())
            }
        }
        stopSelf(startId)
        return START_NOT_STICKY
    }

    private fun Bundle.toCommandResult(): TermuxCommandResult = TermuxCommandResult(
        stdout = getString(RESULT_STDOUT, ""),
        stderr = getString(RESULT_STDERR, ""),
        exitCode = getInt(RESULT_EXIT_CODE, -1),
        internalErrorCode = getInt(RESULT_ERR, Activity.RESULT_OK),
        internalErrorMessage = getString(RESULT_ERRMSG, ""),
    )

    companion object {
        const val EXTRA_EXECUTION_ID = "com.hatake716.linuxdesktop.execution_id"
        private const val RESULT_BUNDLE = "result"
        private const val RESULT_STDOUT = "stdout"
        private const val RESULT_STDERR = "stderr"
        private const val RESULT_EXIT_CODE = "exitCode"
        private const val RESULT_ERR = "err"
        private const val RESULT_ERRMSG = "errmsg"
    }
}

class TermuxCommandClient(private val context: Context) {
    fun isRuntimeInstalled(): Boolean = EmbeddedTermuxRuntime.isBootstrapInstalled()

    suspend fun runBundledHostScript(
        script: String,
        action: String,
        arguments: List<String> = emptyList(),
        timeout: Duration = 60.seconds,
    ): String = runBundledController(
        script = script,
        installedName = "ldfa-host",
        action = action,
        arguments = arguments,
        label = "Linux Desktop: $action",
        timeout = timeout,
    )

    suspend fun runBundledX11Script(
        script: String,
        action: String,
        arguments: List<String> = emptyList(),
        timeout: Duration = 60.seconds,
    ): String = runBundledController(
        script = script,
        installedName = "ldfa-x11",
        action = action,
        arguments = arguments,
        label = "Linux Desktop X11: $action",
        timeout = timeout,
    )

    suspend fun runBundledVncScript(
        script: String,
        action: String,
        arguments: List<String> = emptyList(),
        timeout: Duration = 60.seconds,
    ): String = runBundledController(
        script = script,
        installedName = "ldfa-vnc",
        action = action,
        arguments = arguments,
        label = "Linux Desktop compatibility display: $action",
        timeout = timeout,
    )

    suspend fun runInstalledHost(
        action: String,
        arguments: List<String> = emptyList(),
        timeout: Duration = 30.seconds,
    ): String = execute(
        commandPath = INSTALLED_HOST_SCRIPT,
        arguments = listOf(action) + arguments,
        label = "Linux Desktop watchdog",
        timeout = timeout,
    ).checkedStdout()

    suspend fun runInstalledX11(
        action: String,
        arguments: List<String> = emptyList(),
        timeout: Duration = 30.seconds,
    ): String = execute(
        commandPath = INSTALLED_X11_SCRIPT,
        arguments = listOf(action) + arguments,
        label = "Linux Desktop X11 watchdog",
        timeout = timeout,
    ).checkedStdout()

    suspend fun runInstalledVnc(
        action: String,
        arguments: List<String> = emptyList(),
        timeout: Duration = 30.seconds,
    ): String = execute(
        commandPath = INSTALLED_VNC_SCRIPT,
        arguments = listOf(action) + arguments,
        label = "Linux Desktop compatibility display watchdog",
        timeout = timeout,
    ).checkedStdout()

    private suspend fun runBundledController(
        script: String,
        installedName: String,
        action: String,
        arguments: List<String>,
        label: String,
        timeout: Duration,
    ): String {
        val encodedScript = Base64.encodeToString(script.toByteArray(), Base64.NO_WRAP)
        val commandArguments = buildList {
            add("-lc")
            add(INSTALL_AND_EXECUTE_SCRIPT)
            add("ldfa")
            add(installedName)
            add(encodedScript)
            add(action)
            addAll(arguments)
        }

        return execute(
            commandPath = TERMUX_BASH,
            arguments = commandArguments,
            label = label,
            timeout = timeout,
        ).checkedStdout()
    }

    private suspend fun execute(
        commandPath: String,
        arguments: List<String>,
        label: String,
        timeout: Duration,
    ): TermuxCommandResult {
        if (!isRuntimeInstalled()) {
            throw TermuxCommandException("内蔵ターミナル基盤の準備が完了していません。")
        }
        if (!EmbeddedTermuxRuntime.ensureInternalCommandPolicy(context)) {
            throw TermuxCommandException(
                "内蔵ターミナルのコマンド実行設定を準備できませんでした。アプリのデータ領域を確認してください。",
            )
        }

        val (executionId, deferred) = TermuxResultRegistry.register()
        val resultIntent = Intent(context, TermuxResultService::class.java).apply {
            putExtra(TermuxResultService.EXTRA_EXECUTION_ID, executionId)
        }
        val pendingIntentFlags = PendingIntent.FLAG_ONE_SHOT or
            PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val resultPendingIntent = PendingIntent.getService(
            context,
            executionId,
            resultIntent,
            pendingIntentFlags,
        )

        val commandIntent = Intent(ACTION_RUN_COMMAND).apply {
            component = ComponentName(context, RunCommandService::class.java)
            putExtra(EXTRA_COMMAND_PATH, commandPath)
            putExtra(EXTRA_ARGUMENTS, arguments.toTypedArray())
            putExtra(EXTRA_WORKDIR, TERMUX_HOME)
            putExtra(EXTRA_BACKGROUND, true)
            putExtra(EXTRA_RUNNER, "app-shell")
            putExtra(EXTRA_COMMAND_LABEL, label)
            putExtra(EXTRA_COMMAND_DESCRIPTION, "アプリ内のUbuntu XFCE環境を管理します。")
            putExtra(EXTRA_PENDING_INTENT, resultPendingIntent)
        }

        try {
            context.startService(commandIntent)
                ?: throw TermuxCommandException("内蔵コマンドサービスを開始できませんでした。")
        } catch (exception: Exception) {
            TermuxResultRegistry.remove(executionId)
            throw TermuxCommandException(
                "内蔵ターミナルとの接続に失敗しました。アプリを再起動してください。",
                exception,
            )
        }

        return try {
            withTimeout(timeout) { deferred.await() }
        } catch (exception: Exception) {
            TermuxResultRegistry.remove(executionId)
            throw TermuxCommandException(
                "内蔵ターミナルから応答がありません。処理ログを確認してください。",
                exception,
            )
        }
    }

    private fun TermuxCommandResult.checkedStdout(): String {
        if (internalErrorCode != Activity.RESULT_OK) {
            throw TermuxCommandException(
                internalErrorMessage.ifBlank { "内蔵ターミナルでコマンドを開始できませんでした。" },
            )
        }
        if (exitCode != 0) {
            val detail = stderr.trim().ifBlank { stdout.trim() }
            throw TermuxCommandException(
                if (detail.isBlank()) "コマンドが終了コード $exitCode で失敗しました。"
                else detail.takeLast(7000),
            )
        }
        return stdout.trim()
    }

    companion object {
        private const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
        private const val EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
        private const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
        private const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
        private const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
        private const val EXTRA_RUNNER = "com.termux.RUN_COMMAND_RUNNER"
        private const val EXTRA_COMMAND_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL"
        private const val EXTRA_COMMAND_DESCRIPTION = "com.termux.RUN_COMMAND_COMMAND_DESCRIPTION"
        private const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"
        private const val TERMUX_HOME = "/data/data/com.termux/files/home"
        private const val TERMUX_BASH = "/data/data/com.termux/files/usr/bin/bash"
        private const val INSTALLED_HOST_SCRIPT =
            "/data/data/com.termux/files/home/.local/share/linux-desktop-for-android/bin/ldfa-host"
        private const val INSTALLED_X11_SCRIPT =
            "/data/data/com.termux/files/home/.local/share/linux-desktop-for-android/bin/ldfa-x11"
        private const val INSTALLED_VNC_SCRIPT =
            "/data/data/com.termux/files/home/.local/share/linux-desktop-for-android/bin/ldfa-vnc"

        private val INSTALL_AND_EXECUTE_SCRIPT = """
            set -e
            BASE="${'$'}HOME/.local/share/linux-desktop-for-android"
            mkdir -p "${'$'}BASE/bin"
            NAME="${'$'}1"
            ENCODED="${'$'}2"
            shift 2
            printf '%s' "${'$'}ENCODED" | base64 -d > "${'$'}BASE/bin/${'$'}NAME"
            chmod 700 "${'$'}BASE/bin/${'$'}NAME"
            exec "${'$'}BASE/bin/${'$'}NAME" "${'$'}@"
        """.trimIndent()
    }
}

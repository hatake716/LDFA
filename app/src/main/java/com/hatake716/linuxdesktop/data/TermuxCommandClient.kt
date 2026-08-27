package com.hatake716.linuxdesktop.data

import com.hatake716.linuxdesktop.BuildConfig
import android.app.Activity
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import com.termux.app.EmbeddedTermuxRuntime
import com.termux.app.RunCommandService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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
    private val pending = ConcurrentHashMap<String, CompletableDeferred<TermuxCommandResult>>()

    fun register(): Pair<String, CompletableDeferred<TermuxCommandResult>> {
        val id = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<TermuxCommandResult>()
        pending[id] = deferred
        return id to deferred
    }

    fun complete(id: String, result: TermuxCommandResult) {
        pending.remove(id)?.complete(result)
    }

    fun fail(id: String, throwable: Throwable) {
        pending.remove(id)?.completeExceptionally(throwable)
    }

    fun remove(id: String) {
        pending.remove(id)?.cancel()
    }
}

class TermuxResultService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val executionId = intent?.getStringExtra(EXTRA_EXECUTION_ID)
        if (executionId != null) {
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
    // Serializes installScript() so concurrent commands never race writing the
    // same controller file (the atomic rename + sha stamp must be one critical
    // section).
    private val installLock = Any()

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
        // Write the controller script to its installed path directly from the app
        // process, then exec that path with a tiny argv. The app runs as the same
        // uid that owns /data/data/com.termux (applicationId = "com.termux"), so a
        // plain file write to the Termux data dir succeeds without the shell.
        //
        // The previous approach base64-encoded the whole script and passed it as a
        // single argv element to a bash wrapper. Once the script grew past ~97 KB
        // its base64 (~131 KB) exceeded Linux's per-argument limit MAX_ARG_STRLEN
        // (128 KB, independent of ARG_MAX), so execve failed with E2BIG
        // ("Argument list too long") and EVERY host command — doctor included —
        // silently failed. Writing the file and passing only its path keeps the
        // argv tiny at any script size, so this can never regress on growth.
        val installedPath = installScript(installedName, script)

        return execute(
            commandPath = installedPath,
            arguments = listOf(action) + arguments,
            label = label,
            timeout = timeout,
        ).checkedStdout()
    }

    // Write the bundled script to its installed path (atomically, then chmod 0700)
    // and return that path. Skips the rewrite when the content is byte-identical to
    // what is already installed, keyed on a SHA-256 sidecar so an edited script body
    // is always re-installed even if the version string did not change.
    private fun installScript(installedName: String, script: String): String {
        val binDir = File(INSTALLED_BIN_DIR)
        val target = File(binDir, installedName)
        val bytes = script.toByteArray()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        val stamp = File(binDir, ".$installedName.sha256")

        synchronized(installLock) {
            val current = runCatching { stamp.readText().trim() }.getOrNull()
            if (current == digest && target.canExecute()) {
                return target.absolutePath
            }
            if (!binDir.isDirectory && !binDir.mkdirs()) {
                throw TermuxCommandException("内蔵コマンドの配置先を作成できませんでした。")
            }
            val temporary = File(binDir, ".$installedName.tmp")
            try {
                temporary.outputStream().use { it.write(bytes) }
                if (!temporary.setExecutable(true, true) || !temporary.setReadable(true, true)) {
                    throw TermuxCommandException("内蔵コマンドの実行権限を設定できませんでした。")
                }
                if (!temporary.renameTo(target)) {
                    throw TermuxCommandException("内蔵コマンドを配置できませんでした。")
                }
                stamp.writeText(digest)
            } finally {
                temporary.delete()
            }
        }
        return target.absolutePath
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
            action = "$RESULT_ACTION_PREFIX.$executionId"
            putExtra(TermuxResultService.EXTRA_EXECUTION_ID, executionId)
        }
        val pendingIntentFlags = PendingIntent.FLAG_ONE_SHOT or
            PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val resultPendingIntent = PendingIntent.getService(
            context,
            executionId.hashCode(),
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
            putExtra(EXTRA_COMMAND_DESCRIPTION, "アプリ内のDebian XFCE環境を管理します。")
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
        } catch (exception: TimeoutCancellationException) {
            TermuxResultRegistry.remove(executionId)
            throw TermuxCommandException(
                "内蔵ターミナルから応答がありません。処理ログを確認してください。",
                exception,
            )
        } catch (exception: CancellationException) {
            TermuxResultRegistry.remove(executionId)
            throw exception
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
        // The bundled vendor RunCommandService validates the intent action and reads its
        // extras via TermuxConstants, which builds them as `TERMUX_PACKAGE_NAME + ".RUN_COMMAND*"`.
        // TERMUX_PACKAGE_NAME is now our applicationId (Play rename), so these MUST be derived
        // from BuildConfig.APPLICATION_ID to match — hardcoding "com.termux.*" made the service
        // reject every command ("Invalid intent action to RunCommandService"). The service's
        // permission is a signature permission and it's the same in-process APK, so the sender
        // and receiver just have to agree on these strings.
        private val ACTION_RUN_COMMAND = "${BuildConfig.APPLICATION_ID}.RUN_COMMAND"
        private val EXTRA_COMMAND_PATH = "${BuildConfig.APPLICATION_ID}.RUN_COMMAND_PATH"
        private val EXTRA_ARGUMENTS = "${BuildConfig.APPLICATION_ID}.RUN_COMMAND_ARGUMENTS"
        private val EXTRA_WORKDIR = "${BuildConfig.APPLICATION_ID}.RUN_COMMAND_WORKDIR"
        private val EXTRA_BACKGROUND = "${BuildConfig.APPLICATION_ID}.RUN_COMMAND_BACKGROUND"
        private val EXTRA_RUNNER = "${BuildConfig.APPLICATION_ID}.RUN_COMMAND_RUNNER"
        private val EXTRA_COMMAND_LABEL = "${BuildConfig.APPLICATION_ID}.RUN_COMMAND_COMMAND_LABEL"
        private val EXTRA_COMMAND_DESCRIPTION = "${BuildConfig.APPLICATION_ID}.RUN_COMMAND_COMMAND_DESCRIPTION"
        private val EXTRA_PENDING_INTENT = "${BuildConfig.APPLICATION_ID}.RUN_COMMAND_PENDING_INTENT"
        private const val RESULT_ACTION_PREFIX = "com.hatake716.linuxdesktop.TERMUX_RESULT"
        private const val TERMUX_HOME = "/data/data/com.hatake716.linuxdesktop/files/home"
        private const val INSTALLED_BIN_DIR =
            "/data/data/com.hatake716.linuxdesktop/files/home/.local/share/linux-desktop-for-android/bin"
        private const val INSTALLED_HOST_SCRIPT = "$INSTALLED_BIN_DIR/ldfa-host"
        private const val INSTALLED_X11_SCRIPT = "$INSTALLED_BIN_DIR/ldfa-x11"
        private const val INSTALLED_VNC_SCRIPT = "$INSTALLED_BIN_DIR/ldfa-vnc"
    }
}

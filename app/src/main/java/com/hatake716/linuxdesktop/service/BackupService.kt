package com.hatake716.linuxdesktop.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.hatake716.linuxdesktop.MainActivity
import com.hatake716.linuxdesktop.R
import com.hatake716.linuxdesktop.backup.BackupEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Runs a backup or restore off the UI thread as a foreground `dataSync` service:
 * the work survives the screen turning off and a paused Activity, holds a
 * PARTIAL_WAKE_LOCK, and reports progress both in its notification and through a
 * process-wide [state] flow the UI collects. Only one job runs at a time.
 */
class BackupService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                job?.cancel()
                return START_NOT_STICKY
            }
            ACTION_BACKUP -> startBackup(
                containerId = intent.getStringExtra(EXTRA_CONTAINER_ID).orEmpty(),
                outputDirPath = intent.getStringExtra(EXTRA_OUTPUT_DIR).orEmpty(),
            )
            ACTION_RESTORE -> startRestore(
                inputPath = intent.getStringExtra(EXTRA_INPUT_PATH).orEmpty(),
                existingNames = intent.getStringArrayExtra(EXTRA_EXISTING_NAMES)?.toSet() ?: emptySet(),
            )
            else -> stopSelf(startId)
        }
        // A cancelled/finished job clears itself; we don't want the system to
        // recreate a half-specified restart, so NOT_STICKY.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        job?.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        scope.cancel()
        super.onDestroy()
    }

    // ---- backup ------------------------------------------------------------

    private fun startBackup(containerId: String, outputDirPath: String) {
        if (job?.isActive == true) return
        if (containerId.isBlank() || outputDirPath.isBlank()) { stopSelf(); return }
        if (!goForeground(getString(R.string.backup_notif_creating))) return
        _state.value = BackupUiState.Running(BackupUiState.Op.BACKUP, indeterminate = true)
        job = scope.launch {
            try {
                val engine = BackupEngine(applicationContext)
                val out = engine.createFull(
                    id = containerId,
                    outputDir = File(outputDirPath),
                    listener = { phase -> onBackupPhase(phase) },
                )
                _state.value = BackupUiState.Done(
                    op = BackupUiState.Op.BACKUP,
                    message = getString(
                        R.string.backup_notif_done,
                        out.file.name,
                        humanSize(out.sizeBytes),
                    ),
                    detail = buildString {
                        append(getString(R.string.backup_done_location)).append("\n\n")
                        append(getString(R.string.backup_dest_fullpath, out.file.absolutePath)).append('\n')
                        append("SHA-256: ").append(out.sha256Prefix)
                        if (out.skippedSpecial > 0 || out.unreadableCount > 0) {
                            append('\n').append(
                                getString(R.string.backup_skipped_note, out.skippedSpecial, out.unreadableCount),
                            )
                        }
                    },
                    filePath = out.file.absolutePath,
                )
            } catch (c: CancellationException) {
                _state.value = BackupUiState.Cancelled(BackupUiState.Op.BACKUP)
            } catch (e: BackupEngine.BackupError) {
                _state.value = BackupUiState.Failed(BackupUiState.Op.BACKUP, e.message ?: "失敗しました")
            } catch (t: Throwable) {
                android.util.Log.e(TAG, "backup failed", t)
                _state.value = BackupUiState.Failed(BackupUiState.Op.BACKUP, describeFailure(t))
            } finally {
                finish()
            }
        }
    }

    private fun onBackupPhase(phase: BackupEngine.Phase) {
        when (phase) {
            is BackupEngine.Phase.Counting -> {
                _state.value = BackupUiState.Running(BackupUiState.Op.BACKUP, indeterminate = true)
                updateNotification(getString(R.string.backup_notif_counting), null)
            }
            is BackupEngine.Phase.Writing -> {
                val pct = percent(phase.processed, phase.total)
                _state.value = BackupUiState.Running(
                    BackupUiState.Op.BACKUP,
                    indeterminate = false,
                    percent = pct,
                    processed = phase.processed,
                    total = phase.total,
                    bytes = phase.bytes,
                )
                updateNotification(getString(R.string.backup_notif_creating), pct)
            }
            else -> Unit
        }
    }

    // ---- restore -----------------------------------------------------------

    private fun startRestore(inputPath: String, existingNames: Set<String>) {
        if (job?.isActive == true) return
        if (inputPath.isBlank()) { stopSelf(); return }
        if (!goForeground(getString(R.string.restore_notif_running))) return
        _state.value = BackupUiState.Running(BackupUiState.Op.RESTORE, indeterminate = true)
        job = scope.launch {
            try {
                val engine = BackupEngine(applicationContext)
                val result = engine.restore(
                    input = File(inputPath),
                    existingDisplayNames = existingNames,
                    listener = { phase -> onRestorePhase(phase) },
                )
                _state.value = BackupUiState.Done(
                    op = BackupUiState.Op.RESTORE,
                    message = getString(R.string.restore_notif_done, result.displayName),
                    detail = getString(R.string.restore_done_detail),
                    filePath = null,
                )
            } catch (c: CancellationException) {
                _state.value = BackupUiState.Cancelled(BackupUiState.Op.RESTORE)
            } catch (e: BackupEngine.BackupError) {
                _state.value = BackupUiState.Failed(BackupUiState.Op.RESTORE, e.message ?: "失敗しました")
            } catch (t: Throwable) {
                android.util.Log.e(TAG, "restore failed", t)
                _state.value = BackupUiState.Failed(BackupUiState.Op.RESTORE, describeFailure(t))
            } finally {
                // The picker copies the .ldfa into our cache; it is no longer
                // needed once the restore ends (either way) and can be huge.
                val input = File(inputPath)
                if (input.absolutePath.startsWith(cacheDir.absolutePath)) input.delete()
                finish()
            }
        }
    }

    /**
     * A diagnosable failure message: exception class + message, plus the root
     * cause when it differs. "restore failed" alone is undebuggable from a
     * user report; this is what the error card shows.
     */
    private fun describeFailure(t: Throwable): String {
        val root = generateSequence(t) { it.cause }.last()
        val head = "${t::class.simpleName}: ${t.message ?: ""}".trim().trimEnd(':')
        return if (root !== t) {
            "$head\n(${root::class.simpleName}: ${root.message ?: ""})".trim()
        } else head
    }

    private fun onRestorePhase(phase: BackupEngine.Phase) {
        if (phase is BackupEngine.Phase.Extracting) {
            val pct = percent(phase.processed, phase.total)
            _state.value = BackupUiState.Running(
                BackupUiState.Op.RESTORE,
                indeterminate = false,
                percent = pct,
                processed = phase.processed,
                total = phase.total,
            )
            updateNotification(getString(R.string.restore_notif_running), pct)
        }
    }

    // ---- lifecycle helpers -------------------------------------------------

    private fun finish() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun goForeground(text: String): Boolean {
        val notification = buildNotification(text, null)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException (S+): promoted from the
            // background. Refuse the operation and stop before the
            // did-not-call-startForeground watchdog fires instead of crashing.
            android.util.Log.w("BackupService", "startForeground denied (app in background); stopping", e)
            stopSelf()
            false
        }
    }

    private fun updateNotification(text: String, percent: Int?) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text, percent))
    }

    private fun buildNotification(text: String, percent: Int?): android.app.Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.backup_channel_name))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    20,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .addAction(
                0,
                getString(R.string.cancel),
                PendingIntent.getService(
                    this,
                    21,
                    Intent(this, BackupService::class.java).apply { action = ACTION_CANCEL },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        if (percent != null) builder.setProgress(100, percent, false) else builder.setProgress(0, 0, true)
        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.backup_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.backup_channel_desc)
                setShowBadge(false)
            },
        )
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(PowerManager::class.java)
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:LinuxDesktopBackup",
        ).apply {
            setReferenceCounted(false)
            acquire(30 * 60 * 1000L) // hard cap so a stuck job can't pin the CPU forever
        }
    }

    private fun percent(processed: Long, total: Long): Int =
        if (total <= 0) 0 else ((processed * 100) / total).toInt().coerceIn(0, 100)

    private fun humanSize(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024) "%.2f GB".format(mb / 1024) else "%.0f MB".format(mb)
    }

    companion object {
        private const val TAG = "LdfaBackup"
        private const val CHANNEL_ID = "linux_desktop_backup"
        private const val NOTIFICATION_ID = 717
        private const val ACTION_BACKUP = "com.hatake716.linuxdesktop.BACKUP_CREATE"
        private const val ACTION_RESTORE = "com.hatake716.linuxdesktop.BACKUP_RESTORE"
        private const val ACTION_CANCEL = "com.hatake716.linuxdesktop.BACKUP_CANCEL"
        private const val EXTRA_CONTAINER_ID = "container_id"
        private const val EXTRA_OUTPUT_DIR = "output_dir"
        private const val EXTRA_INPUT_PATH = "input_path"
        private const val EXTRA_EXISTING_NAMES = "existing_names"

        private val _state = MutableStateFlow<BackupUiState>(BackupUiState.Idle)

        /** Process-wide progress/result the UI collects. Reset with [acknowledge]. */
        val state: StateFlow<BackupUiState> = _state.asStateFlow()

        /** Clear a terminal state (Done/Failed/Cancelled) once the UI has shown it. */
        fun acknowledge() {
            if (_state.value.isTerminal) _state.value = BackupUiState.Idle
        }

        fun startBackup(context: Context, containerId: String, outputDir: File) {
            val intent = Intent(context, BackupService::class.java).apply {
                action = ACTION_BACKUP
                putExtra(EXTRA_CONTAINER_ID, containerId)
                putExtra(EXTRA_OUTPUT_DIR, outputDir.absolutePath)
            }
            startGuarded(context, intent)
        }

        fun startRestore(context: Context, inputFile: File, existingNames: Set<String>) {
            val intent = Intent(context, BackupService::class.java).apply {
                action = ACTION_RESTORE
                putExtra(EXTRA_INPUT_PATH, inputFile.absolutePath)
                putExtra(EXTRA_EXISTING_NAMES, existingNames.toTypedArray())
            }
            startGuarded(context, intent)
        }

        private fun startGuarded(context: Context, intent: Intent) {
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException (S+) from the
                // background: the operation just doesn't start — never crash.
                android.util.Log.w("BackupService", "start denied (app in background); operation not started", e)
            }
        }

        fun cancel(context: Context) {
            val intent = Intent(context, BackupService::class.java).apply { action = ACTION_CANCEL }
            context.startService(intent)
        }
    }
}

/** UI-facing state for a backup/restore run, exposed by [BackupService.state]. */
sealed interface BackupUiState {
    val isTerminal: Boolean get() = this is Done || this is Failed || this is Cancelled

    enum class Op { BACKUP, RESTORE }

    object Idle : BackupUiState

    data class Running(
        val op: Op,
        val indeterminate: Boolean,
        val percent: Int = 0,
        val processed: Long = 0,
        val total: Long = 0,
        val bytes: Long = 0,
    ) : BackupUiState

    data class Done(
        val op: Op,
        val message: String,
        val detail: String,
        val filePath: String?,
    ) : BackupUiState

    data class Failed(val op: Op, val message: String) : BackupUiState

    data class Cancelled(val op: Op) : BackupUiState
}

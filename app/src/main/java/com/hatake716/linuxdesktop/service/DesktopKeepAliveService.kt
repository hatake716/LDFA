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
import com.hatake716.linuxdesktop.LinuxDesktopApplication
import com.hatake716.linuxdesktop.MainActivity
import com.hatake716.linuxdesktop.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DesktopKeepAliveService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val repository by lazy { (application as LinuxDesktopApplication).repository }
    private var heartbeatJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SESSION -> stopActiveSession(
                intent.getStringExtra(EXTRA_CONTAINER_ID),
                startId,
            )
            else -> startMonitoring(intent?.getStringExtra(EXTRA_CONTAINER_ID))
        }
        return START_STICKY
    }

    override fun onDestroy() {
        heartbeatJob?.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        scope.cancel()
        super.onDestroy()
    }

    private fun startMonitoring(requestedId: String?) {
        val containerId = requestedId ?: repository.activeContainerId()
        val notification = buildNotification(containerId)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException (S+): promoted from the
            // background. Stop before the did-not-call-startForeground watchdog
            // fires instead of crashing; the desktop keeps running without the
            // keep-alive heartbeat until the next foreground start re-launches it.
            android.util.Log.w("DesktopKeepAlive", "startForeground denied (app in background); stopping", e)
            stopSelf()
            return
        }
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            var idleCycles = 0
            while (isActive) {
                val activeId = repository.activeContainerId() ?: containerId
                val preparing = installationRunning()
                val busy = preparing || repository.heartbeat(activeId)
                if (!busy && repository.activeContainerId() == null) {
                    idleCycles += 1
                    if (idleCycles >= IDLE_CYCLES_BEFORE_STOP) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                        break
                    }
                } else {
                    idleCycles = 0
                }
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(activeId))
                delay(HEARTBEAT_INTERVAL_MILLIS)
            }
        }
    }

    private fun stopActiveSession(requestedId: String?, stopStartId: Int) {
        val activeId = repository.activeContainerId()
        if (requestedId != null && activeId != null && requestedId != activeId) return
        val id = requestedId ?: activeId
        scope.launch {
            if (id != null) {
                try {
                    repository.stopContainer(id)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    // stopContainer performs its owned viewer/server cleanup before
                    // reporting a host failure. Reconcile service ownership below.
                }
            }
            withContext(Dispatchers.Main.immediate) {
                // The stop command may have waited behind a newer session start.
                // Never let an old notification remove that new session's monitor.
                val remainingId = repository.activeContainerId()
                if (remainingId != null) {
                    startMonitoring(remainingId)
                } else if (stopSelfResult(stopStartId)) {
                    // Remove the foreground notification only when this STOP is
                    // still the newest service start. A newer START keeps both
                    // the service and its notification intact.
                    stopForeground(STOP_FOREGROUND_REMOVE)
                }
            }
        }
    }

    private fun installationRunning() =
        (application as LinuxDesktopApplication).preparingInstallation || repository.hasActiveInstallation()

    private fun buildNotification(containerId: String?) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(if (installationRunning()) "Linuxを準備中" else getString(R.string.keep_alive_notification_title))
        .setContentText(if (installationRunning()) "Linuxを準備しています。進捗はアプリで確認できます。" else getString(R.string.keep_alive_notification_text))
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                10,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .apply { if (containerId != null) addAction(
            0,
            getString(R.string.stop),
            PendingIntent.getService(
                this@DesktopKeepAliveService,
                11,
                Intent(this@DesktopKeepAliveService, DesktopKeepAliveService::class.java).apply {
                    action = ACTION_STOP_SESSION
                    putExtra(EXTRA_CONTAINER_ID, containerId)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        ) }
        .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.keep_alive_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.keep_alive_notification_text)
                setShowBadge(false)
            },
        )
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(PowerManager::class.java)
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:LinuxDesktopKeepAlive",
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    companion object {
        private const val CHANNEL_ID = "linux_desktop_keep_alive"
        private const val NOTIFICATION_ID = 716
        private const val EXTRA_CONTAINER_ID = "container_id"
        private const val ACTION_START = "com.hatake716.linuxdesktop.START_KEEP_ALIVE"
        private const val ACTION_STOP_SESSION = "com.hatake716.linuxdesktop.STOP_SESSION"
        private const val HEARTBEAT_INTERVAL_MILLIS = 30_000L
        private const val IDLE_CYCLES_BEFORE_STOP = 2

        fun start(context: Context, containerId: String? = null) {
            val intent = Intent(context, DesktopKeepAliveService::class.java).apply {
                action = ACTION_START
                if (containerId != null) putExtra(EXTRA_CONTAINER_ID, containerId)
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException (S+) from the
                // background: the desktop simply runs without the keep-alive
                // heartbeat until the next foreground start — never crash for it.
                android.util.Log.w("DesktopKeepAlive", "start denied (app in background); skipping keep-alive", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DesktopKeepAliveService::class.java))
        }
    }
}

package com.codex.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

class CodexForegroundService : Service() {

    companion object {
        const val ACTION_ENSURE_SERVER = "com.codex.mobile.action.ENSURE_SERVER"
        private const val CHANNEL_ID = "codex_running"
        private const val NOTIFICATION_ID = 1
        private const val WATCHDOG_INTERVAL_MS = 30_000L
        private const val WAKE_LOCK_TIMEOUT_MS = 120_000L
        private const val TAG = "CodexForegroundService"
    }

    private lateinit var serverManager: CodexServerManager
    private lateinit var watchdogThread: HandlerThread
    private lateinit var watchdogHandler: Handler
    private val ensureRunning = AtomicBoolean(false)

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            ensureServerAsync()
            watchdogHandler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        serverManager = CodexServerManager(applicationContext)
        watchdogThread = HandlerThread("codex-service-watchdog")
        watchdogThread.start()
        watchdogHandler = Handler(watchdogThread.looper)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        watchdogHandler.post(watchdogRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_ENSURE_SERVER || intent?.action == null) {
            ensureServerAsync()
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        ensureServerAsync()
    }

    override fun onDestroy() {
        watchdogHandler.removeCallbacksAndMessages(null)
        watchdogThread.quitSafely()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AnyClaw Running",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Keeps Codex server running in the background"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("AnyClaw is running")
            .setContentText("Server active in background")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun ensureServerAsync() {
        if (!ensureRunning.compareAndSet(false, true)) {
            return
        }

        watchdogHandler.post {
            val wakeLock = acquireWakeLock()
            try {
                ensureServer()
            } finally {
                if (wakeLock?.isHeld == true) {
                    wakeLock.release()
                }
                ensureRunning.set(false)
            }
        }
    }

    private fun ensureServer() {
        if (serverManager.isServerResponsive()) {
            Log.d(TAG, "Codex server is healthy")
            return
        }

        if (!serverManager.isReadyForBackgroundStart()) {
            Log.i(TAG, "Codex runtime is not ready for background start yet")
            return
        }

        Log.w(TAG, "Codex server is down; attempting restart")
        val started = serverManager.ensureRuntimeServices { message ->
            Log.i(TAG, "[ensure] $message")
        }
        if (!started) {
            Log.w(TAG, "Codex server restart attempt did not complete successfully")
        }
    }

    private fun acquireWakeLock(): PowerManager.WakeLock? {
        return try {
            val powerManager = getSystemService(PowerManager::class.java)
            powerManager?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "$packageName:codex-watchdog",
            )?.apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to acquire watchdog wake lock: ${e.message}")
            null
        }
    }
}

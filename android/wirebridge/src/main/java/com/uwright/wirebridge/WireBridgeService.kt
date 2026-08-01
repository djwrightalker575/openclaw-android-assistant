package com.uwright.wirebridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import java.util.concurrent.Executors

class WireBridgeService : Service() {
    companion object {
        const val ACTION_START = "wire.START"
        const val ACTION_STOP = "wire.STOP"
        private const val CHANNEL = "wire_bridge"
        private const val NOTIFICATION_ID = 1941
        private const val PROXY_PORT = 18924

        fun readStatus(context: Context): String {
            val f = File(context.filesDir, "wirebridge/status.txt")
            return if (f.exists()) f.readText() else "No status recorded"
        }
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val statusLock = Any()

    @Volatile
    private var process: Process? = null

    @Volatile
    private var connectProxy: AndroidConnectProxy? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "WIRE Android Bridge", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopBridge()
            else -> startBridge()
        }
        return START_STICKY
    }

    private fun startBridge() {
        if (process?.isAlive == true) return
        startForeground(NOTIFICATION_ID, notification("Starting secure MCP tunnel"))

        executor.execute {
            val root = File(filesDir, "wirebridge").apply { mkdirs() }
            val status = File(root, "status.txt")
            try {
                val prefs = securePrefs()
                val tunnelId = prefs.getString("tunnel_id", null) ?: error("Tunnel ID missing")
                val apiKey = prefs.getString("api_key", null) ?: error("Runtime API key missing")
                val binary = File(root, "tunnel-client")

                status.writeText(
                    "WIRE Android Bridge v0.1.1\n" +
                        "Launching tunnel client\n" +
                        "Tunnel: $tunnelId\n"
                )

                val proxy = AndroidConnectProxy(PROXY_PORT) { line ->
                    appendStatus(status, "[proxy] $line\n")
                }
                if (!proxy.start()) {
                    error("Android CONNECT proxy failed to start")
                }
                connectProxy = proxy
                appendStatus(status, "Native DNS bypass enabled through 127.0.0.1:$PROXY_PORT\n")

                if (!binary.exists()) {
                    assets.open("tunnel-client-linux-arm64").use { input ->
                        binary.outputStream().use { output -> input.copyTo(output) }
                    }
                    binary.setExecutable(true, true)
                }

                val healthUrl = File(root, "health.url")
                val command = listOf(
                    binary.absolutePath,
                    "run",
                    "--embedded-mcp-stub",
                    "--control-plane.tunnel-id", tunnelId,
                    "--health.listen-addr", "127.0.0.1:8080",
                    "--health.url-file", healthUrl.absolutePath,
                )

                val pb = ProcessBuilder(command)
                    .directory(root)
                    .redirectErrorStream(true)

                pb.environment()["CONTROL_PLANE_API_KEY"] = apiKey
                pb.environment()["HTTPS_PROXY"] = "http://127.0.0.1:$PROXY_PORT"
                pb.environment()["https_proxy"] = "http://127.0.0.1:$PROXY_PORT"
                pb.environment()["NO_PROXY"] = "127.0.0.1,localhost,::1"
                pb.environment()["no_proxy"] = "127.0.0.1,localhost,::1"

                process = pb.start()
                appendStatus(status, "Process started; waiting for readiness\n")

                process!!.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        appendStatus(status, line.take(2000) + "\n")
                        trimStatusIfNeeded(status)
                    }
                }

                val exit = process?.waitFor()
                appendStatus(status, "Tunnel client exited: $exit\n")
            } catch (t: Throwable) {
                appendStatus(status, "FAILED: ${t::class.java.simpleName}: ${t.message}\n")
            } finally {
                process = null
                connectProxy?.stop()
                connectProxy = null
                stopForeground(STOP_FOREGROUND_DETACH)
            }
        }
    }

    private fun stopBridge() {
        process?.destroy()
        process = null
        connectProxy?.stop()
        connectProxy = null

        File(filesDir, "wirebridge/status.txt").apply {
            parentFile?.mkdirs()
            appendStatus(this, "Bridge stopped by operator\n")
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun appendStatus(status: File, text: String) {
        synchronized(statusLock) {
            status.appendText(text)
        }
    }

    private fun trimStatusIfNeeded(status: File) {
        synchronized(statusLock) {
            if (status.length() > 250_000) {
                val tail = status.readText().takeLast(150_000)
                status.writeText(tail)
            }
        }
    }

    private fun notification(text: String): Notification = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.stat_sys_upload)
        .setContentTitle("WIRE Android Bridge")
        .setContentText(text)
        .setOngoing(true)
        .build()

    private fun securePrefs() = EncryptedSharedPreferences.create(
        this,
        "wire_bridge_secure",
        MasterKey.Builder(this).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    override fun onBind(intent: Intent?): IBinder? = null
}

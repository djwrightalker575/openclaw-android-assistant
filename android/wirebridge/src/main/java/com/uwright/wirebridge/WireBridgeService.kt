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

        fun readStatus(context: Context): String {
            val f = File(context.filesDir, "wirebridge/status.txt")
            return if (f.exists()) f.readText() else "No status recorded"
        }
    }

    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var process: Process? = null

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
                status.writeText("Launching tunnel client\nTunnel: $tunnelId\n")
                val pb = ProcessBuilder(command)
                    .directory(root)
                    .redirectErrorStream(true)
                pb.environment()["CONTROL_PLANE_API_KEY"] = apiKey
                process = pb.start()
                status.appendText("Process started; waiting for readiness\n")
                process!!.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        status.appendText(line.take(2000) + "\n")
                        if (status.length() > 250_000) {
                            val tail = status.readText().takeLast(150_000)
                            status.writeText(tail)
                        }
                    }
                }
                val exit = process?.waitFor()
                status.appendText("Tunnel client exited: $exit\n")
            } catch (t: Throwable) {
                status.appendText("FAILED: ${t::class.java.simpleName}: ${t.message}\n")
            } finally {
                process = null
                stopForeground(STOP_FOREGROUND_DETACH)
            }
        }
    }

    private fun stopBridge() {
        process?.destroy()
        process = null
        File(filesDir, "wirebridge/status.txt").apply {
            parentFile?.mkdirs()
            appendText("Bridge stopped by operator\n")
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
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

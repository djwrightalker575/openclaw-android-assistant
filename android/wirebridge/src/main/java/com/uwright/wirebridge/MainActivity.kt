package com.uwright.wirebridge

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class MainActivity : AppCompatActivity() {
    private lateinit var tunnelId: EditText
    private lateinit var apiKey: EditText
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = securePrefs()
        tunnelId = EditText(this).apply {
            hint = "Tunnel ID"
            setText(prefs.getString("tunnel_id", "tunnel_6a6d072c8dec8191ab554743dead3f65"))
        }
        apiKey = EditText(this).apply {
            hint = "Restricted tunnel runtime API key"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        status = TextView(this).apply { text = "Stopped" }

        val saveStart = Button(this).apply {
            text = "Save and start bridge"
            setOnClickListener {
                val id = tunnelId.text.toString().trim()
                val key = apiKey.text.toString().trim()
                require(id.startsWith("tunnel_")) { "Invalid tunnel ID" }
                require(key.isNotBlank()) { "Runtime API key required" }
                prefs.edit().putString("tunnel_id", id).putString("api_key", key).apply()
                startForegroundService(Intent(this@MainActivity, WireBridgeService::class.java).setAction(WireBridgeService.ACTION_START))
                apiKey.setText("")
                status.text = "Starting…"
            }
        }

        val stop = Button(this).apply {
            text = "Stop bridge"
            setOnClickListener {
                startService(Intent(this@MainActivity, WireBridgeService::class.java).setAction(WireBridgeService.ACTION_STOP))
                status.text = "Stopped"
            }
        }

        val emergency = Button(this).apply {
            text = "EMERGENCY STOP"
            setOnClickListener {
                startService(Intent(this@MainActivity, WireBridgeService::class.java).setAction(WireBridgeService.ACTION_STOP))
                prefs.edit().remove("api_key").apply()
                status.text = "Stopped; runtime key erased"
            }
        }

        val refresh = Button(this).apply {
            text = "Refresh status"
            setOnClickListener { status.text = WireBridgeService.readStatus(this@MainActivity) }
        }

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (20 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
            addView(TextView(this@MainActivity).apply {
                text = "WIRE Android Bridge\nFULL_LOCAL architecture — transport proof build"
                textSize = 22f
            })
            addView(tunnelId)
            addView(apiKey)
            addView(saveStart)
            addView(stop)
            addView(emergency)
            addView(refresh)
            addView(status)
        }
        setContentView(ScrollView(this).apply { addView(body) })
    }

    private fun securePrefs() = EncryptedSharedPreferences.create(
        this,
        "wire_bridge_secure",
        MasterKey.Builder(this).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
}

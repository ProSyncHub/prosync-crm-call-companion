package com.prosync.crmcompanion

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var settings: SettingsStore
    private val handler = Handler(Looper.getMainLooper())
    private val refreshLoop = object : Runnable {
        override fun run() { render(); handler.postDelayed(this, 2_000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyProSyncSystemBars()
        settings = SettingsStore(this).also {
            it.apiBaseUrl = BuildConfig.CRM_BASE_URL
            it.apiKey = BuildConfig.MOBILE_SYNC_API_KEY
        }
        if (!settings.onboardingComplete) {
            startActivity(Intent(this, OnboardingActivity::class.java)); finish(); return
        }
        setContentView(R.layout.activity_main)
        findViewById<Button>(R.id.callsButton).setOnClickListener { startActivity(Intent(this, CallHistoryActivity::class.java)) }
        findViewById<Button>(R.id.settingsButton).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        findViewById<Button>(R.id.captureToggleButton).setOnClickListener { toggleCapture() }
        if (settings.shiftActive) {
            SyncScheduler.scheduleRecovery(this)
            SyncScheduler.enqueueRecordingAndSync(this, 0)
            ActiveUserNotification.show(this)
        }
        render()
    }

    override fun onResume() {
        super.onResume()
        if (::settings.isInitialized && settings.onboardingComplete) {
            handler.removeCallbacks(refreshLoop); handler.post(refreshLoop)
            if (settings.shiftActive) SyncScheduler.enqueueRecordingAndSync(this, 0)
        }
    }

    override fun onPause() { handler.removeCallbacks(refreshLoop); super.onPause() }

    private fun toggleCapture() {
        if (settings.shiftActive) {
            settings.shiftActive = false; settings.trackingStartedAt = 0L
            CallSessionStore(this).clear(); SyncScheduler.disableCapture(this); ActiveUserNotification.cancel(this)
        } else {
            settings.shiftActive = true; settings.shiftStartedAt = System.currentTimeMillis(); settings.trackingStartedAt = settings.shiftStartedAt
            SyncScheduler.scheduleRecovery(this); SyncScheduler.enqueueRecoveryNow(this); ActiveUserNotification.show(this)
        }
        render()
    }

    private fun render() {
        val db = CallDb(this); val latest = db.latest(3)
        findViewById<TextView>(R.id.employeeHeader).text = settings.activeEmployee.ifBlank { "Unassigned employee" }
        findViewById<TextView>(R.id.captureStatus).apply {
            text = if (settings.shiftActive) "CAPTURE ON\nCalls sync automatically after they end." else "CAPTURE OFF\nNo calls will be captured or uploaded."
            backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, if (settings.shiftActive) R.color.capture_on_surface else R.color.capture_off_surface))
        }
        findViewById<Button>(R.id.captureToggleButton).apply {
            text = if (settings.shiftActive) "Turn capture off" else "Turn capture on"
            backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, if (settings.shiftActive) R.color.capture_off else R.color.capture_on))
        }
        findViewById<TextView>(R.id.totalCalls).text = db.count().toString()
        findViewById<TextView>(R.id.pendingCalls).text = db.pendingCount().toString()
        val df = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())
        findViewById<TextView>(R.id.recentCalls).text = if (latest.isEmpty()) {
            "No calls captured yet. Turn capture on and make a normal SIM call."
        } else latest.joinToString("\n\n") { call ->
            val sync = when {
                call.syncStatus == "SYNCED" && call.analysisStatus == "COMPLETE" -> "CRM + analysis ready"
                call.syncStatus == "SYNCED" -> "In CRM · processing"
                call.syncStatus == "FAILED" -> "Will retry automatically"
                else -> "Queued automatically"
            }
            "${call.direction.lowercase().replaceFirstChar { it.uppercaseChar() }} · ${call.rawNumber.ifBlank { "Private number" }}\n${df.format(Date(call.startedAt))} · $sync"
        }
    }
}

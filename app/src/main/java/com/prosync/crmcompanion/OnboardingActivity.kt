package com.prosync.crmcompanion

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.ViewFlipper
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OnboardingActivity : AppCompatActivity() {
    private lateinit var settings: SettingsStore
    private lateinit var pages: ViewFlipper

    private val phonePermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        findViewById<TextView>(R.id.phonePermissionStatus).text =
            if (hasPermission(Manifest.permission.READ_CALL_LOG) && hasPermission(Manifest.permission.READ_PHONE_STATE)) "Phone access granted ✓" else "Phone access is required to identify completed calls."
    }
    private val audioPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scanPhone() else findViewById<TextView>(R.id.scanStatus).text = "Audio access is required to discover recordings."
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        updateNotificationStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyProSyncSystemBars()
        setContentView(R.layout.activity_onboarding)
        settings = SettingsStore(this).also {
            it.apiBaseUrl = BuildConfig.CRM_BASE_URL
            it.apiKey = BuildConfig.MOBILE_SYNC_API_KEY
        }
        pages = findViewById(R.id.onboardingPages)
        findViewById<Button>(R.id.welcomeContinue).setOnClickListener { showPage(1) }
        findViewById<Button>(R.id.phonePermissionButton).setOnClickListener {
            phonePermissions.launch(arrayOf(Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_PHONE_STATE))
        }
        findViewById<Button>(R.id.phoneContinue).setOnClickListener {
            if (hasPermission(Manifest.permission.READ_CALL_LOG) && hasPermission(Manifest.permission.READ_PHONE_STATE)) showPage(2)
            else findViewById<TextView>(R.id.phonePermissionStatus).text = "Grant both permissions before continuing."
        }
        findViewById<Button>(R.id.scanPermissionButton).setOnClickListener {
            val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
            if (hasPermission(permission)) scanPhone() else audioPermission.launch(permission)
        }
        findViewById<Button>(R.id.scanContinue).setOnClickListener {
            if (settings.recordingScanCompletedAt > 0L) showPage(3)
            else findViewById<TextView>(R.id.scanStatus).text = "Scan the phone before continuing."
        }
        findViewById<Button>(R.id.notificationPermissionButton).setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            else updateNotificationStatus()
        }
        // Phone setup is one-time; the employee signs in on the next screen.
        findViewById<Button>(R.id.notificationContinue).setOnClickListener {
            settings.onboardingComplete = true
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
        showPage(0)
    }

    private fun showPage(index: Int) {
        pages.displayedChild = index
        findViewById<TextView>(R.id.setupProgress).text = "PHONE SETUP ${index + 1} OF 4"
        if (index == 3) updateNotificationStatus()
    }

    private fun hasPermission(permission: String) = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun updateNotificationStatus() {
        val granted = Build.VERSION.SDK_INT < 33 || hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        findViewById<TextView>(R.id.notificationStatus).text = if (granted) "Notifications enabled ✓" else "Enable notifications so Android can keep capture active."
    }

    private fun scanPhone() {
        val status = findViewById<TextView>(R.id.scanStatus)
        val button = findViewById<Button>(R.id.scanPermissionButton)
        button.isEnabled = false
        button.text = "Scanning phone…"
        status.text = "Checking Android media and common call-recording folders…"
        lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) { RecordingLocationDiscovery.scan(this@OnboardingActivity) } }
                .onSuccess {
                    settings.recordingScanCompletedAt = System.currentTimeMillis()
                    settings.recordingScanSummary = it.summary()
                    status.text = it.summary()
                }
                .onFailure { status.text = "Scan failed: ${it.message ?: "Android storage error"}" }
            button.isEnabled = true
            button.text = "Scan phone again"
        }
    }
}

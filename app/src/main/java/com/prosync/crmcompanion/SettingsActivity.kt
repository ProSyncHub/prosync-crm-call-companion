package com.prosync.crmcompanion

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {
    private lateinit var settings: SettingsStore
    private val folderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            settings.recordingFolderUri = uri.toString()
            render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyProSyncSystemBars()
        setContentView(R.layout.activity_settings)
        settings = SettingsStore(this)
        findViewById<Button>(R.id.settingsBackButton).setOnClickListener { finish() }
        findViewById<Button>(R.id.saveDeviceButton).setOnClickListener {
            settings.deviceLabel = findViewById<EditText>(R.id.settingsDeviceLabel).text.toString()
            render("Device name saved ✓")
        }
        findViewById<Button>(R.id.testCrmButton).setOnClickListener { testCrm() }
        findViewById<Button>(R.id.rescanButton).setOnClickListener { scanPhone() }
        findViewById<Button>(R.id.chooseFolderButton).setOnClickListener { folderLauncher.launch(null) }
        findViewById<Button>(R.id.changeEmployeeButton).setOnClickListener {
            settings.onboardingComplete = false
            startActivity(Intent(this, OnboardingActivity::class.java))
            finishAffinity()
        }
        render()
    }

    private fun render(message: String? = null) {
        findViewById<EditText>(R.id.settingsDeviceLabel).setText(settings.deviceLabel)
        findViewById<TextView>(R.id.settingsEmployee).text = "Employee\n${settings.activeEmployee.ifBlank { "Not selected" }}\n${settings.activeEmployeeEmail}"
        findViewById<TextView>(R.id.settingsConnection).text = message ?: if (BuildConfig.MOBILE_SYNC_API_KEY.isBlank()) {
            "CRM connection missing from this APK"
        } else "CRM connection built in ✓\n${BuildConfig.CRM_BASE_URL}"
        val audio = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        fun granted(permission: String) = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        findViewById<TextView>(R.id.settingsPermissions).text =
            "Call log ${if (granted(Manifest.permission.READ_CALL_LOG)) "✓" else "✗"}  ·  Phone ${if (granted(Manifest.permission.READ_PHONE_STATE)) "✓" else "✗"}  ·  Audio ${if (granted(audio)) "✓" else "✗"}"
        findViewById<TextView>(R.id.settingsScan).text = settings.recordingScanSummary.ifBlank { "Phone has not been scanned yet." }
        findViewById<TextView>(R.id.settingsFolder).text = if (settings.recordingFolderUri.isBlank()) "No manual folder selected (normally not needed)." else "Manual recording folder connected ✓"
        findViewById<TextView>(R.id.settingsVersion).text = "App ${BuildConfig.VERSION_NAME}\nDevice ID ${settings.deviceId.take(12)}…"
    }

    private fun testCrm() {
        val status = findViewById<TextView>(R.id.settingsConnection)
        status.text = "Testing CRM connection…"
        lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) { CrmMobileApi.fetchEmployees(settings).size } }
                .onSuccess { render("CRM connected ✓  ·  $it employees available") }
                .onFailure { render("CRM connection failed\n${it.message}") }
        }
    }

    private fun scanPhone() {
        val status = findViewById<TextView>(R.id.settingsScan)
        status.text = "Scanning phone…"
        lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) { RecordingLocationDiscovery.scan(this@SettingsActivity) } }
                .onSuccess {
                    settings.recordingScanCompletedAt = System.currentTimeMillis()
                    settings.recordingScanSummary = it.summary()
                    render()
                    if (settings.shiftActive) SyncScheduler.enqueueRecordingAndSync(this@SettingsActivity, 0)
                }
                .onFailure { status.text = "Scan failed: ${it.message}" }
        }
    }
}

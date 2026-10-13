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

    private val numbersPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) detectSimNumbers() else render("Number permission was not allowed. Type the numbers instead.")
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
        findViewById<Button>(R.id.saveSimButton).setOnClickListener {
            settings.setSimNumber(1, findViewById<EditText>(R.id.settingsSim1).text.toString())
            settings.setSimNumber(2, findViewById<EditText>(R.id.settingsSim2).text.toString())
            render("Phone numbers saved ✓")
        }
        findViewById<Button>(R.id.detectSimButton).setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_NUMBERS) == PackageManager.PERMISSION_GRANTED) detectSimNumbers()
            else numbersPermission.launch(Manifest.permission.READ_PHONE_NUMBERS)
        }
        findViewById<Button>(R.id.testCrmButton).setOnClickListener { testCrm() }
        findViewById<Button>(R.id.rescanButton).setOnClickListener { scanPhone() }
        findViewById<Button>(R.id.chooseFolderButton).setOnClickListener { folderLauncher.launch(null) }
        findViewById<Button>(R.id.changeEmployeeButton).setOnClickListener { confirmSignOut() }
        findViewById<Button>(R.id.rerunSetupButton).setOnClickListener {
            // Re-checks permissions and recording folders; the employee stays signed in.
            settings.onboardingComplete = false
            startActivity(Intent(this, OnboardingActivity::class.java))
            finishAffinity()
        }
        render()
    }

    private fun confirmSignOut() {
        if (!EmployeeSession.isSignedIn(this)) {
            startActivity(Intent(this, LoginActivity::class.java)); finishAffinity(); return
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Sign out ${settings.activeEmployee}?")
            .setMessage("Calls on this phone keep syncing to the CRM as \"Not signed in\" until someone signs in.")
            .setPositiveButton("Sign out") { _, _ ->
                EmployeeSession.signOut(this)
                startActivity(Intent(this, LoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK))
                finishAffinity()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Fills empty number boxes with what Android knows (often blank on Indian SIMs). */
    private fun detectSimNumbers() {
        val lines = SimNumbers.lines(this)
        var found = 0
        lines.forEach { line ->
            if (line.detectedNumber.isNotBlank() && settings.simNumber(line.slot).isBlank() && line.slot in 1..2) {
                settings.setSimNumber(line.slot, line.detectedNumber); found += 1
            }
        }
        render(if (found > 0) "Found $found number(s) ✓" else "This phone does not share its SIM numbers. Type them in and save.")
    }

    private fun render(message: String? = null) {
        findViewById<EditText>(R.id.settingsDeviceLabel).setText(settings.deviceLabel)
        val lines = SimNumbers.lines(this)
        findViewById<TextView>(R.id.settingsSims).text = if (lines.isEmpty()) {
            "No SIM details available. Type this phone's number below."
        } else lines.joinToString("\n") { line ->
            val number = SimNumbers.numberForSlot(this, line.slot, line).ifBlank { "number not set" }
            "SIM ${line.slot}${if (line.carrier.isNotBlank()) " (${line.carrier})" else ""}: $number"
        } + "\nSent with every call so the CRM shows which office line was used."
        findViewById<EditText>(R.id.settingsSim1).setText(settings.simNumber(1))
        findViewById<EditText>(R.id.settingsSim2).setText(settings.simNumber(2))
        findViewById<TextView>(R.id.settingsEmployee).text = if (EmployeeSession.isSignedIn(this)) {
            "Signed in as\n${settings.activeEmployee}\n${settings.activeEmployeeEmail}"
        } else "Nobody is signed in"
        findViewById<Button>(R.id.changeEmployeeButton).text = if (EmployeeSession.isSignedIn(this)) "Sign out" else "Sign in"
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
                    SyncScheduler.enqueueRecordingAndSync(this@SettingsActivity, 0)
                    SyncScheduler.enqueueMissingRescan(this@SettingsActivity)
                }
                .onFailure { status.text = "Scan failed: ${it.message}" }
        }
    }
}

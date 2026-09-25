package com.prosync.crmcompanion

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
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
    private var employees = emptyList<EmployeeOption>()
    private var selectedEmployee: EmployeeOption? = null

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
        findViewById<Button>(R.id.notificationContinue).setOnClickListener { showPage(4); loadEmployees() }
        findViewById<AutoCompleteTextView>(R.id.onboardingEmployee).setOnItemClickListener { parent, _, position, _ ->
            selectedEmployee = parent.getItemAtPosition(position) as EmployeeOption
            findViewById<TextView>(R.id.loginStatus).text = "Selected ${selectedEmployee!!.name}. Enter the CRM / Workforce password."
        }
        findViewById<Button>(R.id.reloadEmployeesButton).setOnClickListener { loadEmployees() }
        findViewById<Button>(R.id.verifyOnboardingEmployee).setOnClickListener { verifyEmployee() }
        findViewById<Button>(R.id.finishOnboardingButton).setOnClickListener {
            if (settings.activeEmployee.isBlank()) {
                findViewById<TextView>(R.id.loginStatus).text = "Verify an employee before finishing setup."
            } else {
                settings.onboardingComplete = true
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }
        }
        showPage(0)
    }

    private fun showPage(index: Int) {
        pages.displayedChild = index
        findViewById<TextView>(R.id.setupProgress).text = "SETUP ${index + 1} OF 5"
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

    private fun loadEmployees() {
        val status = findViewById<TextView>(R.id.loginStatus)
        val button = findViewById<Button>(R.id.reloadEmployeesButton)
        button.isEnabled = false
        status.text = "Connecting securely to CRM…"
        lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) { CrmMobileApi.fetchEmployees(settings) } }
                .onSuccess {
                    employees = it
                    findViewById<AutoCompleteTextView>(R.id.onboardingEmployee).apply {
                        setAdapter(ArrayAdapter(this@OnboardingActivity, android.R.layout.simple_dropdown_item_1line, employees))
                        requestFocus(); showDropDown()
                    }
                    status.text = "${employees.size} employees loaded. Select yourself."
                }
                .onFailure { status.text = "CRM connection failed: ${it.message}" }
            button.isEnabled = true
        }
    }

    private fun verifyEmployee() {
        val selected = selectedEmployee
        val password = findViewById<EditText>(R.id.onboardingPassword).text.toString()
        val status = findViewById<TextView>(R.id.loginStatus)
        if (selected == null) { status.text = "Select an employee first."; return }
        if (password.isBlank()) { status.text = "Enter your CRM / Workforce password."; return }
        lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) { CrmMobileApi.verifyEmployee(settings, selected, password) } }
                .onSuccess {
                    settings.activeEmployee = it.name
                    settings.activeEmployeeId = it.id
                    settings.activeEmployeeEmail = it.email
                    findViewById<EditText>(R.id.onboardingPassword).setText("")
                    status.text = "Verified as ${it.name} ✓"
                }
                .onFailure { status.text = it.message ?: "Verification failed" }
        }
    }
}

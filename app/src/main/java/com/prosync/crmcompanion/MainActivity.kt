package com.prosync.crmcompanion

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.content.res.ColorStateList
import android.widget.Button
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var settings: SettingsStore
    private var employees: List<EmployeeOption> = emptyList()

    data class EmployeeOption(val id: String, val name: String, val email: String, val department: String) {
        override fun toString() = "$name • $department"
    }

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshUi() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        settings = SettingsStore(this)

        findViewById<Button>(R.id.syncEmployeesButton).setOnClickListener { syncEmployees() }
        findViewById<AutoCompleteTextView>(R.id.employeeName).setOnItemClickListener { parent, _, position, _ ->
            val selected = parent.getItemAtPosition(position) as EmployeeOption
            settings.activeEmployee = selected.name
            settings.activeEmployeeId = selected.id
            settings.activeEmployeeEmail = selected.email
            if (settings.shiftActive) ActiveUserNotification.show(this)
        }

        findViewById<Button>(R.id.permissionsButton).setOnClickListener {
            val requested = mutableListOf(
                    Manifest.permission.READ_CALL_LOG,
                    Manifest.permission.READ_PHONE_STATE
                )
            if (Build.VERSION.SDK_INT >= 33) requested += Manifest.permission.READ_MEDIA_AUDIO
            else requested += Manifest.permission.READ_EXTERNAL_STORAGE
            if (Build.VERSION.SDK_INT >= 33) requested += Manifest.permission.POST_NOTIFICATIONS
            permissionsLauncher.launch(requested.toTypedArray())
        }

        findViewById<Button>(R.id.captureToggleButton).setOnClickListener {
            if (settings.shiftActive) {
                settings.shiftActive = false
                settings.trackingStartedAt = 0L
                CallSessionStore(this).clear()
                SyncScheduler.disableCapture(this)
                ActiveUserNotification.cancel(this)
                refreshUi()
                return@setOnClickListener
            }

            val employeeField = findViewById<AutoCompleteTextView>(R.id.employeeName)
            val selected = employees.firstOrNull { it.toString() == employeeField.text.toString() || it.name.equals(employeeField.text.toString(), true) }
                ?: if (settings.activeEmployee.isNotBlank() && settings.activeEmployee.equals(employeeField.text.toString(), true)) {
                    EmployeeOption(settings.activeEmployeeId, settings.activeEmployee, settings.activeEmployeeEmail, "saved")
                } else null
            if (selected == null || selected.email.isBlank()) {
                employeeField.error = "Select an employee synced from CRM"
                return@setOnClickListener
            }
            val employee = selected.name
            val deviceLabel = findViewById<EditText>(R.id.deviceLabel).text.toString().trim()

            settings.activeEmployee = employee
            settings.activeEmployeeId = selected.id
            settings.activeEmployeeEmail = selected.email
            settings.deviceLabel = deviceLabel
            settings.apiBaseUrl = findViewById<EditText>(R.id.apiBaseUrl).text.toString()
            settings.apiKey = findViewById<EditText>(R.id.apiKey).text.toString()
            settings.shiftActive = true
            settings.shiftStartedAt = System.currentTimeMillis()
            settings.trackingStartedAt = settings.shiftStartedAt
            SyncScheduler.scheduleRecovery(this)
            ActiveUserNotification.show(this)
            refreshUi()
        }

        findViewById<Button>(R.id.refreshButton).setOnClickListener { refreshUi() }
        findViewById<Button>(R.id.recoveryScanButton).setOnClickListener {
            if (!settings.shiftActive) return@setOnClickListener
            if (settings.trackingStartedAt == 0L) settings.trackingStartedAt = System.currentTimeMillis()
            SyncScheduler.enqueueRecoveryNow(this)
            window.decorView.postDelayed({ refreshUi() }, 1500)
        }
        findViewById<Button>(R.id.syncButton).setOnClickListener {
            if (!settings.shiftActive) return@setOnClickListener
            settings.apiBaseUrl = findViewById<EditText>(R.id.apiBaseUrl).text.toString()
            settings.apiKey = findViewById<EditText>(R.id.apiKey).text.toString()
            SyncScheduler.enqueueRecordingAndSync(this, 0)
            window.decorView.postDelayed({ refreshUi() }, 1800)
        }

        refreshUi()
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    private fun refreshUi() {
        findViewById<TextView>(R.id.deviceInfo).text = buildString {
            append("Device: ${Build.MANUFACTURER} ${Build.MODEL}\n")
            append("Android API: ${Build.VERSION.SDK_INT}\n")
            append("Device ID: ${settings.deviceId.take(8)}…")
        }

        findViewById<AutoCompleteTextView>(R.id.employeeName).setText(settings.activeEmployee, false)
        findViewById<EditText>(R.id.deviceLabel).setText(settings.deviceLabel)
        findViewById<EditText>(R.id.apiBaseUrl).setText(settings.apiBaseUrl)
        findViewById<EditText>(R.id.apiKey).setText(settings.apiKey)

        val callLogOk = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED
        val phoneStateOk = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        val audioPermission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        val audioOk = ContextCompat.checkSelfPermission(this, audioPermission) == PackageManager.PERMISSION_GRANTED
        val notificationsOk = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        findViewById<TextView>(R.id.permissionStatus).text =
            "Call log: ${if (callLogOk) "✓" else "✗"}    Phone state: ${if (phoneStateOk) "✓" else "✗"}    Audio: ${if (audioOk) "✓" else "✗"}    Notification: ${if (notificationsOk) "✓" else "✗"}"

        val df = SimpleDateFormat("dd MMM yyyy, HH:mm:ss", Locale.getDefault())
        val captureStatus = findViewById<TextView>(R.id.captureStatus)
        val captureButton = findViewById<Button>(R.id.captureToggleButton)
        captureStatus.text = if (settings.shiftActive) {
            ActiveUserNotification.show(this)
            "CAPTURE ON\n${settings.activeEmployee}\nSince ${df.format(Date(settings.shiftStartedAt))}"
        } else {
            "CAPTURE OFF\nNo call history, recording, or upload will be created."
        }
        captureStatus.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, if (settings.shiftActive) R.color.capture_on_surface else R.color.capture_off_surface)
        )
        captureButton.text = if (settings.shiftActive) "TURN CAPTURE OFF" else "TURN CAPTURE ON"
        captureButton.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, if (settings.shiftActive) R.color.capture_off else R.color.capture_on)
        )
        findViewById<Button>(R.id.syncButton).isEnabled = settings.shiftActive
        findViewById<Button>(R.id.recoveryScanButton).isEnabled = settings.shiftActive

        val db = CallDb(this)
        val latest = db.latest(30)
        findViewById<TextView>(R.id.callCount).text = "Captured calls: ${db.count()}"
        findViewById<TextView>(R.id.localCalls).text = if (latest.isEmpty()) {
            "No calls captured yet. Finish setup, turn capture ON, and make one normal SIM call."
        } else {
            buildString {
                latest.forEach { call ->
                    append("${call.direction}  ${call.rawNumber.ifBlank { "PRIVATE/UNKNOWN" }}\n")
                    append("${df.format(Date(call.startedAt))}  •  ${call.durationSeconds}s\n")
                    append("User: ${call.employeeName}  •  ${call.captureMode}")
                    call.captureScore?.let { append("  •  score $it") }
                    append("\nSIM account: ${call.phoneAccountId ?: "unknown"}")
                    append("\nRecording: ${call.recordingStatus}  •  CRM: ${call.syncStatus}")
                    call.syncError?.let { append("\nSync error: $it") }
                    append("\n\n")
                }
            }
        }
    }

    private fun syncEmployees() {
        settings.apiBaseUrl = findViewById<EditText>(R.id.apiBaseUrl).text.toString()
        settings.apiKey = findViewById<EditText>(R.id.apiKey).text.toString()
        val button = findViewById<Button>(R.id.syncEmployeesButton)
        button.isEnabled = false; button.text = "Syncing employees…"
        lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) { fetchEmployees() } }
                .onSuccess { options ->
                    employees = options
                    val field = findViewById<AutoCompleteTextView>(R.id.employeeName)
                    field.setAdapter(ArrayAdapter(this@MainActivity, android.R.layout.simple_dropdown_item_1line, options))
                    field.requestFocus(); field.showDropDown()
                    button.text = "${options.size} Employees Synced"
                }
                .onFailure { button.text = "Sync Failed: ${it.message}" }
            button.isEnabled = true
        }
    }

    private fun fetchEmployees(): List<EmployeeOption> {
        require(settings.apiBaseUrl.isNotBlank() && settings.apiKey.isNotBlank()) { "Enter CRM URL and API key" }
        val connection = (URL("${settings.apiBaseUrl}/api/mobile/employees").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 20_000; readTimeout = 20_000
            setRequestProperty("Authorization", "Bearer ${settings.apiKey}")
        }
        if (connection.responseCode !in 200..299) throw IllegalStateException("CRM returned HTTP ${connection.responseCode}")
        val root = JSONObject(connection.inputStream.bufferedReader().readText())
        val array = root.getJSONArray("employees")
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            EmployeeOption(item.getString("id"), item.getString("name"), item.getString("email"), item.optString("department", "unassigned"))
        }
    }
}

package com.prosync.crmcompanion

import android.content.Context
import java.util.UUID

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("prosync_settings", Context.MODE_PRIVATE)

    var activeEmployee: String
        get() = prefs.getString("active_employee", "") ?: ""
        set(value) = prefs.edit().putString("active_employee", value.trim()).apply()

    var activeEmployeeId: String
        get() = prefs.getString("active_employee_id", "") ?: ""
        set(value) = prefs.edit().putString("active_employee_id", value.trim()).apply()

    var activeEmployeeEmail: String
        get() = prefs.getString("active_employee_email", "") ?: ""
        set(value) = prefs.edit().putString("active_employee_email", value.trim()).apply()

    var deviceLabel: String
        get() = prefs.getString("device_label", "") ?: ""
        set(value) = prefs.edit().putString("device_label", value.trim()).apply()

    var shiftActive: Boolean
        get() = prefs.getBoolean("shift_active", false)
        set(value) = prefs.edit().putBoolean("shift_active", value).apply()

    var shiftStartedAt: Long
        get() = prefs.getLong("shift_started_at", 0L)
        set(value) = prefs.edit().putLong("shift_started_at", value).apply()

    var trackingStartedAt: Long
        get() = prefs.getLong("tracking_started_at", 0L)
        set(value) = prefs.edit().putLong("tracking_started_at", value).apply()

    var apiBaseUrl: String
        get() = prefs.getString("api_base_url", "https://crm.prosyncedu.com") ?: "https://crm.prosyncedu.com"
        set(value) = prefs.edit().putString("api_base_url", value.trim().trimEnd('/')).apply()

    var apiKey: String
        get() = prefs.getString("api_key", "") ?: ""
        set(value) = prefs.edit().putString("api_key", value.trim()).apply()

    var recordingFolderUri: String
        get() = prefs.getString("recording_folder_uri", "") ?: ""
        set(value) = prefs.edit().putString("recording_folder_uri", value.trim()).apply()

    var recordingScanCompletedAt: Long
        get() = prefs.getLong("recording_scan_completed_at", 0L)
        set(value) = prefs.edit().putLong("recording_scan_completed_at", value).apply()

    var recordingScanSummary: String
        get() = prefs.getString("recording_scan_summary", "") ?: ""
        set(value) = prefs.edit().putString("recording_scan_summary", value).apply()

    val deviceId: String
        get() {
            val existing = prefs.getString("device_id", null)
            if (existing != null) return existing
            val created = UUID.randomUUID().toString()
            prefs.edit().putString("device_id", created).apply()
            return created
        }
}

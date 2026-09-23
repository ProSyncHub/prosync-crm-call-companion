package com.prosync.crmcompanion

import android.content.Context

class CallSessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("prosync_call_session", Context.MODE_PRIVATE)

    data class Snapshot(
        val startedAt: Long,
        val endedAt: Long,
        val employeeName: String,
        val employeeEmail: String,
        val numberHint: String?
    )

    fun beginIfNeeded(employeeName: String, employeeEmail: String, numberHint: String?, now: Long = System.currentTimeMillis()) {
        if (prefs.getBoolean("active", false)) {
            if (!numberHint.isNullOrBlank() && prefs.getString("number_hint", "").isNullOrBlank()) {
                prefs.edit().putString("number_hint", numberHint).apply()
            }
            return
        }

        prefs.edit()
            .putBoolean("active", true)
            .putLong("started_at", now)
            .putString("employee", employeeName)
            .putString("employee_email", employeeEmail)
            .putString("number_hint", numberHint.orEmpty())
            .apply()
    }

    fun finish(now: Long = System.currentTimeMillis()): Snapshot? {
        if (!prefs.getBoolean("active", false)) return null

        val snapshot = Snapshot(
            startedAt = prefs.getLong("started_at", now),
            endedAt = now,
            employeeName = prefs.getString("employee", "UNASSIGNED") ?: "UNASSIGNED",
            employeeEmail = prefs.getString("employee_email", "") ?: "",
            numberHint = prefs.getString("number_hint", "")?.takeIf { it.isNotBlank() }
        )

        prefs.edit().clear().apply()
        return snapshot
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}

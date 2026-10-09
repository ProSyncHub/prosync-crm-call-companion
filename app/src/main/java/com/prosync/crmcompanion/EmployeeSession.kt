package com.prosync.crmcompanion

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Sign-in / sign-out for the employee using this phone. Device setup is separate and one-time. */
object EmployeeSession {
    private const val MAX_RECENT = 4
    /** Shown in the CRM for calls made while nobody was signed in on the phone. */
    const val NOT_SIGNED_IN = "Not signed in"

    fun isSignedIn(context: Context) = SettingsStore(context).activeEmployeeId.isNotBlank() ||
        SettingsStore(context).activeEmployee.isNotBlank()

    /** Signing in starts the shift: capture turns on automatically. */
    fun signIn(context: Context, employee: EmployeeOption) {
        val settings = SettingsStore(context)
        settings.activeEmployee = employee.name
        settings.activeEmployeeId = employee.id
        settings.activeEmployeeEmail = employee.email
        rememberRecent(settings, employee)

        settings.shiftActive = true
        settings.shiftStartedAt = System.currentTimeMillis()
        settings.trackingStartedAt = settings.shiftStartedAt
        SyncScheduler.scheduleRecovery(context)
        SyncScheduler.enqueueRecoveryNow(context)
        SyncScheduler.schedulePeriodicSync(context)
        ActiveUserNotification.show(context)
    }

    /**
     * Clears the employee. Capture keeps running: an office phone must never miss a
     * call, so calls made until the next sign-in reach the CRM as "Not signed in".
     */
    fun signOut(context: Context) {
        val settings = SettingsStore(context)
        CallSessionStore(context).clear()
        settings.activeEmployee = ""
        settings.activeEmployeeId = ""
        settings.activeEmployeeEmail = ""
        settings.shiftActive = false
        ensureCaptureWhileSignedOut(context)
        SyncScheduler.enqueueRecordingAndSync(context, 0)
    }

    /** With nobody signed in, capture is always on (pausing is only for a signed-in employee). */
    fun ensureCaptureWhileSignedOut(context: Context) {
        val settings = SettingsStore(context)
        if (!settings.onboardingComplete || isSignedIn(context)) return
        if (!settings.shiftActive) {
            settings.shiftActive = true
            settings.shiftStartedAt = System.currentTimeMillis()
            settings.trackingStartedAt = settings.shiftStartedAt
        }
        SyncScheduler.scheduleRecovery(context)
        SyncScheduler.schedulePeriodicSync(context)
        ActiveUserNotification.show(context)
    }

    fun recentEmployees(context: Context): List<EmployeeOption> = parse(SettingsStore(context).recentEmployeesJson)

    fun cachedEmployees(context: Context): List<EmployeeOption> = parse(SettingsStore(context).cachedEmployeesJson)

    fun cacheEmployees(context: Context, employees: List<EmployeeOption>) {
        SettingsStore(context).cachedEmployeesJson = serialize(employees)
    }

    private fun rememberRecent(settings: SettingsStore, employee: EmployeeOption) {
        val recent = listOf(employee) + parse(settings.recentEmployeesJson).filter { it.id != employee.id }
        settings.recentEmployeesJson = serialize(recent.take(MAX_RECENT))
    }

    private fun serialize(employees: List<EmployeeOption>) = JSONArray().apply {
        employees.forEach {
            put(JSONObject().put("id", it.id).put("name", it.name).put("email", it.email).put("department", it.department))
        }
    }.toString()

    private fun parse(json: String): List<EmployeeOption> = runCatching {
        val array = JSONArray(json)
        (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            EmployeeOption(item.getString("id"), item.getString("name"), item.getString("email"), item.optString("department", ""))
        }
    }.getOrDefault(emptyList())
}

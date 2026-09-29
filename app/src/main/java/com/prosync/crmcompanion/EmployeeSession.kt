package com.prosync.crmcompanion

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Sign-in / sign-out for the employee using this phone. Device setup is separate and one-time. */
object EmployeeSession {
    private const val MAX_RECENT = 4

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

    /** Stops capture and clears the employee. Calls already captured keep uploading. */
    fun signOut(context: Context) {
        val settings = SettingsStore(context)
        settings.shiftActive = false
        settings.trackingStartedAt = 0L
        CallSessionStore(context).clear()
        SyncScheduler.disableCapture(context)
        ActiveUserNotification.cancel(context)
        settings.activeEmployee = ""
        settings.activeEmployeeId = ""
        settings.activeEmployeeEmail = ""
        SyncScheduler.enqueueRecordingAndSync(context, 0)
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

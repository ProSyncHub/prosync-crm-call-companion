package com.prosync.crmcompanion

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CallCaptureWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!SettingsStore(applicationContext).shiftActive) return@withContext Result.success()
        if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            return@withContext Result.failure()
        }

        val sessionStart = inputData.getLong(KEY_SESSION_START, 0L)
        val sessionEnd = inputData.getLong(KEY_SESSION_END, 0L)
        val employee = inputData.getString(KEY_EMPLOYEE).orEmpty().ifBlank { "UNASSIGNED" }
        val employeeEmail = inputData.getString(KEY_EMPLOYEE_EMAIL).orEmpty()
        val numberHint = inputData.getString(KEY_NUMBER_HINT)

        if (sessionStart <= 0L || sessionEnd <= 0L) return@withContext Result.failure()

        val reader = CallLogReader(applicationContext)
        val candidates = reader.readWindow(
            fromMillis = sessionStart - 60_000L,
            toMillis = sessionEnd + 60_000L,
            limit = 30
        )

        val match = CallMatcher.bestMatch(candidates, sessionStart, sessionEnd, numberHint)
            ?: return@withContext Result.retry()

        // A deliberately forgiving threshold: we care more about never losing a call.
        if (match.score < 35) return@withContext Result.retry()

        val settings = SettingsStore(applicationContext)
        if (!settings.shiftActive) return@withContext Result.success()
        val db = CallDb(applicationContext)
        val call = match.call

        db.insert(
            CallRecord(
                callLogId = call.id,
                deviceId = settings.deviceId,
                deviceLabel = settings.deviceLabel.ifBlank { android.os.Build.MODEL },
                employeeName = employee,
                employeeEmail = employeeEmail,
                rawNumber = call.number,
                normalizedNumber = PhoneUtil.normalizeIndia(call.number),
                direction = call.direction,
                startedAt = call.date,
                durationSeconds = call.durationSeconds,
                phoneAccountId = call.phoneAccountId,
                phoneAccountComponent = call.phoneAccountComponent,
                captureMode = "PHONE_STATE_EVENT",
                sessionStartedAt = sessionStart,
                sessionEndedAt = sessionEnd,
                captureScore = match.score
            )
        )

        SyncScheduler.enqueueRecordingAndSync(applicationContext)

        Result.success()
    }

    companion object {
        const val KEY_SESSION_START = "session_start"
        const val KEY_SESSION_END = "session_end"
        const val KEY_EMPLOYEE = "employee"
        const val KEY_EMPLOYEE_EMAIL = "employee_email"
        const val KEY_NUMBER_HINT = "number_hint"
    }
}

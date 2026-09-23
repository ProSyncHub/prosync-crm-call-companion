package com.prosync.crmcompanion

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RecoveryScanWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            return@withContext Result.success()
        }

        val settings = SettingsStore(applicationContext)
        if (!settings.shiftActive) return@withContext Result.success()
        val startedAt = settings.trackingStartedAt
        if (startedAt <= 0L) return@withContext Result.success()

        val db = CallDb(applicationContext)
        val employee = settings.activeEmployee.ifBlank { "UNASSIGNED" }
        val deviceLabel = settings.deviceLabel.ifBlank { android.os.Build.MODEL }

        CallLogReader(applicationContext)
            .readSince(startedAt.coerceAtLeast(System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000))
            .forEach { call ->
                if (!db.exists(call.id)) {
                    db.insert(
                        CallRecord(
                            callLogId = call.id,
                            deviceId = settings.deviceId,
                            deviceLabel = deviceLabel,
                            employeeName = employee,
                            employeeEmail = settings.activeEmployeeEmail,
                            rawNumber = call.number,
                            normalizedNumber = PhoneUtil.normalizeIndia(call.number),
                            direction = call.direction,
                            startedAt = call.date,
                            durationSeconds = call.durationSeconds,
                            phoneAccountId = call.phoneAccountId,
                            phoneAccountComponent = call.phoneAccountComponent,
                            captureMode = "RECOVERY_SCAN",
                            sessionStartedAt = null,
                            sessionEndedAt = null,
                            captureScore = null
                        )
                    )
                }
            }

        SyncScheduler.enqueueRecordingAndSync(applicationContext, 1)

        Result.success()
    }
}

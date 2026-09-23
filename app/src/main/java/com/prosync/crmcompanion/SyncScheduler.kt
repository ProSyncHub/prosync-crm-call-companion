package com.prosync.crmcompanion

import android.content.Context
import androidx.work.Data
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object SyncScheduler {
    private const val CAPTURE_WORK_TAG = "prosync_capture_work"

    fun scheduleRecovery(context: Context) {
        val request = PeriodicWorkRequestBuilder<RecoveryScanWorker>(15, TimeUnit.MINUTES)
            .addTag(CAPTURE_WORK_TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "prosync_call_recovery",
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun enqueueSessionCapture(context: Context, snapshot: CallSessionStore.Snapshot) {
        val input = Data.Builder()
            .putLong(CallCaptureWorker.KEY_SESSION_START, snapshot.startedAt)
            .putLong(CallCaptureWorker.KEY_SESSION_END, snapshot.endedAt)
            .putString(CallCaptureWorker.KEY_EMPLOYEE, snapshot.employeeName)
            .putString(CallCaptureWorker.KEY_EMPLOYEE_EMAIL, snapshot.employeeEmail)
            .putString(CallCaptureWorker.KEY_NUMBER_HINT, snapshot.numberHint)
            .build()

        val request = OneTimeWorkRequestBuilder<CallCaptureWorker>()
            .setInputData(input)
            .setInitialDelay(3, TimeUnit.SECONDS)
            .setBackoffCriteria(
                androidx.work.BackoffPolicy.LINEAR,
                10,
                TimeUnit.SECONDS
            )
            .addTag(CAPTURE_WORK_TAG)
            .build()

        // Session start makes the work unique enough while still deduplicating duplicate IDLE broadcasts.
        WorkManager.getInstance(context).enqueueUniqueWork(
            "prosync_call_${snapshot.startedAt}",
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    fun enqueueRecoveryNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<RecoveryScanWorker>()
            .addTag(CAPTURE_WORK_TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "prosync_recovery_now",
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    fun enqueueRecordingAndSync(context: Context, delaySeconds: Long = 8) {
        val request = OneTimeWorkRequestBuilder<RecordingAndSyncWorker>()
            .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .addTag(CAPTURE_WORK_TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("prosync_recording_sync", ExistingWorkPolicy.REPLACE, request)
    }

    fun disableCapture(context: Context) {
        WorkManager.getInstance(context).cancelAllWorkByTag(CAPTURE_WORK_TAG)
        WorkManager.getInstance(context).cancelUniqueWork("prosync_call_recovery")
    }
}

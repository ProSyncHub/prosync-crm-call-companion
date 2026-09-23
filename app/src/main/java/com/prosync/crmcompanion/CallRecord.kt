package com.prosync.crmcompanion

data class CallRecord(
    val callLogId: Long,
    val deviceId: String,
    val deviceLabel: String,
    val employeeName: String,
    val employeeEmail: String = "",
    val rawNumber: String,
    val normalizedNumber: String,
    val direction: String,
    val startedAt: Long,
    val durationSeconds: Long,
    val phoneAccountId: String?,
    val phoneAccountComponent: String?,
    val captureMode: String,
    val sessionStartedAt: Long?,
    val sessionEndedAt: Long?,
    val captureScore: Int?,
    val recordingUri: String? = null,
    val recordingName: String? = null,
    val recordingStatus: String = "PENDING",
    val syncStatus: String = "PENDING",
    val syncError: String? = null,
    val analysisStatus: String = "PENDING"
)

package com.prosync.crmcompanion

data class SystemCall(
    val id: Long,
    val number: String,
    val date: Long,
    val durationSeconds: Long,
    val direction: String,
    val phoneAccountId: String?,
    val phoneAccountComponent: String?
) {
    val endedAt: Long get() = date + (durationSeconds * 1000L)
}

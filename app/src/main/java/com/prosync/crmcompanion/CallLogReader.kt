package com.prosync.crmcompanion

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import androidx.core.content.ContextCompat

class CallLogReader(private val context: Context) {

    fun readWindow(fromMillis: Long, toMillis: Long, limit: Int = 100): List<SystemCall> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }

        val uri = CallLog.Calls.CONTENT_URI.buildUpon()
            .appendQueryParameter(CallLog.Calls.LIMIT_PARAM_KEY, limit.toString())
            .build()

        val projection = arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.NUMBER,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
            CallLog.Calls.TYPE,
            CallLog.Calls.PHONE_ACCOUNT_ID,
            CallLog.Calls.PHONE_ACCOUNT_COMPONENT_NAME
        )

        val result = mutableListOf<SystemCall>()
        context.contentResolver.query(
            uri,
            projection,
            "${CallLog.Calls.DATE} >= ? AND ${CallLog.Calls.DATE} <= ?",
            arrayOf(fromMillis.toString(), toMillis.toString()),
            "${CallLog.Calls.DATE} ASC"
        )?.use { c ->
            val idI = c.getColumnIndexOrThrow(CallLog.Calls._ID)
            val numberI = c.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
            val dateI = c.getColumnIndexOrThrow(CallLog.Calls.DATE)
            val durationI = c.getColumnIndexOrThrow(CallLog.Calls.DURATION)
            val typeI = c.getColumnIndexOrThrow(CallLog.Calls.TYPE)
            val accountI = c.getColumnIndex(CallLog.Calls.PHONE_ACCOUNT_ID)
            val componentI = c.getColumnIndex(CallLog.Calls.PHONE_ACCOUNT_COMPONENT_NAME)

            while (c.moveToNext()) {
                result += SystemCall(
                    id = c.getLong(idI),
                    number = c.getString(numberI).orEmpty(),
                    date = c.getLong(dateI),
                    durationSeconds = c.getLong(durationI),
                    direction = mapDirection(c.getInt(typeI)),
                    phoneAccountId = if (accountI >= 0 && !c.isNull(accountI)) c.getString(accountI) else null,
                    phoneAccountComponent = if (componentI >= 0 && !c.isNull(componentI)) c.getString(componentI) else null
                )
            }
        }
        return result
    }

    fun readSince(sinceMillis: Long, limit: Int = 250): List<SystemCall> =
        readWindow(sinceMillis, System.currentTimeMillis() + 60_000L, limit)

    private fun mapDirection(type: Int): String = when (type) {
        CallLog.Calls.INCOMING_TYPE -> "INCOMING"
        CallLog.Calls.OUTGOING_TYPE -> "OUTGOING"
        CallLog.Calls.MISSED_TYPE -> "MISSED"
        CallLog.Calls.REJECTED_TYPE -> "REJECTED"
        CallLog.Calls.BLOCKED_TYPE -> "BLOCKED"
        CallLog.Calls.ANSWERED_EXTERNALLY_TYPE -> "ANSWERED_EXTERNALLY"
        else -> "OTHER"
    }
}

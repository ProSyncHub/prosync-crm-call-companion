package com.prosync.crmcompanion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager

class PhoneStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        val settings = SettingsStore(context)
        val sessionStore = CallSessionStore(context)

        if (!settings.shiftActive) {
            sessionStore.clear()
            return
        }

        val employeeAtCallStart = settings.activeEmployee.ifBlank { "UNASSIGNED" }

        // Android can deliver this broadcast twice when both READ_PHONE_STATE and READ_CALL_LOG are granted.
        // beginIfNeeded() and finish() make the duplicate broadcasts harmless.
        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                val numberHint = if (intent.hasExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)) {
                    intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
                } else null
                sessionStore.beginIfNeeded(
                    employeeAtCallStart,
                    settings.activeEmployeeEmail,
                    numberHint,
                    "INCOMING"
                )
            }

            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                sessionStore.beginIfNeeded(
                    employeeAtCallStart,
                    settings.activeEmployeeEmail,
                    null,
                    "OUTGOING"
                )
            }

            TelephonyManager.EXTRA_STATE_IDLE -> {
                val snapshot = sessionStore.finish() ?: return
                SyncScheduler.enqueueSessionCapture(context, snapshot)
            }
        }
    }
}

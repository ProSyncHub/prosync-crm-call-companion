package com.prosync.crmcompanion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            if (SettingsStore(context).shiftActive) {
                SyncScheduler.scheduleRecovery(context)
                SyncScheduler.enqueueRecordingAndSync(context, 0)
                ActiveUserNotification.show(context)
            }
        }
    }
}

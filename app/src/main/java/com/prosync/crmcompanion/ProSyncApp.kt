package com.prosync.crmcompanion

import android.app.Application

class ProSyncApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (SettingsStore(this).shiftActive) {
            SyncScheduler.scheduleRecovery(this)
            SyncScheduler.enqueueRecordingAndSync(this, 0)
            ActiveUserNotification.show(this)
        }
    }
}

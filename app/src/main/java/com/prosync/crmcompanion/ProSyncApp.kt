package com.prosync.crmcompanion

import android.app.Application

class ProSyncApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SyncScheduler.schedulePeriodicSync(this)
        SyncScheduler.enqueueRecordingAndSync(this, 0)
        if (SettingsStore(this).shiftActive) {
            SyncScheduler.scheduleRecovery(this)
            ActiveUserNotification.show(this)
        }
    }
}

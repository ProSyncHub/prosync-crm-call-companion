package com.prosync.crmcompanion

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object ActiveUserNotification {
    private const val CHANNEL_ID = "prosync_active_user"
    private const val NOTIFICATION_ID = 4101

    fun show(context: Context) {
        val settings = SettingsStore(context)
        ensureChannel(context)
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val employee = settings.activeEmployee.ifBlank { "UNASSIGNED" }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentTitle("ProSync call capture is on")
            .setContentText("Calling as $employee • tap to switch employee")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Normal SIM calls are being captured for CRM as $employee. Tap to open the employee selector."))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val existing = manager.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Active call capture",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows which employee normal SIM calls are being captured for."
                setShowBadge(false)
            }
        )
    }
}

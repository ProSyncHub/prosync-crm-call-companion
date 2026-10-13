package com.prosync.crmcompanion

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * After an answered call: "Was this you?" (or "Who took this call?" when nobody
 * is signed in). "Yes" just closes it; "No" opens the picker. Ignoring it changes nothing.
 */
object CallConfirmNotification {
    private const val CHANNEL_ID = "prosync_call_confirm"
    const val EXTRA_CALL_LOG_ID = "call_log_id"

    private fun notificationId(callLogId: Long) = 5000 + (callLogId % 100_000).toInt()

    fun show(context: Context, call: CallRecord) {
        if (call.durationSeconds <= 0L) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        ensureChannel(context)
        val nobody = call.employeeName.isBlank() || call.employeeName == EmployeeSession.NOT_SIGNED_IN
        val number = call.rawNumber.ifBlank { "a private number" }
        val minutes = call.durationSeconds / 60
        val length = if (minutes > 0) "${minutes}m ${call.durationSeconds % 60}s" else "${call.durationSeconds}s"
        val id = notificationId(call.callLogId)

        val pick = PendingIntent.getActivity(
            context, id,
            Intent(context, ConfirmCallerActivity::class.java).putExtra(EXTRA_CALL_LOG_ID, call.callLogId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentTitle(if (nobody) "Who took this call?" else "Did you take this call, ${call.employeeName.substringBefore(' ')}?")
            .setContentText("${if (call.direction == "OUTGOING") "Call to" else "Call from"} $number · $length")
            .setContentIntent(pick)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        if (nobody) {
            builder.addAction(0, "Choose name", pick)
        } else {
            val yes = PendingIntent.getBroadcast(
                context, id,
                Intent(context, CallConfirmReceiver::class.java).putExtra(EXTRA_CALL_LOG_ID, call.callLogId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(0, "Yes, it was me", yes).addAction(0, "No, someone else", pick)
        }
        runCatching { NotificationManagerCompat.from(context).notify(id, builder.build()) }
    }

    fun cancel(context: Context, callLogId: Long) = NotificationManagerCompat.from(context).cancel(notificationId(callLogId))

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Confirm who took a call", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "After a call, asks whether the signed-in person took it."
            }
        )
    }
}

/** "Yes, it was me": nothing to change, just close the question. */
class CallConfirmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(CallConfirmNotification.EXTRA_CALL_LOG_ID, -1L)
        if (id >= 0) CallConfirmNotification.cancel(context, id)
    }
}

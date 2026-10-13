package com.prosync.crmcompanion

import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CallHistoryActivity : AppCompatActivity() {
    private val dateFormat = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
    private val handler = Handler(Looper.getMainLooper())
    private val refreshLoop = object : Runnable {
        override fun run() { renderCalls(); handler.postDelayed(this, 2_000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyProSyncSystemBars()
        setContentView(R.layout.activity_call_history)
        findViewById<Button>(R.id.backButton).setOnClickListener { finish() }
        renderCalls()
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(refreshLoop)
        handler.post(refreshLoop)
    }

    override fun onPause() { handler.removeCallbacks(refreshLoop); super.onPause() }

    private fun renderCalls() {
        val calls = CallDb(this).latest(250)
        val container = findViewById<LinearLayout>(R.id.callHistoryContainer)
        container.removeAllViews()
        val synced = calls.count { it.syncStatus == "SYNCED" }
        val recordings = calls.count { it.recordingUri != null }
        findViewById<TextView>(R.id.historySummary).text =
            "${calls.size} calls  ·  $synced in CRM  ·  $recordings recordings found"

        if (calls.isEmpty()) {
            TextView(this).also {
                it.text = "No calls captured yet. Turn Capture ON and complete a normal SIM call."
                it.setTextColor(ContextCompat.getColor(this, R.color.muted_ink))
                it.textSize = 14f
                it.setPadding(20, 48, 20, 48)
                container.addView(it)
            }
            return
        }

        calls.forEach { call ->
            val view = LayoutInflater.from(this).inflate(R.layout.item_call_record, container, false)
            view.findViewById<TextView>(R.id.callDirection).text = directionLabel(call.direction)
            view.findViewById<TextView>(R.id.callNumber).text = call.rawNumber.ifBlank { "Private / unknown number" }
            view.findViewById<TextView>(R.id.callTime).text =
                "${dateFormat.format(Date(call.startedAt))}  ·  ${call.durationSeconds}s  ·  ${call.employeeName}"
            view.findViewById<TextView>(R.id.callRecordingState).text = CallStatus.recordingLabel(call)
            view.findViewById<TextView>(R.id.callCrmState).apply {
                text = if (call.syncStatus == "SYNCED") "In CRM" else if (call.syncStatus == "FAILED") "Retrying" else "Queued"
                backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(
                        this@CallHistoryActivity,
                        if (call.syncStatus == "SYNCED") R.color.capture_on_surface else R.color.pending_surface
                    )
                )
            }
            view.findViewById<TextView>(R.id.callAnalysisState).text = CallStatus.label(call)
            view.setOnClickListener { showDetails(call) }
            container.addView(view)
        }
    }

    private fun showDetails(call: CallRecord) {
        val message = buildString {
            append("Number\n${call.rawNumber.ifBlank { "Private / unknown" }}\n\n")
            append("Direction\n${directionLabel(call.direction)}\n\n")
            append("Date and time\n${dateFormat.format(Date(call.startedAt))}\n\n")
            append("Duration\n${call.durationSeconds} seconds\n\n")
            append("Employee\n${call.employeeName}\n\n")
            append("Recording\n${CallStatus.recordingLabel(call)}\n\n")
            append("CRM\n${CallStatus.label(call)}")
            call.crmMember?.let { append("\n\nMember\n$it") }
            call.recordingName?.let { append("\n\nRecording file\n$it") }
            call.syncError?.let { append("\n\nLatest detail\n$it") }
        }
        AlertDialog.Builder(this)
            .setTitle("Call details")
            .setMessage(message)
            .setPositiveButton("Close", null)
            .setNeutralButton("Change who took it") { _, _ ->
                startActivity(android.content.Intent(this, ConfirmCallerActivity::class.java).putExtra(CallConfirmNotification.EXTRA_CALL_LOG_ID, call.callLogId))
            }
            .show()
    }

    private fun directionLabel(direction: String) = when (direction) {
        "INCOMING" -> "Incoming call"
        "OUTGOING" -> "Outgoing call"
        "MISSED" -> "Missed incoming call"
        "REJECTED" -> "Rejected incoming call"
        "BLOCKED" -> "Blocked incoming call"
        else -> direction.lowercase().replace('_', ' ').replaceFirstChar { it.uppercaseChar() }
    }
}

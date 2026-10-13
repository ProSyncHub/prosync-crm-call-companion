package com.prosync.crmcompanion

import android.os.Bundle
import android.util.TypedValue
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** "Who took this call?": pick the right person; the CRM is updated (and audit-logged). */
class ConfirmCallerActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyProSyncSystemBars()
        val callLogId = intent.getLongExtra(CallConfirmNotification.EXTRA_CALL_LOG_ID, -1L)
        val call = CallDb(this).find(callLogId)
        if (call == null) { finish(); return }
        CallConfirmNotification.cancel(this, callLogId)

        val pad = dp(20)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        list.addView(TextView(this).apply {
            text = "Who took this call?"
            textSize = 22f
            setTextColor(ContextCompat.getColor(context, R.color.ink))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        list.addView(TextView(this).apply {
            text = "${call.rawNumber.ifBlank { "Private number" }} · ${call.durationSeconds}s · now under ${call.employeeName}"
            setTextColor(ContextCompat.getColor(context, R.color.muted_ink))
            setPadding(0, dp(6), 0, dp(14))
        })

        val recent = EmployeeSession.recentEmployees(this)
        val everyone = (recent + EmployeeSession.cachedEmployees(this).sortedBy { it.name.lowercase() })
            .distinctBy { it.email.lowercase() }
        everyone.forEach { person -> list.addView(choice("${person.name} · ${person.department}") { save(call, person.name, person.email) }) }
        list.addView(choice("Someone not in the list / not sure") { save(call, EmployeeSession.NOT_SIGNED_IN, "") })
        list.addView(Button(this).apply { text = "Cancel"; setOnClickListener { finish() } })

        setContentView(ScrollView(this).apply { addView(list) })
    }

    private fun choice(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun save(call: CallRecord, name: String, email: String) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { CallDb(this@ConfirmCallerActivity).changeEmployee(call.callLogId, name, email) }
            // Sends it to the CRM now (or with the call's own upload if it has not gone yet).
            SyncScheduler.enqueueRecordingAndSync(this@ConfirmCallerActivity, 0)
            Toast.makeText(this@ConfirmCallerActivity, "Saved: $name took this call", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun dp(value: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()
}

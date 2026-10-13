package com.prosync.crmcompanion

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Small CRM calls the sync worker makes after uploading: call status and "who took it". */
object CrmCallApi {
    data class CrmCallStatus(val state: String, val staffName: String, val memberName: String?)

    fun sessionId(call: CallRecord) = "${call.deviceId}:${call.callLogId}"

    private fun post(settings: SettingsStore, path: String, body: JSONObject): Pair<Int, String> {
        val connection = (URL("${settings.apiBaseUrl}$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 15_000; readTimeout = 20_000
            setRequestProperty("Authorization", "Bearer ${settings.apiKey}")
            setRequestProperty("Content-Type", "application/json")
        }
        connection.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = connection.responseCode
        val text = (if (code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.readText().orEmpty()
        return code to text
    }

    /** Where each call is in the CRM (transcribed, transcribing, no recording, ...). */
    fun statuses(settings: SettingsStore, sessionIds: List<String>): Map<String, CrmCallStatus> {
        if (sessionIds.isEmpty()) return emptyMap()
        val (code, text) = post(settings, "/api/mobile/calls/status", JSONObject().put("ids", JSONArray(sessionIds.take(100))))
        if (code !in 200..299) throw IllegalStateException("CRM status HTTP $code")
        val statuses = JSONObject(text).optJSONObject("statuses") ?: return emptyMap()
        return statuses.keys().asSequence().associateWith { key ->
            val item = statuses.getJSONObject(key)
            CrmCallStatus(
                item.optString("state"),
                item.optString("staffName"),
                if (item.isNull("memberName")) null else item.optString("memberName").ifBlank { null }
            )
        }
    }

    /** "Did you make this call?" answer. Returns false when the call is not in the CRM yet (try later). */
    fun setStaff(settings: SettingsStore, call: CallRecord): Boolean {
        val body = JSONObject()
            .put("call_session_id", sessionId(call))
            .put("employee_email", call.employeeEmail)
            .put("device_label", call.deviceLabel)
        val (code, text) = post(settings, "/api/mobile/calls/staff", body)
        if (code == 404) return false
        if (code !in 200..299) throw IllegalStateException("CRM staff change HTTP $code: ${text.take(160)}")
        return true
    }
}

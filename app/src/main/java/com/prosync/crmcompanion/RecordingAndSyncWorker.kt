package com.prosync.crmcompanion

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

class RecordingAndSyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val db = CallDb(applicationContext); val settings = SettingsStore(applicationContext)
        if (!settings.shiftActive) return@withContext Result.success()
        var retryNeeded = false
        db.pendingSync().forEach { original ->
            var call = original
            if (call.recordingStatus == "PENDING") {
                val found = runCatching { RecordingScanner.findForCall(applicationContext, call) }.getOrNull()
                if (found != null) db.attachRecording(call.callLogId, found.uri, found.name)
                call = db.pendingSync().firstOrNull { it.callLogId == call.callLogId } ?: call
            }
            if (settings.apiBaseUrl.isBlank() || settings.apiKey.isBlank()) {
                retryNeeded = true
                return@forEach
            }
            runCatching { upload(call, settings) }
                .onSuccess { response ->
                    if (call.recordingUri != null && response.callLogId.isNotBlank()) {
                        runCatching { analyze(response.callLogId, settings) }
                            .onSuccess {
                                db.markAnalysisComplete(call.callLogId)
                                db.markSynced(call.callLogId)
                            }
                            .onFailure {
                                retryNeeded = true
                                db.markSyncFailed(call.callLogId, it.message ?: "Transcription and AI analysis failed")
                            }
                    } else {
                        retryNeeded = true
                        db.markWaitingForRecording(call.callLogId)
                    }
                }
                .onFailure {
                    retryNeeded = true
                    db.markSyncFailed(call.callLogId, it.message ?: "Sync failed")
                }
        }
        if (retryNeeded) Result.retry() else Result.success()
    }

    data class UploadResponse(val callLogId: String, val unmatchedCallId: String)

    private fun upload(call: CallRecord, settings: SettingsStore): UploadResponse {
        val boundary = "ProSync-${System.currentTimeMillis()}"
        val connection = (URL("${settings.apiBaseUrl}/api/mobile/calls").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 20_000; readTimeout = 60_000
            setRequestProperty("Authorization", "Bearer ${settings.apiKey}"); setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        connection.outputStream.buffered().use { output ->
            fun text(name: String, value: String) { output.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray()) }
            val payload = JSONObject().apply {
                put("call_session_id", "${call.deviceId}:${call.callLogId}")
                put("remote_number", call.rawNumber)
                put("direction", call.direction)
                put("started_at", Instant.ofEpochMilli(call.startedAt).toString())
                put("ended_at", Instant.ofEpochMilli(call.startedAt + call.durationSeconds * 1000L).toString())
                put("duration_seconds", call.durationSeconds)
                put("employee_name", call.employeeName)
                put("employee_email", call.employeeEmail)
                put("device_id", call.deviceId)
                put("device_label", call.deviceLabel)
                put("sim_account", call.phoneAccountId ?: "")
                put("recording_status", call.recordingStatus.lowercase())
                put("recording_file_name", call.recordingName ?: "")
                put("recording_match_confidence", if (call.recordingUri == null) "none" else "high")
                put("app_version", "0.4.0-capture-control")
            }
            text("payload", payload.toString())
            call.recordingUri?.let { uriValue ->
                output.write("--$boundary\r\nContent-Disposition: form-data; name=\"recording\"; filename=\"${call.recordingName ?: "call.m4a"}\"\r\nContent-Type: audio/*\r\n\r\n".toByteArray())
                applicationContext.contentResolver.openInputStream(Uri.parse(uriValue))!!.use { it.copyTo(output) }; output.write("\r\n".toByteArray())
            }
            output.write("--$boundary--\r\n".toByteArray())
        }
        val response = connection.responseCode
        if (response !in 200..299) throw IllegalStateException("CRM returned HTTP $response")
        val body = connection.inputStream.bufferedReader().readText()
        val json = JSONObject(body)
        return UploadResponse(
            callLogId = json.optString("callLogId", ""),
            unmatchedCallId = json.optString("unmatchedCallId", "")
        )
    }

    private fun analyze(callLogId: String, settings: SettingsStore) {
        val connection = (URL("${settings.apiBaseUrl}/api/mobile/calls/$callLogId/analyze").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 20_000; readTimeout = 120_000
            setRequestProperty("Authorization", "Bearer ${settings.apiKey}")
            setRequestProperty("Content-Length", "0")
        }
        val response = connection.responseCode
        if (response !in 200..299) {
            throw IllegalStateException("CRM analyze returned HTTP $response")
        }
    }
}

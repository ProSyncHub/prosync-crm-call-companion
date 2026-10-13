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

private const val RECORDING_WAIT_MS = 3 * 60 * 60 * 1000L

class RecordingAndSyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val db = CallDb(applicationContext); val settings = SettingsStore(applicationContext)
        // Uploading is independent of capture: calls captured before capture was turned
        // off must still reach the CRM.
        if (settings.apiBaseUrl.isBlank() || settings.apiKey.isBlank()) return@withContext Result.retry()
        var retryNeeded = false
        rescanMissingRecordings(db, settings, inputData.getBoolean(KEY_FORCE_RESCAN, false))
        db.pendingSync().forEach { original ->
            var call = original
            if (call.recordingStatus == "PENDING") {
                val found = runCatching { RecordingScanner.findForCall(applicationContext, call) }.getOrNull()
                val callEnded = call.startedAt + call.durationSeconds * 1000L
                when {
                    found != null -> db.attachRecording(call.callLogId, found.uri, found.name)
                    // No audio exists for unanswered calls, and OEM recorders write within
                    // minutes; stop waiting so the row cannot block the queue forever.
                    call.durationSeconds <= 0L || System.currentTimeMillis() - callEnded > RECORDING_WAIT_MS ->
                        db.markRecordingUnavailable(call.callLogId)
                }
                call = db.find(call.callLogId) ?: call
            }

            val needsUpload = call.syncStatus != "SYNCED" ||
                (call.recordingUri != null && call.analysisStatus == "PENDING")
            if (!needsUpload && !call.directionResync) {
                // Already in CRM and still waiting for the phone's recorder to finish.
                if (call.recordingStatus == "PENDING") retryNeeded = true
                return@forEach
            }
            val includeRecording = needsUpload && call.recordingUri != null

            runCatching { upload(call, settings, includeRecording) }
                .onSuccess { response ->
                    if (call.directionResync) db.markDirectionResynced(call.callLogId)
                    when {
                      !needsUpload -> Unit
                      // The CRM could not store the audio: keep the recording queued, even for
                      // calls from unknown numbers, so it is never lost.
                      response.recordingWarning.isNotBlank() && includeRecording -> {
                        retryNeeded = true
                        db.markRecordingUploadPending(call.callLogId, response.recordingWarning)
                      }
                      response.unmatchedCallId.isNotBlank() -> {
                        db.markUnmatchedSynced(call.callLogId)
                        // Still looking for its recording: come back soon, not in 15 minutes.
                        if (call.recordingStatus == "PENDING") retryNeeded = true
                      }
                      response.recordingWarning.isNotBlank() -> {
                        retryNeeded = true
                        db.markRecordingUploadPending(call.callLogId, response.recordingWarning)
                      }
                      includeRecording && response.callLogId.isNotBlank() -> {
                        // New CRM builds transcribe in the background and retry on their own.
                        val handedOff = response.analysisQueued || runCatching { analyze(response.callLogId, settings) }.isSuccess
                        if (handedOff) {
                            db.markAnalysisComplete(call.callLogId)
                            db.markSynced(call.callLogId)
                        } else {
                            retryNeeded = true
                            db.markAnalysisPending(call.callLogId, "CRM did not confirm transcription; will retry")
                        }
                      }
                      response.callLogId.isNotBlank() && call.recordingStatus == "PENDING" -> {
                        retryNeeded = true
                        db.markWaitingForRecording(call.callLogId)
                      }
                      response.callLogId.isNotBlank() -> {
                        db.markSynced(call.callLogId)
                      }
                      else -> {
                        retryNeeded = true
                        db.markSyncFailed(call.callLogId, "CRM accepted the request without returning a call reference")
                      }
                    }
                }
                .onFailure {
                    retryNeeded = true
                    if (needsUpload) db.markSyncFailed(call.callLogId, it.message ?: "Sync failed")
                }
        }
        sendStaffChanges(db, settings)
        refreshCrmStatus(db, settings)
        if (retryNeeded) Result.retry() else Result.success()
    }

    /** Calls marked "no recording" are checked again (at most every 20 min unless forced). */
    private fun rescanMissingRecordings(db: CallDb, settings: SettingsStore, force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - settings.missingRescanAt < 20 * 60_000L) return
        settings.missingRescanAt = now
        db.missingRecordings(now - 3L * 24 * 60 * 60 * 1000).forEach { call ->
            runCatching { RecordingScanner.findForCall(applicationContext, call) }.getOrNull()?.let {
                db.attachRecording(call.callLogId, it.uri, it.name)
            }
        }
    }

    private fun sendStaffChanges(db: CallDb, settings: SettingsStore) {
        db.pendingStaffChanges().forEach { call ->
            runCatching { CrmCallApi.setStaff(settings, call) }
                .onSuccess { sent -> if (sent) db.clearStaffChange(call.callLogId) }
        }
    }

    /** Asks the CRM how recent calls are doing, so both screens show the real state. */
    private fun refreshCrmStatus(db: CallDb, settings: SettingsStore) {
        val calls = db.recentSynced(System.currentTimeMillis() - 2L * 24 * 60 * 60 * 1000)
        if (calls.isEmpty()) return
        val byId = calls.associateBy { CrmCallApi.sessionId(it) }
        runCatching { CrmCallApi.statuses(settings, byId.keys.toList()) }.getOrNull()?.forEach { (id, status) ->
            byId[id]?.let { db.setCrmState(it.callLogId, status.state, status.memberName) }
        }
    }

    data class UploadResponse(
        val callLogId: String,
        val unmatchedCallId: String,
        val recordingWarning: String,
        val analysisQueued: Boolean
    )

    private fun upload(call: CallRecord, settings: SettingsStore, includeRecording: Boolean): UploadResponse {
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
                // Which office line (this phone's own number) made or took the call.
                val (simSlot, deviceNumber) = SimNumbers.numberForCall(applicationContext, call.phoneAccountId)
                if (simSlot != null) put("sim_slot", simSlot)
                if (deviceNumber.isNotBlank()) put("device_number", deviceNumber)
                put("recording_status", call.recordingStatus.lowercase())
                put("recording_file_name", call.recordingName ?: "")
                put("recording_match_confidence", if (call.recordingUri == null) "none" else "high")
                put("app_version", BuildConfig.VERSION_NAME)
            }
            text("payload", payload.toString())
            call.recordingUri?.takeIf { includeRecording }?.let { uriValue ->
                output.write("--$boundary\r\nContent-Disposition: form-data; name=\"recording\"; filename=\"${call.recordingName ?: "call.m4a"}\"\r\nContent-Type: audio/*\r\n\r\n".toByteArray())
                applicationContext.contentResolver.openInputStream(Uri.parse(uriValue))!!.use { it.copyTo(output) }; output.write("\r\n".toByteArray())
            }
            output.write("--$boundary--\r\n".toByteArray())
        }
        val response = connection.responseCode
        if (response !in 200..299) {
            val detail = runCatching { connection.errorStream?.bufferedReader()?.readText().orEmpty() }.getOrDefault("")
            throw IllegalStateException("CRM returned HTTP $response${if (detail.isBlank()) "" else ": ${detail.take(240)}"}")
        }
        val body = connection.inputStream.bufferedReader().readText()
        val json = JSONObject(body)
        return UploadResponse(
            callLogId = json.cleanString("callLogId"),
            unmatchedCallId = json.cleanString("unmatchedCallId"),
            recordingWarning = json.cleanString("recordingWarning"),
            analysisQueued = json.optBoolean("analysisQueued", false)
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
            val detail = runCatching { connection.errorStream?.bufferedReader()?.readText().orEmpty() }.getOrDefault("")
            throw IllegalStateException("CRM analyze returned HTTP $response${if (detail.isBlank()) "" else ": ${detail.take(240)}"}")
        }
    }

    companion object {
        const val KEY_FORCE_RESCAN = "force_rescan"
    }
}

/** org.json's optString turns a JSON null into the text "null"; treat it as empty. */
private fun JSONObject.cleanString(name: String): String =
    if (isNull(name)) "" else optString(name, "").trim()

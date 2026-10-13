package com.prosync.crmcompanion

/**
 * One wording for a call's state, used by the home screen and the full call list,
 * so both always say the same thing. Once the CRM has reported back (crmState)
 * that wins over the phone's own guess.
 */
object CallStatus {
    fun label(call: CallRecord): String {
        if (call.durationSeconds <= 0L) return "In CRM · no recording (not answered)".takeIf { call.syncStatus == "SYNCED" } ?: "Queued for CRM"
        if (call.syncStatus == "FAILED") return "Will retry automatically"
        if (call.syncStatus != "SYNCED") return if (call.recordingUri == null && call.recordingStatus == "PENDING") "Looking for recording…" else "Uploading to CRM…"
        return when (call.crmState) {
            "transcribed" -> "In CRM · transcribed ✓"
            "transcribing" -> "In CRM · transcribing…"
            "no_recording" -> "In CRM · no recording found"
            "no_speech" -> "In CRM · no speech in recording"
            "failed" -> "In CRM · transcription retrying"
            "ignored" -> "In CRM · ignored"
            "waiting_recording" -> if (call.recordingUri != null) "Uploading recording…" else "In CRM · looking for recording…"
            else -> when {
                call.recordingStatus == "NOT_FOUND" -> "In CRM · no recording found (is call recording on?)"
                call.recordingUri == null -> "In CRM · looking for recording…"
                else -> "In CRM · transcribing…"
            }
        }
    }

    fun recordingLabel(call: CallRecord) = when {
        call.recordingUri != null -> "Recording found"
        call.durationSeconds <= 0L -> "No recording expected"
        call.recordingStatus == "NOT_FOUND" -> "No recording on phone (checked again regularly)"
        else -> "Looking for recording"
    }

    fun isFinished(call: CallRecord) = call.crmState in setOf("transcribed", "no_recording", "no_speech", "ignored")
}

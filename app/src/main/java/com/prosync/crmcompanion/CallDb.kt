package com.prosync.crmcompanion

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class CallDb(context: Context) : SQLiteOpenHelper(context, "prosync_calls.db", null, 6) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE calls (
              call_log_id INTEGER PRIMARY KEY,
              device_id TEXT NOT NULL,
              device_label TEXT NOT NULL,
              employee_name TEXT NOT NULL,
              employee_email TEXT NOT NULL DEFAULT '',
              raw_number TEXT NOT NULL,
              normalized_number TEXT NOT NULL,
              direction TEXT NOT NULL,
              started_at INTEGER NOT NULL,
              duration_seconds INTEGER NOT NULL,
              phone_account_id TEXT,
              phone_account_component TEXT,
              capture_mode TEXT NOT NULL,
              session_started_at INTEGER,
              session_ended_at INTEGER,
              capture_score INTEGER,
              recording_uri TEXT,
              recording_name TEXT,
              recording_status TEXT NOT NULL DEFAULT 'PENDING',
              sync_status TEXT NOT NULL DEFAULT 'PENDING',
              sync_error TEXT,
              analysis_status TEXT NOT NULL DEFAULT 'PENDING',
              direction_resync INTEGER NOT NULL DEFAULT 0,
              created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE calls ADD COLUMN recording_uri TEXT")
            db.execSQL("ALTER TABLE calls ADD COLUMN recording_name TEXT")
            db.execSQL("ALTER TABLE calls ADD COLUMN recording_status TEXT NOT NULL DEFAULT 'PENDING'")
            db.execSQL("ALTER TABLE calls ADD COLUMN sync_status TEXT NOT NULL DEFAULT 'PENDING'")
            db.execSQL("ALTER TABLE calls ADD COLUMN sync_error TEXT")
        }
        if (oldVersion < 4) db.execSQL("ALTER TABLE calls ADD COLUMN employee_email TEXT NOT NULL DEFAULT ''")
        if (oldVersion < 5) db.execSQL("ALTER TABLE calls ADD COLUMN analysis_status TEXT NOT NULL DEFAULT 'PENDING'")
        if (oldVersion < 6) {
            // CRM builds before this one tagged every OUTGOING call as inbound. Re-send the
            // metadata of every already-synced call once so the CRM can correct it.
            db.execSQL("ALTER TABLE calls ADD COLUMN direction_resync INTEGER NOT NULL DEFAULT 0")
            db.execSQL("UPDATE calls SET direction_resync = 1 WHERE sync_status = 'SYNCED'")
            // Calls with no audio (missed / 0 seconds) waited forever for a recording and
            // blocked the sync queue. Release them.
            db.execSQL("UPDATE calls SET recording_status = 'NOT_FOUND' WHERE recording_status = 'PENDING' AND duration_seconds <= 0")
        }
    }

    fun exists(callLogId: Long): Boolean {
        readableDatabase.rawQuery(
            "SELECT 1 FROM calls WHERE call_log_id = ? LIMIT 1",
            arrayOf(callLogId.toString())
        ).use { return it.moveToFirst() }
    }

    fun insert(call: CallRecord): Boolean {
        val values = ContentValues().apply {
            put("call_log_id", call.callLogId)
            put("device_id", call.deviceId)
            put("device_label", call.deviceLabel)
            put("employee_name", call.employeeName)
            put("employee_email", call.employeeEmail)
            put("raw_number", call.rawNumber)
            put("normalized_number", call.normalizedNumber)
            put("direction", call.direction)
            put("started_at", call.startedAt)
            put("duration_seconds", call.durationSeconds)
            put("phone_account_id", call.phoneAccountId)
            put("phone_account_component", call.phoneAccountComponent)
            put("capture_mode", call.captureMode)
            if (call.sessionStartedAt != null) put("session_started_at", call.sessionStartedAt)
            if (call.sessionEndedAt != null) put("session_ended_at", call.sessionEndedAt)
            if (call.captureScore != null) put("capture_score", call.captureScore)
            put("recording_uri", call.recordingUri)
            put("recording_name", call.recordingName)
            put("recording_status", call.recordingStatus)
            put("sync_status", call.syncStatus)
            put("sync_error", call.syncError)
            put("analysis_status", call.analysisStatus)
            put("created_at", System.currentTimeMillis())
        }
        return writableDatabase.insertWithOnConflict(
            "calls", null, values, SQLiteDatabase.CONFLICT_IGNORE
        ) != -1L
    }

    fun count(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM calls", emptyArray()).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    fun pendingCount(): Int {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM calls WHERE $PENDING_WHERE",
            PENDING_ARGS
        ).use { c -> return if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    fun latest(limit: Int = 30): List<CallRecord> {
        val rows = mutableListOf<CallRecord>()
        readableDatabase.query(
            "calls", null, null, null, null, null, "started_at DESC", limit.toString()
        ).use { c ->
            while (c.moveToNext()) rows += c.toRecord()
        }
        return rows
    }

    fun pendingSync(limit: Int = 100): List<CallRecord> {
        val rows = mutableListOf<CallRecord>()
        readableDatabase.query(
            "calls", null,
            // Newest first: today's calls reach the CRM before an old backlog drains.
            PENDING_WHERE, PENDING_ARGS, null, null, "started_at DESC", limit.toString()
        ).use { c ->
            while (c.moveToNext()) rows += c.toRecord()
        }
        return rows
    }

    fun find(callLogId: Long): CallRecord? =
        readableDatabase.query("calls", null, "call_log_id = ?", arrayOf(callLogId.toString()), null, null, null, "1")
            .use { c -> if (c.moveToFirst()) c.toRecord() else null }

    fun attachRecording(callLogId: Long, uri: String, name: String) {
        writableDatabase.update("calls", ContentValues().apply {
            put("recording_uri", uri); put("recording_name", name); put("recording_status", "FOUND"); put("sync_status", "PENDING")
        }, "call_log_id = ?", arrayOf(callLogId.toString()))
    }

    fun markRecordingUnavailable(callLogId: Long) = writableDatabase.update("calls", ContentValues().apply {
        put("recording_status", "NOT_FOUND"); put("analysis_status", "NO_RECORDING")
    }, "call_log_id = ?", arrayOf(callLogId.toString()))
    fun markDirectionResynced(callLogId: Long) = writableDatabase.update("calls", ContentValues().apply { put("direction_resync", 0) }, "call_log_id = ?", arrayOf(callLogId.toString()))
    fun markWaitingForRecording(callLogId: Long) = writableDatabase.update("calls", ContentValues().apply {
        put("recording_status", "PENDING"); put("sync_status", "SYNCED"); put("analysis_status", "PENDING")
        put("sync_error", "CRM synced · waiting for the native phone recording")
    }, "call_log_id = ?", arrayOf(callLogId.toString()))
    fun markUnmatchedSynced(callLogId: Long) = writableDatabase.update("calls", ContentValues().apply {
        put("sync_status", "SYNCED"); put("analysis_status", "UNMATCHED"); putNull("sync_error")
    }, "call_log_id = ?", arrayOf(callLogId.toString()))
    fun markSynced(callLogId: Long) = writableDatabase.update("calls", ContentValues().apply { put("sync_status", "SYNCED"); putNull("sync_error") }, "call_log_id = ?", arrayOf(callLogId.toString()))
    fun markAnalysisComplete(callLogId: Long) = writableDatabase.update("calls", ContentValues().apply { put("analysis_status", "COMPLETE") }, "call_log_id = ?", arrayOf(callLogId.toString()))
    fun markAnalysisPending(callLogId: Long, error: String) = writableDatabase.update("calls", ContentValues().apply {
        put("sync_status", "SYNCED")
        put("analysis_status", "PENDING")
        put("sync_error", "CRM synced · transcript/AI pending: ${error.take(400)}")
    }, "call_log_id = ?", arrayOf(callLogId.toString()))
    fun markRecordingUploadPending(callLogId: Long, error: String) = writableDatabase.update("calls", ContentValues().apply {
        put("sync_status", "SYNCED")
        put("recording_status", "FOUND")
        put("analysis_status", "PENDING")
        put("sync_error", "Call details saved in CRM · recording upload pending: ${error.take(350)}")
    }, "call_log_id = ?", arrayOf(callLogId.toString()))
    fun markSyncFailed(callLogId: Long, error: String) = writableDatabase.update("calls", ContentValues().apply { put("sync_status", "FAILED"); put("sync_error", error.take(500)) }, "call_log_id = ?", arrayOf(callLogId.toString()))

    private fun Cursor.toRecord(): CallRecord = CallRecord(
        callLogId = getLong(getColumnIndexOrThrow("call_log_id")),
        deviceId = getString(getColumnIndexOrThrow("device_id")),
        deviceLabel = getString(getColumnIndexOrThrow("device_label")),
        employeeName = getString(getColumnIndexOrThrow("employee_name")),
        employeeEmail = getString(getColumnIndexOrThrow("employee_email")),
        rawNumber = getString(getColumnIndexOrThrow("raw_number")),
        normalizedNumber = getString(getColumnIndexOrThrow("normalized_number")),
        direction = getString(getColumnIndexOrThrow("direction")),
        startedAt = getLong(getColumnIndexOrThrow("started_at")),
        durationSeconds = getLong(getColumnIndexOrThrow("duration_seconds")),
        phoneAccountId = stringOrNull("phone_account_id"),
        phoneAccountComponent = stringOrNull("phone_account_component"),
        captureMode = getString(getColumnIndexOrThrow("capture_mode")),
        sessionStartedAt = longOrNull("session_started_at"),
        sessionEndedAt = longOrNull("session_ended_at"),
        captureScore = intOrNull("capture_score"),
        recordingUri = stringOrNull("recording_uri"), recordingName = stringOrNull("recording_name"),
        recordingStatus = getString(getColumnIndexOrThrow("recording_status")), syncStatus = getString(getColumnIndexOrThrow("sync_status")),
        syncError = stringOrNull("sync_error"),
        analysisStatus = getString(getColumnIndexOrThrow("analysis_status")),
        directionResync = getInt(getColumnIndexOrThrow("direction_resync")) == 1
    )

    private fun Cursor.stringOrNull(name: String): String? {
        val i = getColumnIndexOrThrow(name)
        return if (isNull(i)) null else getString(i)
    }

    private fun Cursor.longOrNull(name: String): Long? {
        val i = getColumnIndexOrThrow(name)
        return if (isNull(i)) null else getLong(i)
    }

    private fun Cursor.intOrNull(name: String): Int? {
        val i = getColumnIndexOrThrow(name)
        return if (isNull(i)) null else getInt(i)
    }

    companion object {
        // A row needs work when it never reached the CRM, is still looking for its
        // recording, has a recording whose AI hand-off is unconfirmed, or needs its
        // direction re-sent. Finished rows (COMPLETE / UNMATCHED / NO_RECORDING) never match.
        private const val PENDING_WHERE =
            "sync_status != ? OR recording_status = ? OR (recording_uri IS NOT NULL AND analysis_status = ?) OR direction_resync = 1"
        private val PENDING_ARGS = arrayOf("SYNCED", "PENDING", "PENDING")
    }
}

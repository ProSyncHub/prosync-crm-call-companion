package com.prosync.crmcompanion

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import kotlin.math.abs

object RecordingScanner {
    data class Recording(val uri: String, val name: String, val modifiedAt: Long, val durationMs: Long)

    fun findForCall(context: Context, call: CallRecord): Recording? {
        val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.DATE_MODIFIED, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.RELATIVE_PATH)
        val fromSeconds = (call.startedAt / 1000L) - 120L
        val candidates = mutableListOf<Recording>()
        context.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, "${MediaStore.Audio.Media.DATE_MODIFIED} >= ?", arrayOf(fromSeconds.toString()), "${MediaStore.Audio.Media.DATE_MODIFIED} ASC")?.use { c ->
            val idI = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID); val nameI = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val dateI = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED); val durationI = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION); val pathI = c.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH)
            while (c.moveToNext()) {
                val relative = c.getString(pathI).orEmpty().lowercase()
                if (!(relative.contains("recordings/call") || relative.contains("call recordings") || relative.contains("recording/call"))) continue
                val id = c.getLong(idI); val duration = if (c.isNull(durationI)) 0 else c.getLong(durationI)
                candidates += Recording(ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id).toString(), c.getString(nameI), c.getLong(dateI) * 1000L, duration)
            }
        }
        val expectedEnd = call.startedAt + call.durationSeconds * 1000L
        return candidates.minByOrNull { candidate -> abs(candidate.modifiedAt - expectedEnd) + abs(candidate.durationMs - call.durationSeconds * 1000L) }
            ?.takeIf { abs(it.modifiedAt - expectedEnd) <= 5 * 60_000L }
    }
}

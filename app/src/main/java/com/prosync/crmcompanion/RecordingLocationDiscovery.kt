package com.prosync.crmcompanion

import android.content.Context
import android.provider.MediaStore

object RecordingLocationDiscovery {
    data class Result(
        val audioFilesScanned: Int,
        val likelyRecordingFiles: Int,
        val locations: List<Pair<String, Int>>
    ) {
        fun summary(): String = when {
            locations.isNotEmpty() -> buildString {
                append("Automatic scan complete ✓  Found $likelyRecordingFiles likely call recording")
                if (likelyRecordingFiles != 1) append("s")
                append(" in ")
                append(locations.joinToString { (path, count) -> "$path ($count)" })
            }
            else -> "Automatic scan complete ✓  Scanned $audioFilesScanned audio files. No existing indexed call recording was found yet; new recordings will be detected automatically."
        }
    }

    private val strongHints = listOf(
        "recordings/call",
        "call recordings",
        "call_record",
        "callrecord",
        "call_rec",
        "phonerecord",
        "phone_record",
        "sound_recorder/call",
        "miui/sound_recorder/call"
    )
    private val weakHints = listOf("recording", "recorder", "voice record", "sound_recorder")

    fun scan(context: Context): Result {
        val projection = arrayOf(
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.RELATIVE_PATH
        )
        var scanned = 0
        val strongLocations = linkedMapOf<String, Int>()
        val possibleLocations = linkedMapOf<String, Int>()
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            "${MediaStore.Audio.Media.DATE_MODIFIED} DESC"
        )?.use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val pathIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH)
            while (cursor.moveToNext()) {
                scanned += 1
                val name = cursor.getString(nameIndex).orEmpty()
                val path = cursor.getString(pathIndex).orEmpty().ifBlank { "Audio storage" }
                val searchable = "$path/$name".lowercase()
                when {
                    strongHints.any(searchable::contains) -> strongLocations[path] = (strongLocations[path] ?: 0) + 1
                    weakHints.any(searchable::contains) -> possibleLocations[path] = (possibleLocations[path] ?: 0) + 1
                }
            }
        }
        val selected = if (strongLocations.isNotEmpty()) strongLocations else possibleLocations
        return Result(
            audioFilesScanned = scanned,
            likelyRecordingFiles = selected.values.sum(),
            locations = selected.entries.sortedByDescending { it.value }.take(5).map { it.key to it.value }
        )
    }
}

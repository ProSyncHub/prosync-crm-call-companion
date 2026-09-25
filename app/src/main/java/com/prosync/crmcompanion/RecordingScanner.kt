package com.prosync.crmcompanion

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import kotlin.math.abs

object RecordingScanner {
    data class Recording(
        val uri: String,
        val name: String,
        val modifiedAt: Long,
        val durationMs: Long,
        val location: String,
        val trustedFolder: Boolean
    )

    private val audioExtensions = setOf("m4a", "mp3", "aac", "wav", "amr", "3gp", "ogg", "opus")
    private val callPathHints = listOf(
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

    fun findForCall(context: Context, call: CallRecord): Recording? {
        if (call.durationSeconds <= 0L) return null
        val candidates = mediaStoreCandidates(context, call).toMutableList()
        val folderUri = SettingsStore(context).recordingFolderUri
        if (folderUri.isNotBlank()) candidates += documentTreeCandidates(context, Uri.parse(folderUri))

        val expectedEnd = call.startedAt + call.durationSeconds * 1000L
        val expectedDuration = call.durationSeconds * 1000L
        val numberDigits = call.normalizedNumber.takeLast(10)
        return candidates
            .asSequence()
            .filter { candidate ->
                val timeClose = candidate.modifiedAt <= 0L || abs(candidate.modifiedAt - expectedEnd) <= 15 * 60_000L
                val durationClose = candidate.durationMs <= 0L || abs(candidate.durationMs - expectedDuration) <= maxOf(30_000L, expectedDuration / 2)
                timeClose && durationClose && (candidate.trustedFolder || looksLikeCallRecording(candidate.location, candidate.name) || candidate.durationMs > 0L)
            }
            .minByOrNull { candidate ->
                val timePenalty = if (candidate.modifiedAt > 0L) abs(candidate.modifiedAt - expectedEnd) else 4 * 60_000L
                val durationPenalty = if (candidate.durationMs > 0L) abs(candidate.durationMs - expectedDuration) else 2 * 60_000L
                val folderBonus = if (candidate.trustedFolder || looksLikeCallRecording(candidate.location, candidate.name)) 3 * 60_000L else 0L
                val numberBonus = if (numberDigits.length >= 7 && candidate.name.filter(Char::isDigit).contains(numberDigits.takeLast(7))) 2 * 60_000L else 0L
                timePenalty + durationPenalty - folderBonus - numberBonus
            }
    }

    private fun mediaStoreCandidates(context: Context, call: CallRecord): List<Recording> {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.RELATIVE_PATH
        )
        val fromSeconds = (call.startedAt / 1000L) - 20 * 60L
        val candidates = mutableListOf<Recording>()
        context.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, "${MediaStore.Audio.Media.DATE_MODIFIED} >= ?", arrayOf(fromSeconds.toString()), "${MediaStore.Audio.Media.DATE_MODIFIED} ASC")?.use { c ->
            val idI = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID); val nameI = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val dateI = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED); val durationI = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION); val pathI = c.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH)
            while (c.moveToNext()) {
                val relative = c.getString(pathI).orEmpty()
                val name = c.getString(nameI).orEmpty()
                if (!isAudioName(name)) continue
                val id = c.getLong(idI); val duration = if (c.isNull(durationI)) 0 else c.getLong(durationI)
                candidates += Recording(
                    ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id).toString(),
                    name,
                    c.getLong(dateI) * 1000L,
                    duration,
                    relative,
                    false
                )
            }
        }
        return candidates
    }

    private fun documentTreeCandidates(context: Context, treeUri: Uri): List<Recording> {
        val results = mutableListOf<Recording>()
        val rootId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return results
        scanDocumentChildren(context, treeUri, rootId, results, 0)
        return results
    }

    private fun scanDocumentChildren(
        context: Context,
        treeUri: Uri,
        parentId: String,
        results: MutableList<Recording>,
        depth: Int
    ) {
        if (depth > 3 || results.size >= 500) return
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        runCatching {
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val modifiedIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                while (cursor.moveToNext() && results.size < 500) {
                    val documentId = cursor.getString(idIndex)
                    val name = cursor.getString(nameIndex).orEmpty()
                    val mime = cursor.getString(mimeIndex).orEmpty()
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        scanDocumentChildren(context, treeUri, documentId, results, depth + 1)
                    } else if (mime.startsWith("audio/") || isAudioName(name)) {
                        val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
                        results += Recording(
                            documentUri.toString(),
                            name,
                            if (cursor.isNull(modifiedIndex)) 0L else cursor.getLong(modifiedIndex),
                            readDuration(context, documentUri),
                            documentId,
                            true
                        )
                    }
                }
            }
        }
    }

    private fun readDuration(context: Context, uri: Uri): Long = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } finally {
            retriever.release()
        }
    }.getOrDefault(0L)

    private fun isAudioName(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in audioExtensions

    private fun looksLikeCallRecording(path: String, name: String): Boolean {
        val value = "$path/$name".lowercase()
        return callPathHints.any(value::contains)
    }
}

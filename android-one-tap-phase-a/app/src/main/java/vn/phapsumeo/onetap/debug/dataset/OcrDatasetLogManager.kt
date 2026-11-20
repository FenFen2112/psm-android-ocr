package vn.phapsumeo.onetap.debug.dataset

import android.content.Context
import vn.phapsumeo.onetap.util.PrefsStore
import java.io.File

/**
 * Append-only NDJSON storage under `filesDir/ocr_dataset/`.
 * Rotates [current.jsonl] when size exceeds [MAX_ACTIVE_FILE_BYTES].
 * Deletes oldest `archive_*.jsonl` beyond [MAX_ARCHIVE_FILES].
 */
object OcrDatasetLogManager {

    const val MAX_ACTIVE_FILE_BYTES = 5L * 1024 * 1024
    const val MAX_ARCHIVE_FILES = 12
    private const val DIR_NAME = "ocr_dataset"
    private const val ACTIVE_NAME = "current.jsonl"
    private val lock = Any()

    fun isEnabled(context: Context): Boolean = PrefsStore(context).ocrDatasetLogging()

    fun appendJsonLine(context: Context, jsonObjectLine: String) {
        if (!PrefsStore(context).ocrDatasetLogging()) return
        val line = if (jsonObjectLine.endsWith("\n")) jsonObjectLine else "$jsonObjectLine\n"
        val bytes = line.toByteArray(Charsets.UTF_8)
        synchronized(lock) {
            val dir = File(context.filesDir, DIR_NAME).apply { mkdirs() }
            val active = File(dir, ACTIVE_NAME)
            val nextLen = (if (active.exists()) active.length() else 0L) + bytes.size
            if (active.exists() && active.length() > 0 && nextLen > MAX_ACTIVE_FILE_BYTES) {
                val arch = File(dir, "archive_${System.currentTimeMillis()}.jsonl")
                active.renameTo(arch)
            }
            active.appendBytes(bytes)
            pruneArchives(dir)
        }
    }

    fun datasetDirectory(context: Context): File =
        File(context.filesDir, DIR_NAME).apply { mkdirs() }

    fun pruneArchives(dir: File) {
        val archives = dir.listFiles { f ->
            f.isFile && f.name.startsWith("archive_") && f.name.endsWith(".jsonl")
        }?.sortedBy { it.lastModified() } ?: return
        val overflow = archives.size - MAX_ARCHIVE_FILES
        if (overflow <= 0) return
        archives.take(overflow).forEach { runCatching { it.delete() } }
    }

    /** Removes all JSONL files (e.g. user privacy reset). */
    fun deleteAllLogs(context: Context) {
        synchronized(lock) {
            val dir = File(context.filesDir, DIR_NAME)
            if (!dir.isDirectory) return
            dir.listFiles()?.forEach { runCatching { it.delete() } }
        }
    }
}

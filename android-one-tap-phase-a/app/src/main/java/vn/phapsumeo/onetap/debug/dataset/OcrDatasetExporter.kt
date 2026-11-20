package vn.phapsumeo.onetap.debug.dataset

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object OcrDatasetExporter {
    data class ExportResult(
        val uri: Uri,
        val displayPath: String,
        val fileName: String,
        val sizeBytes: Long,
        val imageCount: Int,
        val recordCount: Int,
    )

    const val EXPORT_FOLDER = "Download/PSM_OCR/"

    fun zipAllJsonl(activity: Activity): ExportResult? {
        val dir = OcrDatasetLogManager.datasetDirectory(activity)
        if (!dir.isDirectory) return null
        val files = dir.listFiles { f ->
            f.isFile && f.name.endsWith(".jsonl")
        }?.sortedBy { it.name }?.toList().orEmpty()
        if (files.isEmpty()) {
            Toast.makeText(activity, "Chưa có dữ liệu OCR để xuất.", Toast.LENGTH_SHORT).show()
            return null
        }

        val fileName = "psm_du_lieu_ai_${System.currentTimeMillis()}.zip"
        val tempDir = File(activity.cacheDir, "ocr_dataset_export").apply { mkdirs() }
        val tempZip = File(tempDir, fileName)
        val recordCount = countRecords(files)
        val imageCount = countImageRecords(files)

        return try {
            ZipOutputStream(FileOutputStream(tempZip)).use { zos ->
                for (f in files) {
                    zos.putNextEntry(ZipEntry(f.name))
                    FileInputStream(f).use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
            val saved = copyZipToDownloads(activity, tempZip, fileName, imageCount, recordCount)
            runCatching { tempZip.delete() }
            saved
        } catch (e: Exception) {
            Toast.makeText(activity, "Không xuất được dữ liệu AI: ${e.message}", Toast.LENGTH_LONG).show()
            runCatching { tempZip.delete() }
            null
        }
    }

    private fun copyZipToDownloads(
        activity: Activity,
        source: File,
        fileName: String,
        imageCount: Int,
        recordCount: Int,
    ): ExportResult {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/PSM_OCR")
            }
            val uri = activity.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Không tạo được file trong thư mục Download")
            activity.contentResolver.openOutputStream(uri)?.use { out ->
                FileInputStream(source).use { it.copyTo(out) }
            } ?: error("Không mở được file để ghi")
            return ExportResult(
                uri = uri,
                displayPath = "$EXPORT_FOLDER$fileName",
                fileName = fileName,
                sizeBytes = querySize(activity, uri),
                imageCount = imageCount,
                recordCount = recordCount,
            )
        }

        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "PSM_OCR")
            .apply { mkdirs() }
        val outFile = File(dir, fileName)
        FileInputStream(source).use { input ->
            FileOutputStream(outFile).use { output -> input.copyTo(output) }
        }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", outFile)
        return ExportResult(
            uri = uri,
            displayPath = "$EXPORT_FOLDER$fileName",
            fileName = fileName,
            sizeBytes = outFile.length(),
            imageCount = imageCount,
            recordCount = recordCount,
        )
    }

    private fun countRecords(files: List<File>): Int =
        files.sumOf { file -> file.useLines(Charsets.UTF_8) { lines -> lines.count { it.isNotBlank() } } }

    private fun countImageRecords(files: List<File>): Int =
        files.sumOf { file ->
            file.useLines(Charsets.UTF_8) { lines ->
                lines.count {
                    it.contains("\"kind\":\"${OcrDatasetSchema.KIND_BUBBLE_CAPTURE}\"") ||
                        it.contains("\"kind\":\"${OcrDatasetSchema.KIND_SHARE_OCR_CAPTURE}\"")
                }
            }
        }

    private fun querySize(activity: Activity, uri: Uri): Long {
        return activity.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else 0L
            } ?: 0L
    }

    fun shareZip(activity: Activity, uri: Uri) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = android.content.ClipData.newRawUri("psm_ai_data", uri)
        }
        activity.startActivity(Intent.createChooser(send, "Chia sẻ dữ liệu AI"))
    }
}

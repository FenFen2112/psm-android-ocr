package vn.phapsumeo.onetap.debug.ocr

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

object OcrDebugAnnotatedExporter {
    data class ExportResult(
        val uri: Uri,
        val displayPath: String,
        val fileName: String,
        val sizeBytes: Long,
        val imageCount: Int,
        val recordCount: Int,
    )

    const val EXPORT_FOLDER = "Download/PSM_OCR/"

    fun exportPng(
        activity: Activity,
        baseBitmap: Bitmap,
        snapshot: OcrDebugSnapshot,
    ): ExportResult? {
        val density = activity.resources.displayMetrics.density
        val lineCount = snapshot.parserDecisions.size + snapshot.confidenceDetailText.lines().size + 4
        val footerReserve = (density * 11f * lineCount).toInt().coerceIn((220 * density).toInt(), 4500)

        val outH = baseBitmap.height + footerReserve
        val out = try {
            Bitmap.createBitmap(baseBitmap.width, outH, Bitmap.Config.ARGB_8888)
        } catch (_: OutOfMemoryError) {
            Toast.makeText(activity, "Không đủ bộ nhớ để xuất ảnh OCR.", Toast.LENGTH_LONG).show()
            return null
        }

        val c = Canvas(out)
        c.drawBitmap(baseBitmap, 0f, 0f, null)
        val dst = RectF(0f, 0f, baseBitmap.width.toFloat(), baseBitmap.height.toFloat())
        OcrDebugCanvasRenderer.drawGeometryOnly(
            c,
            snapshot,
            baseBitmap.width,
            baseBitmap.height,
            dst,
            density,
            drawDecorations = true,
        )
        OcrDebugCanvasRenderer.drawExportFooter(
            c,
            snapshot,
            topY = baseBitmap.height.toFloat(),
            _width = baseBitmap.width.toFloat(),
            density = density,
            maxBottom = outH.toFloat(),
        )

        return try {
            val result = savePngToDownloads(activity, out, "psm_ocr_${System.currentTimeMillis()}.png", snapshot)
            if (!out.isRecycled && out !== baseBitmap) out.recycle()
            result
        } catch (e: Exception) {
            if (!out.isRecycled && out !== baseBitmap) out.recycle()
            Toast.makeText(activity, "Không lưu được ảnh OCR: ${e.message}", Toast.LENGTH_LONG).show()
            null
        }
    }

    private fun savePngToDownloads(
        activity: Activity,
        bitmap: Bitmap,
        fileName: String,
        snapshot: OcrDebugSnapshot,
    ): ExportResult {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/PSM_OCR")
            }
            val uri = activity.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Không tạo được file trong thư mục Download")
            activity.contentResolver.openOutputStream(uri)?.use { stream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 92, stream)
            } ?: error("Không mở được file để ghi")
            return ExportResult(
                uri = uri,
                displayPath = "$EXPORT_FOLDER$fileName",
                fileName = fileName,
                sizeBytes = querySize(activity, uri),
                imageCount = 1,
                recordCount = snapshot.lines.size,
            )
        }

        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "PSM_OCR")
            .apply { mkdirs() }
        val file = File(dir, fileName)
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 92, it) }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
        return ExportResult(
            uri = uri,
            displayPath = "$EXPORT_FOLDER$fileName",
            fileName = fileName,
            sizeBytes = file.length(),
            imageCount = 1,
            recordCount = snapshot.lines.size,
        )
    }

    private fun querySize(activity: Activity, uri: Uri): Long {
        return activity.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else 0L
            } ?: 0L
    }

    fun share(activity: Activity, uri: Uri) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = android.content.ClipData.newRawUri("ocr_debug", uri)
        }
        activity.startActivity(Intent.createChooser(send, "Chia sẻ ảnh OCR"))
    }

    fun decodeForSnapshot(activity: Activity, imageUri: Uri, snapshot: OcrDebugSnapshot): Bitmap? {
        val raw = decodeScaledToMaxSide(activity, imageUri, maxSide = 4000) ?: return null
        if (raw.width == snapshot.previewWidthPx && raw.height == snapshot.previewHeightPx) return raw
        return try {
            val scaled = Bitmap.createScaledBitmap(
                raw,
                snapshot.previewWidthPx,
                snapshot.previewHeightPx,
                true,
            )
            if (scaled !== raw && !raw.isRecycled) raw.recycle()
            scaled
        } catch (_: Exception) {
            if (!raw.isRecycled) raw.recycle()
            null
        }
    }

    fun decodeScaledToMaxSide(activity: Activity, imageUri: Uri, maxSide: Int = 2400): Bitmap? {
        return try {
            val (origW, origH) = activity.contentResolver.openInputStream(imageUri)?.use { stream ->
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(stream, null, o)
                o.outWidth to o.outHeight
            } ?: return null
            if (origW <= 0 || origH <= 0) return null
            var sample = 1
            while (max(origW, origH) / sample > maxSide) sample *= 2
            activity.contentResolver.openInputStream(imageUri)?.use { stream ->
                val opts = BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1) }
                BitmapFactory.decodeStream(stream, null, opts)
            }
        } catch (_: Exception) {
            null
        }
    }
}

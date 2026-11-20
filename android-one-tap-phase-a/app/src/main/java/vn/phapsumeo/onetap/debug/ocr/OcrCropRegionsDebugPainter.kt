package vn.phapsumeo.onetap.debug.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.util.Log
import kotlin.math.max
import kotlin.math.min

/**
 * Vẽ khung nhìn thấy được cho các vùng cắt OCR (thumb / title / price) lên ảnh chụp full màn hình.
 * THUMB = cam, TITLE = đỏ, PRICE = lục.
 */
object OcrCropRegionsDebugPainter {

    private const val LOG_TAG = "PSM_OCR_CROP_RECT"

    private val THUMB_COLOR = 0xFFFF9800.toInt()
    private val TITLE_COLOR = 0xFFE53935.toInt()
    private val PRICE_COLOR = 0xFF43A047.toInt()
    private val LABEL_BG = 0xF0FFFFFF.toInt()

    fun logCropCoordinates(
        captureW: Int,
        captureH: Int,
        thumbnailRect: Rect?,
        titleRect: Rect?,
        priceRect: Rect?,
    ) {
        Log.d(
            LOG_TAG,
            "capture=${captureW}x${captureH} " +
                "THUMB=${rectToLog(thumbnailRect)} " +
                "TITLE=${rectToLog(titleRect)} " +
                "PRICE=${rectToLog(priceRect)}",
        )
    }

    private fun rectToLog(r: Rect?): String =
        if (r == null || r.width() <= 0 || r.height() <= 0) "null"
        else "(${r.left},${r.top})-(${r.right},${r.bottom}) w=${r.width()} h=${r.height()}"

    fun draw(
        fullScreen: Bitmap,
        thumbnailRect: Rect?,
        titleRect: Rect?,
        priceRect: Rect?,
    ): Bitmap {
        val out = fullScreen.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val minSide = min(fullScreen.width, fullScreen.height).coerceAtLeast(1)
        val stroke = (minSide / 55f).coerceIn(10f, 22f)
        val textPx = (minSide * 0.034f).coerceIn(14f, 28f)

        fun drawOne(r: Rect?, fillColor: Int, label: String) {
            if (r == null || r.width() < 4 || r.height() < 4) return
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                this.color = fillColor and 0x00FFFFFF or (0x22 shl 24)
            }
            canvas.drawRect(r, fill)

            val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = stroke
                this.color = fillColor
                strokeJoin = Paint.Join.ROUND
                strokeCap = Paint.Cap.ROUND
            }
            canvas.drawRect(r, boxPaint)

            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = fillColor
                textSize = textPx
                typeface = Typeface.DEFAULT_BOLD
                setShadowLayer(4f, 1f, 1f, 0xFF000000.toInt())
            }
            val pad = max(6f, stroke * 0.75f)
            val fm = textPaint.fontMetrics
            val textH = fm.descent - fm.ascent
            val tw = textPaint.measureText(label)
            val boxLeft = r.left.toFloat() + pad
            val boxTop = r.top + pad + textH
            val bgLeft = boxLeft - pad * 0.5f
            val bgTop = boxTop - textH - pad * 0.5f
            val bgRight = bgLeft + tw + pad * 1.5f
            val bgBottom = boxTop + pad * 0.5f
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = LABEL_BG }
            canvas.drawRect(bgLeft, bgTop, bgRight, bgBottom, bgPaint)
            canvas.drawText(label, boxLeft, boxTop - fm.descent, textPaint)
        }

        drawOne(thumbnailRect, THUMB_COLOR, "THUMB")
        drawOne(titleRect, TITLE_COLOR, "TITLE")
        drawOne(priceRect, PRICE_COLOR, "PRICE")
        return out
    }
}

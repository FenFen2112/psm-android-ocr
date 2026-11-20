package vn.phapsumeo.onetap.ocr.preprocess

import android.graphics.Rect
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Maps OCR bounding boxes produced on the *processed* bitmap back into the original bitmap coords.
 */
data class OcrCoordMapping(
    val originalWidthPx: Int,
    val originalHeightPx: Int,
    val scaleX: Double,
    val scaleY: Double,
    /**
     * Crop rectangle in the *scaled* bitmap coordinate system (before crop).
     * The processed output is a crop of the scaled bitmap.
     */
    val cropLeftPxInScaled: Int,
    val cropTopPxInScaled: Int,
    val cropWidthPx: Int,
    val cropHeightPx: Int,
) {
    fun mapRectToOriginal(rectInProcessed: Rect): Rect {
        // rectInProcessed is relative to the cropped output bitmap.
        val leftScaled = rectInProcessed.left + cropLeftPxInScaled
        val topScaled = rectInProcessed.top + cropTopPxInScaled
        val rightScaled = rectInProcessed.right + cropLeftPxInScaled
        val bottomScaled = rectInProcessed.bottom + cropTopPxInScaled

        fun mapX(x: Int): Int =
            (x / scaleX).roundToInt().coerceIn(0, originalWidthPx)

        fun mapY(y: Int): Int =
            (y / scaleY).roundToInt().coerceIn(0, originalHeightPx)

        val l = mapX(leftScaled)
        val t = mapY(topScaled)
        val r = mapX(rightScaled)
        val b = mapY(bottomScaled)

        return Rect(l, t, r, b)
    }
}


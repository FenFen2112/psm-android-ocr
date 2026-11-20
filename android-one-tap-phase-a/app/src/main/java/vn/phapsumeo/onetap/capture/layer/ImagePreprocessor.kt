package vn.phapsumeo.onetap.capture.layer

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.DisplayMetrics
import android.util.Log
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

/**
 * Tiền xử lý ảnh chụp đơn: thẻ đơn + vùng crop OCR.
 * Tọa độ vùng theo **tỉ lệ trong thẻ** (card `cw`×`ch`) + neo **thumbnail** phát hiện pixel — không dùng rect pixel cố định trên màn hình.
 */
fun interface ImagePreprocessor {
    fun preprocess(fullBitmap: Bitmap): PreprocessResult
}

data class PreprocessResult(
    val productBitmap: Bitmap,
    val cropRectInCapture: Rect?,
    val thumbnailCropRectInCapture: Rect? = null,
    val titleBitmap: Bitmap? = null,
    val titleCropRectInCapture: Rect? = null,
    val titleTextOcrBitmap: Bitmap? = null,
    val titleTextOcrCropRectInCapture: Rect? = null,
    val priceBitmap: Bitmap? = null,
    val priceCropRectInCapture: Rect? = null,
    val metaBitmap: Bitmap? = null,
    val metaCropRectInCapture: Rect? = null,
)

class OrderCardCropPreprocessor : ImagePreprocessor {

    override fun preprocess(fullBitmap: Bitmap): PreprocessResult {
        val fw = fullBitmap.width
        val fh = fullBitmap.height
        if (fw < 16 || fh < 16) {
            return PreprocessResult(fullBitmap, null)
        }
        val inner = orderCardRectInner(fw, fh)
        val padded = ThumbnailCropDetector.inflateClamped(inner, CARD_PAD_PX, fw, fh)
        val cw = padded.width()
        val ch = padded.height()
        if (cw < 8 || ch < 8) {
            return PreprocessResult(fullBitmap, null)
        }
        return try {
            val cardBmp = Bitmap.createBitmap(fullBitmap, padded.left, padded.top, cw, ch)

            val gapAfterThumbFr = gapAfterThumbFrac(fullBitmap, cw)
            val reserveRightFr = reserveRightFrac(fullBitmap, cw)
            val gapBelowThumbFr = gapBelowThumbFrac(fullBitmap, ch)
            val insetXFr = horizontalInsetFrac(cw)

            val visualThumb = ThumbnailCropDetector.findBestCropRect(
                cardBmp,
                skipTopFraction = THUMB_SEARCH_SKIP_TOP_FRAC,
                searchBottomFraction = THUMB_SEARCH_BOTTOM_FRAC,
            )
            val thumbLocalRaw = visualThumb?.rect
                ?: ThumbnailCropDetector.fallbackCropRect(cw, ch, topFrac = THUMB_FALLBACK_TOP_FRAC)
            val thumbLocal = Rect(
                thumbLocalRaw.left.coerceIn(0, cw - 2),
                thumbLocalRaw.top.coerceIn(0, ch - 2),
                thumbLocalRaw.right.coerceIn(thumbLocalRaw.left + 8, cw),
                thumbLocalRaw.bottom.coerceIn(thumbLocalRaw.top + 8, ch),
            )
            val thumbCapture = intersectRect(
                Rect(
                    padded.left + thumbLocal.left,
                    padded.top + thumbLocal.top,
                    padded.left + thumbLocal.right,
                    padded.top + thumbLocal.bottom,
                ),
                fw,
                fh,
            ) ?: intersectRect(
                fallbackThumbRectInCapture(padded, cw, ch, insetXFr),
                fw,
                fh,
            ) ?: intersectRect(
                Rect(
                    padded.left + (cw * insetXFr).toInt(),
                    padded.top + (ch * THUMB_FALLBACK_TOP_FRAC).toInt(),
                    padded.left + (cw * (insetXFr + THUMB_FALLBACK_SIDE_FRAC)).toInt().coerceAtMost(padded.left + cw - 4),
                    padded.top + (ch * (THUMB_FALLBACK_TOP_FRAC + THUMB_FALLBACK_SIDE_FRAC)).toInt()
                        .coerceAtMost(padded.top + ch - 4),
                ),
                fw,
                fh,
            ) ?: run {
                Log.d(TAG, "No thumbCapture - preprocess null")
                if (!cardBmp.isRecycled) cardBmp.recycle()
                return PreprocessResult(fullBitmap, null)
            }

            // Cột tên bên phải ảnh, đủ cao để có cả hai món, không chỉ món đầu.
            val titleRect = Rect(
                padded.left + thumbLocal.right + dpPx(fullBitmap, 6f),
                padded.top + (ch * 0.12f).toInt(),
                padded.right - dpPx(fullBitmap, 8f),
                padded.top + (ch * 0.70f).toInt(),
            ).let {
                val clamped = intersectRect(it, fw, fh)
                Log.d(TAG, "titleRect raw: $it, clamped: $clamped")
                clamped
            }

            // 实付款 nằm ngay dưới các ảnh món, full ngang để còn nhãn bên trái.
            val productRects = ThumbnailCropDetector.findStackedProductRects(cardBmp)
            val feeTop = (productRects.firstOrNull()?.bottom ?: (ch * 0.30f).toInt())
                .coerceIn((ch * 0.16f).toInt(), (ch * 0.62f).toInt())
            val feeBottom = (feeTop + (ch * 0.42f).toInt())
                .coerceAtMost(padded.height() - 2)
                .coerceAtLeast(feeTop + dpPx(fullBitmap, 72f))
            val priceRect = Rect(
                padded.left + dpPx(fullBitmap, 6f),
                padded.top + feeTop,
                padded.right - dpPx(fullBitmap, 6f),
                padded.top + feeBottom,
            ).let {
                val clamped = intersectRect(it, fw, fh)
                Log.d(TAG, "priceRect raw: $it, clamped: $clamped")
                clamped
            }

            val titleBmp = titleRect?.let { safeCrop(fullBitmap, it) }
            val priceBmp = priceRect?.let { safeCrop(fullBitmap, it) }

            // META: Removed as per requirement
            val metaRect: Rect? = null
            val metaBmp: Bitmap? = null

            Log.d(
                TAG,
                JSONObject().apply {
                    put("sessionId", "418c16")
                    put("location", "OrderCardCropPreprocessor.preprocess")
                    put("message", "card_frac_thumb_anchor")
                    put("cardW", cw)
                    put("cardH", ch)
                    put("gapAfterThumbFr", gapAfterThumbFr.toDouble())
                    put("reserveRightFr", reserveRightFr.toDouble())
                    put("priceBandHFr", PRICE_TEXT_BAND_HEIGHT_FRAC.toDouble())
                    put("hasTitle", titleBmp != null)
                    put("hasPrice", priceBmp != null)
                }.toString(),
            )

            PreprocessResult(
                productBitmap = cardBmp,
                cropRectInCapture = padded,
                thumbnailCropRectInCapture = thumbCapture,
                titleBitmap = titleBmp,
                titleCropRectInCapture = titleRect,
                titleTextOcrBitmap = null,
                titleTextOcrCropRectInCapture = null,
                priceBitmap = priceBmp,
                priceCropRectInCapture = priceRect,
                metaBitmap = null,
                metaCropRectInCapture = null,
            )
        } catch (e: Exception) {
            Log.w(TAG, "crop_failed: ${e.message}")
            PreprocessResult(fullBitmap, null)
        }
    }

    private fun orderCardRectInner(fw: Int, fh: Int): Rect {
        val marginH = max(8, min(fw, fh) / 48)
        val top = (fh * ORDER_CARD_TOP_FRAC).toInt().coerceAtLeast(marginH)
        val bottom = (fh * ORDER_CARD_BOTTOM_FRAC).toInt().coerceAtMost(fh - marginH)
        return Rect(marginH, top, fw - marginH, bottom)
    }

    /** Inset ngang trong thẻ (tỉ lệ cw), thay cho margin pixel cố định. */
    private fun horizontalInsetFrac(cw: Int): Float =
        (6.coerceAtMost(cw / 24).coerceAtLeast(4) / cw.toFloat()).coerceIn(0.008f, 0.06f)

    private fun gapAfterThumbFrac(bitmap: Bitmap, cw: Int): Float =
        (dpPx(bitmap, 12f).coerceIn(8, 40) / cw.toFloat()).coerceIn(0.012f, 0.12f)

    private fun reserveRightFrac(bitmap: Bitmap, cw: Int): Float =
        (dpPx(bitmap, 120f).coerceIn(72, 280) / cw.toFloat()).coerceIn(0.15f, 0.42f)

    private fun gapBelowThumbFrac(bitmap: Bitmap, ch: Int): Float =
        (dpPx(bitmap, 8f).coerceIn(6, 20) / ch.toFloat()).coerceIn(0.008f, 0.045f)

    private fun fallbackThumbRectInCapture(padded: Rect, cw: Int, ch: Int, insetXFr: Float): Rect {
        val side = (min(cw, ch) * THUMB_FALLBACK_SIDE_FRAC).coerceIn(40f, cw * 0.42f).toInt()
        val left = padded.left + (cw * insetXFr).toInt()
        val top = padded.top + (ch * THUMB_FALLBACK_TOP_FRAC).toInt()
        return Rect(left, top, (left + side).coerceAtMost(padded.right - 4), (top + side).coerceAtMost(padded.bottom - 4))
    }

    private fun rectFromCardFractions(
        padded: Rect,
        cw: Int,
        ch: Int,
        fw: Int,
        fh: Int,
        leftFr: Float,
        topFr: Float,
        rightFr: Float,
        bottomFr: Float,
    ): Rect? {
        val l = (leftFr.coerceIn(0f, 1f) * cw).toInt() + padded.left
        val t = (topFr.coerceIn(0f, 1f) * ch).toInt() + padded.top
        val r = (rightFr.coerceIn(0f, 1f) * cw).toInt() + padded.left
        val b = (bottomFr.coerceIn(0f, 1f) * ch).toInt() + padded.top
        return intersectRect(Rect(l, t, r, b), fw, fh)
    }

    private fun intersectRect(r: Rect, fw: Int, fh: Int): Rect? {
        val l = r.left.coerceIn(0, fw - 1)
        val t = r.top.coerceIn(0, fh - 1)
        val rt = r.right.coerceIn(l + 8, fw)
        val b = r.bottom.coerceIn(t + 8, fh)
        if (rt - l < 12 || b - t < 12) return null
        return Rect(l, t, rt, b)
    }

    private fun safeCrop(src: Bitmap, r: Rect): Bitmap? =
        try {
            Bitmap.createBitmap(src, r.left, r.top, r.width(), r.height())
        } catch (_: Exception) {
            null
        }

    private fun dpPx(bitmap: Bitmap, dp: Float): Int {
        val d = bitmap.density.takeIf { it > 0 } ?: DisplayMetrics.DENSITY_DEVICE_STABLE
        return (dp * d / DisplayMetrics.DENSITY_DEFAULT).toInt().coerceAtLeast(1)
    }

    companion object {
        private const val TAG = "PSM_CARD_CROP"
        private const val CARD_PAD_PX = 22

        /** Thẻ đơn theo tỉ lệ chiều cao màn hình (không phải pixel cố định). */
        private const val ORDER_CARD_TOP_FRAC = 0.045f
        private const val ORDER_CARD_BOTTOM_FRAC = 0.90f

        private const val THUMB_SEARCH_SKIP_TOP_FRAC = 0.11f
        private const val THUMB_SEARCH_BOTTOM_FRAC = 0.50f
        private const val THUMB_REJECT_IF_BOTTOM_BELOW_FRAC = 0.24f
        private const val THUMB_FALLBACK_TOP_FRAC = 0.20f
        private const val THUMB_FALLBACK_SIDE_FRAC = 0.30f

        /** Chiều cao vùng chỉ chứa block giá (成交价/商品总价/运费), tỉ lệ trên chiều cao thẻ. */
        private const val PRICE_TEXT_BAND_HEIGHT_FRAC = 0.42f
        private const val PRICE_BAND_MAX_BOTTOM_FRAC = 0.96f
    }
}

package vn.phapsumeo.onetap.capture.layer

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Tìm thumbnail sản phẩm trên ảnh chụp đơn Taobao/Xianyu bằng tín hiệu thị giác
 * (biến thiên độ sáng + mật độ gradient), **không** dùng khung chữ OCR.
 *
 * Heuristic: ảnh sản phẩm thường nằm trái thẻ, gần vuông, đủ lớn; vùng chữ giá
 * (成交价/商品总价) thường có gradient rất cao trên diện tích nhỏ.
 */
object ThumbnailCropDetector {

    private const val ANALYSIS_MAX_SIDE = 480

    /** Cạnh tối thiểu trên ảnh gốc (px). */
    private const val MIN_SIDE_FULL_PX = 60

    /** Tỉ lệ cạnh gần 1:1 (w/h). */
    private const val ASPECT_MIN = 0.88f
    private const val ASPECT_MAX = 1.12f

    private fun luma(pixel: Int): Float {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return 0.299f * r + 0.587f * g + 0.114f * b
    }

    private fun pixel(b: Bitmap, x: Int, y: Int): Int =
        b.getPixel(x.coerceIn(0, b.width - 1), y.coerceIn(0, b.height - 1))

    /**
     * Khung tốt nhất trên ảnh gốc, hoặc null nếu không đạt ngưỡng (gọi fallback).
     *
     * @param skipTopFraction phần đỉnh **bỏ qua** khi dò (0..0.35). Trên **thẻ đơn** (timeline/địa chỉ
     *   phía trên) dùng ~0.11f để không neo TITLE vào logistics.
     * @param searchBottomFraction cạnh dưới cửa sổ dò (theo chiều cao ảnh phân tích), ví dụ 0.50f.
     */
    /** Các ảnh sản phẩm xếp dọc trên một đơn, từ trên xuống. */
    fun findStackedProductRects(source: Bitmap): List<Rect> {
        if (source.width < MIN_SIDE_FULL_PX || source.height < MIN_SIDE_FULL_PX) return emptyList()
        val found = mutableListOf<Rect>()
        var skip = 0.04f
        var guard = 0
        while (skip < 0.88f && found.size < 4 && guard < 6) {
            guard += 1
            val bottom = (skip + 0.40f).coerceAtMost(0.94f)
            if (bottom - skip < 0.12f) break
            val hit = findBestCropRect(source, skipTopFraction = skip, searchBottomFraction = bottom)
            if (hit == null) {
                skip = bottom
                continue
            }
            val rect = hit.rect
            if (found.any { overlaps(it, rect) }) {
                skip = (rect.bottom.toFloat() / source.height + 0.01f).coerceAtLeast(bottom)
                continue
            }
            found += rect
            val next = rect.bottom.toFloat() / source.height + 0.01f
            if (next <= skip) break
            skip = next
        }
        return found
    }

    private fun overlaps(a: Rect, b: Rect): Boolean {
        val dx = minOf(a.right, b.right) - maxOf(a.left, b.left)
        val dy = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        if (dx <= 0 || dy <= 0) return false
        val inter = dx * dy
        val smaller = minOf(a.width() * a.height(), b.width() * b.height()).coerceAtLeast(1)
        return inter.toFloat() / smaller > 0.45f
    }

    fun findBestCropRect(
        source: Bitmap,
        skipTopFraction: Float = 0.04f,
        searchBottomFraction: Float = 0.56f,
    ): DetectionResult? {
        val fw = source.width
        val fh = source.height
        if (fw < MIN_SIDE_FULL_PX || fh < MIN_SIDE_FULL_PX) return null

        val scaleDown = min(ANALYSIS_MAX_SIDE.toFloat() / max(fw, fh), 1f)
        val sw = max(1, (fw * scaleDown).toInt())
        val sh = max(1, (fh * scaleDown).toInt())
        val small = if (scaleDown >= 0.999f) source else Bitmap.createScaledBitmap(source, sw, sh, true)
        val recycleSmall = small !== source
        return try {
            val cand = findBestInSmall(
                small,
                fw,
                fh,
                skipTopFraction.coerceIn(0f, 0.70f),
                searchBottomFraction.coerceIn(0.18f, 0.90f),
            )
            if (cand == null || cand.score < scoreThreshold(sw, sh)) null
            else {
                val fullRect = mapSmallToFull(cand.rect, sw, sh, fw, fh)
                DetectionResult(rect = fullRect, score = cand.score, usedVisual = true)
            }
        } finally {
            if (recycleSmall) small.recycle()
        }
    }

    data class DetectionResult(
        val rect: Rect,
        val score: Float,
        val usedVisual: Boolean,
    )

    private data class ScoredRect(val rect: Rect, val score: Float)

    private fun scoreThreshold(sw: Int, sh: Int): Float {
        val base = 95f
        val q = min(sw, sh) / 420f
        return base * q.coerceIn(0.55f, 1.35f)
    }

    private fun findBestInSmall(
        s: Bitmap,
        fullW: Int,
        @Suppress("UNUSED_PARAMETER") fullH: Int,
        skipTopFraction: Float,
        searchBottomFraction: Float,
    ): ScoredRect? {
        val iw = s.width
        val ih = s.height
        val minSide = max(12, (MIN_SIDE_FULL_PX * iw) / fullW)
        val maxSideByImage = min((iw * 0.46f).toInt(), (ih * 0.42f).toInt())
        val maxSide = max(minSide + 8, maxSideByImage)
        if (minSide > maxSide) return null
        val xLimit = (iw * 0.40f).toInt().coerceAtLeast(minSide + 6)
        val yTop = (ih * skipTopFraction).toInt().coerceAtLeast(0)
        val yBottomLimit = (ih * searchBottomFraction).toInt().coerceAtLeast(yTop + minSide + 12)

        var best: ScoredRect? = null
        var side = minSide
        while (side <= maxSide) {
            val stepXY = max(5, side / 14)
            val stepSide = max(4, side / 16)
            var y = yTop
            while (y + side <= yBottomLimit && y + side <= ih) {
                var x = 0
                while (x + side <= xLimit && x + side <= iw) {
                    val r = Rect(x, y, x + side, y + side)
                    val sc = scorePatch(s, r) * verticalThumbLikelihood(r, ih)
                    if (best == null || sc > best.score) best = ScoredRect(r, sc)
                    x += stepXY
                }
                y += stepXY
            }
            // thêm biến thể hơi dọc (một số layout ảnh không vuông hoàn toàn)
            val h2 = (side / ASPECT_MAX).toInt().coerceAtLeast(minSide)
            val w2 = (side * ASPECT_MAX).toInt().coerceAtMost(maxSide)
            if (w2 > minSide && h2 > minSide && w2 <= maxSide && abs(w2.toFloat() / h2 - 1f) <= 0.15f) {
                var y2 = yTop
                while (y2 + h2 <= yBottomLimit && y2 + h2 <= ih) {
                    var x2 = 0
                    while (x2 + w2 <= xLimit && x2 + w2 <= iw) {
                        val r2 = Rect(x2, y2, x2 + w2, y2 + h2)
                        val ar = r2.width().toFloat() / r2.height()
                        if (ar in ASPECT_MIN..ASPECT_MAX) {
                            val sc2 = scorePatch(s, r2) * verticalThumbLikelihood(r2, ih)
                            if (best == null || sc2 > best.score) best = ScoredRect(r2, sc2)
                        }
                        x2 += stepXY
                    }
                    y2 += stepXY
                }
            }
            side += stepSide
        }
        return best
    }

    /** Ưu tiên patch nằm dưới band timeline/địa chỉ đỉnh thẻ (ảnh SP thật thường 12%–42% chiều cao thẻ). */
    private fun verticalThumbLikelihood(r: Rect, ih: Int): Float {
        if (ih <= 1) return 1f
        val cy = (r.top + r.bottom) / 2f / ih
        return when {
            cy < 0.07f -> 0.42f
            cy < 0.12f -> 0.55f
            cy < 0.20f -> 0.40f
            cy <= 0.46f -> 1f
            else -> 0.92f
        }
    }

    /**
     * Điểm cao hơn khi: phương sai độ sáng đủ (ảnh thật), gradient trung bình không cực đoan (tránh vùng chữ dày).
     */
    private fun scorePatch(b: Bitmap, r: Rect): Float {
        val st = max(2, min(r.width(), r.height()) / 26)
        var n = 0
        var sum = 0.0
        var sumSq = 0.0
        var gradSum = 0.0
        var chromaSum = 0.0
        var y = r.top
        while (y < r.bottom) {
            var x = r.left
            while (x < r.right) {
                val p = pixel(b, x, y)
                val lum = luma(p)
                sum += lum
                sumSq += lum * lum
                val lx = luma(pixel(b, x + 1, y))
                val ly = luma(pixel(b, x, y + 1))
                gradSum += abs(lx - lum) + abs(ly - lum)
                val rr = (p shr 16) and 0xFF
                val gg = (p shr 8) and 0xFF
                val bb = p and 0xFF
                chromaSum += abs(rr - gg) + abs(gg - bb)
                n++
                x += st
            }
            y += st
        }
        if (n == 0) return 0f
        val mean = sum / n
        val variance = (sumSq / n - mean * mean).toFloat().coerceAtLeast(0f)
        val gradMean = (gradSum / n).toFloat().coerceAtLeast(0.01f)
        val chromaMean = (chromaSum / n).toFloat()
        // Nền phẳng, hoặc chữ đen trên nền trắng (badge / trạng thái) — không phải ảnh sản phẩm.
        if (variance < 35f) return 0f
        if (gradMean > 18f && chromaMean < 14f && mean > 185.0) return 0f
        val gradientPenalty = sqrt(gradMean)
        val texture = variance / (6f + gradientPenalty)
        val colorBonus = (chromaMean / 18f).coerceIn(0.55f, 1.45f)
        val cx = (r.left + r.right) / 2f
        val leftBias = 1f + (0.38f - cx / b.width).coerceIn(0f, 0.38f) * 0.22f
        val sizeBonus = sqrt((r.width() * r.height()).toFloat()) / 90f
        return texture * leftBias * sizeBonus * colorBonus
    }

    private fun mapSmallToFull(r: Rect, sw: Int, sh: Int, fw: Int, fh: Int): Rect {
        val l = (r.left * fw.toLong() / sw).toInt().coerceIn(0, fw - 1)
        val t = (r.top * fh.toLong() / sh).toInt().coerceIn(0, fh - 1)
        val rr = (r.right * fw.toLong() / sw).toInt().coerceIn(l + MIN_SIDE_FULL_PX / 4, fw)
        val bb = (r.bottom * fh.toLong() / sh).toInt().coerceIn(t + MIN_SIDE_FULL_PX / 4, fh)
        return Rect(l, t, rr, bb)
    }

    /** Padding 12–24px: dùng giá trị giữa 18px. */
    fun inflateClamped(r: Rect, pad: Int, fw: Int, fh: Int): Rect =
        Rect(
            (r.left - pad).coerceAtLeast(0),
            (r.top - pad).coerceAtLeast(0),
            (r.right + pad).coerceAtMost(fw),
            (r.bottom + pad).coerceAtMost(fh),
        )

    /**
     * Fallback hình học khi không phát hiện được khối ảnh (vẫn không dùng OCR).
     * @param topFrac đỉnh ô vuông theo tỉ lệ chiều cao thẻ; trên thẻ có timeline nên ~0.12f.
     */
    fun fallbackCropRect(fw: Int, fh: Int, topFrac: Float = 0.085f): Rect {
        val side = minOf(
            (fw * 0.32f).toInt(),
            (fh * 0.28f).toInt(),
            fw - 4,
            fh - 4,
        ).coerceAtLeast(MIN_SIDE_FULL_PX)
        val left = (fw * 0.02f).toInt().coerceAtLeast(0)
        val top = (fh * topFrac.coerceIn(0.04f, 0.28f)).toInt().coerceAtLeast(0)
        val right = (left + side).coerceAtMost(fw)
        val bottom = (top + side).coerceAtMost(fh)
        return Rect(left, top, right, bottom)
    }
}

package vn.phapsumeo.onetap.extraction.grouping

import android.graphics.Rect

internal object GeometryUtils {

    fun horizontalGap(a: Rect, b: Rect): Float {
        return if (a.right < b.left) (b.left - a.right).toFloat()
        else if (b.right < a.left) (a.left - b.right).toFloat()
        else 0f
    }

    fun verticalGap(a: Rect, b: Rect): Float {
        return if (a.bottom < b.top) (b.top - a.bottom).toFloat()
        else if (b.bottom < a.top) (a.top - b.bottom).toFloat()
        else 0f
    }

    /** Intersection width / min(width) — language-agnostic “same column” signal. */
    fun horizontalOverlapRatio(a: Rect, b: Rect): Float {
        val interLeft = maxOf(a.left, b.left)
        val interRight = minOf(a.right, b.right)
        val w = (interRight - interLeft).coerceAtLeast(0)
        if (w == 0) return 0f
        val minW = minOf(a.width(), b.width()).coerceAtLeast(1)
        return (w / minW.toFloat()).coerceIn(0f, 1f)
    }

    fun verticalOverlapRatio(a: Rect, b: Rect): Float {
        val interTop = maxOf(a.top, b.top)
        val interBottom = minOf(a.bottom, b.bottom)
        val h = (interBottom - interTop).coerceAtLeast(0)
        if (h == 0) return 0f
        val minH = minOf(a.height(), b.height()).coerceAtLeast(1)
        return (h / minH.toFloat()).coerceIn(0f, 1f)
    }

    fun intersectionArea(a: Rect, b: Rect): Int {
        val interLeft = maxOf(a.left, b.left)
        val interRight = minOf(a.right, b.right)
        val interTop = maxOf(a.top, b.top)
        val interBottom = minOf(a.bottom, b.bottom)
        val w = (interRight - interLeft).coerceAtLeast(0)
        val h = (interBottom - interTop).coerceAtLeast(0)
        return w * h
    }

    fun union(a: Rect, b: Rect): Rect {
        return Rect(
            minOf(a.left, b.left),
            minOf(a.top, b.top),
            maxOf(a.right, b.right),
            maxOf(a.bottom, b.bottom),
        )
    }

    fun unionAll(rects: List<Rect>): Rect? {
        if (rects.isEmpty()) return null
        return rects.drop(1).fold(rects.first()) { acc, r -> union(acc, r) }
    }

    fun centerDistance(a: Rect, b: Rect): Float {
        val cxA = (a.left + a.right) / 2f
        val cyA = (a.top + a.bottom) / 2f
        val cxB = (b.left + b.right) / 2f
        val cyB = (b.top + b.bottom) / 2f
        val dx = cxA - cxB
        val dy = cyA - cyB
        return kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
    }
}

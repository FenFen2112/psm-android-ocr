package vn.phapsumeo.onetap.extraction.layout

import android.graphics.Rect
import vn.phapsumeo.onetap.extraction.api.LayoutAnalyzer
import vn.phapsumeo.onetap.extraction.core.LayoutHints
import vn.phapsumeo.onetap.extraction.core.OcrDocument
import vn.phapsumeo.onetap.extraction.core.OcrLine
import vn.phapsumeo.onetap.extraction.profile.xianyu.XianyuLayoutKeys
import kotlin.math.max
import kotlin.math.min

/**
 * Derives a **content frame** (union of OCR boxes) and **median line height** so entity rules
 * can use positions relative to OCR content instead of raw full-screen ratios.
 */
class XianyuLayoutAnalyzer : LayoutAnalyzer {

    override fun analyze(document: OcrDocument): LayoutHints {
        val sw = document.screen.widthPx.coerceAtLeast(1)
        val sh = document.screen.heightPx.coerceAtLeast(1)
        val lines = document.allLines()
        val content = contentFramePx(lines, sw, sh)
        val medianH = medianLineHeightPx(lines)

        val nl = content.left.toFloat() / sw
        val nt = content.top.toFloat() / sh
        val nr = content.right.toFloat() / sw
        val nb = content.bottom.toFloat() / sh

        return LayoutHints(
            normalizedRegions = mapOf(
                XianyuLayoutKeys.REGION_CONTENT_FRAME to LayoutHints.NormalizedRect(
                    left = nl.coerceIn(0f, 1f),
                    top = nt.coerceIn(0f, 1f),
                    right = nr.coerceIn(0f, 1f),
                    bottom = nb.coerceIn(0f, 1f),
                ),
            ),
            metrics = buildMap {
                put(XianyuLayoutKeys.METRIC_MEDIAN_LINE_HEIGHT_PX, medianH)
                put(XianyuLayoutKeys.METRIC_CONTENT_WIDTH_PX, content.width().toFloat().coerceAtLeast(1f))
                put(XianyuLayoutKeys.METRIC_CONTENT_HEIGHT_PX, content.height().toFloat().coerceAtLeast(1f))
            },
        )
    }

    private fun contentFramePx(lines: List<OcrLine>, screenW: Int, screenH: Int): Rect {
        val boxes = lines.mapNotNull { it.boundingBox }
        if (boxes.isEmpty()) {
            return Rect(0, 0, screenW, screenH)
        }
        var l = Int.MAX_VALUE
        var t = Int.MAX_VALUE
        var r = Int.MIN_VALUE
        var b = Int.MIN_VALUE
        for (box in boxes) {
            l = min(l, box.left)
            t = min(t, box.top)
            r = max(r, box.right)
            b = max(b, box.bottom)
        }
        return Rect(
            l.coerceIn(0, screenW),
            t.coerceIn(0, screenH),
            r.coerceIn(0, screenW),
            b.coerceIn(0, screenH),
        ).let { frame ->
            if (frame.width() < 1 || frame.height() < 1) {
                Rect(0, 0, screenW, screenH)
            } else {
                frame
            }
        }
    }

    private fun medianLineHeightPx(lines: List<OcrLine>): Float {
        val heights = lines.mapNotNull { it.boundingBox?.height() }.filter { it > 0 }.sorted()
        if (heights.isEmpty()) return 48f
        val mid = heights.size / 2
        return if (heights.size % 2 == 1) {
            heights[mid].toFloat()
        } else {
            ((heights[mid - 1] + heights[mid]) / 2f)
        }
    }
}

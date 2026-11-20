package vn.phapsumeo.onetap.debug.ocr

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.max
import kotlin.math.min

internal object OcrDebugCanvasRenderer {

    private val tmpTextBounds = Rect()

    fun colorForEntity(type: OcrDebugEntityType): Int = when (type) {
        OcrDebugEntityType.UNKNOWN -> 0xCCAAAAAA.toInt()
        OcrDebugEntityType.ORDER_ID -> 0xFF7E57C2.toInt()
        OcrDebugEntityType.TRACKING -> 0xFF00BCD4.toInt()
        OcrDebugEntityType.TITLE -> 0xFFFF9800.toInt()
        OcrDebugEntityType.PRICE_TONG -> 0xFF4CAF50.toInt()
        OcrDebugEntityType.PRICE_HANG -> 0xFF8BC34A.toInt()
        OcrDebugEntityType.PRICE_SHIP -> 0xFFC8E6C9.toInt()
        OcrDebugEntityType.TAB -> 0xFF9C27B0.toInt()
        OcrDebugEntityType.PRICE_AMBIGUOUS -> 0xFFFFEB3B.toInt()
    }

    private val fillHeader = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99000000.toInt()
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 22f
        typeface = Typeface.MONOSPACE
    }

    private val smallText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 18f
        typeface = Typeface.MONOSPACE
    }

    private val lineStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }

    private val blockStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
        color = 0xFFE040FB.toInt()
        pathEffect = DashPathEffect(floatArrayOf(14f, 10f), 0f)
    }

    private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xDD000000.toInt()
        style = Paint.Style.FILL
    }

    private val labelText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.MONOSPACE
    }

    private val legendText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.MONOSPACE
    }

    private val legendSwatch = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val accentStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    fun prepareStrokeWidths(density: Float) {
        textPaint.textSize = 11f * density
        smallText.textSize = 9f * density
        lineStroke.strokeWidth = 2f * density.coerceAtMost(3f)
        blockStroke.strokeWidth = 3f * density.coerceAtMost(4f)
        labelText.textSize = 8.5f * density.coerceAtMost(11f)
        legendText.textSize = 8f * density.coerceAtMost(10f)
    }

    /**
     * Line boxes + grouped block outlines. Optional [drawDecorations]: entity tags, block indices, color legend.
     * Used on-screen ([drawOverlay]) and for annotated PNG export.
     */
    fun drawGeometryOnly(
        canvas: Canvas,
        snapshot: OcrDebugSnapshot,
        srcW: Int,
        srcH: Int,
        dst: RectF,
        density: Float,
        drawDecorations: Boolean = false,
    ) {
        if (srcW <= 0 || srcH <= 0) return
        prepareStrokeWidths(density)
        canvas.save()
        canvas.clipRect(dst)

        snapshot.blockBoundsPreview.forEachIndexed { idx, bb ->
            val b = bb ?: return@forEachIndexed
            mapRectF(b, srcW, srcH, dst)?.let { rf ->
                canvas.drawRect(rf, blockStroke)
                if (drawDecorations) {
                    drawBlockIndexTag(canvas, dst, rf, "#$idx", density)
                }
            }
        }

        for (lv in snapshot.lines) {
            val b = lv.boundsPreview ?: continue
            val rf = mapRectF(b, srcW, srcH, dst) ?: continue
            lineStroke.color = colorForEntity(lv.type)
            canvas.drawRect(rf, lineStroke)
        }

        if (drawDecorations) {
            for (lv in snapshot.lines) {
                if (lv.type == OcrDebugEntityType.UNKNOWN) continue
                val b = lv.boundsPreview ?: continue
                val rf = mapRectF(b, srcW, srcH, dst) ?: continue
                drawEntityLineTag(canvas, dst, rf, lv, density)
            }
            drawLegendBar(canvas, snapshot, dst, density)
        }

        canvas.restore()
    }

    private fun drawBlockIndexTag(canvas: Canvas, clip: RectF, blockRf: RectF, tag: String, density: Float) {
        val pad = 3f * density
        labelText.color = Color.WHITE
        labelText.getTextBounds(tag, 0, tag.length, tmpTextBounds)
        val boxW = tmpTextBounds.width() + pad * 2
        val boxH = tmpTextBounds.height() + pad * 2
        var left = blockRf.left + pad * 1.2f
        var top = blockRf.top + pad * 1.2f
        var right = left + boxW
        var bottom = top + boxH
        if (bottom > blockRf.bottom - 2f) {
            top = max(clip.top + 2f, blockRf.bottom - boxH - pad)
            bottom = top + boxH
        }
        if (right > clip.right - 2f) {
            left = max(clip.left + 2f, clip.right - boxW - 2f)
            right = left + boxW
        }
        canvas.drawRoundRect(RectF(left, top, right, bottom), 3f * density, 3f * density, labelBg)
        val baseline = bottom - pad - tmpTextBounds.bottom
        canvas.drawText(tag, left + pad - tmpTextBounds.left.toFloat(), baseline, labelText)
    }

    private fun drawEntityLineTag(
        canvas: Canvas,
        clip: RectF,
        lineRf: RectF,
        lv: OcrDebugLineVisual,
        density: Float,
    ) {
        val abbr = shortEntityAbbr(lv.type)
        val snippet = lv.textSnippet.replace('|', ' ').take(16)
        var tag = "$abbr|$snippet"
        val pad = 2.5f * density
        val fm = labelText.fontMetrics
        labelText.color = Color.WHITE
        while (tag.length > 4 && labelText.measureText(tag) > (clip.width() * 0.45f).coerceAtLeast(80f)) {
            tag = tag.dropLast(1)
        }
        labelText.getTextBounds(tag, 0, tag.length, tmpTextBounds)
        val boxW = tmpTextBounds.width() + pad * 2
        val boxH = tmpTextBounds.height() + pad * 2
        var baseline = lineRf.top - 4f * density - fm.descent
        var top = baseline + fm.ascent - pad
        if (top < clip.top + 4f * density) {
            baseline = lineRf.bottom + 4f * density - fm.ascent + pad * 2
            top = baseline + fm.ascent - pad
        }
        val bottom = baseline + fm.descent + pad
        var left = max(clip.left + 2f, lineRf.left)
        var right = left + boxW
        if (right > clip.right - 2f) {
            left = max(clip.left + 2f, clip.right - boxW - 2f)
            right = left + boxW
        }
        accentStroke.color = colorForEntity(lv.type)
        accentStroke.strokeWidth = 2.5f * density.coerceAtMost(3.5f)
        val stripeX = left + 2f * density
        canvas.drawLine(stripeX, top, stripeX, bottom, accentStroke)
        canvas.drawRoundRect(RectF(left, top, right, bottom), 2.5f * density, 2.5f * density, labelBg)
        canvas.drawText(tag, left + pad - tmpTextBounds.left.toFloat(), baseline, labelText)
    }

    private fun drawLegendBar(canvas: Canvas, snapshot: OcrDebugSnapshot, dst: RectF, density: Float) {
        val used = snapshot.lines.map { it.type }.toSet()
        val order = listOf(
            OcrDebugEntityType.ORDER_ID,
            OcrDebugEntityType.TRACKING,
            OcrDebugEntityType.TITLE,
            OcrDebugEntityType.PRICE_TONG,
            OcrDebugEntityType.PRICE_HANG,
            OcrDebugEntityType.PRICE_SHIP,
            OcrDebugEntityType.TAB,
            OcrDebugEntityType.PRICE_AMBIGUOUS,
            OcrDebugEntityType.UNKNOWN,
        )
        val entries = order.filter { it in used }.map { t -> t to legendName(t) }
        if (entries.isEmpty()) return

        val barH = 22f * density
        val barTop = min(dst.bottom - barH - 2f * density, dst.bottom - 3f * density)
        if (barTop <= dst.top + barH) return
        canvas.drawRect(dst.left, barTop, dst.right, dst.bottom, labelBg)

        var x = dst.left + 6f * density
        val yMid = barTop + barH / 2f
        val sq = 6f * density
        val gap = 4f * density
        for ((type, name) in entries) {
            if (x > dst.right - 24f * density) break
            legendSwatch.color = colorForEntity(type)
            canvas.drawRect(x, yMid - sq / 2f, x + sq, yMid + sq / 2f, legendSwatch)
            x += sq + gap
            legendText.color = Color.WHITE
            canvas.drawText(name, x, yMid + legendText.textSize * 0.35f, legendText)
            x += legendText.measureText(name) + 8f * density
        }
        if (x < dst.right - 4f) {
            legendText.color = 0xFFBBBBBB.toInt()
            val hint = "| blocks: magenta dash"
            canvas.drawText(hint, x, yMid + legendText.textSize * 0.35f, legendText)
        }
    }

    private fun legendName(t: OcrDebugEntityType): String = when (t) {
        OcrDebugEntityType.ORDER_ID -> "Mã đơn"
        OcrDebugEntityType.TRACKING -> "Mã vận đơn"
        OcrDebugEntityType.TITLE -> "Sản phẩm"
        OcrDebugEntityType.PRICE_TONG -> "Tổng"
        OcrDebugEntityType.PRICE_HANG -> "Giá hàng"
        OcrDebugEntityType.PRICE_SHIP -> "Phí ship"
        OcrDebugEntityType.TAB -> "TAB"
        OcrDebugEntityType.PRICE_AMBIGUOUS -> "Giá?"
        OcrDebugEntityType.UNKNOWN -> "Khác"
    }

    private fun shortEntityAbbr(t: OcrDebugEntityType): String = when (t) {
        OcrDebugEntityType.ORDER_ID -> "ORD"
        OcrDebugEntityType.TRACKING -> "TRK"
        OcrDebugEntityType.TITLE -> "TTL"
        OcrDebugEntityType.PRICE_TONG -> "TONG"
        OcrDebugEntityType.PRICE_HANG -> "HANG"
        OcrDebugEntityType.PRICE_SHIP -> "SHIP"
        OcrDebugEntityType.TAB -> "TAB"
        OcrDebugEntityType.PRICE_AMBIGUOUS -> "P?"
        OcrDebugEntityType.UNKNOWN -> "?"
    }

    /**
     * @param srcW srcH intrinsic bitmap size (preview space)
     * @param dst where the image is drawn inside the canvas (view coordinates)
     */
    fun drawOverlay(
        canvas: Canvas,
        snapshot: OcrDebugSnapshot,
        srcW: Int,
        srcH: Int,
        dst: RectF,
        density: Float,
    ) {
        if (srcW <= 0 || srcH <= 0) return
        prepareStrokeWidths(density)

        val pad = 6f * density
        val d = snapshot.draft
        val entitySummary = buildString {
            append("ĐH:")
            append(d.maDonHang.take(12))
            append("  MVD:")
            append(d.maVanChuyen.take(12))
            append("  ¥:")
            append(d.tongCn?.toString() ?: "—")
            append("  ")
            append(d.tenSanPham.take(20).replace('\n', ' '))
        }
        val headerLines = buildList {
            add("PSM OCR - Độ tin cậy ${snapshot.heuristicUiPercent}%")
            add(entitySummary.take(118))
            snapshot.confidenceSummaryLines.take(3).forEach { add(it) }
            snapshot.parserDecisions.take(2).forEach { add("» $it") }
        }
        val lineH = textPaint.textSize + 3f
        val headerH = pad * 2 + headerLines.size * lineH

        canvas.drawRect(0f, 0f, canvas.width.toFloat(), headerH, fillHeader)
        var y = pad + textPaint.textSize
        for (ln in headerLines) {
            canvas.drawText(ln.take(120), pad, y, textPaint)
            y += lineH
        }

        drawGeometryOnly(canvas, snapshot, srcW, srcH, dst, density, drawDecorations = true)
    }

    /** Footer below image for export. */
    fun drawExportFooter(
        canvas: Canvas,
        snapshot: OcrDebugSnapshot,
        topY: Float,
        _width: Float,
        density: Float,
        maxBottom: Float,
    ) {
        smallText.textSize = 10f * density
        val pad = 8f * density
        var y = topY + pad + smallText.textSize
        val lines = snapshot.parserDecisions + snapshot.confidenceDetailText.lines()
        for (raw in lines) {
            val line = raw.take(200)
            var rest = line
            while (rest.isNotEmpty()) {
                val chunk = rest.take(90)
                rest = rest.drop(90)
                canvas.drawText(chunk, pad, y, smallText)
                y += smallText.textSize + 4f
                if (y > maxBottom - pad) return
            }
        }
    }

    fun mapRectF(r: Rect, srcW: Int, srcH: Int, dst: RectF): RectF {
        val lf = dst.left + r.left * dst.width() / srcW
        val tp = dst.top + r.top * dst.height() / srcH
        val rt = dst.left + r.right * dst.width() / srcW
        val bt = dst.top + r.bottom * dst.height() / srcH
        return RectF(lf, tp, rt, bt)
    }
}

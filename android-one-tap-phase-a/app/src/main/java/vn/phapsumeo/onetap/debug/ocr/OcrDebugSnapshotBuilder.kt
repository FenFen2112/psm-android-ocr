package vn.phapsumeo.onetap.debug.ocr

import android.graphics.Rect
import vn.phapsumeo.onetap.extraction.ExtractionFacades
import vn.phapsumeo.onetap.extraction.confidence.ConfidenceEvaluationContext
import vn.phapsumeo.onetap.extraction.confidence.OrderDraftConfidenceEngine
import vn.phapsumeo.onetap.extraction.confidence.model.ConfidenceDimension
import vn.phapsumeo.onetap.extraction.core.OcrLine
import vn.phapsumeo.onetap.extraction.profile.xianyu.XianyuPatternPlugin
import vn.phapsumeo.onetap.model.OrderDraft

/**
 * Builds [OcrDebugSnapshot] in **preview bitmap** coordinates (post-crop if applicable).
 */
object OcrDebugSnapshotBuilder {

    private val confidenceEngine = OrderDraftConfidenceEngine(XianyuPatternPlugin())

    fun build(
        linesCapture: List<OcrLine>,
        captureWidth: Int,
        captureHeight: Int,
        cropInCapture: Rect?,
        draft: OrderDraft,
        parserDecisions: List<String>,
        heuristicUiPercent: Int,
    ): OcrDebugSnapshot {
        val crop = cropInCapture ?: Rect(0, 0, captureWidth, captureHeight)
        val previewW = crop.width().coerceAtLeast(1)
        val previewH = crop.height().coerceAtLeast(1)

        val linesPreview = linesCapture.mapNotNull { line ->
            val b = line.boundingBox?.toPreviewSpace(crop) ?: return@mapNotNull null
            OcrDebugLineVisual(
                boundsPreview = b,
                textSnippet = line.text.replace("\n", " ").take(48),
                type = OcrDebugClassifier.classify(line, draft),
            )
        }

        val grouped = ExtractionFacades.documentFromLinesGrouped(
            linesCapture,
            captureWidth,
            captureHeight,
        )
        val blocksPreview = grouped.blocks.map { block ->
            block.boundingBox?.toPreviewSpace(crop)
        }

        val doc = grouped.toDocument(draft.sharedImageUri)
        val conf = confidenceEngine.evaluateDetailed(
            ConfidenceEvaluationContext(draft = draft, document = doc, grouped = grouped),
        )
        val summary = buildList {
            add("Độ tin cậy=${"%.0f".format(conf.overallWeighted * 100)}%")
            conf.contributions.take(4).forEach { c ->
                add("${dimensionLabel(c.dimension)}=${"%.0f".format(c.score01 * 100)}%")
            }
        }
        val detailText = buildString {
            appendLine("Độ tin cậy tổng: ${"%.0f".format(conf.overallWeighted * 100)}%")
            conf.contributions.forEach { c ->
                appendLine("${dimensionLabel(c.dimension)}: ${"%.0f".format(c.score01 * 100)}%")
            }
            if (conf.warnings.isNotEmpty()) {
                appendLine("Cần kiểm tra:")
                conf.warnings.forEach { warning ->
                    appendLine("- ${warning.dimension?.let(::dimensionLabel) ?: "Thông tin khác"}")
                }
            }
        }.trimEnd()

        return OcrDebugSnapshot(
            previewWidthPx = previewW,
            previewHeightPx = previewH,
            lines = linesPreview,
            blockBoundsPreview = blocksPreview,
            draft = draft,
            parserDecisions = parserDecisions,
            confidenceSummaryLines = summary,
            confidenceDetailText = detailText,
            overallWeighted = conf.overallWeighted,
            heuristicUiPercent = heuristicUiPercent,
        )
    }

    private fun dimensionLabel(dimension: ConfidenceDimension): String =
        when (dimension) {
            ConfidenceDimension.OCR_QUALITY -> "Chất lượng nhận diện"
            ConfidenceDimension.FIELD_CONSISTENCY -> "Độ khớp dữ liệu"
            ConfidenceDimension.PRICE_DETECTION -> "Tổng tiền"
            ConfidenceDimension.TRACKING_DETECTION -> "Mã vận đơn"
            ConfidenceDimension.TITLE_DETECTION -> "Tên sản phẩm"
            ConfidenceDimension.LAYOUT_RELIABILITY -> "Bố cục ảnh"
            ConfidenceDimension.MARKETPLACE_PATTERN -> "Mẫu màn hình"
        }

    private fun Rect.toPreviewSpace(crop: Rect): Rect? {
        val r = Rect(this)
        r.offset(-crop.left, -crop.top)
        val clip = Rect(0, 0, crop.width(), crop.height())
        return if (r.intersect(clip)) r else null
    }
}

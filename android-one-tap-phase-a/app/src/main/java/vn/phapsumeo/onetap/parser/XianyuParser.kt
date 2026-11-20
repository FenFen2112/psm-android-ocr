package vn.phapsumeo.onetap.parser

import vn.phapsumeo.onetap.extraction.core.OcrBlock
import vn.phapsumeo.onetap.extraction.core.OcrDocument
import vn.phapsumeo.onetap.extraction.core.ScreenMeta
import vn.phapsumeo.onetap.extraction.pipeline.DefaultExtractionPipeline
import vn.phapsumeo.onetap.extraction.profile.xianyu.XianyuMarketplaceProfile
import vn.phapsumeo.onetap.model.OrderDraft

/**
 * Public entry compatible with older call sites: orchestrates the modular pipeline
 * (layout → entity → confidence) for Xianyu.
 */
object XianyuParser {

    private val pipeline = DefaultExtractionPipeline()

    fun parseWithCoords(
        lines: List<OcrLine>,
        imageUri: String = "",
        screenWidth: Int,
        screenHeight: Int,
    ): OrderDraft {
        val document = OcrDocument(
            screen = ScreenMeta(widthPx = screenWidth, heightPx = screenHeight, densityDpi = 0),
            blocks = listOf(OcrBlock(lines = lines, readingOrder = 0)),
            imageUri = imageUri,
        )
        val draft = pipeline.run(document, XianyuMarketplaceProfile.Shared).draft
        val fullText = lines.joinToString("\n") { it.text }
        return XianyuRuleTextExtractor.merge(draft, fullText)
    }

    /** Fallback when line boxes are missing — same synthetic document size as before. */
    fun parse(raw: String, imageUri: String = ""): OrderDraft {
        return parseWithCoords(raw.lines().map { OcrLine(it, null) }, imageUri, 1080, 2400)
    }
}

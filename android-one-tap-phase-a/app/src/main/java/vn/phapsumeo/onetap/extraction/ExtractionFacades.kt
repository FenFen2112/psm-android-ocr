package vn.phapsumeo.onetap.extraction

import vn.phapsumeo.onetap.extraction.core.OcrBlock
import vn.phapsumeo.onetap.extraction.core.OcrDocument
import vn.phapsumeo.onetap.extraction.core.OcrLine
import vn.phapsumeo.onetap.extraction.core.ScreenMeta
import vn.phapsumeo.onetap.extraction.grouping.DefaultOcrBlockGrouper
import vn.phapsumeo.onetap.extraction.grouping.GroupedOcrOutput
import vn.phapsumeo.onetap.extraction.grouping.OcrBlockGroupingConfig
import vn.phapsumeo.onetap.extraction.pipeline.DefaultExtractionPipeline
import vn.phapsumeo.onetap.extraction.profile.xianyu.XianyuMarketplaceProfile
import vn.phapsumeo.onetap.extraction.core.ExtractionResult

/**
 * Stable entry points for callers migrating off direct [vn.phapsumeo.onetap.parser.XianyuParser] usage.
 * UI and services can adopt these incrementally without behavior change (same legacy detector).
 */
object ExtractionFacades {

    private val defaultPipeline = DefaultExtractionPipeline()
    private val xianyuProfile = XianyuMarketplaceProfile.Shared

    /**
     * Build [OcrDocument] from flat lines (current ML Kit output) as a single block.
     */
    fun documentFromLines(
        lines: List<OcrLine>,
        screenWidth: Int,
        screenHeight: Int,
        imageUri: String = "",
        densityDpi: Int = 0,
    ): OcrDocument = OcrDocument(
        screen = ScreenMeta(widthPx = screenWidth, heightPx = screenHeight, densityDpi = densityDpi),
        blocks = listOf(OcrBlock(lines = lines, readingOrder = 0)),
        imageUri = imageUri,
    )

    /**
     * Runs generic spatial grouping (union–find on boxes + reading order), then builds [OcrDocument].
     * Does not invoke any marketplace parser — use with [extractXianyu] or a future profile when ready.
     */
    fun documentFromLinesGrouped(
        lines: List<OcrLine>,
        screenWidth: Int,
        screenHeight: Int,
        densityDpi: Int = 0,
        config: OcrBlockGroupingConfig = OcrBlockGroupingConfig.DEFAULT,
    ): GroupedOcrOutput {
        val screen = ScreenMeta(widthPx = screenWidth, heightPx = screenHeight, densityDpi = densityDpi)
        return DefaultOcrBlockGrouper.group(lines, screen, config)
    }

    /** Same as [documentFromLinesGrouped] but returns only [OcrDocument] (spatial relations discarded). */
    fun documentFromLinesGroupedAsDocument(
        lines: List<OcrLine>,
        screenWidth: Int,
        screenHeight: Int,
        imageUri: String = "",
        densityDpi: Int = 0,
        config: OcrBlockGroupingConfig = OcrBlockGroupingConfig.DEFAULT,
    ): OcrDocument =
        documentFromLinesGrouped(lines, screenWidth, screenHeight, densityDpi, config).toDocument(imageUri)

    fun extractXianyu(document: OcrDocument): ExtractionResult =
        defaultPipeline.run(document, xianyuProfile)
}

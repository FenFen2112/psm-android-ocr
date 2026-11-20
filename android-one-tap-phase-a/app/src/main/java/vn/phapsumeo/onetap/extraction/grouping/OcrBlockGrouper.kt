package vn.phapsumeo.onetap.extraction.grouping

import vn.phapsumeo.onetap.extraction.core.OcrLine
import vn.phapsumeo.onetap.extraction.core.ScreenMeta

/**
 * Converts flat OCR lines into grouped blocks + [vn.phapsumeo.onetap.extraction.core.SpatialRelation] edges.
 * Pure geometry + configurable thresholds — no marketplace rules.
 */
interface OcrBlockGrouper {
    fun group(
        lines: List<OcrLine>,
        screen: ScreenMeta,
        config: OcrBlockGroupingConfig = OcrBlockGroupingConfig.DEFAULT,
    ): GroupedOcrOutput
}

package vn.phapsumeo.onetap.extraction.layout

import vn.phapsumeo.onetap.extraction.api.LayoutAnalyzer
import vn.phapsumeo.onetap.extraction.core.LayoutHints
import vn.phapsumeo.onetap.extraction.core.OcrDocument

/**
 * Phase-1 stub: no derived layout; profiles that need ratios use [OcrDocument.screen] + line boxes directly.
 */
class PassthroughLayoutAnalyzer : LayoutAnalyzer {
    override fun analyze(document: OcrDocument): LayoutHints = LayoutHints.EMPTY
}

package vn.phapsumeo.onetap.extraction.api

import vn.phapsumeo.onetap.extraction.core.LayoutHints
import vn.phapsumeo.onetap.extraction.core.OcrDocument

/**
 * Derives layout structure from raw OCR (regions, columns, reading order) without marketplace semantics.
 */
fun interface LayoutAnalyzer {
    fun analyze(document: OcrDocument): LayoutHints
}

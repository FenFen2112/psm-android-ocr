package vn.phapsumeo.onetap.extraction.api

import vn.phapsumeo.onetap.extraction.core.LayoutHints
import vn.phapsumeo.onetap.extraction.core.OcrDocument
import vn.phapsumeo.onetap.model.OrderDraft

/**
 * Marketplace-specific: maps OCR + layout hints to an [OrderDraft].
 */
fun interface EntityDetector {
    fun detect(document: OcrDocument, layoutHints: LayoutHints): OrderDraft
}

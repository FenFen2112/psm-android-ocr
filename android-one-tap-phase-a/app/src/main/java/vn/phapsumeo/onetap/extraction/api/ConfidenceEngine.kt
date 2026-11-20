package vn.phapsumeo.onetap.extraction.api

import vn.phapsumeo.onetap.extraction.core.OcrDocument
import vn.phapsumeo.onetap.model.OrderDraft

/**
 * Scores extraction quality (separate from UI auto-submit threshold in MainActivity).
 * Prefer [DetailConfidenceEngine] when you need [vn.phapsumeo.onetap.extraction.confidence.model.ExtractionConfidence].
 */
interface ConfidenceEngine {
    fun score(draft: OrderDraft, document: OcrDocument): Pair<Double, Map<String, Double>>
}

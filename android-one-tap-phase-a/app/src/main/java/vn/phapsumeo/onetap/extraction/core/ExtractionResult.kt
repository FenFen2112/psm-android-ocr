package vn.phapsumeo.onetap.extraction.core

import vn.phapsumeo.onetap.extraction.confidence.model.ExtractionConfidence
import vn.phapsumeo.onetap.model.OrderDraft

/**
 * Output of [vn.phapsumeo.onetap.extraction.api.ExtractionPipeline].
 * Keeps [OrderDraft] for GAS compatibility; adds extraction-time confidence (UI may keep its own score).
 */
data class ExtractionResult(
    val draft: OrderDraft,
    val overallConfidence: Double,
    val fieldConfidence: Map<String, Double> = emptyMap(),
    val profileId: String,
    /** Modular explainable confidence when pipeline uses [vn.phapsumeo.onetap.extraction.api.DetailConfidenceEngine]. */
    val confidenceDetail: ExtractionConfidence? = null,
)

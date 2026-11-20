package vn.phapsumeo.onetap.extraction.api

import vn.phapsumeo.onetap.extraction.confidence.ConfidenceEvaluationContext
import vn.phapsumeo.onetap.extraction.confidence.model.ExtractionConfidence
import vn.phapsumeo.onetap.extraction.core.OcrDocument
import vn.phapsumeo.onetap.model.OrderDraft

/**
 * [ConfidenceEngine] plus an explainable [ExtractionConfidence] breakdown for pipelines and debug.
 */
interface DetailConfidenceEngine : ConfidenceEngine {

    fun evaluateDetailed(context: ConfidenceEvaluationContext): ExtractionConfidence

    override fun score(draft: OrderDraft, document: OcrDocument): Pair<Double, Map<String, Double>> =
        evaluateDetailed(ConfidenceEvaluationContext(draft, document)).toLegacyPair()
}

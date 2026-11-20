package vn.phapsumeo.onetap.extraction.confidence

import vn.phapsumeo.onetap.extraction.core.OcrDocument
import vn.phapsumeo.onetap.extraction.grouping.GroupedOcrOutput
import vn.phapsumeo.onetap.model.OrderDraft

/**
 * Inputs for modular confidence — marketplace-agnostic except via [patternPlugin] hooks.
 */
data class ConfidenceEvaluationContext(
    val draft: OrderDraft,
    val document: OcrDocument,
    /** Optional: spatial grouping output improves layout reliability scoring. */
    val grouped: GroupedOcrOutput? = null,
)

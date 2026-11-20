package vn.phapsumeo.onetap.extraction.confidence.contributors

import vn.phapsumeo.onetap.extraction.confidence.ConfidenceEvaluationContext
import vn.phapsumeo.onetap.extraction.confidence.model.ConfidenceDimension

/**
 * Single confidence axis: returns score in \[0, 1\] and explainable reasons.
 */
interface AxisContributor {
    val dimension: ConfidenceDimension
    fun contribute(context: ConfidenceEvaluationContext): Pair<Double, List<String>>
}

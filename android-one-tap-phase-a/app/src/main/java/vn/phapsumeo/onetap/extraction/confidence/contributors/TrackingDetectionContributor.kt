package vn.phapsumeo.onetap.extraction.confidence.contributors

import vn.phapsumeo.onetap.extraction.confidence.ConfidenceEvaluationContext
import vn.phapsumeo.onetap.extraction.confidence.model.ConfidenceDimension

object TrackingDetectionContributor : AxisContributor {
    override val dimension = ConfidenceDimension.TRACKING_DETECTION

    private val alnum = Regex("^[A-Za-z0-9\\-]+$")

    override fun contribute(context: ConfidenceEvaluationContext): Pair<Double, List<String>> {
        val t = context.draft.maVanChuyen.trim()
        val reasons = mutableListOf<String>()
        if (t.isEmpty()) return 0.15 to listOf("tracking_empty")

        reasons += "len=${t.length}"
        val digits = t.count { it.isDigit() }
        val score = when {
            t.length >= 12 && digits >= 8 -> 0.95
            t.length >= 10 -> 0.82
            t.length >= 8 -> 0.68
            else -> 0.45
        }
        if (t.length >= 8 && !alnum.matches(t.replace(" ", ""))) {
            reasons += "contains_non_alnum_may_be_address"
            return (score * 0.85).coerceIn(0.0, 1.0) to reasons
        }
        return score to reasons
    }
}

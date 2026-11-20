package vn.phapsumeo.onetap.extraction.confidence.contributors

import kotlin.math.abs
import vn.phapsumeo.onetap.extraction.confidence.ConfidenceEvaluationContext
import vn.phapsumeo.onetap.extraction.confidence.model.ConfidenceDimension

/**
 * Internal numeric consistency (e.g. total vs hang + ship) without marketplace labels.
 */
object FieldConsistencyContributor : AxisContributor {
    override val dimension = ConfidenceDimension.FIELD_CONSISTENCY

    override fun contribute(context: ConfidenceEvaluationContext): Pair<Double, List<String>> {
        val d = context.draft
        val reasons = mutableListOf<String>()
        val hang = d.giaHangCn
        val ship = d.shipNoiDiaCn
        val tong = d.tongCn

        if (hang == null && ship == null && tong == null) {
            return 0.35 to listOf("No price fields to cross-check")
        }

        if (tong != null && hang != null && ship != null && tong > 0) {
            val sum = hang + ship
            val relErr = abs(tong - sum) / tong
            reasons += "tong=${"%.2f".format(tong)} hang+ship=${"%.2f".format(sum)} rel_err=${"%.3f".format(relErr)}"
            val score = when {
                relErr < 0.02 -> 1.0
                relErr < 0.08 -> 0.85
                relErr < 0.15 -> 0.65
                else -> 0.4
            }
            if (relErr > 0.15) reasons += "price_components_mismatch"
            return score to reasons
        }

        if (tong != null && tong > 0) {
            reasons += "partial_fields:tong_only"
            return 0.72 to reasons
        }

        reasons += "partial_price_fields"
        return 0.55 to reasons
    }
}

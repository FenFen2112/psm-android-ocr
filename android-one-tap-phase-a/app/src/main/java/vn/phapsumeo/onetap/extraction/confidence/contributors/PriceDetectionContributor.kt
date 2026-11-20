package vn.phapsumeo.onetap.extraction.confidence.contributors

import vn.phapsumeo.onetap.extraction.confidence.ConfidenceEvaluationContext
import vn.phapsumeo.onetap.extraction.confidence.model.ConfidenceDimension

object PriceDetectionContributor : AxisContributor {
    override val dimension = ConfidenceDimension.PRICE_DETECTION

    override fun contribute(context: ConfidenceEvaluationContext): Pair<Double, List<String>> {
        val tong = context.draft.tongCn
        val hang = context.draft.giaHangCn
        val reasons = mutableListOf<String>()

        if (tong != null && tong > 0) {
            reasons += "tong_positive=${"%.2f".format(tong)}"
            var s = 0.88
            if (hang != null && hang > 0) {
                reasons += "hang_present"
                s = (s + 0.07).coerceAtMost(1.0)
            }
            return s to reasons
        }

        if (hang != null && hang > 0) {
            reasons += "only_hang_no_tong"
            return 0.55 to reasons
        }

        return 0.2 to listOf("No positive total price detected")
    }
}

package vn.phapsumeo.onetap.extraction.confidence.contributors

import vn.phapsumeo.onetap.extraction.confidence.ConfidenceEvaluationContext
import vn.phapsumeo.onetap.extraction.confidence.model.ConfidenceDimension

object TitleDetectionContributor : AxisContributor {
    override val dimension = ConfidenceDimension.TITLE_DETECTION

    override fun contribute(context: ConfidenceEvaluationContext): Pair<Double, List<String>> {
        val title = context.draft.tenSanPham.trim()
        val reasons = mutableListOf<String>()
        if (title.isEmpty()) return 0.12 to listOf("title_empty")

        val digitRatio = title.count { it.isDigit() }.toDouble() / title.length.coerceAtLeast(1)
        reasons += "len=${title.length} digit_ratio=${"%.2f".format(digitRatio)}"

        var score = when {
            title.length >= 24 -> 0.9
            title.length >= 12 -> 0.78
            title.length >= 4 -> 0.62
            else -> 0.45
        }
        if (digitRatio > 0.55) {
            reasons += "title_mostly_digits_penalty"
            score *= 0.65
        }
        return score.coerceIn(0.0, 1.0) to reasons
    }
}

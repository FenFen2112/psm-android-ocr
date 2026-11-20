package vn.phapsumeo.onetap.extraction.confidence.contributors

import vn.phapsumeo.onetap.extraction.confidence.ConfidenceEvaluationContext
import vn.phapsumeo.onetap.extraction.confidence.model.ConfidenceDimension

/**
 * Proxies for OCR cleanliness: bbox coverage, empty lines, very short token spam.
 */
object OcrQualityContributor : AxisContributor {
    override val dimension = ConfidenceDimension.OCR_QUALITY

    override fun contribute(context: ConfidenceEvaluationContext): Pair<Double, List<String>> {
        val lines = context.document.allLines()
        if (lines.isEmpty()) return 0.0 to listOf("No OCR lines")

        val withBox = lines.count { it.boundingBox != null }
        val coverage = withBox.toDouble() / lines.size
        val reasons = mutableListOf<String>()
        reasons += "bbox_coverage=${"%.2f".format(coverage)} (${withBox}/${lines.size})"

        val shortSpam = lines.count { it.text.trim().length == 1 } / lines.size.toDouble()
        val shortPenalty = (shortSpam * 0.4).coerceIn(0.0, 0.35)
        if (shortSpam > 0.2) reasons += "high_single_char_ratio=${"%.2f".format(shortSpam)}"

        val score = (coverage * 0.85 + (1 - shortSpam) * 0.15 - shortPenalty).coerceIn(0.0, 1.0)
        return score to reasons
    }
}

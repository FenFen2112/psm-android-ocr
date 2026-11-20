package vn.phapsumeo.onetap.extraction.confidence.contributors

import vn.phapsumeo.onetap.extraction.confidence.ConfidenceEvaluationContext
import vn.phapsumeo.onetap.extraction.confidence.model.ConfidenceDimension

/**
 * Uses block structure and bbox coverage when [ConfidenceEvaluationContext.grouped] is present;
 * otherwise falls back to document block count + line bbox ratio.
 */
object LayoutReliabilityContributor : AxisContributor {
    override val dimension = ConfidenceDimension.LAYOUT_RELIABILITY

    override fun contribute(context: ConfidenceEvaluationContext): Pair<Double, List<String>> {
        val reasons = mutableListOf<String>()
        val g = context.grouped
        if (g != null) {
            val n = g.blocks.size
            val withUnion = g.blocks.count { it.boundingBox != null }
            reasons += "spatial_blocks=$n union_boxes=$withUnion"
            val score = when {
                n in 2..40 && withUnion >= n * 0.7 -> 0.88
                n in 1..60 -> 0.72
                else -> 0.55
            }
            return score to reasons
        }

        val lines = context.document.allLines()
        val blocks = context.document.blocks.size
        val cov = if (lines.isEmpty()) 0.0 else lines.count { it.boundingBox != null }.toDouble() / lines.size
        reasons += "document_blocks=$blocks line_bbox_coverage=${"%.2f".format(cov)}"
        val score = (0.55 + cov * 0.35 + (if (blocks > 1) 0.1 else 0.0)).coerceIn(0.0, 1.0)
        return score to reasons
    }
}

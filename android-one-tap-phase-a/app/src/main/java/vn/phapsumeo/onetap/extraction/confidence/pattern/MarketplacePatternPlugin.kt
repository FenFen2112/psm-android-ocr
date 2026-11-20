package vn.phapsumeo.onetap.extraction.confidence.pattern

import vn.phapsumeo.onetap.extraction.confidence.ConfidenceEvaluationContext

/**
 * Optional marketplace-specific pattern layer (keywords, currency symbols, etc.).
 */
fun interface MarketplacePatternPlugin {
    fun patternScore(context: ConfidenceEvaluationContext): Pair<Double, List<String>>
}

object NeutralPatternPlugin : MarketplacePatternPlugin {
    override fun patternScore(context: ConfidenceEvaluationContext): Pair<Double, List<String>> =
        0.5 to listOf("neutral_pattern_plugin")
}

package vn.phapsumeo.onetap.extraction.profile.xianyu

import vn.phapsumeo.onetap.extraction.confidence.ConfidenceEvaluationContext
import vn.phapsumeo.onetap.extraction.confidence.pattern.MarketplacePatternPlugin

/**
 * Weak structural signals common on Chinese commerce screenshots — not full Xianyu parsing.
 */
class XianyuPatternPlugin : MarketplacePatternPlugin {

    override fun patternScore(context: ConfidenceEvaluationContext): Pair<Double, List<String>> {
        val blob = context.document.allLines().joinToString("\n") { it.text }
        val reasons = mutableListOf<String>()
        var score = 0.45

        if (blob.contains('¥') || blob.contains('￥')) {
            score += 0.18
            reasons += "currency_symbol_present"
        }
        if (blob.contains("运单") || blob.contains("订单")) {
            score += 0.12
            reasons += "shipping_or_order_tokens"
        }
        if (blob.any { it in '一'..'龯' }) {
            score += 0.08
            reasons += "cjk_script_present"
        }
        return score.coerceIn(0.0, 1.0) to reasons
    }
}

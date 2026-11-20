package vn.phapsumeo.onetap.extraction.confidence

import vn.phapsumeo.onetap.extraction.api.DetailConfidenceEngine
import vn.phapsumeo.onetap.extraction.confidence.model.ExtractionConfidence
import vn.phapsumeo.onetap.extraction.confidence.pattern.MarketplacePatternPlugin
import vn.phapsumeo.onetap.extraction.confidence.pattern.NeutralPatternPlugin

/**
 * Modular weighted confidence with explainable breakdown; implements [DetailConfidenceEngine]
 * for use in [vn.phapsumeo.onetap.extraction.pipeline.DefaultExtractionPipeline].
 */
class OrderDraftConfidenceEngine(
    patternPlugin: MarketplacePatternPlugin = NeutralPatternPlugin,
) : DetailConfidenceEngine {

    private val modular = ModularConfidenceEngine.default(patternPlugin)

    override fun evaluateDetailed(context: ConfidenceEvaluationContext): ExtractionConfidence =
        modular.evaluate(context)
}

package vn.phapsumeo.onetap.extraction.pipeline

import vn.phapsumeo.onetap.extraction.api.DetailConfidenceEngine
import vn.phapsumeo.onetap.extraction.api.ExtractionPipeline
import vn.phapsumeo.onetap.extraction.api.MarketplaceProfile
import vn.phapsumeo.onetap.extraction.confidence.ConfidenceEvaluationContext
import vn.phapsumeo.onetap.extraction.core.ExtractionResult
import vn.phapsumeo.onetap.extraction.core.OcrDocument

/**
 * Default orchestration: layout → entity detection → confidence.
 */
class DefaultExtractionPipeline : ExtractionPipeline {

    override fun run(document: OcrDocument, profile: MarketplaceProfile): ExtractionResult {
        val hints = profile.layoutAnalyzer.analyze(document)
        val draft = profile.entityDetector.detect(document, hints)
        val engine = profile.confidenceEngine
        val detail = (engine as? DetailConfidenceEngine)
            ?.evaluateDetailed(ConfidenceEvaluationContext(draft = draft, document = document))
        val (overall, fields) = detail?.toLegacyPair() ?: engine.score(draft, document)
        return ExtractionResult(
            draft = draft,
            overallConfidence = overall,
            fieldConfidence = fields,
            profileId = profile.id,
            confidenceDetail = detail,
        )
    }
}

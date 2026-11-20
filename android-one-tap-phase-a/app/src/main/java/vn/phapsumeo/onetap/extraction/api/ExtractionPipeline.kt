package vn.phapsumeo.onetap.extraction.api

import vn.phapsumeo.onetap.extraction.core.ExtractionResult
import vn.phapsumeo.onetap.extraction.core.OcrDocument

/**
 * Orchestrates: layout → entities → confidence.
 */
fun interface ExtractionPipeline {
    fun run(document: OcrDocument, profile: MarketplaceProfile): ExtractionResult
}

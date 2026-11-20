package vn.phapsumeo.onetap.extraction.api

/**
 * Bundles analyzers for one marketplace (e.g. Xianyu). New marketplaces = new profile implementations.
 */
interface MarketplaceProfile {
    val id: String
    val displayName: String
    val layoutAnalyzer: LayoutAnalyzer
    val entityDetector: EntityDetector
    val confidenceEngine: ConfidenceEngine
}

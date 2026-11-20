package vn.phapsumeo.onetap.extraction.profile.xianyu

import vn.phapsumeo.onetap.extraction.api.ConfidenceEngine
import vn.phapsumeo.onetap.extraction.api.EntityDetector
import vn.phapsumeo.onetap.extraction.api.LayoutAnalyzer
import vn.phapsumeo.onetap.extraction.api.MarketplaceProfile
import vn.phapsumeo.onetap.extraction.confidence.OrderDraftConfidenceEngine
import vn.phapsumeo.onetap.extraction.layout.XianyuLayoutAnalyzer

/**
 * Xianyu marketplace profile: [XianyuLayoutAnalyzer] → [XianyuHeuristicEntityDetector] →
 * [OrderDraftConfidenceEngine].
 */
class XianyuMarketplaceProfile(
    override val layoutAnalyzer: LayoutAnalyzer,
    override val entityDetector: EntityDetector,
    override val confidenceEngine: ConfidenceEngine,
) : MarketplaceProfile {
    override val id: String = "xianyu"
    override val displayName: String = "闲鱼 / Xianyu"

    constructor() : this(
        layoutAnalyzer = XianyuLayoutAnalyzer(),
        entityDetector = XianyuHeuristicEntityDetector(),
        confidenceEngine = OrderDraftConfidenceEngine(XianyuPatternPlugin()),
    )

    companion object {
        private val sharedLayout: LayoutAnalyzer = XianyuLayoutAnalyzer()
        private val sharedEntity: EntityDetector = XianyuHeuristicEntityDetector()
        private val sharedConfidence: ConfidenceEngine = OrderDraftConfidenceEngine(XianyuPatternPlugin())

        /** Shared instances for [vn.phapsumeo.onetap.parser.XianyuParser] and [ExtractionFacades]. */
        val Shared: XianyuMarketplaceProfile = XianyuMarketplaceProfile(
            layoutAnalyzer = sharedLayout,
            entityDetector = sharedEntity,
            confidenceEngine = sharedConfidence,
        )
    }
}

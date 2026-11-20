package vn.phapsumeo.onetap.extraction.core

/**
 * Output of [vn.phapsumeo.onetap.extraction.api.LayoutAnalyzer] — normalized regions, reading order, etc.
 * Phase 1: mostly empty; profiles may ignore until layout analyzers are implemented per marketplace.
 */
data class LayoutHints(
    val normalizedRegions: Map<String, NormalizedRect> = emptyMap(),
    val readingOrderIndices: List<Int> = emptyList(),
    /** Scalar layout metrics (e.g. median line height px) for proximity heuristics without raw screen ratios. */
    val metrics: Map<String, Float> = emptyMap(),
) {
    data class NormalizedRect(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    )

    companion object {
        val EMPTY = LayoutHints()
    }
}

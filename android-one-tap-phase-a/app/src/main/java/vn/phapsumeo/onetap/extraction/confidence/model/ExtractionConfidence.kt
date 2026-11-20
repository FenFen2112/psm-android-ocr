package vn.phapsumeo.onetap.extraction.confidence.model

/**
 * Full explainable confidence output for an extraction pass.
 */
data class ExtractionConfidence(
    /** Weighted sum of dimension scores (weights normalized to 1). */
    val overallWeighted: Double,
    val contributions: List<DimensionContribution>,
    val warnings: List<ConfidenceWarning>,
    /** Monotonic increasing debug id for correlating logs. */
    val evaluationSequence: Long = System.nanoTime(),
) {
    val isLowConfidence: Boolean =
        overallWeighted < Companion.LOW_CONFIDENCE_THRESHOLD ||
            warnings.any { it.severity == ConfidenceWarningSeverity.CRITICAL } ||
            warnings.count { it.severity == ConfidenceWarningSeverity.WARN } >= 3

    fun toLegacyPair(): Pair<Double, Map<String, Double>> =
        overallWeighted.coerceIn(0.0, 1.0) to toLegacyFieldMap()

    /**
     * Keys aligned with legacy UI / [vn.phapsumeo.onetap.MainActivity] field names where applicable.
     */
    fun toLegacyFieldMap(): Map<String, Double> {
        val m = LinkedHashMap<String, Double>()
        for (c in contributions) {
            when (c.dimension) {
                ConfidenceDimension.TRACKING_DETECTION -> m["maVanChuyen"] = c.score01
                ConfidenceDimension.TITLE_DETECTION -> m["tenSanPham"] = c.score01
                ConfidenceDimension.PRICE_DETECTION -> m["tongCn"] = c.score01
                ConfidenceDimension.FIELD_CONSISTENCY -> m["fieldConsistency"] = c.score01
                ConfidenceDimension.OCR_QUALITY -> m["ocrQuality"] = c.score01
                ConfidenceDimension.LAYOUT_RELIABILITY -> m["layoutReliability"] = c.score01
                ConfidenceDimension.MARKETPLACE_PATTERN -> m["marketplacePattern"] = c.score01
            }
        }
        return m
    }

    fun toDebugString(): String = buildString {
        appendLine("ExtractionConfidence seq=$evaluationSequence overall=${"%.3f".format(overallWeighted)} low=$isLowConfidence")
        for (c in contributions) {
            append("  [${c.dimension}] score=${"%.2f".format(c.score01)} w=${"%.2f".format(c.normalizedWeight)} contrib=${"%.3f".format(c.weightedContribution)}")
            if (c.reasons.isNotEmpty()) append(" :: ${c.reasons.joinToString("; ")}")
            appendLine()
        }
        if (warnings.isNotEmpty()) {
            appendLine("Warnings:")
            warnings.forEach { w ->
                appendLine("  [${w.severity}] ${w.code}: ${w.message}")
            }
        }
    }

    companion object {
        const val LOW_CONFIDENCE_THRESHOLD = 0.45
    }
}

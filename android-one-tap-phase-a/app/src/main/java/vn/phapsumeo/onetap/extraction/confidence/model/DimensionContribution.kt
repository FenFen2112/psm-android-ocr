package vn.phapsumeo.onetap.extraction.confidence.model

/**
 * One axis of the final score with human-readable reasons (explainability).
 *
 * @param score01 Quality on this axis in \[0, 1\].
 * @param normalizedWeight Weight after global normalization (all dimensions sum to 1).
 * @param weightedContribution `score01 * normalizedWeight` — partial sum toward [ExtractionConfidence.overallWeighted].
 */
data class DimensionContribution(
    val dimension: ConfidenceDimension,
    val score01: Double,
    val normalizedWeight: Double,
    val weightedContribution: Double,
    val reasons: List<String>,
)

package vn.phapsumeo.onetap.extraction.confidence

import vn.phapsumeo.onetap.extraction.confidence.model.ConfidenceDimension

/**
 * Relative importance of each dimension. Values are normalized to sum 1 at evaluation time.
 */
data class ConfidenceWeights(
    val ocrQuality: Double = 0.10,
    val fieldConsistency: Double = 0.12,
    val priceDetection: Double = 0.20,
    val trackingDetection: Double = 0.20,
    val titleDetection: Double = 0.16,
    val layoutReliability: Double = 0.12,
    val marketplacePattern: Double = 0.10,
) {
    fun asMap(): Map<ConfidenceDimension, Double> = mapOf(
        ConfidenceDimension.OCR_QUALITY to ocrQuality,
        ConfidenceDimension.FIELD_CONSISTENCY to fieldConsistency,
        ConfidenceDimension.PRICE_DETECTION to priceDetection,
        ConfidenceDimension.TRACKING_DETECTION to trackingDetection,
        ConfidenceDimension.TITLE_DETECTION to titleDetection,
        ConfidenceDimension.LAYOUT_RELIABILITY to layoutReliability,
        ConfidenceDimension.MARKETPLACE_PATTERN to marketplacePattern,
    )

    fun normalized(): Map<ConfidenceDimension, Double> {
        val raw = asMap()
        val sum = raw.values.sum().takeIf { it > 0 } ?: 1.0
        return raw.mapValues { (_, v) -> v / sum }
    }

    companion object {
        val DEFAULT = ConfidenceWeights()

        /** Weights chosen so weighted overall stays comparable to legacy 0–1 additive heuristics. */
        fun legacyBalanced(): ConfidenceWeights = ConfidenceWeights(
            ocrQuality = 0.08,
            fieldConsistency = 0.10,
            priceDetection = 0.22,
            trackingDetection = 0.22,
            titleDetection = 0.18,
            layoutReliability = 0.12,
            marketplacePattern = 0.08,
        )
    }
}

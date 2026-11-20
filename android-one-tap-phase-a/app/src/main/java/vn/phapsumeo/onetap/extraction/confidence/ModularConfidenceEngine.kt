package vn.phapsumeo.onetap.extraction.confidence

import vn.phapsumeo.onetap.extraction.confidence.contributors.AxisContributor
import vn.phapsumeo.onetap.extraction.confidence.contributors.FieldConsistencyContributor
import vn.phapsumeo.onetap.extraction.confidence.contributors.LayoutReliabilityContributor
import vn.phapsumeo.onetap.extraction.confidence.contributors.OcrQualityContributor
import vn.phapsumeo.onetap.extraction.confidence.contributors.PriceDetectionContributor
import vn.phapsumeo.onetap.extraction.confidence.contributors.TitleDetectionContributor
import vn.phapsumeo.onetap.extraction.confidence.contributors.TrackingDetectionContributor
import vn.phapsumeo.onetap.extraction.confidence.model.ConfidenceDimension
import vn.phapsumeo.onetap.extraction.confidence.model.ConfidenceWarning
import vn.phapsumeo.onetap.extraction.confidence.model.ConfidenceWarningSeverity
import vn.phapsumeo.onetap.extraction.confidence.model.DimensionContribution
import vn.phapsumeo.onetap.extraction.confidence.model.ExtractionConfidence
import vn.phapsumeo.onetap.extraction.confidence.pattern.MarketplacePatternPlugin
import vn.phapsumeo.onetap.extraction.confidence.pattern.NeutralPatternPlugin

/**
 * Weighted multi-axis confidence with explainable breakdown and warnings.
 */
class ModularConfidenceEngine(
    private val weights: ConfidenceWeights,
    private val axisContributors: List<AxisContributor>,
    private val patternPlugin: MarketplacePatternPlugin,
) {

    fun evaluate(context: ConfidenceEvaluationContext): ExtractionConfidence {
        val norm = weights.normalized()
        val contributions = ArrayList<DimensionContribution>()

        for (axis in axisContributors) {
            val (score01, reasons) = axis.contribute(context)
            val w = norm[axis.dimension] ?: 0.0
            val s = score01.coerceIn(0.0, 1.0)
            contributions += DimensionContribution(axis.dimension, s, w, s * w, reasons)
        }

        val (pScore, pReasons) = patternPlugin.patternScore(context)
        val pw = norm[ConfidenceDimension.MARKETPLACE_PATTERN] ?: 0.0
        val ps = pScore.coerceIn(0.0, 1.0)
        contributions += DimensionContribution(
            ConfidenceDimension.MARKETPLACE_PATTERN,
            ps,
            pw,
            ps * pw,
            pReasons,
        )

        val overall = contributions.sumOf { it.weightedContribution }.coerceIn(0.0, 1.0)
        val warnings = buildWarnings(contributions, overall)
        return ExtractionConfidence(
            overallWeighted = overall,
            contributions = contributions,
            warnings = warnings,
        )
    }

    private fun buildWarnings(
        contributions: List<DimensionContribution>,
        overall: Double,
    ): List<ConfidenceWarning> {
        val w = mutableListOf<ConfidenceWarning>()
        if (overall < ExtractionConfidence.Companion.LOW_CONFIDENCE_THRESHOLD) {
            w += ConfidenceWarning(
                code = "LOW_OVERALL",
                message = "Weighted confidence ${"%.2f".format(overall)} is below ${ExtractionConfidence.Companion.LOW_CONFIDENCE_THRESHOLD}",
                severity = ConfidenceWarningSeverity.WARN,
                dimension = null,
            )
        }
        for (c in contributions) {
            when {
                c.dimension == ConfidenceDimension.TRACKING_DETECTION && c.score01 < 0.35 ->
                    w += ConfidenceWarning(
                        code = "WEAK_TRACKING",
                        message = "Tracking axis score ${"%.2f".format(c.score01)}",
                        severity = ConfidenceWarningSeverity.WARN,
                        dimension = c.dimension,
                    )
                c.dimension == ConfidenceDimension.PRICE_DETECTION && c.score01 < 0.3 ->
                    w += ConfidenceWarning(
                        code = "WEAK_PRICE",
                        message = "Price axis score ${"%.2f".format(c.score01)}",
                        severity = ConfidenceWarningSeverity.WARN,
                        dimension = c.dimension,
                    )
                c.dimension == ConfidenceDimension.OCR_QUALITY && c.score01 < 0.25 ->
                    w += ConfidenceWarning(
                        code = "POOR_OCR_LAYOUT",
                        message = "OCR quality axis ${"%.2f".format(c.score01)} — many lines missing boxes or noisy",
                        severity = ConfidenceWarningSeverity.WARN,
                        dimension = c.dimension,
                    )
                c.dimension == ConfidenceDimension.FIELD_CONSISTENCY && c.score01 < 0.4 ->
                    w += ConfidenceWarning(
                        code = "FIELD_MISMATCH",
                        message = "Field consistency ${"%.2f".format(c.score01)} — check totals vs components",
                        severity = ConfidenceWarningSeverity.INFO,
                        dimension = c.dimension,
                    )
            }
        }
        if (contributions.any { it.dimension == ConfidenceDimension.TITLE_DETECTION && it.score01 < 0.2 }) {
            w += ConfidenceWarning(
                code = "MISSING_TITLE",
                message = "Title detection very weak",
                severity = ConfidenceWarningSeverity.CRITICAL,
                dimension = ConfidenceDimension.TITLE_DETECTION,
            )
        }
        return w
    }

    companion object {
        fun default(patternPlugin: MarketplacePatternPlugin = NeutralPatternPlugin): ModularConfidenceEngine =
            ModularConfidenceEngine(
                weights = ConfidenceWeights.legacyBalanced(),
                axisContributors = listOf(
                    OcrQualityContributor,
                    FieldConsistencyContributor,
                    PriceDetectionContributor,
                    TrackingDetectionContributor,
                    TitleDetectionContributor,
                    LayoutReliabilityContributor,
                ),
                patternPlugin = patternPlugin,
            )
    }
}

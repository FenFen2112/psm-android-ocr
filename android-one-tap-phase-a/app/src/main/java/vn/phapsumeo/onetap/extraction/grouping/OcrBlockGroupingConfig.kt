package vn.phapsumeo.onetap.extraction.grouping

/**
 * Tunable thresholds for generic line clustering (screen-normalized where noted).
 * No marketplace-specific rules.
 */
data class OcrBlockGroupingConfig(
    /** Min vertical projection overlap (0–1) to treat two boxes as same text row fragment. */
    val sameRowVerticalOverlapMin: Float = 0.28f,
    /** Max horizontal gap as a fraction of [referenceTextHeightPx] to merge same-row fragments. */
    val sameRowMaxHorizontalGapFactor: Float = 1.35f,
    /** Min horizontal projection overlap (0–1) to treat as same column for vertical stacking. */
    val columnHorizontalOverlapMin: Float = 0.12f,
    /** Max vertical gap as a fraction of [referenceTextHeightPx] to merge vertical neighbours. */
    val verticalStackMaxGapFactor: Float = 1.1f,
    /** Merge if centre distance &lt;= this * referenceTextHeight (noise glue). */
    val maxCenterDistanceFactor: Float = 1.8f,
    /** When no boxes exist, synthetic line height in px (fallback). */
    val fallbackLineHeightPx: Float = 24f,
    /** Max horizontal gap as fraction of screen min dimension for same-row (absolute cap). */
    val sameRowMaxGapScreenFrac: Float = 0.045f,
    /** Emit block–block relations for pairs within this centre-distance (× reference height). */
    val neighbourCenterDistanceFactor: Float = 4.5f,
) {
    companion object {
        val DEFAULT = OcrBlockGroupingConfig()
    }
}

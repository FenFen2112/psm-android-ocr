package vn.phapsumeo.onetap.extraction.core

/**
 * Qualitative spatial relationship between two boxes or reading-order elements.
 * Language- and marketplace-agnostic (geometry only).
 */
enum class SpatialRelation {
    UNKNOWN,
    /** Intersection area greater than zero. */
    OVERLAPS,
    LEFT_OF,
    RIGHT_OF,
    ABOVE,
    BELOW,
    /**
     * High vertical overlap and small horizontal gap — typical OCR split of one UI line
     * (e.g. Latin + CJK fragments).
     */
    SAME_LINE_FRAGMENT,
}

/**
 * Pairwise relation between two **line** indices (into the filtered line list used for grouping).
 */
data class LineSpatialRelation(
    val fromLineIndex: Int,
    val toLineIndex: Int,
    val relation: SpatialRelation,
    val horizontalGapPx: Float,
    val verticalGapPx: Float,
    val horizontalOverlapRatio: Float,
    val verticalOverlapRatio: Float,
)

/**
 * Pairwise relation between two **block** indices (after grouping, sorted by [OcrBlock.readingOrder]).
 */
data class BlockSpatialRelation(
    val fromBlockIndex: Int,
    val toBlockIndex: Int,
    val relation: SpatialRelation,
    val horizontalGapPx: Float,
    val verticalGapPx: Float,
    val horizontalOverlapRatio: Float,
    val verticalOverlapRatio: Float,
)

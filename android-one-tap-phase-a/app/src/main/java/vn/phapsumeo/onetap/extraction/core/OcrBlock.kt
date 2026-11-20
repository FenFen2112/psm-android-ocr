package vn.phapsumeo.onetap.extraction.core

import android.graphics.Rect

/**
 * Group of lines (e.g. one ML Kit [Text.TextBlock]); optional block-level bounds.
 * [readingOrder] is global sort key (top-then-left) after grouping.
 */
data class OcrBlock(
    val lines: List<OcrLine>,
    val boundingBox: Rect? = null,
    val readingOrder: Int = 0,
)

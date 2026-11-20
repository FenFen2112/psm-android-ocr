package vn.phapsumeo.onetap.extraction.core

import android.graphics.Rect

/**
 * Single OCR text line with optional bounding box (pixel space, screenshot coordinates).
 * Canonical type for the extraction engine; [vn.phapsumeo.onetap.parser.OcrLine] is a typealias.
 */
data class OcrLine(
    val text: String,
    val boundingBox: Rect?,
)

package vn.phapsumeo.onetap.extraction.core

/**
 * Full OCR input to the extraction pipeline: structured blocks + screen + optional image URI.
 */
data class OcrDocument(
    val screen: ScreenMeta,
    val blocks: List<OcrBlock>,
    val imageUri: String = "",
) {
    /** Blocks in reading order; line order within each block is whatever the producer set (e.g. grouper or ML Kit). */
    fun allLines(): List<OcrLine> = blocks.sortedBy { it.readingOrder }.flatMap { it.lines }
}

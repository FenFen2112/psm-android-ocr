package vn.phapsumeo.onetap.debug.ocr

/**
 * Heuristic entity coloring for OCR lines (draft + pattern hints), not a second parser.
 */
enum class OcrDebugEntityType {
    UNKNOWN,
    ORDER_ID,
    TRACKING,
    TITLE,
    PRICE_TONG,
    PRICE_HANG,
    PRICE_SHIP,
    TAB,
    PRICE_AMBIGUOUS,
}

package vn.phapsumeo.onetap.extraction.confidence.model

/**
 * Independent scoring axis for extraction quality (geometry, fields, optional marketplace signals).
 */
enum class ConfidenceDimension {
    OCR_QUALITY,
    FIELD_CONSISTENCY,
    PRICE_DETECTION,
    TRACKING_DETECTION,
    TITLE_DETECTION,
    LAYOUT_RELIABILITY,
    MARKETPLACE_PATTERN,
}

package vn.phapsumeo.onetap.extraction.confidence.model

enum class ConfidenceWarningSeverity {
    INFO,
    WARN,
    CRITICAL,
}

data class ConfidenceWarning(
    val code: String,
    val message: String,
    val severity: ConfidenceWarningSeverity,
    val dimension: ConfidenceDimension?,
)

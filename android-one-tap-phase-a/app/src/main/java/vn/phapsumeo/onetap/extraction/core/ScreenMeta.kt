package vn.phapsumeo.onetap.extraction.core

/**
 * Physical / logical screen dimensions used to normalize layout (ratios, proximity).
 */
data class ScreenMeta(
    val widthPx: Int,
    val heightPx: Int,
    val densityDpi: Int = 0,
)

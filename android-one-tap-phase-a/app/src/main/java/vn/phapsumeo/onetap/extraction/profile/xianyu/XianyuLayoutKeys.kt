package vn.phapsumeo.onetap.extraction.profile.xianyu

/**
 * Stable keys for [vn.phapsumeo.onetap.extraction.core.LayoutHints] entries produced by
 * [XianyuLayoutAnalyzer]. Keeps multi-marketplace maps collision-free.
 */
object XianyuLayoutKeys {
    /** Union of OCR line boxes, normalized to full screen (0–1). */
    const val REGION_CONTENT_FRAME = "xianyu.content_frame"

    const val METRIC_MEDIAN_LINE_HEIGHT_PX = "xianyu.median_line_height_px"
    const val METRIC_CONTENT_WIDTH_PX = "xianyu.content_width_px"
    const val METRIC_CONTENT_HEIGHT_PX = "xianyu.content_height_px"
}

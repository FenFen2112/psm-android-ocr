package vn.phapsumeo.onetap.debug.ocr

import android.graphics.Rect
import vn.phapsumeo.onetap.model.OrderDraft

data class OcrDebugLineVisual(
    val boundsPreview: Rect?,
    val textSnippet: String,
    val type: OcrDebugEntityType,
)

/**
 * All geometry is in **preview bitmap** pixel space (same coordinates as [android.widget.ImageView] drawable intrinsic).
 */
data class OcrDebugSnapshot(
    val previewWidthPx: Int,
    val previewHeightPx: Int,
    val lines: List<OcrDebugLineVisual>,
    val blockBoundsPreview: List<Rect?>,
    val draft: OrderDraft,
    val parserDecisions: List<String>,
    /** Short lines for on-screen overlay. */
    val confidenceSummaryLines: List<String>,
    /** Full text for exported screenshots / Logcat. */
    val confidenceDetailText: String,
    val overallWeighted: Double,
    val heuristicUiPercent: Int,
)

object OcrDebugSessionStore {
    private val lock = Any()
    private var snapshot: OcrDebugSnapshot? = null

    @Synchronized
    fun publish(s: OcrDebugSnapshot) {
        snapshot = s
    }

    @Synchronized
    fun peek(): OcrDebugSnapshot? = snapshot

    @Synchronized
    fun clear() {
        snapshot = null
    }
}

package vn.phapsumeo.onetap.capture.layer

import android.graphics.Bitmap

/**
 * Capture layer: isolates screenshot capture orchestration from OCR/extraction.
 *
 * Implementations can come from different sources (MediaProjection, gallery, test fakes, etc.).
 */
interface CaptureLayer {
    suspend fun capture(launcher: CaptureActivityLauncher): CaptureFrame
}

/**
 * The only capability the capture layer needs from the Android component that triggers it.
 *
 * In production this will be `BubbleOverlayService.startActivity(CaptureActivity.createIntent(...))`.
 * In tests you can provide a no-op launcher.
 */
fun interface CaptureActivityLauncher {
    fun launchForSession(sessionId: Long)
}

data class CaptureFrame(
    val bitmap: Bitmap,
    val widthPx: Int = bitmap.width,
    val heightPx: Int = bitmap.height,
    val sourceId: String = "media_projection",
)


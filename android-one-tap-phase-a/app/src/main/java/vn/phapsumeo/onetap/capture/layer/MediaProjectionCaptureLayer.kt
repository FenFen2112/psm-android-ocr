package vn.phapsumeo.onetap.capture.layer

import android.content.Context
import kotlinx.coroutines.flow.first
import vn.phapsumeo.onetap.capture.CaptureBridge
import vn.phapsumeo.onetap.capture.ScreenCaptureHelper

/**
 * MediaProjection-backed capture layer implementation.
 *
 * Isolates:
 * - session/grant lifecycle (CaptureBridge ↔ CaptureActivity)
 * - MediaProjection + VirtualDisplay handling (delegated to ScreenCaptureHelper)
 */
class MediaProjectionCaptureLayer(
    private val context: Context,
    private val helper: ScreenCaptureHelper = ScreenCaptureHelper(context),
) : CaptureLayer {

    override suspend fun capture(launcher: CaptureActivityLauncher): CaptureFrame {
        val sessionId = CaptureBridge.beginSession()
        launcher.launchForSession(sessionId)

        val grant = CaptureBridge.projectionGrant.first { it.sessionId == sessionId }
        val result = when (grant) {
            is CaptureBridge.ProjectionGrant.Granted -> helper.capture(grant)
            is CaptureBridge.ProjectionGrant.Denied -> CaptureBridge.CaptureResult.Failure(grant.reason)
        }

        return when (result) {
            is CaptureBridge.CaptureResult.Success -> CaptureFrame(
                bitmap = result.bitmap,
                sourceId = "media_projection",
            )
            is CaptureBridge.CaptureResult.Failure -> throw IllegalStateException(result.error)
        }
    }
}


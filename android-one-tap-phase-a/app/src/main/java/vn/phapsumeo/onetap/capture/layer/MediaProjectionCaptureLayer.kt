package vn.phapsumeo.onetap.capture.layer

import android.content.Context
import kotlinx.coroutines.flow.first
import vn.phapsumeo.onetap.capture.CaptureBridge
import vn.phapsumeo.onetap.capture.ScreenCaptureHelper
import vn.phapsumeo.onetap.capture.ScreenCaptureSession

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
        if (helper.hasLiveProjection()) {
            return toFrame(helper.capture(cachedOrEmptyGrant()))
        }

        val cached = ScreenCaptureSession.readToken(context)
        if (cached != null) {
            val reused = helper.capture(
                CaptureBridge.ProjectionGrant.Granted(
                    sessionId = 0L,
                    resultCode = cached.first,
                    data = cached.second,
                ),
            )
            if (reused is CaptureBridge.CaptureResult.Success || helper.hasLiveProjection()) {
                return toFrame(reused)
            }
        }

        val sessionId = CaptureBridge.beginSession()
        launcher.launchForSession(sessionId)
        val grant = CaptureBridge.projectionGrant.first { it.sessionId == sessionId }
        if (grant is CaptureBridge.ProjectionGrant.Granted) {
            ScreenCaptureSession.save(context, grant.resultCode, grant.data)
        }
        val result = when (grant) {
            is CaptureBridge.ProjectionGrant.Granted -> helper.capture(grant)
            is CaptureBridge.ProjectionGrant.Denied -> CaptureBridge.CaptureResult.Failure(grant.reason)
        }
        return toFrame(result)
    }

    private fun cachedOrEmptyGrant(): CaptureBridge.ProjectionGrant.Granted {
        val cached = ScreenCaptureSession.readToken(context)
        return if (cached != null) {
            CaptureBridge.ProjectionGrant.Granted(0L, cached.first, cached.second)
        } else {
            CaptureBridge.ProjectionGrant.Granted(0L, android.app.Activity.RESULT_OK, android.content.Intent())
        }
    }

    private fun toFrame(result: CaptureBridge.CaptureResult): CaptureFrame =
        when (result) {
            is CaptureBridge.CaptureResult.Success -> CaptureFrame(
                bitmap = result.bitmap,
                sourceId = "media_projection",
            )
            is CaptureBridge.CaptureResult.Failure -> throw IllegalStateException(result.error)
        }
}


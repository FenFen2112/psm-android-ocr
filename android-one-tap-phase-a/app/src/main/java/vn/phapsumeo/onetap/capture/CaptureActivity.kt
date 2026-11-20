package vn.phapsumeo.onetap.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Activity trong suốt, duy nhất mục đích: xin quyền [MediaProjectionManager].
 *
 * ### Bug 1 đã fix — không còn circular emit
 * Phiên bản cũ có 2 vấn đề trong `observeProjectionRequest()`:
 * ```kotlin
 * // VẤN ĐỀ: Activity vừa collect projectionRequest...
 * CaptureBridge.projectionRequest.collect { projectionLauncher.launch(...) }
 * // ...vừa tự emit requestProjection() → dialog mở 2 lần
 * CaptureBridge.requestProjection()
 * ```
 * Fix: Activity mở dialog ngay trong `onCreate()` — không cần signal từ Service.
 * CaptureActivity là single-use (start mỗi lần tap bubble), nên mở dialog ngay là đúng.
 *
 * ### Bug 2 đã fix — sessionId gắn vào grant
 * Activity đọc [EXTRA_SESSION_ID] từ Intent và đính vào [CaptureBridge.ProjectionGrant].
 * Service filter `.first { it.sessionId == expectedId }` để không nhận grant cũ.
 *
 * ### Android 14 — token không tái sử dụng
 * Từ API 34, mỗi lần muốn chụp phải xin lại quyền. BubbleOverlayService start lại
 * Activity này mỗi lần tap. Không cache token.
 */
class CaptureActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SESSION_ID = "capture_session_id"
        private const val INVALID_SESSION = -1L

        /**
         * Tạo Intent để BubbleOverlayService start Activity.
         * @param sessionId Từ [CaptureBridge.beginSession] — dùng để filter stale grant.
         */
        fun createIntent(context: Context, sessionId: Long): Intent =
            Intent(context, CaptureActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
                putExtra(EXTRA_SESSION_ID, sessionId)
            }
    }

    private lateinit var projectionManager: MediaProjectionManager
    private var sessionId: Long = INVALID_SESSION

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            lifecycleScope.launch {
                val grant = if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                    CaptureBridge.ProjectionGrant.Granted(
                        sessionId = sessionId,
                        resultCode = result.resultCode,
                        data = result.data!!,
                    )
                } else {
                    CaptureBridge.ProjectionGrant.Denied(sessionId = sessionId)
                }
                CaptureBridge.deliverGrant(grant)
            }
            finishAndAnimateOut()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sessionId = intent.getLongExtra(EXTRA_SESSION_ID, INVALID_SESSION)
        if (sessionId == INVALID_SESSION) {
            // Không có sessionId hợp lệ — không thể deliver grant đúng session, bỏ qua
            finishAndAnimateOut()
            return
        }

        projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        // FIX: mở dialog ngay — không collect/emit bất kỳ Bridge signal nào
        if (savedInstanceState == null) {
            projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
        }
    }

    private fun finishAndAnimateOut() {
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
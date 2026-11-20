package vn.phapsumeo.onetap.capture

import android.content.Intent
import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Cầu nối 1 chiều giữa BubbleOverlayService ↔ CaptureActivity.
 *
 * ### Thiết kế (sau khi fix 3 bug):
 *
 * **Bug 1 đã fix — projectionRequest bị xóa hoàn toàn.**
 * Trước đây, Activity vừa collect `projectionRequest` vừa tự emit `requestProjection()` →
 * circular emit, dialog mở 2 lần. Fix: Activity không cần signal — nó mở dialog
 * ngay khi `onCreate()` được gọi (CaptureActivity là single-use, được start mỗi lần tap bubble).
 *
 * **Bug 2 đã fix — sessionId trên mỗi ProjectionGrant.**
 * `projectionGrant.first()` không có filter có thể consume grant từ capture TRƯỚC đó
 * vẫn còn nằm trong buffer `extraBufferCapacity=1`. Fix: Service tạo sessionId mới qua
 * [beginSession] trước mỗi capture, Activity đính kèm sessionId vào grant,
 * Service filter `.first { it.sessionId == expectedId }`.
 *
 * **Bug 3 đã fix — captureResult bị xóa hoàn toàn.**
 * Trước đây Service gọi `handleCaptureResult()` trực tiếp sau `captureHelper.capture()` VÀ
 * cũng collect `captureResult` flow → double processing. Fix: ScreenCaptureHelper trả kết quả
 * trực tiếp qua suspend function, không đi qua Bridge. Bridge chỉ còn 1 flow duy nhất.
 *
 * Dùng [SharedFlow] để không giữ strong reference vào Activity/Service → không leak.
 */
object CaptureBridge {

    // ── Session tracking — ngăn stale grant ─────────────────────────────────────

    @Volatile
    private var currentSessionId: Long = 0L

    /**
     * Service gọi trước mỗi lần tap bubble để tạo session mới.
     * SessionId được đính vào [ProjectionGrant] và dùng để filter stale grant.
     * @return sessionId mới — Service lưu lại để dùng khi filter.
     */
    fun beginSession(): Long {
        currentSessionId = System.nanoTime()
        return currentSessionId
    }

    // ── Activity → Service: kết quả MediaProjection (granted hoặc denied) ───────
    private val _projectionGrant = MutableSharedFlow<ProjectionGrant>(extraBufferCapacity = 1)
    val projectionGrant: SharedFlow<ProjectionGrant> = _projectionGrant.asSharedFlow()

    /**
     * CaptureActivity gọi sau khi user grant/deny MediaProjection.
     * @param sessionId Lấy từ Intent extra [CaptureActivity.EXTRA_SESSION_ID].
     */
    suspend fun deliverGrant(grant: ProjectionGrant) {
        _projectionGrant.emit(grant)
    }

    // ── Data classes ─────────────────────────────────────────────────────────────

    /**
     * Kết quả từ CaptureActivity — bao gồm sessionId để Service lọc grant đúng session.
     */
    sealed class ProjectionGrant {
        abstract val sessionId: Long

        data class Granted(
            override val sessionId: Long,
            val resultCode: Int,
            val data: Intent,
        ) : ProjectionGrant()

        data class Denied(
            override val sessionId: Long,
            val reason: String = "Người dùng từ chối quyền chụp màn hình.",
        ) : ProjectionGrant()
    }

    /**
     * Kết quả capture từ [ScreenCaptureHelper] — trả trực tiếp qua suspend function,
     * KHÔNG đi qua Bridge (tránh double-processing).
     */
    sealed class CaptureResult {
        data class Success(val bitmap: Bitmap) : CaptureResult()
        data class Failure(val error: String) : CaptureResult()
    }
}
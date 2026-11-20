package vn.phapsumeo.onetap.capture

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import java.net.URISyntaxException

/**
 * Lưu trữ token MediaProjection (resultCode + Intent) để dùng lại khi chụp màn hình.
 * Token được persist vào SharedPreferences để tồn tại qua app restart.
 *
 * Quan trọng: chỉ lưu ClipData URI (bản cũ) thường **không đủ** để khôi phục quyền trên nhiều máy/Android.
 * Ưu tiên lưu toàn bộ Intent qua [Intent.toUri] / [Intent.parseUri].
 */
object ScreenCaptureSession {
    private const val PREFS_NAME = "screen_capture_session"
    private const val KEY_RESULT_CODE = "result_code"
    /** Serialize đầy đủ Intent (EXTRA của MediaProjection). */
    private const val KEY_INTENT_URI = "intent_uri_scheme"
    /** Legacy — chỉ ClipData; giữ để đọc bản cũ rồi migrate khi save lại. */
    private const val KEY_CLIP_DATA_URI = "clip_data_uri"

    private const val TAG = "PSM_CAPTURE_SESSION"

    @Volatile
    private var cachedResultCode: Int? = null
    @Volatile
    private var cachedDataIntent: Intent? = null

    /**
     * Lưu token MediaProjection vào memory cache.
     * Android 14+ hạn chế dùng lại token từ disk, nên ta chỉ giữ trong memory.
     */
    fun save(context: Context, resultCode: Int, data: Intent) {
        cachedResultCode = resultCode
        cachedDataIntent = Intent(data)
        
        // Xóa bản lưu trên disk nếu có để tránh stale token
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
    }

    fun hasPermissionToken(context: Context): Boolean {
        return cachedResultCode != null && cachedDataIntent != null
    }

    /**
     * Đọc token từ memory cache.
     */
    fun readToken(context: Context): Pair<Int, Intent>? {
        val code = cachedResultCode
        val data = cachedDataIntent
        if (code != null && data != null) {
            return code to Intent(data)
        }

        // Android 14+: không dùng disk persistence → trả null nếu memory cache trống
        Log.d(TAG, "readToken: no cached token in memory")
        return null
    }

    fun clear(context: Context) {
        cachedResultCode = null
        cachedDataIntent = null
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
    }
}

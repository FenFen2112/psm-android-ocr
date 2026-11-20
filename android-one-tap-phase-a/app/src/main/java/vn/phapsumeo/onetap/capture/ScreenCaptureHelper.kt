package vn.phapsumeo.onetap.capture

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

private const val TAG = "ScreenCaptureHelper"
private const val VIRTUAL_DISPLAY_NAME = "PSMCapture"
private const val MAX_IMAGES_IN_FLIGHT = 2
private const val MAX_ACQUIRE_ATTEMPTS = 60
private const val RETRY_MS = 50L

/**
 * VirtualDisplay + ImageReader → Bitmap.
 *
 * - Retry khi [acquireLatestImage] trả null (frame chưa sẵn sàng), có giới hạn, trên [backgroundHandler].
 * - [MediaProjection.Callback]: Android 14+ stop / visibility — [release] idempotent, tránh đệ quy onStop.
 */
class ScreenCaptureHelper(private val context: Context) {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    @Volatile
    private var isReleasing = false

    /** Token đã đưa vào getMediaProjection — Android chỉ cho dùng một lần. */
    @Volatile
    private var tokenSpent = false

    private var captureWidth = 0
    private var captureHeight = 0
    private val captureMutex = Mutex()

    fun hasLiveProjection(): Boolean =
        mediaProjection != null && virtualDisplay != null && imageReader != null && backgroundHandler != null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.w(TAG, "MediaProjection stopped by system — releasing resources")
            release()
            // Notify system that projection is no longer valid
            val intent = Intent("vn.phapsumeo.onetap.PROJECTION_STOPPED")
            context.sendBroadcast(intent)
        }

        override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                Log.d(TAG, "Captured content visibility: $isVisible")
            }
        }
    }

    /**
     * Giữ một MediaProjection + VirtualDisplay cho cả phiên bong bóng.
     * Lần quét sau chỉ lấy frame mới, không gọi lại hộp thoại quyền.
     */
    suspend fun capture(
        grant: CaptureBridge.ProjectionGrant.Granted,
    ): CaptureBridge.CaptureResult = captureMutex.withLock {
        try {
            val reusing = hasLiveProjection()
            if (!reusing) {
                openSession(grant)?.let { return@withLock it }
            } else if (displaySizeChanged()) {
                Log.w(TAG, "Display size changed — projection needs a new grant")
                tokenSpent = true
                release()
                return@withLock CaptureBridge.CaptureResult.Failure(
                    "Màn hình đổi kích thước. Hãy quét lại để cấp quyền chụp.",
                )
            } else {
                delay(80)
            }
            val reader = imageReader
            val handler = backgroundHandler
            if (reader == null || handler == null || captureWidth <= 0 || captureHeight <= 0) {
                return@withLock CaptureBridge.CaptureResult.Failure("Phiên chụp chưa sẵn sàng.")
            }
            val bitmap = awaitNextBitmap(reader, captureWidth, captureHeight, handler)
            if (bitmap != null) {
                Log.d(TAG, "Capture success: ${bitmap.width}x${bitmap.height} reuse=$reusing")
                CaptureBridge.CaptureResult.Success(bitmap)
            } else {
                CaptureBridge.CaptureResult.Failure("Không lấy được frame từ màn hình.")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "capture() exception", e)
            tokenSpent = true
            release()
            CaptureBridge.CaptureResult.Failure("Lỗi capture: ${e.message}")
        }
    }

    /** @return lỗi nếu không mở được phiên; null khi VirtualDisplay đang chạy. */
    private fun openSession(grant: CaptureBridge.ProjectionGrant.Granted): CaptureBridge.CaptureResult.Failure? {
        val metrics = realDisplayMetrics()
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi
        Log.d(TAG, "Open projection: ${width}x${height} @${density}dpi")

        startBackgroundThread()
        val handler = backgroundHandler
            ?: return CaptureBridge.CaptureResult.Failure("Không khởi tạo được thread chụp.")

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, MAX_IMAGES_IN_FLIGHT)
        imageReader = reader
        captureWidth = width
        captureHeight = height

        val projManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        tokenSpent = true
        val projection = try {
            projManager.getMediaProjection(grant.resultCode, grant.data)
        } catch (e: Exception) {
            Log.e(TAG, "getMediaProjection failed", e)
            null
        }
        ScreenCaptureSession.clear(context)
        if (projection == null) {
            releaseCaptureResources()
            return CaptureBridge.CaptureResult.Failure("MediaProjection null. Hãy cấp quyền chụp lại.")
        }
        mediaProjection = projection
        projection.registerCallback(projectionCallback, handler)
        virtualDisplay = projection.createVirtualDisplay(
            VIRTUAL_DISPLAY_NAME,
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            handler,
        )
        return null
    }

    private fun displaySizeChanged(): Boolean {
        val metrics = realDisplayMetrics()
        return metrics.widthPixels != captureWidth || metrics.heightPixels != captureHeight
    }

    private suspend fun awaitNextBitmap(
        reader: ImageReader,
        width: Int,
        height: Int,
        handler: Handler,
    ): Bitmap? = suspendCancellableCoroutine { cont ->
        val lock = Any()
        var finished = false
        var attempts = 0

        fun finish(bmp: Bitmap?) {
            val should = synchronized(lock) {
                if (finished) false else {
                    finished = true
                    true
                }
            }
            if (!should) {
                bmp?.recycle()
                return
            }
            reader.setOnImageAvailableListener(null, null)
            if (cont.isActive) cont.resume(bmp)
        }

        fun tryAcquire(reason: String) {
            synchronized(lock) {
                if (finished) return
            }
            attempts++
            val image = reader.acquireLatestImage()
            if (image != null) {
                try {
                    finish(imageToBitmap(image, width, height))
                } catch (e: Exception) {
                    Log.e(TAG, "imageToBitmap error", e)
                    finish(null)
                } finally {
                    runCatching { image.close() }
                }
                return
            }
            Log.v(TAG, "acquireLatestImage null attempt=$attempts reason=$reason")
            if (attempts >= MAX_ACQUIRE_ATTEMPTS) {
                finish(null)
                return
            }
            handler.postDelayed({ tryAcquire("retry") }, RETRY_MS)
        }

        drain(reader)
        reader.setOnImageAvailableListener({ tryAcquire("callback") }, handler)
        handler.post { tryAcquire("kick") }

        cont.invokeOnCancellation {
            reader.setOnImageAvailableListener(null, null)
        }
    }

    private fun drain(reader: ImageReader) {
        while (true) {
            val image = reader.acquireLatestImage() ?: break
            runCatching { image.close() }
        }
    }

    private fun imageToBitmap(image: android.media.Image, width: Int, height: Int): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width

        val paddedWidth = width + rowPadding / pixelStride
        val rawBitmap = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        rawBitmap.copyPixelsFromBuffer(buffer)

        return if (rowPadding > 0) {
            val cropped = Bitmap.createBitmap(rawBitmap, 0, 0, width, height)
            rawBitmap.recycle()
            cropped
        } else {
            rawBitmap
        }
    }

    @Suppress("DEPRECATION")
    private fun realDisplayMetrics(): DisplayMetrics {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            DisplayMetrics().apply {
                widthPixels = bounds.width()
                heightPixels = bounds.height()
                densityDpi = context.resources.displayMetrics.densityDpi
            }
        } else {
            DisplayMetrics().also { wm.defaultDisplay.getRealMetrics(it) }
        }
    }

    private fun startBackgroundThread() {
        stopBackgroundThread()
        backgroundThread = HandlerThread("PSMCaptureThread").also { thread ->
            thread.start()
            backgroundHandler = Handler(thread.looper)
        }
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join(500)
        } catch (_: InterruptedException) {
        }
        backgroundThread = null
        backgroundHandler = null
    }

    private fun unregisterProjectionCallbackSafe() {
        val mp = mediaProjection
        if (mp != null) {
            runCatching { mp.unregisterCallback(projectionCallback) }
        }
    }

    /**
     * Giải phóng VirtualDisplay, ImageReader, MediaProjection.
     * Idempotent — có thể gọi nhiều lần an toàn.
     * Được gọi từ projectionCallback.onStop() và cancellation.
     */
    private fun releaseCaptureResources() {
        if (isReleasing) return
        isReleasing = true

        runCatching { virtualDisplay?.release() }
        virtualDisplay = null

        runCatching { imageReader?.close() }
        imageReader = null

        runCatching { mediaProjection?.stop() }
        mediaProjection = null
        captureWidth = 0
        captureHeight = 0

        stopBackgroundThread()
        isReleasing = false
    }

    /**
     * Public release method — caller có thể gọi để cleanup thủ công nếu cần.
     */
    fun release() {
        releaseCaptureResources()
        if (tokenSpent) {
            ScreenCaptureSession.clear(context)
            tokenSpent = false
        }
    }
}
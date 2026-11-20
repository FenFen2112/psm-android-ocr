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
import kotlinx.coroutines.suspendCancellableCoroutine
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

    suspend fun capture(
        grant: CaptureBridge.ProjectionGrant.Granted,
    ): CaptureBridge.CaptureResult {
        return try {
            val metrics = realDisplayMetrics()
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.densityDpi

            Log.d(TAG, "Capture start: ${width}x${height} @${density}dpi")

            startBackgroundThread()
            val handler = backgroundHandler
                ?: return CaptureBridge.CaptureResult.Failure("Không khởi tạo được thread chụp.")

            val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, MAX_IMAGES_IN_FLIGHT)
            imageReader = reader

            val projManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = projManager.getMediaProjection(grant.resultCode, grant.data)
                ?: return CaptureBridge.CaptureResult.Failure("MediaProjection null.")
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

            val bitmap = try {
                awaitFirstBitmap(reader, width, height, handler)
            } finally {
                unregisterProjectionCallbackSafe()
                releaseCaptureResources()
            }

            if (bitmap != null) {
                Log.d(TAG, "Capture success: ${bitmap.width}x${bitmap.height}")
                CaptureBridge.CaptureResult.Success(bitmap)
            } else {
                CaptureBridge.CaptureResult.Failure("Không lấy được frame từ màn hình.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "capture() exception", e)
            unregisterProjectionCallbackSafe()
            releaseCaptureResources()
            CaptureBridge.CaptureResult.Failure("Lỗi capture: ${e.message}")
        }
    }

    private suspend fun awaitFirstBitmap(
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

        reader.setOnImageAvailableListener({ tryAcquire("callback") }, handler)
        handler.post { tryAcquire("kick") }

        cont.invokeOnCancellation {
            reader.setOnImageAvailableListener(null, null)
            unregisterProjectionCallbackSafe()
            releaseCaptureResources()
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

        stopBackgroundThread()
        isReleasing = false
    }

    /**
     * Public release method — caller có thể gọi để cleanup thủ công nếu cần.
     */
    fun release() {
        releaseCaptureResources()
    }
}
package vn.phapsumeo.onetap.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import vn.phapsumeo.onetap.MainActivity
import vn.phapsumeo.onetap.capture.CaptureActivity
import vn.phapsumeo.onetap.capture.ScreenCaptureHelper
import vn.phapsumeo.onetap.capture.layer.CaptureActivityLauncher
import vn.phapsumeo.onetap.capture.layer.ImagePreprocessor
import vn.phapsumeo.onetap.capture.layer.OrderCardCropPreprocessor
import vn.phapsumeo.onetap.capture.layer.MediaProjectionCaptureLayer
import vn.phapsumeo.onetap.gas.GasClient
import vn.phapsumeo.onetap.model.OrderDraft
import vn.phapsumeo.onetap.debug.OcrCropDebugLog
import vn.phapsumeo.onetap.debug.dataset.OcrDatasetFacade
import vn.phapsumeo.onetap.debug.dataset.OcrDatasetRecordBuilder
import vn.phapsumeo.onetap.debug.ocr.OcrCropRegionsDebugPainter
import vn.phapsumeo.onetap.debug.ocr.OcrDebugSessionStore
import vn.phapsumeo.onetap.debug.ocr.OcrDebugSnapshotBuilder
import vn.phapsumeo.onetap.debug.ocr.OcrDebugUiHeuristic
import vn.phapsumeo.onetap.util.PrefsStore
import vn.phapsumeo.onetap.util.RetryQueueStore

class BubbleOverlayService : Service() {
    private companion object {
        const val TAG = "PSM_BUBBLE"
        const val MULTI_REGION_OCR_TIMEOUT_MS = 12_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wm: WindowManager? = null
    private var bubbleView: View? = null
    private var removeView: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var removeParams: WindowManager.LayoutParams? = null

    private var lastInteractionTime = System.currentTimeMillis()
    private var captureHelper: ScreenCaptureHelper? = null
    private lateinit var captureLayer: MediaProjectionCaptureLayer
    private val productPreprocessor: ImagePreprocessor = OrderCardCropPreprocessor()

    private var bubbleBlurIv: ImageView? = null
    private var bubbleThumbIv: ImageView? = null
    private var bubbleFallbackTv: TextView? = null
    private var previousBubbleBlur: Bitmap? = null
    private var previousBubbleThumb: Bitmap? = null

    override fun onCreate() {
        super.onCreate()
        captureHelper = ScreenCaptureHelper(this)
        captureLayer = MediaProjectionCaptureLayer(this, captureHelper!!)
        startAsForegroundService()
        setupBubble()
        startIdleMonitor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { wm?.removeView(bubbleView) }
        runCatching { wm?.removeView(removeView) }
        bubbleView = null
        removeView = null
        recycleBubblePreviewBitmaps()
        bubbleBlurIv = null
        bubbleThumbIv = null
        bubbleFallbackTv = null
        captureHelper?.release()
        captureHelper = null
        scope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun setupBubble() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 300
        }

        val density = resources.displayMetrics.density
        val bubbleSize = (60 * density).toInt()
        val cornerPx = 12 * density

        val inner = android.widget.FrameLayout(this).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(bubbleSize, bubbleSize, Gravity.CENTER)
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, cornerPx)
                }
            }
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = cornerPx
                setColor(0x88000000.toInt())
                setStroke((2 * density).toInt(), 0xFFFFFFFF.toInt())
            }
        }

        val blurIv = ImageView(this).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.INVISIBLE
        }

        val thumbInset = max(2, (5 * density).toInt())
        val thumbIv = ImageView(this).apply {
            val thumbSide = max(1, bubbleSize - thumbInset * 2)
            layoutParams = android.widget.FrameLayout.LayoutParams(
                thumbSide,
                thumbSide,
                Gravity.CENTER,
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.INVISIBLE
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    val r = max(1f, cornerPx - thumbInset)
                    outline.setRoundRect(0, 0, view.width, view.height, r)
                }
            }
        }

        val tv = TextView(this).apply {
            text = "PSM"
            gravity = Gravity.CENTER
            textSize = 16f
            setPadding(0, 0, 0, 0)
            setTextColor(0xFFFFFFFF.toInt())
            val shape = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = cornerPx
                setColor(0xCCFF6D00.toInt())
                setStroke((2 * density).toInt(), 0xFFFFFFFF.toInt())
            }
            background = shape
            layoutParams = android.widget.FrameLayout.LayoutParams(bubbleSize, bubbleSize, Gravity.CENTER)
        }

        inner.addView(blurIv)
        inner.addView(thumbIv)
        inner.addView(tv)

        bubbleBlurIv = blurIv
        bubbleThumbIv = thumbIv
        bubbleFallbackTv = tv

        // Wrap bubble in a FrameLayout to ensure touch events are captured reliably
        val bubbleContainer = android.widget.FrameLayout(this).apply {
            layoutParams = params
            addView(inner)
        }
        bubbleView = bubbleContainer

        // Setup Remove View (X)
        removeParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            (100 * resources.displayMetrics.density).toInt(),
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        }

        removeView = TextView(this).apply {
            text = "KÉO VÀO ĐÂY ĐỂ TẮT ✕"
            gravity = Gravity.CENTER
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            val shape = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = (20 * resources.displayMetrics.density)
                setColor(0xAAFF0000.toInt())
            }
            background = shape
            visibility = View.GONE
        }
        
        val removeW = (220 * resources.displayMetrics.density).toInt()
        val removeH = (50 * resources.displayMetrics.density).toInt()
        val removeContainer = android.widget.FrameLayout(this).apply {
            addView(removeView, android.widget.FrameLayout.LayoutParams(removeW, removeH, Gravity.CENTER))
        }
        removeView = removeContainer

        attachDragMove(bubbleContainer) { 
            Log.d("PSM_BUBBLE", "[DEBUG-BUBBLE] lambda onTap called")
            onBubbleClick() 
        }
        wm?.addView(bubbleContainer, params)
        wm?.addView(removeContainer, removeParams)
    }

    private fun onBubbleClick() {
        Log.d("PSM_BUBBLE", "[DEBUG-BUBBLE] onBubbleClick entered")
        logMemoryHealth("onBubbleClick:entry")
        updateInteraction()

        scope.launch {
            var capturedBitmap: Bitmap? = null
            try {
                val bubblePrefs = PrefsStore(this@BubbleOverlayService)
                Log.d("PSM_BUBBLE", "[DEBUG-BUBBLE] click:start via CaptureActivity")
                
                // Show loading state
                bubbleFallbackTv?.text = "..."
                bubbleFallbackTv?.visibility = View.VISIBLE
                bubbleThumbIv?.alpha = 0.3f

                capturedBitmap = try {
                    // Hide bubble before capture
                    bubbleView?.visibility = View.GONE

                    captureLayer.capture(
                        CaptureActivityLauncher { sessionId ->
                            val intent = CaptureActivity.createIntent(this@BubbleOverlayService, sessionId)
                            startActivity(intent)
                        },
                    ).bitmap
                } finally {
                    bubbleView?.visibility = View.VISIBLE
                    bubbleFallbackTv?.text = "PSM"
                    bubbleThumbIv?.alpha = 1.0f
                }
                val bitmap = capturedBitmap ?: return@launch
                Log.d("PSM_BUBBLE", "[DEBUG-BUBBLE] capture:ok size=${bitmap.width}x${bitmap.height}")
                updateBubbleFromCapture(bitmap)

                // Cắt thumbnail sản phẩm (phân tích pixel, không bbox OCR) — chạy nền tránh giật UI.
                val preprocess = withContext(Dispatchers.Default) {
                    productPreprocessor.preprocess(bitmap)
                }
                val productBmp = preprocess.productBitmap
                val cropForDebug = preprocess.cropRectInCapture
                val titleCropCsv = cropBoundsCsv(preprocess.titleCropRectInCapture)
                val priceCropCsv = cropBoundsCsv(preprocess.priceCropRectInCapture)
                val thumbCropCsv = cropBoundsCsv(preprocess.thumbnailCropRectInCapture)
                OcrCropRegionsDebugPainter.logCropCoordinates(
                    bitmap.width,
                    bitmap.height,
                    preprocess.thumbnailCropRectInCapture,
                    preprocess.titleCropRectInCapture,
                    preprocess.priceCropRectInCapture,
                )
                // #region agent log
                if (bubblePrefs.developerMode()) {
                    OcrCropDebugLog.append(
                        this@BubbleOverlayService,
                        hypothesisId = "H_pipeline",
                        location = "BubbleOverlayService.onBubbleClick",
                        message = "after_product_preprocess",
                        data = mapOf(
                            "fullW" to bitmap.width,
                            "fullH" to bitmap.height,
                            "productSameAsFull" to (productBmp === bitmap),
                            "cropRectNull" to (cropForDebug == null),
                            "productW" to productBmp.width,
                            "productH" to productBmp.height,
                            "hasTitleRegion" to (preprocess.titleBitmap != null),
                            "hasThumbRegion" to (preprocess.thumbnailCropRectInCapture != null),
                            "hasPriceRegion" to (preprocess.priceBitmap != null),
                        ),
                    )
                } else {
                    android.util.Log.i(
                        "PSM_GATE",
                        """{"sessionId":"418c16","hypothesisId":"H3","location":"BubbleOverlayService.onBubbleClick","skippedCropNdjson":true,"reason":"devMode_off"}""",
                    )
                }
                // #endregion

                val sheetBmp = productPhotoForSheet(bitmap, preprocess.thumbnailCropRectInCapture, productBmp)
                val imageUri = withContext(Dispatchers.IO) {
                    saveBitmapToCache(sheetBmp)
                }
                if (sheetBmp !== productBmp && sheetBmp !== bitmap && !sheetBmp.isRecycled) sheetBmp.recycle()
                if (productBmp !== bitmap) productBmp.recycle()

                Log.d("PSM_BUBBLE", "[DEBUG-BUBBLE] image saved: $imageUri")

                // #region agent log
                agentDebugLog(
                    "H5",
                    "BubbleOverlayService.onBubbleClick",
                    "before_multi_region_ocr",
                    mapOf(
                        "hasTitleBmp" to (preprocess.titleBitmap != null),
                        "hasTitleTextOcrBmp" to (preprocess.titleTextOcrBitmap != null),
                        "hasPriceBmp" to (preprocess.priceBitmap != null),
                        "titleFullRectCsv" to cropBoundsCsv(preprocess.titleCropRectInCapture),
                        "titleTextOcrRectCsv" to cropBoundsCsv(preprocess.titleTextOcrCropRectInCapture),
                        "titleTextOcrBmpW" to (preprocess.titleTextOcrBitmap?.width ?: -1),
                        "titleTextOcrBmpH" to (preprocess.titleTextOcrBitmap?.height ?: -1),
                    ),
                )
                // #endregion

                Log.d(TAG, "Pre OCR: bitmap=${bitmap.width}x${bitmap.height}, preprocess=${preprocess != null}")
    
                val mr = withContext(Dispatchers.Default) {
                    withTimeoutOrNull(MULTI_REGION_OCR_TIMEOUT_MS) {
                        BubbleMultiRegionOcr.build(
                            fullW = bitmap.width,
                            fullH = bitmap.height,
                            imageUri = imageUri.orEmpty(),
                            preprocess = preprocess,
                        )
                    } ?: BubbleMultiRegionOcr.Result(
                        draft = OrderDraft(sharedImageUri = imageUri.orEmpty()),
                        combinedLines = emptyList(),
                        combinedRawText = "=== OCR TIMEOUT ===",
                        parserDecisions = listOf("multiRegion:timeout"),
                    )
                }
                if (mr.parserDecisions.contains("multiRegion:timeout")) {
                    agentDebugLog(
                        "H_ocr_timeout",
                        "BubbleOverlayService.onBubbleClick",
                        "multi_region_ocr_timeout",
                        mapOf(
                            "timeoutMs" to MULTI_REGION_OCR_TIMEOUT_MS,
                            "hasTitleBmp" to (preprocess.titleBitmap != null),
                            "hasPriceBmp" to (preprocess.priceBitmap != null),
                        ),
                    )
                }

                // Debug nặng (copy full màn + PNG + server) chỉ khi bật chế độ nhà phát triển, và không chạy trên main.
                if (bubblePrefs.developerMode()) {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            vn.phapsumeo.onetap.debug.OcrAdvancedDebug.init(this@BubbleOverlayService)
                            vn.phapsumeo.onetap.debug.OcrAdvancedDebug.logOcrRun(
                                context = this@BubbleOverlayService,
                                fullBmp = bitmap,
                                titleBmp = preprocess.titleBitmap,
                                priceBmp = preprocess.priceBitmap,
                                titleRaw = mr.titleRegionOcrText,
                                priceRaw = mr.priceRegionOcrText,
                                parsedTitle = mr.draft.tenSanPham,
                                parsedPrice = mr.draft.tongCn ?: 0.0,
                                confidence = 0.8f,
                                titleRect = preprocess.titleCropRectInCapture,
                                priceRect = preprocess.priceCropRectInCapture,
                            )
                        }.onFailure { e ->
                            Log.e(TAG, "Debug logging failed, but continuing...", e)
                        }
                    }
                }

                // Ảnh khung vùng chỉ khi debug. Luồng thường dùng ảnh thẻ đã lưu, không encode full màn thêm lần nữa.
                val cropDebugSave = if (bubblePrefs.developerMode()) {
                    withContext(Dispatchers.Default) {
                        runCatching {
                            val painted = OcrCropRegionsDebugPainter.draw(
                                bitmap,
                                preprocess.thumbnailCropRectInCapture,
                                preprocess.titleCropRectInCapture,
                                preprocess.priceCropRectInCapture,
                            )
                            try {
                                saveCropDebugBitmapToCache(painted)
                            } finally {
                                if (!painted.isRecycled && painted !== bitmap) painted.recycle()
                            }
                        }.getOrNull()
                    }
                } else {
                    null
                }
                val cropDebugAnnotatedUri = cropDebugSave?.contentUri
                val cropDebugAbsPath = cropDebugSave?.absolutePath.orEmpty()
                // #region agent log
                agentDebugLog(
                    "H_crop_viz",
                    "BubbleOverlayService.onBubbleClick",
                    "crop_debug_saved",
                    mapOf(
                        "uriLen" to (cropDebugAnnotatedUri?.length ?: 0),
                        "absPathLen" to cropDebugAbsPath.length,
                        "titleOcrLen" to mr.titleRegionOcrText.length,
                        "priceOcrLen" to mr.priceRegionOcrText.length,
                        "titleRect" to titleCropCsv,
                        "thumbRect" to thumbCropCsv,
                        "priceRect" to priceCropCsv,
                    ),
                )
                // #endregion

                preprocess.titleBitmap?.takeIf { !it.isRecycled }?.recycle()
                preprocess.titleTextOcrBitmap?.takeIf { !it.isRecycled }?.recycle()
                preprocess.priceBitmap?.takeIf { !it.isRecycled }?.recycle()

                val ocrLines = mr.combinedLines
                val fullText = mr.combinedRawText
                val draft = mr.draft

                // #region agent log
                agentDebugLog(
                    "H6",
                    "BubbleOverlayService.onBubbleClick",
                    "after_multi_region_ocr",
                    mapOf(
                        "lines" to ocrLines.size,
                        "titleLen" to draft.tenSanPham.length,
                        "hasTong" to (draft.tongCn != null),
                        "verified" to draft.tongTienDaXacMinh,
                        "decisions" to mr.parserDecisions.joinToString("|"),
                        "titleOcrPreview" to mr.titleRegionOcrText.take(120).replace("\n", "|"),
                    ),
                )
                // #endregion

                Log.d(
                    "PSM_BUBBLE",
                    "[DEBUG-BUBBLE] parse:ok tracking=${draft.maVanChuyen} product=${draft.tenSanPham.take(80)} tong=${draft.tongCn} fast=${PrefsStore(this@BubbleOverlayService).bubbleFastMode()}",
                )

                if (bubblePrefs.developerMode() && bubblePrefs.ocrDebugVisualization()) {
                    withContext(Dispatchers.Default) {
                        runCatching {
                            val snap = OcrDebugSnapshotBuilder.build(
                                linesCapture = ocrLines,
                                captureWidth = bitmap.width,
                                captureHeight = bitmap.height,
                                cropInCapture = cropForDebug,
                                draft = draft,
                                parserDecisions = mr.parserDecisions + listOf(
                                    "Nguồn: OCR đa vùng (title / giá)",
                                    "Kích thước ảnh: ${bitmap.width}x${bitmap.height}",
                                    "Số dòng nhận diện (gộp): ${ocrLines.size}",
                                    "Chế độ gửi nhanh: ${if (bubblePrefs.bubbleFastMode()) "bật" else "tắt"}",
                                    "Ảnh đã cắt vùng chính: ${if (cropForDebug != null) "có" else "không"}",
                                ),
                                heuristicUiPercent = OcrDebugUiHeuristic.percent(draft),
                            )
                            OcrDebugSessionStore.publish(snap)
                        }.onFailure { ex ->
                            Log.w("PSM_BUBBLE", "ocr_debug_publish: ${ex.message}")
                        }
                    }
                }

                val ocrDatasetRecordId =
                    if (bubblePrefs.developerMode() && bubblePrefs.ocrDatasetLogging()) {
                        OcrDatasetRecordBuilder.newRecordId()
                    } else {
                        null
                    }
                if (ocrDatasetRecordId != null) {
                    withContext(Dispatchers.Default) {
                        runCatching {
                            OcrDatasetFacade.appendBubbleCapture(
                                this@BubbleOverlayService,
                                ocrDatasetRecordId,
                                ocrLines,
                                bitmap.width,
                                bitmap.height,
                                cropForDebug,
                                draft,
                                draft.maVanChuyen.isBlank() && draft.maDonHang.isBlank() &&
                                    draft.tenSanPham.isBlank() && draft.tongCn == null,
                            )
                        }.onFailure { ex ->
                            Log.w("PSM_BUBBLE", "ocr_dataset_log: ${ex.message}")
                        }
                    }
                }

                if (draft.maVanChuyen.isBlank() && draft.maDonHang.isBlank() &&
                    draft.tenSanPham.isBlank() && draft.tongCn == null
                ) {
                    Log.d("PSM_BUBBLE", "[DEBUG-BUBBLE] parse:empty -> preview")
                    launchMainForBubblePreview(
                        draft,
                        fullText,
                        imageUri,
                        ocrDatasetRecordId,
                        cropDebugAnnotatedUri,
                        mr.titleRegionOcrText,
                        mr.priceRegionOcrText,
                        titleCropCsv,
                        priceCropCsv,
                        cropDebugAbsPath,
                    )
                    Toast.makeText(this@BubbleOverlayService, "Chưa nhận diện đủ thông tin, đã mở màn hình kiểm tra để bạn sửa tay.", Toast.LENGTH_LONG).show()
                    return@launch
                }
                if (bubblePrefs.bubbleFastMode()) {
                    Log.d("PSM_BUBBLE", "[DEBUG-BUBBLE] submit:fast:start")
                    submitFastFromBubble(
                        draft,
                        ocrDatasetRecordId,
                        cropDebugAnnotatedUri,
                        mr.titleRegionOcrText,
                        mr.priceRegionOcrText,
                        fullText,
                        imageUri,
                        titleCropCsv,
                        priceCropCsv,
                        cropDebugAbsPath,
                    )
                    Log.d("PSM_BUBBLE", "[DEBUG-BUBBLE] submit:fast:done")
                } else {
                    Log.d("PSM_BUBBLE", "[DEBUG-BUBBLE] preview:start")
                    launchMainForBubblePreview(
                        draft,
                        fullText,
                        imageUri,
                        ocrDatasetRecordId,
                        cropDebugAnnotatedUri,
                        mr.titleRegionOcrText,
                        mr.priceRegionOcrText,
                        titleCropCsv,
                        priceCropCsv,
                        cropDebugAbsPath,
                    )
                    Toast.makeText(this@BubbleOverlayService, "Đã mở màn hình kiểm tra trước khi gửi.", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("PSM_BUBBLE", "[DEBUG-BUBBLE] error:${e.javaClass.simpleName}:${e.message}", e)
                agentDebugLog(
                    "H_catch",
                    "BubbleOverlayService.onBubbleClick",
                    "catch_exception",
                    mapOf(
                        "type" to e.javaClass.simpleName,
                        "message" to e.message.orEmpty(),
                    ),
                )
                Toast.makeText(this@BubbleOverlayService, "Lỗi bong bóng: ${e.message}", Toast.LENGTH_LONG).show()
                launchMainForManualReview()
            } finally {
                capturedBitmap?.takeIf { !it.isRecycled }?.recycle()
                logMemoryHealth("onBubbleClick:finally")
            }
        }
    }

    private suspend fun submitFastFromBubble(
        draft: OrderDraft,
        ocrDatasetParentId: String? = null,
        cropDebugAnnotatedUri: String? = null,
        titleRegionOcr: String = "",
        priceRegionOcr: String = "",
        rawTextForPreview: String = "",
        imageUri: String? = null,
        titleCropCsv: String = "",
        priceCropCsv: String = "",
        cropDebugAbsPath: String = "",
    ) {
        val prefs = PrefsStore(this)
        val gasUrl = prefs.gasUrl().trim()
        val secret = prefs.hmacSecret().trim()
        try {
            GasClient(this).submitOrder(gasUrl, secret, draft)
            if (ocrDatasetParentId != null) {
                OcrDatasetFacade.appendBubbleFastSubmit(
                    this,
                    OcrDatasetRecordBuilder.newRecordId(),
                    ocrDatasetParentId,
                    draft,
                    gasSuccess = true,
                    errorMessage = null,
                )
            }
            Toast.makeText(this, "Bong bóng gửi nhanh thành công.", Toast.LENGTH_LONG).show()
            if (prefs.developerMode() && prefs.ocrDebugVisualization()) {
                launchMainForBubblePreview(
                    draft,
                    rawTextForPreview,
                    imageUri,
                    ocrDatasetParentId,
                    cropDebugAnnotatedUri,
                    titleRegionOcr,
                    priceRegionOcr,
                    titleCropCsv,
                    priceCropCsv,
                    cropDebugAbsPath,
                )
            }
        } catch (e: Exception) {
            RetryQueueStore(this).enqueue(draft, "bubble_fast:${e.message.orEmpty()}")
            Toast.makeText(this, "Gửi nhanh lỗi, đã lưu vào danh sách chờ gửi.", Toast.LENGTH_LONG).show()
            if (ocrDatasetParentId != null) {
                OcrDatasetFacade.appendBubbleFastSubmit(
                    this,
                    OcrDatasetRecordBuilder.newRecordId(),
                    ocrDatasetParentId,
                    draft,
                    gasSuccess = false,
                    errorMessage = e.message,
                )
            }
            launchMainForBubblePreview(
                draft,
                rawTextForPreview,
                imageUri,
                ocrDatasetParentId,
                cropDebugAnnotatedUri,
                titleRegionOcr,
                priceRegionOcr,
                titleCropCsv,
                priceCropCsv,
                cropDebugAbsPath,
            )
        }
    }

    private fun cropBoundsCsv(r: android.graphics.Rect?): String =
        r?.takeIf { it.width() > 0 && it.height() > 0 }
            ?.let { "${it.left},${it.top},${it.right},${it.bottom}" }.orEmpty()

    private fun launchMainForBubblePreview(
        draft: vn.phapsumeo.onetap.model.OrderDraft,
        rawText: String,
        imageUri: String? = null,
        ocrDatasetRecordId: String? = null,
        cropDebugAnnotatedUri: String? = null,
        titleRegionOcr: String = "",
        priceRegionOcr: String = "",
        titleCropCsv: String = "",
        priceCropCsv: String = "",
        cropDebugAbsPath: String = "",
    ) {
        val i = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(MainActivity.EXTRA_BUBBLE_PREVIEW, true)
            putExtra(MainActivity.EXTRA_RAW_TEXT, rawText)
            putExtra(MainActivity.EXTRA_ORDER_ID, draft.maDonHang)
            putExtra(MainActivity.EXTRA_TRACKING, draft.maVanChuyen)
            putExtra(MainActivity.EXTRA_ORDER_STATUS, draft.trangThaiDon)
            putExtra(MainActivity.EXTRA_PRODUCT, draft.tenSanPham)
            putExtra(MainActivity.EXTRA_HANG, draft.giaHangCn ?: Double.NaN)
            putExtra(MainActivity.EXTRA_SHIP, draft.shipNoiDiaCn ?: Double.NaN)
            putExtra(MainActivity.EXTRA_TONG, draft.tongCn ?: Double.NaN)
            putExtra(MainActivity.EXTRA_TAB, draft.tenTab)
            if (!ocrDatasetRecordId.isNullOrBlank()) {
                putExtra(MainActivity.EXTRA_OCR_DATASET_RECORD_ID, ocrDatasetRecordId)
            }
            putExtra("bubble_image_uri", imageUri)
            putExtra(MainActivity.EXTRA_CROP_DEBUG_IMAGE_URI, cropDebugAnnotatedUri)
            putExtra(MainActivity.EXTRA_TITLE_REGION_OCR, titleRegionOcr)
            putExtra(MainActivity.EXTRA_PRICE_REGION_OCR, priceRegionOcr)
            putExtra(MainActivity.EXTRA_META_REGION_OCR, "")
            putExtra(MainActivity.EXTRA_TITLE_CROP_BOUNDS, titleCropCsv)
            putExtra(MainActivity.EXTRA_PRICE_CROP_BOUNDS, priceCropCsv)
            putExtra(MainActivity.EXTRA_META_CROP_BOUNDS, "")
            putExtra(MainActivity.EXTRA_CROP_DEBUG_ABS_PATH, cropDebugAbsPath)
            val contentUris = buildList {
                if (!imageUri.isNullOrBlank()) runCatching { add(android.net.Uri.parse(imageUri)) }
                if (!cropDebugAnnotatedUri.isNullOrBlank()) {
                    runCatching { add(android.net.Uri.parse(cropDebugAnnotatedUri)) }
                }
            }.mapNotNull { u ->
                if ("content".equals(u.scheme, ignoreCase = true)) u else null
            }
            if (contentUris.isNotEmpty()) {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val clip = android.content.ClipData(
                    "bubble_files",
                    arrayOf("image/*"),
                    android.content.ClipData.Item(contentUris.first()),
                )
                for (idx in 1 until contentUris.size) {
                    clip.addItem(android.content.ClipData.Item(contentUris[idx]))
                }
                clipData = clip
            }
        }
        startActivity(i)
    }

    private fun launchMainForManualReview() {
        val i = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(i)
    }

    private fun attachDragMove(view: View, onTap: () -> Unit) {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        view.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var dragging = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                updateInteraction()
                val p = params ?: return false
                val rv = removeView ?: return false
                
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        Log.d("PSM_BUBBLE", "[DEBUG-TOUCH] ACTION_DOWN")
                        dragging = false
                        initialX = p.x
                        initialY = p.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = abs(event.rawX - initialTouchX)
                        val dy = abs(event.rawY - initialTouchY)
                        if (!dragging && dx <= touchSlop && dy <= touchSlop) {
                            return true
                        }
                        if (!dragging) {
                            rv.visibility = View.VISIBLE
                        }
                        dragging = true
                        Log.d("PSM_BUBBLE", "[DEBUG-TOUCH] ACTION_MOVE dx=$dx dy=$dy dragging=$dragging")
                        p.x = initialX + (event.rawX - initialTouchX).toInt()
                        p.y = initialY + (event.rawY - initialTouchY).toInt()
                        wm?.updateViewLayout(view, p)
                        
                        // Check if in remove zone
                        val screenHeight = resources.displayMetrics.heightPixels
                        if (event.rawY > screenHeight - (150 * resources.displayMetrics.density)) {
                            rv.alpha = 1.0f
                            v.alpha = 0.5f
                        } else {
                            rv.alpha = 0.5f
                            v.alpha = 1.0f
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        Log.d("PSM_BUBBLE", "[DEBUG-TOUCH] ACTION_UP")
                        rv.visibility = View.GONE
                        v.alpha = 1.0f
                        
                        val screenHeight = resources.displayMetrics.heightPixels
                        if (dragging && event.rawY > screenHeight - (150 * resources.displayMetrics.density)) {
                            Log.d("PSM_BUBBLE", "[DEBUG-BUBBLE] Dismissed by user")
                            stopSelf()
                            return true
                        }

                        val dx = abs(event.rawX - initialTouchX)
                        val dy = abs(event.rawY - initialTouchY)
                        Log.d("PSM_BUBBLE", "[DEBUG-TOUCH] ACTION_UP final dx=$dx dy=$dy dragging=$dragging")
                        if (!dragging && dx <= touchSlop && dy <= touchSlop) {
                            onTap()
                        }
                        dragging = false
                        return true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        Log.d("PSM_BUBBLE", "[DEBUG-TOUCH] ACTION_CANCEL")
                        dragging = false
                        return true
                    }
                }
                return true
            }
        })
    }

    private fun centerCropSquare(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val side = min(w, h).coerceAtLeast(1)
        val x = (w - side) / 2
        val y = (h - side) / 2
        return Bitmap.createBitmap(src, x, y, side, side)
    }

    /** createScaledBitmap đôi khi trả về đúng instance nguồn — copy để an toàn khi recycle sau. */
    private fun scaleBitmapDistinct(src: Bitmap, w: Int, h: Int): Bitmap {
        val scaled = Bitmap.createScaledBitmap(src, w, h, true)
        return if (scaled === src) {
            scaled.copy(src.config ?: Bitmap.Config.ARGB_8888, false)
        } else {
            scaled
        }
    }

    /** Pre-API 31: giả lập mờ bằng scale nhỏ rồi phóng lại; API 31+: bitmap nét + RenderEffect blur trên view. */
    private fun prepareBlurLayerBitmap(square: Bitmap, targetSize: Int): Bitmap {
        val sharp = scaleBitmapDistinct(square, targetSize, targetSize)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return sharp
        }
        val small = max(4, targetSize / 12)
        val down = scaleBitmapDistinct(sharp, small, small)
        val up = scaleBitmapDistinct(down, targetSize, targetSize)
        if (!down.isRecycled) down.recycle()
        if (!sharp.isRecycled) sharp.recycle()
        return up
    }

    private fun recycleBubblePreviewBitmaps() {
        previousBubbleBlur?.takeIf { !it.isRecycled }?.recycle()
        previousBubbleThumb?.takeIf { !it.isRecycled }?.recycle()
        previousBubbleBlur = null
        previousBubbleThumb = null
    }

    private fun agentDebugLog(hypothesisId: String, location: String, message: String, data: Map<String, Any?>) {
        val payload = JSONObject().apply {
            put("sessionId", "418c16")
            put("runId", "bubble-ocr")
            put("hypothesisId", hypothesisId)
            put("location", location)
            put("message", message)
            put("timestamp", System.currentTimeMillis())
            put(
                "data",
                JSONObject().apply {
                    data.forEach { (k, v) ->
                        when (v) {
                            null -> put(k, JSONObject.NULL)
                            is Number -> put(k, v)
                            is Boolean -> put(k, v)
                            else -> put(k, v.toString())
                        }
                    }
                },
            )
        }
        Log.d("PSM_AGENT_DEBUG", payload.toString())
        scope.launch(Dispatchers.IO) {
            try {
                File(filesDir, "debug-418c16.log").appendText(payload.toString() + "\n")
            } catch (_: Exception) {
                // ignore
            }
            try {
                // Forward realtime debug JSON only when a debug ingest target is configured.
                val ingestUrl = debugIngestUrl() ?: return@launch
                val url = URL(ingestUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 2000
                conn.readTimeout = 2000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                val responseCode = conn.responseCode
                Log.d("PSM_INGEST", "Ingest response: $responseCode")
                conn.inputStream.use { }
                conn.disconnect()
            } catch (_: Exception) {
                // Host/mobile: ingest có thể không tới được; Logcat vẫn có PSM_AGENT_DEBUG
            }
        }
    }

    private fun debugIngestUrl(): String? {
        val target = PrefsStore(this).debugIngestTarget().trim().trimEnd('/')
        if (target.isBlank()) return null
        return if (target.startsWith("http://", ignoreCase = true) ||
            target.startsWith("https://", ignoreCase = true)
        ) {
            if (target.endsWith("/ingest", ignoreCase = true)) target else "$target/ingest"
        } else {
            val hostPort = if (target.contains(":")) target else "$target:7478"
            "http://$hostPort/ingest"
        }
    }

    private fun updateBubbleFromCapture(bitmap: Bitmap) {
        val blurIv = bubbleBlurIv ?: return
        val thumbIv = bubbleThumbIv ?: return
        val tv = bubbleFallbackTv ?: return

        // #region agent log
        agentDebugLog(
            "H1",
            "BubbleOverlayService.kt:updateBubbleFromCapture",
            "entry",
            mapOf("bmpW" to bitmap.width, "bmpH" to bitmap.height),
        )
        // #endregion

        val density = resources.displayMetrics.density
        val bubbleSize = (60 * density).toInt().coerceAtLeast(1)
        val thumbInset = max(2, (5 * density).toInt())
        val thumbPx = max(1, bubbleSize - thumbInset * 2)

        val thumbSquare = centerCropSquare(bitmap)

        // #region agent log
        agentDebugLog(
            "H2",
            "BubbleOverlayService.kt:updateBubbleFromCapture",
            "afterCrop",
            mapOf("squareW" to thumbSquare.width, "squareH" to thumbSquare.height, "bubbleSize" to bubbleSize),
        )
        // #endregion

        val blurBmp = prepareBlurLayerBitmap(thumbSquare, bubbleSize)
        val thumbBmp = scaleBitmapDistinct(thumbSquare, thumbPx, thumbPx)

        recycleBubblePreviewBitmaps()
        previousBubbleBlur = blurBmp
        previousBubbleThumb = thumbBmp
        if (!thumbSquare.isRecycled) thumbSquare.recycle()

        blurIv.setImageBitmap(blurBmp)
        thumbIv.setImageBitmap(thumbBmp)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            blurIv.setRenderEffect(
                RenderEffect.createBlurEffect(18f, 18f, Shader.TileMode.CLAMP),
            )
            // #region agent log
            agentDebugLog(
                "H3",
                "BubbleOverlayService.kt:updateBubbleFromCapture",
                "blurPath",
                mapOf("mode" to "RenderEffect", "api" to Build.VERSION.SDK_INT),
            )
            // #endregion
        } else {
            blurIv.setRenderEffect(null)
            // #region agent log
            agentDebugLog(
                "H3",
                "BubbleOverlayService.kt:updateBubbleFromCapture",
                "blurPath",
                mapOf("mode" to "scaledFallback", "api" to Build.VERSION.SDK_INT),
            )
            // #endregion
        }

        blurIv.visibility = View.VISIBLE
        thumbIv.visibility = View.VISIBLE
        tv.visibility = View.GONE

        // #region agent log
        agentDebugLog(
            "H4",
            "BubbleOverlayService.kt:updateBubbleFromCapture",
            "uiApplied",
            mapOf(
                "blurVis" to blurIv.visibility,
                "thumbVis" to thumbIv.visibility,
                "fallbackVis" to tv.visibility,
            ),
        )
        // #endregion
    }

    /** Một ảnh sản phẩm cho sheet. Ảnh khung debug chỉ khi bật chế độ nhà phát triển. */
    private fun productPhotoForSheet(full: Bitmap, thumb: Rect?, card: Bitmap): Bitmap {
        val r = thumb ?: return card
        val left = r.left.coerceIn(0, full.width - 1)
        val top = r.top.coerceIn(0, full.height - 1)
        val width = r.width().coerceAtMost(full.width - left)
        val height = r.height().coerceAtMost(full.height - top)
        if (width < 8 || height < 8) return card
        return try {
            Bitmap.createBitmap(full, left, top, width, height)
        } catch (_: Exception) {
            card
        }
    }

    private fun saveBitmapToCache(bitmap: Bitmap): String? {
        return try {
            val file = File(cacheDir, "bubble_capture_${System.currentTimeMillis()}.jpg")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                file,
            ).toString()
        } catch (e: Exception) {
            Log.e("PSM_BUBBLE", "Error saving bitmap: ${e.message}", e)
            null
        }
    }

    private data class CachedCropDebugPng(val contentUri: String, val absolutePath: String)

    private fun saveCropDebugBitmapToCache(bitmap: Bitmap): CachedCropDebugPng? {
        return try {
            val file = File(cacheDir, "bubble_crop_debug_${System.currentTimeMillis()}.jpg")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
            }
            val path = file.absolutePath
            val uriStr = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                file,
            ).toString()
            Log.i("PSM_CROP_DEBUG_FILE", "saved_crop_debug_png path=$path uri=$uriStr")
            CachedCropDebugPng(uriStr, path)
        } catch (e: Exception) {
            Log.e("PSM_BUBBLE", "Error saving crop debug bitmap: ${e.message}", e)
            null
        }
    }

    private fun startAsForegroundService() {
        val channelId = "psm_bubble_overlay_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                channelId,
                "Bong bóng PSM",
                NotificationManager.IMPORTANCE_LOW,
            )
            manager.createNotificationChannel(channel)
        }
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("PSM bong bóng đang chạy")
            .setContentText("Bấm bong bóng để chụp màn hình và gửi Sheet.")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(2002, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(2002, notification)
        }
    }

    private fun logMemoryHealth(tag: String) {
        val runtime = Runtime.getRuntime()
        val used = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
        val max = runtime.maxMemory() / 1024 / 1024
        Log.i("PSM_HEALTH", "[$tag] Memory: ${used}MB / ${max}MB")
    }

    private fun updateInteraction() {
        lastInteractionTime = System.currentTimeMillis()
    }

    private fun startIdleMonitor() {
        scope.launch {
            while (true) {
                delay(60_000) // Check every minute
                val idleMs = System.currentTimeMillis() - lastInteractionTime
                if (idleMs > 30 * 60_000) { // 30 minutes
                    Log.d("PSM_BUBBLE", "[DEBUG-BUBBLE] Auto-stopping due to 30m idle")
                    stopSelf()
                    break
                }
            }
        }
    }
}

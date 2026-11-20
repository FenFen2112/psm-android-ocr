package vn.phapsumeo.onetap

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.CountDownTimer
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import vn.phapsumeo.onetap.databinding.ActivityMainBinding
import vn.phapsumeo.onetap.gas.GasClient
import vn.phapsumeo.onetap.gas.GasPayloadBuilder
import vn.phapsumeo.onetap.capture.ScreenCaptureSession
import vn.phapsumeo.onetap.model.OrderDraft
import vn.phapsumeo.onetap.ocr.OcrFacade
import vn.phapsumeo.onetap.overlay.BubbleOverlayService
import vn.phapsumeo.onetap.i18n.TitleTranslator
import vn.phapsumeo.onetap.parser.XianyuParser
import vn.phapsumeo.onetap.parser.XianyuRuleTextExtractor
import vn.phapsumeo.onetap.debug.DebugNdjsonLog
import vn.phapsumeo.onetap.debug.ocr.OcrDebugAnnotatedExporter
import vn.phapsumeo.onetap.debug.ocr.OcrDebugSessionStore
import vn.phapsumeo.onetap.debug.ocr.OcrDebugSnapshotBuilder
import vn.phapsumeo.onetap.debug.dataset.OcrDatasetExporter
import vn.phapsumeo.onetap.debug.dataset.OcrDatasetFacade
import vn.phapsumeo.onetap.debug.dataset.OcrDatasetLogManager
import vn.phapsumeo.onetap.debug.dataset.OcrDatasetRecordBuilder
import vn.phapsumeo.onetap.debug.ocr.OcrDebugUiHeuristic
import vn.phapsumeo.onetap.debug.ui.OcrDebugPanel
import vn.phapsumeo.onetap.share.ShareIntentMapper
import vn.phapsumeo.onetap.util.PrefsStore
import vn.phapsumeo.onetap.util.RetryHistoryStore
import vn.phapsumeo.onetap.util.RetryQueueProcessor
import vn.phapsumeo.onetap.util.RetryQueueStore
import vn.phapsumeo.onetap.worker.QueueRetryWorker

class MainActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_BUBBLE_PREVIEW = "bubble_preview"
        const val EXTRA_RAW_TEXT = "bubble_raw_text"
        const val EXTRA_TRACKING = "bubble_tracking"
        const val EXTRA_PRODUCT = "bubble_product"
        const val EXTRA_HANG = "bubble_hang"
        const val EXTRA_SHIP = "bubble_ship"
        const val EXTRA_TONG = "bubble_tong"
        const val EXTRA_TAB = "bubble_tab"
        const val EXTRA_ORDER_ID = "bubble_order_id"
        const val EXTRA_ORDER_STATUS = "bubble_order_status"
        /** Correlates [KIND_USER_SUBMIT] rows with a prior bubble [KIND_BUBBLE_CAPTURE] record. */
        const val EXTRA_OCR_DATASET_RECORD_ID = "ocr_dataset_record_id"
        /** Ảnh chụp full màn hình + khung THUMB / TITLE / PRICE (debug). */
        const val EXTRA_CROP_DEBUG_IMAGE_URI = "crop_debug_image_uri"
        const val EXTRA_TITLE_REGION_OCR = "title_region_ocr"
        const val EXTRA_PRICE_REGION_OCR = "price_region_ocr"
        const val EXTRA_META_REGION_OCR = "meta_region_ocr"
        const val EXTRA_TITLE_CROP_BOUNDS = "title_crop_bounds"
        const val EXTRA_PRICE_CROP_BOUNDS = "price_crop_bounds"
        const val EXTRA_META_CROP_BOUNDS = "meta_crop_bounds"
        /** Đường dẫn tuyệt đối file PNG debug (log / UI). */
        const val EXTRA_CROP_DEBUG_ABS_PATH = "crop_debug_abs_path"
    }

    private val autoSubmitThreshold = 0.72
    private val filterAll = "all"
    private val filterForeground = "foreground"
    private val filterWorker = "worker"
    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: PrefsStore
    private lateinit var queueStore: RetryQueueStore
    private lateinit var retryHistoryStore: RetryHistoryStore
    private var sharedImageUri: String = ""
    private var productImageUris: List<String> = emptyList()
    /** Parser snapshot for dataset `user_submit` diff (bubble preview, OCR run, or parse). */
    private var initialDraftForDataset: OrderDraft? = null
    /** Parent `record_id` from bubble capture when preview opened from bubble. */
    private var pendingOcrDatasetRecordId: String? = null
    /** Parent `record_id` from last share-image OCR capture in this session. */
    private var lastShareOcrDatasetRecordId: String? = null
    private var countDownTimer: CountDownTimer? = null
    private lateinit var ocrDebugPanel: OcrDebugPanel
    private var titleTranslateJob: Job? = null
    private var selectedHistoryFilter: String = filterAll
    /** Khi true: ảnh preview là PNG đã vẽ khung vùng — tắt overlay bbox OCR dòng để tránh lệch tọa độ. */
    private var suppressOcrLineOverlayOnPreview: Boolean = false
    private val reqPostNotifications = 1101
    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            result.data?.let { data ->
                ScreenCaptureSession.save(this, result.resultCode, data)
                Toast.makeText(this, "Đã cấp quyền chụp màn hình.", Toast.LENGTH_SHORT).show()
                startBubbleService()
            }
        } else {
            Toast.makeText(this, "Bạn chưa cấp quyền chụp màn hình.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = PrefsStore(this)
        queueStore = RetryQueueStore(this)
        retryHistoryStore = RetryHistoryStore(this)
        showOcrPrivacyNoticeIfNeeded()

        binding.edtGasUrl.setText(prefs.gasUrl())
        binding.edtHmacSecret.setText(prefs.hmacSecret())
        binding.edtGeminiKey.setText(prefs.geminiApiKey())
        binding.edtOpenAiKey.setText(prefs.openAiApiKey())
        binding.edtDebugIngestTarget.setText(prefs.debugIngestTarget())
        binding.chkBubbleFastMode.isChecked = prefs.bubbleFastMode()
        applyAdvancedSettingsUi()
        binding.btnToggleAdvancedSettings.setOnClickListener {
            prefs.saveAdvancedSettingsExpanded(!prefs.advancedSettingsExpanded())
            applyAdvancedSettingsUi()
        }
        binding.ocrDebugOverlay.bindTargetImageView(binding.imgPreview)
        ocrDebugPanel = OcrDebugPanel(
            activity = this,
            binding = binding,
            prefs = prefs,
            onRefreshOcrOverlay = { refreshOcrDebugOverlay() },
            onDeveloperModeChanged = { },
        )
        ocrDebugPanel.bindOnCreate()
        binding.btnExportOcrDebug.setOnClickListener { exportOcrDebugPng() }
        binding.btnExportOcrDataset.setOnClickListener { exportOcrDatasetZip() }
        binding.btnClearOcrDataset.setOnClickListener {
            if (!prefs.developerMode()) {
                Toast.makeText(this, "Bật Chế độ nhà phát triển để xóa dữ liệu đã lưu.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            OcrDatasetLogManager.deleteAllLogs(this)
            Toast.makeText(this, "Đã xóa dữ liệu OCR đã lưu trên thiết bị.", Toast.LENGTH_SHORT).show()
        }

        // Setup Spinner chọn tháng
        val months = (1..12).map { "Tháng $it" }
        val adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, months)
        binding.spinnerSheetMonth.setAdapter(adapter)
        binding.spinnerSheetMonth.setText(prefs.getSelectedMonth(), false)
        binding.spinnerSheetMonth.setOnItemClickListener { _, _, position, _ ->
            val selected = months[position]
            prefs.saveSelectedMonth(selected)
        }

        val uri = ShareIntentMapper.readSharedImage(intent)
        sharedImageUri = uri?.toString().orEmpty()
        if (sharedImageUri.isNotBlank()) {
            binding.txtSharedUri.text = "Ảnh Share: $sharedImageUri"
        }
        handleBubblePreviewIntent(intent)
        refreshOcrDebugOverlay()
        ocrDebugPanel.applyExpandCollapseUi()
        ocrDebugPanel.applyDeveloperModeUi()

        binding.btnSaveConfig.setOnClickListener {
            prefs.saveGasUrl(binding.edtGasUrl.text?.toString().orEmpty().trim())
            prefs.saveHmacSecret(binding.edtHmacSecret.text?.toString().orEmpty().trim())
            prefs.saveGeminiApiKey(binding.edtGeminiKey.text?.toString().orEmpty().trim())
            prefs.saveOpenAiApiKey(binding.edtOpenAiKey.text?.toString().orEmpty().trim())
            prefs.saveDebugIngestTarget(binding.edtDebugIngestTarget.text?.toString().orEmpty().trim())
            Toast.makeText(this, "Đã lưu cấu hình", Toast.LENGTH_SHORT).show()
        }
        binding.btnGrantCapture.setOnClickListener { requestScreenCapturePermission() }
        binding.btnStartBubble.setOnClickListener { startBubbleMode() }
        binding.btnStopBubble.setOnClickListener { stopBubbleMode() }
        binding.chkBubbleFastMode.setOnCheckedChangeListener { _, checked ->
            prefs.saveBubbleFastMode(checked)
            val mode = if (checked) "Gửi nhanh" else "An toàn (kiểm tra 4 giây)"
            Toast.makeText(this, "Bong bóng: $mode", Toast.LENGTH_SHORT).show()
        }

        binding.btnRetryQueue.setOnClickListener {
            lifecycleScope.launch { retryPendingQueue() }
        }
        binding.btnClearQueue.setOnClickListener {
            queueStore.clear()
            refreshQueueStatus()
            Toast.makeText(this, "Đã xóa danh sách đơn chờ gửi.", Toast.LENGTH_SHORT).show()
        }
        binding.btnClearRetryHistory.setOnClickListener {
            retryHistoryStore.clear()
            refreshRetryHistory()
            Toast.makeText(this, "Đã xóa lịch sử retry.", Toast.LENGTH_SHORT).show()
        }
        binding.btnFilterAll.setOnClickListener { setHistoryFilter(filterAll) }
        binding.btnFilterForeground.setOnClickListener { setHistoryFilter(filterForeground) }
        binding.btnFilterWorker.setOnClickListener { setHistoryFilter(filterWorker) }

        val priceWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                refreshPriceSumVerifiedBanner()
            }
        }
        binding.edtHang.addTextChangedListener(priceWatcher)
        binding.edtShip.addTextChangedListener(priceWatcher)
        binding.edtTong.addTextChangedListener(priceWatcher)

        binding.btnRunOcr.setOnClickListener {
            if (sharedImageUri.isBlank()) {
                Toast.makeText(this, "Chưa có ảnh share. Mở ảnh -> Share sang app.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val parsedUri = uri ?: return@setOnClickListener
            lifecycleScope.launch {
                try {
                    binding.btnRunOcr.isEnabled = false
                    val text = OcrFacade().extractText(this@MainActivity, parsedUri)
                    binding.edtRawText.setText(text)
                    val parsed = XianyuParser.parse(text, sharedImageUri)
                    fillDraft(parsed)
                    binding.txtPayload.text = GasPayloadBuilder.buildPreviewJson(buildCurrentDraft())
                    evaluateConfidenceAndAutoSubmit()
                    val afterDraft = buildCurrentDraft()
                    publishOcrDebugFromSharedImageIfEnabled(parsedUri, afterDraft)
                    publishOcrDatasetShareAfterOcr(parsedUri, afterDraft)
                    initialDraftForDataset = afterDraft.copy()
                    Toast.makeText(this@MainActivity, "OCR xong, kiểm tra lại rồi gửi.", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "OCR lỗi: ${e.message}", Toast.LENGTH_LONG).show()
                } finally {
                    binding.btnRunOcr.isEnabled = true
                }
            }
        }

        binding.btnParse.setOnClickListener {
            val raw = binding.edtRawText.text?.toString().orEmpty()
            if (raw.isBlank()) {
                Toast.makeText(this, "Dán text OCR trước khi phân tích", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            lifecycleScope.launch {
                val parsed = XianyuParser.parse(raw, sharedImageUri)
                fillDraft(parsed)
                binding.txtPayload.text = GasPayloadBuilder.buildPreviewJson(buildCurrentDraft())
                evaluateConfidenceAndAutoSubmit()
                if (prefs.developerMode() && prefs.ocrDebugVisualization()) {
                    publishOcrDebugTextOnlyIfEnabled(raw)
                }
                if (prefs.developerMode() && prefs.ocrDatasetLogging()) {
                    val id = OcrDatasetRecordBuilder.newRecordId()
                    OcrDatasetFacade.appendTextParseOnly(this@MainActivity, id, raw.length, buildCurrentDraft())
                    initialDraftForDataset = buildCurrentDraft().copy()
                }
            }
        }

        binding.btnSubmitStub.setOnClickListener {
            cancelAutoSubmitCountdown()
            val gasUrl = binding.edtGasUrl.text?.toString().orEmpty().trim()
            val secret = binding.edtHmacSecret.text?.toString().orEmpty().trim()
            val draft = buildCurrentDraft()
            binding.txtPayload.text = GasPayloadBuilder.buildPreviewJson(draft)

            lifecycleScope.launch {
                try {
                    binding.btnSubmitStub.isEnabled = false
                    GasClient(this@MainActivity).submitOrder(gasUrl, secret, draft)
                    logDatasetUserSubmitIfEnabled(draft, gasSuccess = true, errorMessage = null)
                    Toast.makeText(this@MainActivity, "Đã gửi thành công lên GAS.", Toast.LENGTH_LONG).show()
                    refreshQueueStatus()
                } catch (e: Exception) {
                    queueStore.enqueue(draft, e.message.orEmpty())
                    refreshQueueStatus()
                    logDatasetUserSubmitIfEnabled(draft, gasSuccess = false, errorMessage = e.message)
                    Toast.makeText(this@MainActivity, "Gửi lỗi: ${e.message}", Toast.LENGTH_LONG).show()
                } finally {
                    binding.btnSubmitStub.isEnabled = true
                }
            }
        }

        binding.btnCancelCountdown.setOnClickListener { cancelAutoSubmitCountdown() }

        // Auto-cancel countdown when user interacts with inputs
        val cancelOnTouch = View.OnTouchListener { _, _ ->
            cancelAutoSubmitCountdown()
            false
        }
        binding.edtOrderId.setOnTouchListener(cancelOnTouch)
        binding.edtTracking.setOnTouchListener(cancelOnTouch)
        binding.edtProduct.setOnTouchListener(cancelOnTouch)
        binding.edtHang.setOnTouchListener(cancelOnTouch)
        binding.edtShip.setOnTouchListener(cancelOnTouch)
        binding.edtTong.setOnTouchListener(cancelOnTouch)
        binding.edtTab.setOnTouchListener(cancelOnTouch)
        binding.edtRawText.setOnTouchListener(cancelOnTouch)

        refreshQueueStatus()
        refreshRetryHistory()
        ensureNotificationPermission()
        ensurePeriodicRetryWorker()
        lifecycleScope.launch { retryPendingQueue() }
    }

    override fun onDestroy() {
        super.onDestroy()
        titleTranslateJob?.cancel()
        cancelAutoSubmitCountdown()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleBubblePreviewIntent(intent)
    }

    private fun fillDraft(d: OrderDraft) {
        binding.txtOrderStatus.text = d.trangThaiDon.ifBlank { "—" }
        binding.edtOrderId.setText(d.maDonHang)
        binding.edtTracking.setText(d.maVanChuyen)
        binding.edtProduct.setText(d.tenSanPham)
        binding.edtHang.setText(d.giaHangCn?.toString().orEmpty())
        binding.edtShip.setText(d.shipNoiDiaCn?.toString().orEmpty())
        binding.edtTong.setText(d.tongCn?.toString().orEmpty())
        if (d.tenTab.isNotBlank()) binding.edtTab.setText(d.tenTab)
        if (d.tenSanPhamDichThamKhao.isNotBlank()) {
            binding.txtProductTitleVi.text = d.tenSanPhamDichThamKhao
        } else {
            binding.txtProductTitleVi.text = getString(R.string.product_ref_placeholder)
            scheduleTitleReferenceTranslate(d.tenSanPham)
        }
        refreshPriceSumVerifiedBanner(d)
    }

    private fun buildCurrentDraft(): OrderDraft {
        val h = binding.edtHang.text?.toString()?.trim()?.toDoubleOrNull()
        val s = binding.edtShip.text?.toString()?.trim()?.toDoubleOrNull()
        val t = binding.edtTong.text?.toString()?.trim()?.toDoubleOrNull()
        return OrderDraft(
            maDonHang = binding.edtOrderId.text?.toString().orEmpty().trim(),
            maVanChuyen = binding.edtTracking.text?.toString().orEmpty().trim(),
            trangThaiDon = binding.txtOrderStatus.text?.toString().orEmpty().trim().let { tx ->
                if (tx == "—" || tx == "-") "" else tx
            },
            tenSanPham = binding.edtProduct.text?.toString().orEmpty().trim(),
            tenSanPhamDichThamKhao = binding.txtProductTitleVi.text?.toString().orEmpty().trim(),
            giaHangCn = h,
            shipNoiDiaCn = s,
            tongCn = t,
            tenTab = binding.edtTab.text?.toString().orEmpty().ifBlank { "Trung Quốc" },
            sheetName = binding.spinnerSheetMonth.text?.toString().orEmpty().trim(),
            sharedImageUri = sharedImageUri,
            productImageUris = productImageUris,
            nhieuSanPham = binding.edtProduct.text?.toString().orEmpty().contains('+'),
            tongTienDaXacMinh = XianyuRuleTextExtractor.sumMatchesTotal(h, s, t),
        )
    }

    private fun applyAdvancedSettingsUi() {
        val expanded = prefs.advancedSettingsExpanded()
        binding.panelAdvancedSettings.visibility = if (expanded) View.VISIBLE else View.GONE
        binding.btnToggleAdvancedSettings.text = getString(
            if (expanded) R.string.advanced_settings_toggle_expanded else R.string.advanced_settings_toggle_collapsed,
        )
    }

    private fun refreshPriceSumVerifiedBanner(d: OrderDraft? = null) {
        val h = binding.edtHang.text?.toString()?.trim()?.toDoubleOrNull()
        val s = binding.edtShip.text?.toString()?.trim()?.toDoubleOrNull()
        val t = binding.edtTong.text?.toString()?.trim()?.toDoubleOrNull()
        val ok = XianyuRuleTextExtractor.sumMatchesTotal(h, s, t) || (d?.tongTienDaXacMinh == true)
        binding.txtPriceVerified.visibility = if (ok) View.VISIBLE else View.GONE
    }

    private fun scheduleTitleReferenceTranslate(chineseTitle: String) {
        titleTranslateJob?.cancel()
        val title = chineseTitle.trim()
        if (title.isBlank()) {
            binding.txtProductTitleVi.text = getString(R.string.product_ref_placeholder)
            return
        }
        titleTranslateJob = lifecycleScope.launch {
            val vi = TitleTranslator.translateProductTitleReference(prefs.geminiApiKey(), title)
            binding.txtProductTitleVi.text =
                vi.ifBlank { getString(R.string.product_ref_placeholder) }
        }
    }

    private fun evaluateConfidenceAndAutoSubmit() {
        val draft = buildCurrentDraft()
        refreshPriceSumVerifiedBanner(draft)
        val score = confidenceScore(draft)
        val pct = (score * 100).toInt()
        binding.txtConfidence.text = "Độ tin cậy: $pct% (tự gửi từ ${(autoSubmitThreshold * 100).toInt()}%)"
        ocrDebugPanel.applyDeveloperModeUi()
        if (!binding.chkAutoSubmit.isChecked) return
        if (score < autoSubmitThreshold) return
        startAutoSubmitCountdown()
    }

    private fun handleBubblePreviewIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_BUBBLE_PREVIEW, false) != true) return
        suppressOcrLineOverlayOnPreview = false
        val rawText = intent.getStringExtra(EXTRA_RAW_TEXT).orEmpty()
        if (rawText.isNotBlank()) binding.edtRawText.setText(rawText)
        binding.edtOrderId.setText(intent.getStringExtra(EXTRA_ORDER_ID).orEmpty())
        binding.edtTracking.setText(intent.getStringExtra(EXTRA_TRACKING).orEmpty())
        binding.txtOrderStatus.text = intent.getStringExtra(EXTRA_ORDER_STATUS).orEmpty().ifBlank { "—" }
        binding.edtProduct.setText(intent.getStringExtra(EXTRA_PRODUCT).orEmpty())
        binding.edtTab.setText(intent.getStringExtra(EXTRA_TAB).orEmpty().ifBlank { "Trung Quốc" })
        setDoubleOrBlank(binding.edtHang, intent.getDoubleExtra(EXTRA_HANG, Double.NaN))
        setDoubleOrBlank(binding.edtShip, intent.getDoubleExtra(EXTRA_SHIP, Double.NaN))
        setDoubleOrBlank(binding.edtTong, intent.getDoubleExtra(EXTRA_TONG, Double.NaN))

        val bubbleRuleMerged = if (rawText.isNotBlank()) {
            XianyuRuleTextExtractor.merge(buildCurrentDraft(), rawText)
        } else {
            null
        }
        if (bubbleRuleMerged != null) {
            binding.edtHang.setText(bubbleRuleMerged.giaHangCn?.toString().orEmpty())
            binding.edtShip.setText(bubbleRuleMerged.shipNoiDiaCn?.toString().orEmpty())
            binding.edtTong.setText(bubbleRuleMerged.tongCn?.toString().orEmpty())
            if (bubbleRuleMerged.maDonHang.isNotBlank()) {
                binding.edtOrderId.setText(bubbleRuleMerged.maDonHang)
            }
        }

        val imageUri = intent.getStringExtra("bubble_image_uri")
        productImageUris = intent.getStringExtra("bubble_product_images").orEmpty()
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (productImageUris.isEmpty() && !imageUri.isNullOrBlank()) {
            productImageUris = listOf(imageUri)
        }
        sharedImageUri = productImageUris.firstOrNull().orEmpty()
        if (sharedImageUri.isNotBlank()) {
            binding.imgPreview.visibility = View.VISIBLE
            binding.imgPreview.setImageURI(android.net.Uri.parse(sharedImageUri))
        } else {
            binding.imgPreview.visibility = View.GONE
        }

        applyBubbleCropDebugUi(intent)

        pendingOcrDatasetRecordId = intent.getStringExtra(EXTRA_OCR_DATASET_RECORD_ID)
        initialDraftForDataset = buildCurrentDraft().copy()

        // #region agent log
        if (prefs.developerMode()) {
            DebugNdjsonLog.append(
                this,
                "H6",
                "MainActivity.handleBubblePreviewIntent",
                "bubble_preview_fields",
                mapOf(
                    "rawLen" to rawText.length,
                    "sharedUriLen" to sharedImageUri.length,
                    "bubbleUriLen" to (imageUri?.length ?: 0),
                    "cropDebugUriLen" to (intent.getStringExtra(EXTRA_CROP_DEBUG_IMAGE_URI)?.length ?: 0),
                    "cropDebugAbsPathLen" to (intent.getStringExtra(EXTRA_CROP_DEBUG_ABS_PATH)?.length ?: 0),
                ),
            )
        }
        // #endregion

        val shownDraft = bubbleRuleMerged ?: buildCurrentDraft()
        binding.txtPayload.text = GasPayloadBuilder.buildPreviewJson(shownDraft)
        binding.txtConfidence.text =
            "Độ tin cậy: ${OcrDebugUiHeuristic.percent(shownDraft)}% (tự gửi từ ${(autoSubmitThreshold * 100).toInt()}%)"
        startBubblePreviewCountdown()
        refreshOcrDebugOverlay()
        ocrDebugPanel.applyDeveloperModeUi()
        refreshPriceSumVerifiedBanner(bubbleRuleMerged ?: buildCurrentDraft())
        scheduleTitleReferenceTranslate(binding.edtProduct.text?.toString().orEmpty())
    }

    private fun setDoubleOrBlank(editText: android.widget.EditText, value: Double) {
        editText.setText(if (value.isNaN()) "" else value.toString())
    }

    /** Hiển thị ảnh full màn hình + khung vùng cắt và chữ OCR từng vùng (bubble). */
    private fun applyBubbleCropDebugUi(intent: Intent) {
        val cropUri = intent.getStringExtra(EXTRA_CROP_DEBUG_IMAGE_URI)
        val absPath = intent.getStringExtra(EXTRA_CROP_DEBUG_ABS_PATH).orEmpty()
        val t = intent.getStringExtra(EXTRA_TITLE_REGION_OCR).orEmpty()
        val p = intent.getStringExtra(EXTRA_PRICE_REGION_OCR).orEmpty()
        val m = intent.getStringExtra(EXTRA_META_REGION_OCR).orEmpty()
        val tb = intent.getStringExtra(EXTRA_TITLE_CROP_BOUNDS).orEmpty()
        val pb = intent.getStringExtra(EXTRA_PRICE_CROP_BOUNDS).orEmpty()
        val mb = intent.getStringExtra(EXTRA_META_CROP_BOUNDS).orEmpty()
        val hasImage = !cropUri.isNullOrBlank()
        val head = buildString {
            if (tb.isNotBlank()) appendLine("TITLE bounds (l,t,r,b): $tb")
            if (pb.isNotBlank()) appendLine("PRICE bounds (l,t,r,b): $pb")
            if (mb.isNotBlank()) appendLine("META bounds (l,t,r,b): $mb")
            if (absPath.isNotBlank()) {
                appendLine("PNG file: $absPath")
                appendLine()
            }
            if (length > 0) appendLine()
        }
        val block = buildString {
            appendLine("TITLE OCR:")
            appendLine(if (t.isBlank()) "(empty)" else t.trim())
            appendLine()
            appendLine("PRICE OCR:")
            appendLine(if (p.isBlank()) "(empty)" else p.trim())
            if (m.isNotBlank() || mb.isNotBlank()) {
                appendLine()
                appendLine("META OCR:")
                appendLine(if (m.isBlank()) "(empty)" else m.trim())
            }
        }.trimEnd()
        val showCard = hasImage || t.isNotBlank() || p.isNotBlank() || m.isNotBlank() ||
            tb.isNotBlank() || pb.isNotBlank() || mb.isNotBlank() || absPath.isNotBlank()
        if (!showCard) {
            binding.cardOcrCropDebug.visibility = View.GONE
            return
        }
        binding.cardOcrCropDebug.visibility = View.VISIBLE
        if (hasImage) {
            suppressOcrLineOverlayOnPreview = true
            binding.imgOcrCropDebug.visibility = View.VISIBLE
            binding.imgPreview.visibility = View.VISIBLE
            val u = cropUri!!
            runCatching {
                val parsed = Uri.parse(u)
                binding.imgOcrCropDebug.setImageURI(parsed)
                binding.imgPreview.setImageURI(parsed)
            }.onFailure {
                binding.imgOcrCropDebug.visibility = View.GONE
                suppressOcrLineOverlayOnPreview = false
            }
        } else {
            binding.imgOcrCropDebug.visibility = View.GONE
        }
        binding.txtOcrCropRegions.text = buildString {
            val h = head.trimEnd()
            if (h.isNotEmpty()) {
                appendLine(h)
                appendLine()
            }
            append(block)
        }.trimEnd()
        Log.d(
            "PSM_CROP_DEBUG",
            "TITLE OCR:\n${t.take(4000)}\n---\nPRICE OCR:\n${p.take(4000)}\n---\nMETA OCR:\n${m.take(4000)}",
        )
        Log.d(
            "PSM_OCR_CROP_RECT",
            "preview TITLE=$tb PRICE=$pb META=$mb hasAnnotatedBitmap=$hasImage",
        )
        if (absPath.isNotBlank()) {
            Log.i("PSM_CROP_DEBUG_FILE", "crop_debug_png path=$absPath uri=${cropUri.orEmpty()}")
        }
        // #region agent log
        if (prefs.developerMode()) {
            DebugNdjsonLog.append(
                this,
                "H_crop_ui",
                "MainActivity.applyBubbleCropDebugUi",
                "crop_debug_visible",
                mapOf(
                    "hasImage" to hasImage,
                    "titleOcrLen" to t.length,
                    "priceOcrLen" to p.length,
                    "metaOcrLen" to m.length,
                ),
            )
        }
        // #endregion
    }

    private fun confidenceScore(draft: OrderDraft): Double = OcrDebugUiHeuristic.score01(draft)

    private fun refreshOcrDebugOverlay() {
        val on = prefs.developerMode() && prefs.ocrDebugVisualization() && !suppressOcrLineOverlayOnPreview
        binding.ocrDebugOverlay.setOverlayEnabled(on)
        val snap = if (on) OcrDebugSessionStore.peek() else null
        binding.ocrDebugOverlay.setSnapshot(snap)
        binding.btnExportOcrDebug.isEnabled = snap != null && sharedImageUri.isNotBlank()
        ocrDebugPanel.applyDeveloperModeUi()
        // #region agent log
        android.util.Log.i(
            "PSM_GATE",
            """{"sessionId":"418c16","hypothesisId":"H1","location":"MainActivity.refreshOcrDebugOverlay","devMode":${prefs.developerMode()},"viz":${prefs.ocrDebugVisualization()},"suppressCropOverlay":$suppressOcrLineOverlayOnPreview,"overlayOn":$on}""",
        )
        // #endregion
    }

    private fun exportOcrDebugPng() {
        if (!prefs.developerMode()) {
            Toast.makeText(this, "Bật Chế độ nhà phát triển để xuất ảnh OCR.", Toast.LENGTH_SHORT).show()
            return
        }
        val snap = OcrDebugSessionStore.peek() ?: run {
            Toast.makeText(this, "Chưa có dữ liệu kiểm tra OCR.", Toast.LENGTH_SHORT).show()
            return
        }
        val uriStr = sharedImageUri.takeIf { it.isNotBlank() } ?: run {
            Toast.makeText(this, "Cần có ảnh từ chia sẻ hoặc bong bóng.", Toast.LENGTH_SHORT).show()
            return
        }
        binding.btnExportOcrDebug.isEnabled = false
        lifecycleScope.launch {
            try {
                val uri = Uri.parse(uriStr)
                val bmp = withContext(Dispatchers.IO) {
                    OcrDebugAnnotatedExporter.decodeForSnapshot(this@MainActivity, uri, snap)
                }
                if (bmp == null) {
                    Toast.makeText(this@MainActivity, "Không đọc được ảnh.", Toast.LENGTH_LONG).show()
                    return@launch
                }
                val exported = OcrDebugAnnotatedExporter.exportPng(this@MainActivity, bmp, snap)
                if (!bmp.isRecycled) bmp.recycle()
                if (exported != null) {
                    Toast.makeText(
                        this@MainActivity,
                        "Đã lưu tại: ${getString(R.string.export_folder_display)}",
                        Toast.LENGTH_LONG,
                    ).show()
                    logExportResult(
                        kind = "ocr_image",
                        path = exported.displayPath,
                        sizeBytes = exported.sizeBytes,
                        imageCount = exported.imageCount,
                        recordCount = exported.recordCount,
                    )
                    showExportActions(
                        title = "Đã xuất ảnh OCR",
                        uri = exported.uri,
                        mimeType = "image/png",
                        displayPath = exported.displayPath,
                    )
                }
            } finally {
                binding.btnExportOcrDebug.isEnabled = true
                refreshOcrDebugOverlay()
            }
        }
    }

    private suspend fun publishOcrDatasetShareAfterOcr(parsedUri: Uri, draft: OrderDraft) {
        if (!prefs.developerMode() || !prefs.ocrDatasetLogging()) return
        val recordId = OcrDatasetRecordBuilder.newRecordId()
        var wrote = false
        withContext(Dispatchers.IO) {
            val bmp = OcrDebugAnnotatedExporter.decodeScaledToMaxSide(this@MainActivity, parsedUri) ?: return@withContext
            try {
                val lines = OcrFacade().extractLinesWithCoords(bmp)
                OcrDatasetFacade.appendShareOcrCapture(
                    this@MainActivity,
                    recordId,
                    lines,
                    bmp.width,
                    bmp.height,
                    draft,
                )
                wrote = true
            } finally {
                if (!bmp.isRecycled) bmp.recycle()
            }
        }
        if (wrote) lastShareOcrDatasetRecordId = recordId
    }

    private suspend fun publishOcrDebugFromSharedImageIfEnabled(parsedUri: Uri, draft: OrderDraft) {
        if (!prefs.developerMode() || !prefs.ocrDebugVisualization()) {
            refreshOcrDebugOverlay()
            return
        }
        withContext(Dispatchers.IO) {
            val bmp = OcrDebugAnnotatedExporter.decodeScaledToMaxSide(this@MainActivity, parsedUri) ?: return@withContext
            val lines = OcrFacade().extractLinesWithCoords(bmp)
            val snap = OcrDebugSnapshotBuilder.build(
                linesCapture = lines,
                captureWidth = bmp.width,
                captureHeight = bmp.height,
                cropInCapture = null,
                draft = draft,
                parserDecisions = listOf(
                    "Nguồn: đọc chữ từ ảnh chia sẻ",
                    "Kích thước ảnh: ${bmp.width}x${bmp.height}",
                    "Số dòng nhận diện: ${lines.size}",
                ),
                heuristicUiPercent = OcrDebugUiHeuristic.percent(draft),
            )
            if (!bmp.isRecycled) bmp.recycle()
            OcrDebugSessionStore.publish(snap)
        }
        refreshOcrDebugOverlay()
    }

    private fun logDatasetUserSubmitIfEnabled(
        userDraft: OrderDraft,
        gasSuccess: Boolean,
        errorMessage: String?,
    ) {
        if (!prefs.developerMode() || !prefs.ocrDatasetLogging()) return
        val parent = pendingOcrDatasetRecordId ?: lastShareOcrDatasetRecordId
        val submitId = OcrDatasetRecordBuilder.newRecordId()
        OcrDatasetFacade.appendUserSubmit(
            this,
            submitId,
            parent,
            userDraft,
            initialDraftForDataset,
            gasSuccess,
            errorMessage,
        )
    }

    private fun exportOcrDatasetZip() {
        if (!prefs.developerMode()) {
            Toast.makeText(this, "Bật Chế độ nhà phát triển để xuất dữ liệu AI.", Toast.LENGTH_SHORT).show()
            // #region agent log
            android.util.Log.i(
                "PSM_GATE",
                """{"sessionId":"418c16","hypothesisId":"H2","location":"MainActivity.exportOcrDatasetZip","skipped":true,"reason":"devMode_off"}""",
            )
            // #endregion
            return
        }
        lifecycleScope.launch {
            val exported = withContext(Dispatchers.IO) { OcrDatasetExporter.zipAllJsonl(this@MainActivity) }
            if (exported != null) {
                Toast.makeText(
                    this@MainActivity,
                    "Đã lưu tại: ${getString(R.string.export_folder_display)}",
                    Toast.LENGTH_LONG,
                ).show()
                logExportResult(
                    kind = "ai_data_zip",
                    path = exported.displayPath,
                    sizeBytes = exported.sizeBytes,
                    imageCount = exported.imageCount,
                    recordCount = exported.recordCount,
                )
                showExportActions(
                    title = "Đã xuất dữ liệu AI",
                    uri = exported.uri,
                    mimeType = "application/zip",
                    displayPath = exported.displayPath,
                )
            }
        }
    }

    private fun showOcrPrivacyNoticeIfNeeded() {
        if (prefs.hasSeenOcrPrivacyNotice()) return
        AlertDialog.Builder(this)
            .setTitle("Lưu dữ liệu OCR")
            .setMessage(getString(R.string.ocr_privacy_notice))
            .setPositiveButton("Đã hiểu") { _, _ -> prefs.markOcrPrivacyNoticeSeen() }
            .show()
    }

    private fun showExportActions(
        title: String,
        uri: Uri,
        mimeType: String,
        displayPath: String,
    ) {
        val message = buildString {
            append("Đã lưu tại: ")
            append(displayPath)
            if (isProbablyEmulator()) {
                append("\n\nFile đang lưu trong bộ nhớ máy ảo Android")
            }
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Chia sẻ file") { _, _ -> shareExportedFile(uri, mimeType) }
            .setNegativeButton("Mở thư mục") { _, _ -> openExportFolder() }
            .setNeutralButton("Copy path") { _, _ -> copyExportPath(displayPath) }
            .show()
    }

    private fun shareExportedFile(uri: Uri, mimeType: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri("psm_export", uri)
        }
        startActivity(Intent.createChooser(send, "Chia sẻ file"))
    }

    private fun openExportFolder() {
        val folderUri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload%2FPSM_OCR")
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(folderUri, "vnd.android.document/directory")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(viewIntent) }
            .onFailure {
                runCatching { startActivity(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)) }
                    .onFailure { Toast.makeText(this, "Không mở được thư mục. Hãy vào Download/PSM_OCR.", Toast.LENGTH_LONG).show() }
            }
    }

    private fun copyExportPath(displayPath: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("PSM_OCR_path", displayPath))
        Toast.makeText(this, "Đã copy: $displayPath", Toast.LENGTH_SHORT).show()
    }

    private fun logExportResult(
        kind: String,
        path: String,
        sizeBytes: Long,
        imageCount: Int,
        recordCount: Int,
    ) {
        Log.i(
            "PSM_EXPORT",
            "kind=$kind path=$path sizeBytes=$sizeBytes imageCount=$imageCount recordCount=$recordCount",
        )
    }

    private fun isProbablyEmulator(): Boolean {
        val fingerprint = Build.FINGERPRINT.lowercase()
        val model = Build.MODEL.lowercase()
        val manufacturer = Build.MANUFACTURER.lowercase()
        val brand = Build.BRAND.lowercase()
        val device = Build.DEVICE.lowercase()
        val product = Build.PRODUCT.lowercase()
        return fingerprint.contains("generic") ||
            fingerprint.contains("emulator") ||
            model.contains("emulator") ||
            model.contains("android sdk built for") ||
            manufacturer.contains("genymotion") ||
            (brand.startsWith("generic") && device.startsWith("generic")) ||
            product.contains("sdk")
    }

    private suspend fun publishOcrDebugTextOnlyIfEnabled(raw: String) {
        if (!prefs.developerMode() || !prefs.ocrDebugVisualization()) {
            refreshOcrDebugOverlay()
            return
        }
        val draft = buildCurrentDraft()
        val w = resources.displayMetrics.widthPixels.coerceAtLeast(1)
        val h = resources.displayMetrics.heightPixels.coerceAtLeast(1)
        val snap = withContext(Dispatchers.Default) {
            OcrDebugSnapshotBuilder.build(
                linesCapture = emptyList(),
                captureWidth = w,
                captureHeight = h,
                cropInCapture = null,
                draft = draft,
                parserDecisions = listOf(
                    "Nguồn: văn bản dán tay",
                    "Số ký tự: ${raw.length}",
                ),
                heuristicUiPercent = OcrDebugUiHeuristic.percent(draft),
            )
        }
        OcrDebugSessionStore.publish(snap)
        refreshOcrDebugOverlay()
    }

    private fun startAutoSubmitCountdown() {
        cancelAutoSubmitCountdown()
        binding.btnCancelCountdown.visibility = View.VISIBLE
        countDownTimer = object : CountDownTimer(3000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val sec = (millisUntilFinished / 1000).toInt() + 1
                binding.btnCancelCountdown.text = "Hủy đếm ngược auto-submit (${sec}s)"
            }

            override fun onFinish() {
                binding.btnCancelCountdown.visibility = View.GONE
                binding.btnSubmitStub.performClick()
            }
        }.start()
    }

    private fun startBubblePreviewCountdown() {
        cancelAutoSubmitCountdown()
        binding.btnCancelCountdown.visibility = View.VISIBLE
        countDownTimer = object : CountDownTimer(4000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val sec = (millisUntilFinished / 1000).toInt() + 1
                binding.btnCancelCountdown.text = "Hủy tự gửi (${sec}s)"
            }

            override fun onFinish() {
                binding.btnCancelCountdown.visibility = View.GONE
                binding.btnSubmitStub.performClick()
            }
        }.start()
    }

    private fun cancelAutoSubmitCountdown() {
        countDownTimer?.cancel()
        countDownTimer = null
        binding.btnCancelCountdown.visibility = View.GONE
        binding.btnCancelCountdown.text = getString(R.string.btn_cancel_countdown)
    }

    private fun refreshQueueStatus() {
        val total = queueStore.size()
        val due = queueStore.dueCount()
        binding.txtQueueStatus.text = "Đơn chờ gửi lại: $total ($due đến hạn gửi lại)"
    }

    private fun refreshRetryHistory() {
        binding.txtRetryHistory.text = retryHistoryStore.renderRecentStyled(
            limit = 8,
            filterSource = selectedHistoryFilter,
        )
        updateFilterButtonsState()
    }

    private fun setHistoryFilter(filter: String) {
        selectedHistoryFilter = filter
        refreshRetryHistory()
    }

    private fun updateFilterButtonsState() {
        binding.btnFilterAll.isEnabled = selectedHistoryFilter != filterAll
        binding.btnFilterForeground.isEnabled = selectedHistoryFilter != filterForeground
        binding.btnFilterWorker.isEnabled = selectedHistoryFilter != filterWorker
    }

    private suspend fun retryPendingQueue() {
        val result = RetryQueueProcessor.process(this@MainActivity, source = "foreground")
        refreshQueueStatus()
        refreshRetryHistory()
        if (result.sentCount > 0) {
            Toast.makeText(this, "Đã gửi lại thành công ${result.sentCount} đơn.", Toast.LENGTH_LONG).show()
        }
    }

    private fun ensurePeriodicRetryWorker() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val work = PeriodicWorkRequestBuilder<QueueRetryWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "psm_queue_retry_periodic",
            ExistingPeriodicWorkPolicy.UPDATE,
            work,
        )
    }

    private fun ensureNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (granted) return
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            reqPostNotifications,
        )
    }

    private fun requestScreenCapturePermission() {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun startBubbleMode() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Cần bật quyền hiển thị trên ứng dụng khác.", Toast.LENGTH_LONG).show()
            val i = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:$packageName"),
            )
            startActivity(i)
            return
        }
        if (ScreenCaptureSession.hasPermissionToken(this)) {
            startBubbleService()
            return
        }
        requestScreenCapturePermission()
    }

    private fun startBubbleService() {
        val i = Intent(this, BubbleOverlayService::class.java)
        ContextCompat.startForegroundService(this, i)
        Toast.makeText(this, "Đã bật bong bóng PSM.", Toast.LENGTH_SHORT).show()
    }

    private fun stopBubbleMode() {
        stopService(Intent(this, BubbleOverlayService::class.java))
        Toast.makeText(this, "Đã tắt bong bóng PSM.", Toast.LENGTH_SHORT).show()
    }
}

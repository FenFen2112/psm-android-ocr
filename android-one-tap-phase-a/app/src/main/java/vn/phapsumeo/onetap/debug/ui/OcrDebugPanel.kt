package vn.phapsumeo.onetap.debug.ui

import android.view.View
import android.widget.CompoundButton
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import vn.phapsumeo.onetap.MainActivity
import vn.phapsumeo.onetap.R
import vn.phapsumeo.onetap.databinding.ActivityMainBinding
import vn.phapsumeo.onetap.util.PrefsStore

class OcrDebugPanel(
    private val activity: MainActivity,
    private val binding: ActivityMainBinding,
    private val prefs: PrefsStore,
    private val onRefreshOcrOverlay: () -> Unit,
    private val onDeveloperModeChanged: (Boolean) -> Unit,
) {

    private var trackProgressAutoOffJob: Job? = null
    private var suppressVizCheckedCallback: Boolean = false

    private val ocrVizCheckedListener =
        CompoundButton.OnCheckedChangeListener { _, checked ->
            if (suppressVizCheckedCallback) return@OnCheckedChangeListener
            prefs.saveOcrDebugVisualization(checked)
            if (checked) {
                scheduleTrackProgressAutoOffIfNeeded()
            } else {
                cancelTrackProgressAutoOff()
            }
            onRefreshOcrOverlay()
            if (checked && prefs.developerMode() &&
                vn.phapsumeo.onetap.debug.ocr.OcrDebugSessionStore.peek() == null
            ) {
                Toast.makeText(
                    activity,
                    "Đã bật theo dõi. Hãy đọc chữ từ ảnh hoặc dùng bong bóng để có dữ liệu kiểm tra. Bong bóng: luôn lưu PNG full màn có khung cam=THUMB, đỏ=TITLE, lục=PRICE.",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }

    fun bindOnCreate() {
        binding.chkOcrDebugViz.isChecked = prefs.ocrDebugVisualization()
        binding.chkOcrDatasetLogging.isChecked = prefs.ocrDatasetLogging()
        binding.chkOcrDatasetRedact.isChecked = prefs.ocrDatasetRedactText()

        binding.btnToggleOcrDebug.setOnClickListener {
            prefs.saveOcrDebugSectionExpanded(!prefs.ocrDebugSectionExpanded())
            applyExpandCollapseUi()
            applyDeveloperModeUi()
        }

        binding.switchDeveloperMode.isChecked = prefs.developerMode()
        binding.switchDeveloperMode.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
            prefs.saveDeveloperMode(checked)
            if (!checked) {
                cancelTrackProgressAutoOff()
                binding.ocrDebugOverlay.setOverlayEnabled(false)
                binding.ocrDebugOverlay.setSnapshot(null)
            } else if (binding.chkOcrDebugViz.isChecked) {
                scheduleTrackProgressAutoOffIfNeeded()
            }
            onDeveloperModeChanged(checked)
            applyDeveloperModeUi()
            onRefreshOcrOverlay()
        }

        binding.chkOcrDebugViz.setOnCheckedChangeListener(ocrVizCheckedListener)

        binding.chkOcrDatasetLogging.setOnCheckedChangeListener { _, checked ->
            prefs.saveOcrDatasetLogging(checked)
            if (!checked) {
                Toast.makeText(activity, "Đã tắt lưu dữ liệu huấn luyện AI.", Toast.LENGTH_SHORT).show()
            }
        }

        binding.chkOcrDatasetRedact.setOnCheckedChangeListener { _, checked ->
            prefs.saveOcrDatasetRedactText(checked)
        }

        applyExpandCollapseUi()
        applyDeveloperModeUi()
        if (prefs.developerMode() && prefs.ocrDebugVisualization()) {
            scheduleTrackProgressAutoOffIfNeeded()
        }
    }

    fun applyExpandCollapseUi() {
        val expanded = prefs.ocrDebugSectionExpanded()
        binding.btnToggleOcrDebug.text = activity.getString(
            if (expanded) R.string.ocr_debug_toggle_expanded else R.string.ocr_debug_toggle_collapsed,
        )
        binding.cardOcrDebugSurface.visibility = if (expanded) View.VISIBLE else View.GONE
    }

    fun applyDeveloperModeUi() {
        val dev = prefs.developerMode()
        val expanded = prefs.ocrDebugSectionExpanded()
        binding.panelOcrDebugDevOnly.visibility = if (expanded && dev) View.VISIBLE else View.GONE
        binding.switchDeveloperMode.visibility = if (expanded) View.VISIBLE else View.GONE
        binding.labelDeveloperMode.visibility = if (expanded) View.VISIBLE else View.GONE

        binding.cardPayloadPreview.visibility = if (expanded && dev) View.VISIBLE else View.GONE
        binding.txtConfidence.visibility = if (expanded && dev) View.VISIBLE else View.GONE

        if (!dev) {
            binding.ocrDebugOverlay.setOverlayEnabled(false)
        }
    }

    fun allowDatasetIo(): Boolean = prefs.developerMode() && prefs.ocrDatasetLogging()

    fun allowOcrDebugVisualization(): Boolean =
        prefs.developerMode() && prefs.ocrDebugVisualization()

    private fun cancelTrackProgressAutoOff() {
        trackProgressAutoOffJob?.cancel()
        trackProgressAutoOffJob = null
    }

    /**
     * Tự tắt "Theo dõi tiến độ cập nhật" sau vài giây để tránh overlay chạy liên tục.
     */
    private fun scheduleTrackProgressAutoOffIfNeeded() {
        cancelTrackProgressAutoOff()
        if (!prefs.developerMode() || !binding.chkOcrDebugViz.isChecked) return
        trackProgressAutoOffJob = activity.lifecycleScope.launch {
            try {
                val delayMs = TRACK_PROGRESS_AUTO_OFF_MS_RANGE.random()
                delay(delayMs)
                if (activity.isFinishing || activity.isDestroyed) return@launch
                suppressVizCheckedCallback = true
                try {
                    prefs.saveOcrDebugVisualization(false)
                    binding.chkOcrDebugViz.isChecked = false
                } finally {
                    suppressVizCheckedCallback = false
                }
                onRefreshOcrOverlay()
                Toast.makeText(
                    activity,
                    activity.getString(R.string.toast_track_progress_auto_off),
                    Toast.LENGTH_SHORT,
                ).show()
                // #region agent log
                android.util.Log.i(
                    "PSM_GATE",
                    """{"sessionId":"418c16","hypothesisId":"H_track_auto","location":"OcrDebugPanel.scheduleTrackProgressAutoOff","autoOffMs":$delayMs}""",
                )
                // #endregion
            } finally {
                trackProgressAutoOffJob = null
            }
        }
    }

    companion object {
        private val TRACK_PROGRESS_AUTO_OFF_MS_RANGE = 10_000L..15_000L
    }
}


package vn.phapsumeo.onetap.debug.ocr

import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import android.widget.ImageView
import java.lang.ref.WeakReference
import kotlin.math.max

/**
 * Debug-only overlay (pref `ocr_debug_visualization`, default off): OCR line bounding boxes,
 * spatially grouped blocks (magenta dashed), heuristic entity tags + color legend,
 * modular confidence + draft summary in the top HUD. No-op when disabled.
 */
class OcrDebugVisualizationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var targetRef: WeakReference<ImageView>? = null
    private var snapshot: OcrDebugSnapshot? = null
    private var overlayEnabled: Boolean = false

    private val layoutListener =
        OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> if (overlayEnabled) invalidate() }

    fun bindTargetImageView(imageView: ImageView) {
        targetRef?.get()?.removeOnLayoutChangeListener(layoutListener)
        targetRef = WeakReference(imageView)
        imageView.addOnLayoutChangeListener(layoutListener)
    }

    fun setOverlayEnabled(enabled: Boolean) {
        overlayEnabled = enabled
        applyVisibilityAndDrawFlag()
    }

    fun setSnapshot(s: OcrDebugSnapshot?) {
        snapshot = s
        applyVisibilityAndDrawFlag()
    }

    private fun applyVisibilityAndDrawFlag() {
        val show = overlayEnabled && snapshot != null
        setWillNotDraw(!show)
        visibility = if (show) VISIBLE else GONE
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (!overlayEnabled) return
        val snap = snapshot ?: return
        val iv = targetRef?.get() ?: return
        val d: Drawable = iv.drawable ?: return
        val dst = imageDisplayRect(iv, d) ?: return
        val srcW = max(d.intrinsicWidth, 1)
        val srcH = max(d.intrinsicHeight, 1)
        OcrDebugCanvasRenderer.drawOverlay(canvas, snap, srcW, srcH, dst, resources.displayMetrics.density)
    }

    private fun imageDisplayRect(iv: ImageView, d: Drawable): RectF? {
        val vw = iv.width - iv.paddingLeft - iv.paddingRight
        val vh = iv.height - iv.paddingTop - iv.paddingBottom
        if (vw <= 0 || vh <= 0) return null
        val iw = d.intrinsicWidth
        val ih = d.intrinsicHeight
        if (iw <= 0 || ih <= 0) return null
        val scale = kotlin.math.min(vw / iw.toFloat(), vh / ih.toFloat())
        val dw = iw * scale
        val dh = ih * scale
        val left = iv.paddingLeft + (vw - dw) / 2f
        val top = iv.paddingTop + (vh - dh) / 2f
        return RectF(left, top, left + dw, top + dh)
    }
}

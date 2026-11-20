package vn.phapsumeo.onetap.debug.dataset

import android.content.Context
import android.graphics.Rect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import vn.phapsumeo.onetap.extraction.ExtractionFacades
import vn.phapsumeo.onetap.extraction.confidence.ConfidenceEvaluationContext
import vn.phapsumeo.onetap.extraction.confidence.OrderDraftConfidenceEngine
import vn.phapsumeo.onetap.extraction.core.OcrLine
import vn.phapsumeo.onetap.extraction.profile.xianyu.XianyuPatternPlugin
import vn.phapsumeo.onetap.model.OrderDraft
import vn.phapsumeo.onetap.util.PrefsStore

/**
 * Single entry for building + appending dataset rows (grouping + confidence in one place).
 */
object OcrDatasetFacade {

    private val confidenceEngine = OrderDraftConfidenceEngine(XianyuPatternPlugin())

    suspend fun appendBubbleCapture(
        context: Context,
        recordId: String,
        lines: List<OcrLine>,
        captureWidth: Int,
        captureHeight: Int,
        cropInCapture: Rect?,
        parserDraft: OrderDraft,
        parserEmptyFlag: Boolean,
    ) {
        val prefs = PrefsStore(context)
        if (!prefs.ocrDatasetLogging()) return
        withContext(Dispatchers.Default) {
            val grouped = ExtractionFacades.documentFromLinesGrouped(lines, captureWidth, captureHeight)
            val doc = grouped.toDocument(parserDraft.sharedImageUri)
            val conf = confidenceEngine.evaluateDetailed(
                ConfidenceEvaluationContext(draft = parserDraft, document = doc, grouped = grouped),
            )
            val redact = prefs.ocrDatasetRedactText()
            val jo = OcrDatasetRecordBuilder.buildCaptureRecord(
                context = context,
                recordId = recordId,
                kind = OcrDatasetSchema.KIND_BUBBLE_CAPTURE,
                marketplace = OcrDatasetSchema.MARKETPLACE_XIANYU,
                lines = lines,
                captureWidth = captureWidth,
                captureHeight = captureHeight,
                cropInCapture = cropInCapture,
                grouped = grouped,
                parserDraft = parserDraft,
                confidence = conf,
                redactText = redact,
                parserEmptyFlag = parserEmptyFlag,
            )
            OcrDatasetLogManager.appendJsonLine(context, jo.toString())
        }
    }

    suspend fun appendShareOcrCapture(
        context: Context,
        recordId: String,
        lines: List<OcrLine>,
        captureWidth: Int,
        captureHeight: Int,
        parserDraft: OrderDraft,
    ) {
        val prefs = PrefsStore(context)
        if (!prefs.ocrDatasetLogging()) return
        withContext(Dispatchers.Default) {
            val grouped = ExtractionFacades.documentFromLinesGrouped(lines, captureWidth, captureHeight)
            val doc = grouped.toDocument(parserDraft.sharedImageUri)
            val conf = confidenceEngine.evaluateDetailed(
                ConfidenceEvaluationContext(draft = parserDraft, document = doc, grouped = grouped),
            )
            val redact = prefs.ocrDatasetRedactText()
            val jo = OcrDatasetRecordBuilder.buildCaptureRecord(
                context = context,
                recordId = recordId,
                kind = OcrDatasetSchema.KIND_SHARE_OCR_CAPTURE,
                marketplace = OcrDatasetSchema.MARKETPLACE_XIANYU,
                lines = lines,
                captureWidth = captureWidth,
                captureHeight = captureHeight,
                cropInCapture = null,
                grouped = grouped,
                parserDraft = parserDraft,
                confidence = conf,
                redactText = redact,
                parserEmptyFlag = false,
            )
            OcrDatasetLogManager.appendJsonLine(context, jo.toString())
        }
    }

    suspend fun appendTextParseOnly(
        context: Context,
        recordId: String,
        rawCharCount: Int,
        parserDraft: OrderDraft,
    ) {
        val prefs = PrefsStore(context)
        if (!prefs.ocrDatasetLogging()) return
        withContext(Dispatchers.Default) {
            val w = context.resources.displayMetrics.widthPixels.coerceAtLeast(1)
            val h = context.resources.displayMetrics.heightPixels.coerceAtLeast(1)
            val grouped = ExtractionFacades.documentFromLinesGrouped(emptyList(), w, h)
            val doc = grouped.toDocument(parserDraft.sharedImageUri)
            val conf = confidenceEngine.evaluateDetailed(
                ConfidenceEvaluationContext(draft = parserDraft, document = doc, grouped = grouped),
            )
            val jo = OcrDatasetRecordBuilder.buildTextParseOnlyRecord(
                context = context,
                recordId = recordId,
                rawCharCount = rawCharCount,
                parserDraft = parserDraft,
                confidence = conf,
                redactText = prefs.ocrDatasetRedactText(),
            )
            OcrDatasetLogManager.appendJsonLine(context, jo.toString())
        }
    }

    fun appendUserSubmit(
        context: Context,
        recordId: String,
        parentRecordId: String?,
        userDraft: OrderDraft,
        parserDraft: OrderDraft?,
        gasSuccess: Boolean,
        errorMessage: String?,
    ) {
        if (!PrefsStore(context).ocrDatasetLogging()) return
        val jo = OcrDatasetRecordBuilder.buildUserSubmitRecord(
            context = context,
            recordId = recordId,
            parentRecordId = parentRecordId,
            userDraft = userDraft,
            parserDraft = parserDraft,
            gasSuccess = gasSuccess,
            errorMessage = errorMessage,
        )
        OcrDatasetLogManager.appendJsonLine(context, jo.toString())
    }

    fun appendBubbleFastSubmit(
        context: Context,
        recordId: String,
        parentRecordId: String,
        draft: OrderDraft,
        gasSuccess: Boolean,
        errorMessage: String?,
    ) {
        if (!PrefsStore(context).ocrDatasetLogging()) return
        val jo = OcrDatasetRecordBuilder.buildBubbleFastSubmitRecord(
            context = context,
            recordId = recordId,
            parentRecordId = parentRecordId,
            draft = draft,
            gasSuccess = gasSuccess,
            errorMessage = errorMessage,
        )
        OcrDatasetLogManager.appendJsonLine(context, jo.toString())
    }
}

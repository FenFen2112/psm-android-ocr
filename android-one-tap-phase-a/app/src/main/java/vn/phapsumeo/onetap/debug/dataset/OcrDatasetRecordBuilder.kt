package vn.phapsumeo.onetap.debug.dataset

import android.content.Context
import android.graphics.Rect
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import vn.phapsumeo.onetap.extraction.confidence.model.ExtractionConfidence
import vn.phapsumeo.onetap.extraction.core.OcrLine
import vn.phapsumeo.onetap.extraction.grouping.GroupedOcrOutput
import vn.phapsumeo.onetap.model.OrderDraft
import java.util.UUID

object OcrDatasetRecordBuilder {

    fun newRecordId(): String = UUID.randomUUID().toString()

    private fun deviceJson(context: Context): JSONObject {
        val dm = context.resources.displayMetrics
        return JSONObject().apply {
            put("android_sdk", Build.VERSION.SDK_INT)
            put("manufacturer", Build.MANUFACTURER ?: "")
            put("model", Build.MODEL ?: "")
            put("screen_px_w", dm.widthPixels)
            put("screen_px_h", dm.heightPixels)
            put("density_dpi", dm.densityDpi)
        }
    }

    private fun rectJson(r: Rect): JSONArray =
        JSONArray().apply {
            put(r.left)
            put(r.top)
            put(r.right)
            put(r.bottom)
        }

    private fun ocrLinesJson(lines: List<OcrLine>, redactText: Boolean): JSONObject =
        JSONObject().apply {
            val arr = JSONArray()
            for (ln in lines) {
                val o = JSONObject()
                if (redactText) {
                    o.put("text_len", ln.text.length)
                    o.put("text", JSONObject.NULL)
                } else {
                    o.put("text", ln.text)
                }
                val b = ln.boundingBox
                if (b != null) o.put("bbox", rectJson(b)) else o.put("bbox", JSONObject.NULL)
                arr.put(o)
            }
            put("lines", arr)
            if (!redactText) {
                put("full_text", lines.joinToString("\n") { it.text })
            }
        }

    private fun groupedBlocksJson(grouped: GroupedOcrOutput): JSONArray {
        val arr = JSONArray()
        for (b in grouped.blocks) {
            val o = JSONObject()
            o.put("reading_order", b.readingOrder)
            o.put("line_count", b.lines.size)
            val bb = b.boundingBox
            if (bb != null) o.put("bbox", rectJson(bb)) else o.put("bbox", JSONObject.NULL)
            arr.put(o)
        }
        return arr
    }

    private fun confidenceJson(conf: ExtractionConfidence): JSONObject =
        JSONObject().apply {
            put("overall_weighted", conf.overallWeighted)
            put("is_low_confidence", conf.isLowConfidence)
            val axes = JSONArray()
            for (c in conf.contributions) {
                val ax = JSONObject()
                ax.put("dimension", c.dimension.name)
                ax.put("score01", c.score01)
                ax.put("normalized_weight", c.normalizedWeight)
                ax.put("weighted_contribution", c.weightedContribution)
                val rs = JSONArray()
                c.reasons.take(8).forEach { rs.put(it.take(160)) }
                ax.put("reasons", rs)
                axes.put(ax)
            }
            put("axes", axes)
            val ws = JSONArray()
            conf.warnings.take(12).forEach {
                ws.put(
                    JSONObject().apply {
                        put("code", it.code)
                        put("severity", it.severity.name)
                        put("message", it.message.take(200))
                    },
                )
            }
            put("warnings", ws)
        }

    fun buildCaptureRecord(
        context: Context,
        recordId: String,
        kind: String,
        marketplace: String,
        lines: List<OcrLine>,
        captureWidth: Int,
        captureHeight: Int,
        cropInCapture: Rect?,
        grouped: GroupedOcrOutput,
        parserDraft: OrderDraft,
        confidence: ExtractionConfidence,
        redactText: Boolean,
        parserEmptyFlag: Boolean,
    ): JSONObject =
        JSONObject().apply {
            put("schema", OcrDatasetSchema.SCHEMA_NAME)
            put("schema_version", OcrDatasetSchema.CURRENT_SCHEMA_VERSION)
            put("record_id", recordId)
            put("parent_record_id", JSONObject.NULL)
            put("timestamp_ms", System.currentTimeMillis())
            put("kind", kind)
            put("marketplace", marketplace)
            put("device", deviceJson(context))
            put("privacy", OcrDatasetPrivacy.privacyBlock(parserDraft.sharedImageUri, redactText))
            put(
                "capture",
                JSONObject().apply {
                    put("width_px", captureWidth)
                    put("height_px", captureHeight)
                    if (cropInCapture != null) {
                        put("crop_rect_capture", rectJson(cropInCapture))
                    } else {
                        put("crop_rect_capture", JSONObject.NULL)
                    }
                    put("parser_empty", parserEmptyFlag)
                },
            )
            put("ocr", ocrLinesJson(lines, redactText))
            put("grouped_blocks", groupedBlocksJson(grouped))
            put("parser_output", OcrDatasetPrivacy.draftToJson(parserDraft))
            put("confidence", confidenceJson(confidence))
        }

    fun buildUserSubmitRecord(
        context: Context,
        recordId: String,
        parentRecordId: String?,
        userDraft: OrderDraft,
        parserDraft: OrderDraft?,
        gasSuccess: Boolean,
        errorMessage: String?,
    ): JSONObject =
        JSONObject().apply {
            put("schema", OcrDatasetSchema.SCHEMA_NAME)
            put("schema_version", OcrDatasetSchema.CURRENT_SCHEMA_VERSION)
            put("record_id", recordId)
            put("parent_record_id", parentRecordId ?: JSONObject.NULL)
            put("timestamp_ms", System.currentTimeMillis())
            put("kind", OcrDatasetSchema.KIND_USER_SUBMIT)
            put("marketplace", OcrDatasetSchema.MARKETPLACE_XIANYU)
            put("device", deviceJson(context))
            put("privacy", OcrDatasetPrivacy.privacyBlock(userDraft.sharedImageUri, false))
            put("parser_output", parserDraft?.let { OcrDatasetPrivacy.draftToJson(it) } ?: JSONObject.NULL)
            put("user_corrected", OcrDatasetPrivacy.draftToJson(userDraft))
            put(
                "user_correction",
                JSONObject().apply {
                    if (parserDraft != null) {
                        val changed = OcrDatasetPrivacy.fieldsChanged(parserDraft, userDraft)
                        val arr = JSONArray()
                        changed.forEach { arr.put(it) }
                        put("fields_changed", arr)
                    } else {
                        put("fields_changed", JSONObject.NULL)
                    }
                    put("gas_submit_success", gasSuccess)
                    put("gas_error", errorMessage?.take(300) ?: JSONObject.NULL)
                },
            )
        }

    fun buildBubbleFastSubmitRecord(
        context: Context,
        recordId: String,
        parentRecordId: String,
        draft: OrderDraft,
        gasSuccess: Boolean,
        errorMessage: String?,
    ): JSONObject =
        JSONObject().apply {
            put("schema", OcrDatasetSchema.SCHEMA_NAME)
            put("schema_version", OcrDatasetSchema.CURRENT_SCHEMA_VERSION)
            put("record_id", recordId)
            put("parent_record_id", parentRecordId)
            put("timestamp_ms", System.currentTimeMillis())
            put("kind", OcrDatasetSchema.KIND_BUBBLE_FAST_SUBMIT)
            put("marketplace", OcrDatasetSchema.MARKETPLACE_XIANYU)
            put("device", deviceJson(context))
            put("privacy", OcrDatasetPrivacy.privacyBlock(draft.sharedImageUri, false))
            put("parser_output", OcrDatasetPrivacy.draftToJson(draft))
            put("user_corrected", OcrDatasetPrivacy.draftToJson(draft))
            put(
                "user_correction",
                JSONObject().apply {
                    put("fields_changed", JSONArray()) // no UI edit
                    put("gas_submit_success", gasSuccess)
                    put("gas_error", errorMessage?.take(300) ?: JSONObject.NULL)
                },
            )
        }

    fun buildTextParseOnlyRecord(
        context: Context,
        recordId: String,
        rawCharCount: Int,
        parserDraft: OrderDraft,
        confidence: ExtractionConfidence,
        redactText: Boolean,
    ): JSONObject =
        JSONObject().apply {
            put("schema", OcrDatasetSchema.SCHEMA_NAME)
            put("schema_version", OcrDatasetSchema.CURRENT_SCHEMA_VERSION)
            put("record_id", recordId)
            put("parent_record_id", JSONObject.NULL)
            put("timestamp_ms", System.currentTimeMillis())
            put("kind", OcrDatasetSchema.KIND_TEXT_PARSE_ONLY)
            put("marketplace", OcrDatasetSchema.MARKETPLACE_XIANYU)
            put("device", deviceJson(context))
            put("privacy", OcrDatasetPrivacy.privacyBlock(parserDraft.sharedImageUri, redactText))
            put("capture", JSONObject.NULL)
            put("ocr", JSONObject().apply { put("lines", JSONArray()); put("raw_char_count", rawCharCount) })
            put("grouped_blocks", JSONArray())
            put("parser_output", OcrDatasetPrivacy.draftToJson(parserDraft))
            put("confidence", confidenceJson(confidence))
        }
}

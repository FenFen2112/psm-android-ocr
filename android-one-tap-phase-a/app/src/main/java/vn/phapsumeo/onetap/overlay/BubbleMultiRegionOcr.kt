package vn.phapsumeo.onetap.overlay

import android.graphics.Bitmap
import android.graphics.Rect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import vn.phapsumeo.onetap.capture.layer.PreprocessResult
import vn.phapsumeo.onetap.model.OrderDraft
import vn.phapsumeo.onetap.ocr.OcrFacade
import vn.phapsumeo.onetap.ocr.preprocess.OcrPreprocessConfigs
import vn.phapsumeo.onetap.parser.OcrLine
import vn.phapsumeo.onetap.parser.XianyuRuleTextExtractor

/**
 * OCR theo vùng (title / giá) — không đọc toàn màn hình một lần.
 */
object BubbleMultiRegionOcr {
    private const val REGION_OCR_TIMEOUT_MS = 4_500L

    data class Result(
        val draft: OrderDraft,
        val combinedLines: List<OcrLine>,
        val combinedRawText: String,
        val parserDecisions: List<String>,
        /** Chữ OCR thô từng vùng (debug; không dùng để parse thêm). */
        val titleRegionOcrText: String = "",
        val priceRegionOcrText: String = "",
        val metaRegionOcrText: String = "",
    )

    suspend fun build(
        fullW: Int,
        fullH: Int,
        imageUri: String,
        preprocess: PreprocessResult,
    ): Result {
        val ocr = OcrFacade()
        val decisions = mutableListOf<String>()
        val combined = mutableListOf<OcrLine>()

        suspend fun runRegion(
            bmp: Bitmap?,
            rect: Rect?,
            tag: String,
        ): String {
            if (bmp == null || rect == null) {
                decisions += "$tag:skip"
                return ""
            }
            val lines = try {
                withTimeoutOrNull(REGION_OCR_TIMEOUT_MS) {
                    ocr.extractLinesWithCoords(bmp, OcrPreprocessConfigs.RegionCrop)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                decisions += "$tag:error=${e.javaClass.simpleName}"
                emptyList()
            }
            if (lines == null) {
                decisions += "$tag:timeout"
                return ""
            }
            val dx = rect.left
            val dy = rect.top
            for (line in lines) {
                val b = line.boundingBox?.let { br ->
                    Rect(
                        br.left + dx,
                        br.top + dy,
                        br.right + dx,
                        br.bottom + dy,
                    )
                }
                combined += OcrLine(line.text, b)
            }
            val text = lines.joinToString("\n") { it.text }
            decisions += "$tag:lines=${lines.size}"
            return text
        }

        val titleOcrBmp = preprocess.titleTextOcrBitmap ?: preprocess.titleBitmap
        val titleOcrRect = preprocess.titleTextOcrCropRectInCapture ?: preprocess.titleCropRectInCapture
        if (preprocess.titleTextOcrBitmap != null && preprocess.titleTextOcrCropRectInCapture != null) {
            decisions += "title:ocrSource=textStrip"
        } else {
            decisions += "title:ocrSource=fullRowFallback"
        }
        val titleText = runRegion(titleOcrBmp, titleOcrRect, "title")
        val priceText = runRegion(preprocess.priceBitmap, preprocess.priceCropRectInCapture, "price")

        val productTitle = pickProductTitleFromTitleRegion(titleText)
        var draft = OrderDraft(
            tenSanPham = productTitle.take(120),
            sharedImageUri = imageUri,
        )
        draft = XianyuRuleTextExtractor.merge(draft, priceText)
        // Mã đơn / MVĐ / trạng thái: lấy từ title/price text
        draft = XianyuRuleTextExtractor.mergeMetadata(draft, "", titleText + "\n" + priceText)

        val combinedRaw = buildString {
            appendLine("=== TITLE ===")
            appendLine(titleText)
            appendLine("=== PRICE ===")
            appendLine(priceText)
        }.trim()

        decisions += "productTitleLen=${productTitle.length}"
        return Result(
            draft = draft,
            combinedLines = combined,
            combinedRawText = combinedRaw,
            parserDecisions = decisions,
            titleRegionOcrText = titleText,
            priceRegionOcrText = priceText,
            metaRegionOcrText = "",
        )
    }

    private fun pickProductTitleFromTitleRegion(raw: String): String {
        val lines = raw.lines().map { it.trim() }.filter { it.length >= 2 }
        if (lines.isEmpty()) return ""
        return lines
            .filterNot(::looksLikeAddressOrNoise)
            .maxByOrNull { scoreProductTitleLine(it) }
            .orEmpty()
    }

    private fun looksLikeAddressOrNoise(s: String): Boolean {
        val t = s.replace(" ", "")
        if (t.any { it in "省" } && t.any { it in "市县区州" }) return true
        if (t.contains("自治区")) return true
        if (t.contains("街道") || t.contains("大道") || t.contains("南路") || t.contains("北路")) return true
        if (Regex("1[3-9]\\d{9}").containsMatchIn(t)) return true
        if (t.contains("复制") && t.length < 20) return true
        if (t == "专人专拍" || (t.contains("专人专拍") && t.length <= 8)) return true
        if (t.startsWith("￥") || t.startsWith("¥")) return true
        if (Regex("^\\d+[.,]?\\d*$").matches(t)) return true
        if (t.contains("Hãykiênnhẫn") || t.contains("chờđợi")) return true
        return false
    }

    private fun scoreProductTitleLine(s: String): Int {
        val han = s.count { it in '\u4e00'..'\u9fff' }
        val digitRuns = Regex("\\d+").findAll(s).count()
        return han * 200 + s.length - digitRuns * 40
    }
}

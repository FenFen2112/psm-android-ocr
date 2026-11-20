package vn.phapsumeo.onetap.extraction.profile.xianyu

import android.graphics.Rect
import android.util.Log
import vn.phapsumeo.onetap.extraction.api.EntityDetector
import vn.phapsumeo.onetap.extraction.core.LayoutHints
import vn.phapsumeo.onetap.extraction.core.OcrDocument
import vn.phapsumeo.onetap.extraction.core.OcrLine
import vn.phapsumeo.onetap.model.OrderDraft
import vn.phapsumeo.onetap.parser.XianyuRuleTextExtractor
import kotlin.math.abs
import kotlin.math.max

/**
 * Xianyu-specific entity extraction: regex + spatial heuristics.
 * Uses [LayoutHints] from [XianyuLayoutAnalyzer] for content-relative Y/X and label proximity,
 * reducing reliance on raw full-screen height ratios.
 */
class XianyuHeuristicEntityDetector : EntityDetector {

    override fun detect(document: OcrDocument, layoutHints: LayoutHints): OrderDraft {
        val lines = document.allLines()
        val geo = ContentGeometry.from(document, layoutHints)

        lines.forEach { line ->
            val box = line.boundingBox
            val cy = if (box != null) geo.nyContent(box) else -1.0
            Log.d(TAG, "[COORD] nyContent=${String.format("%.2f", cy)} | text=${line.text}")
        }

        val fullText = lines.joinToString("\n") { it.text }

        val trangThai = extractStatusSnippet(fullText)
        var maDonHang = ""
        ORDER_REGEX.find(fullText)?.let {
            val id = it.groupValues[1].replace(" ", "")
            if (id.length >= 16 && !Regex("""^1[3-9]\d{9}$""").matches(id)) maDonHang = id
        }

        var maVc = ""
        if (shouldParseTracking(fullText)) {
            TRACK_REGEX.find(fullText)?.let { maVc = it.groupValues[1].replace(" ", "") }
        }

        var tenSp = ""
        var tong: Double? = null

        val labelProximityPx = labelProximityPx(layoutHints, geo)

        val labelLine = lines.find {
            it.text.contains("成交价") || it.text.contains("实付款") || it.text.contains("合计")
        }

        if (labelLine != null) {
            val labelBox = labelLine.boundingBox
            if (labelBox != null) {
                val nearPrice = lines.filter {
                    val b = it.boundingBox ?: return@filter false
                    abs(b.centerY() - labelBox.centerY()) < labelProximityPx && it != labelLine
                }.mapNotNull {
                    DISPLAY_PRICE_REGEX.find(it.text)?.let { m -> parseMoney(m.groupValues[1]) }
                        ?: Regex("([\\d.]{2,10})").find(it.text)?.let { m -> parseMoney(m.groupValues[1]) }
                }.firstOrNull()
                if (nearPrice != null) {
                    tong = nearPrice
                    Log.d(TAG, "[PRICE] Found via label proximity: $tong (px=$labelProximityPx)")
                }
            }
        }

        if (tong == null || tong == 50.0 || tong == 0.0) {
            val candidates = mutableListOf<Double>()
            for (line in lines) {
                val box = line.boundingBox ?: continue
                val ny = geo.nyContent(box)
                if (ny in PRICE_FALLBACK_NY_RANGE) {
                    val txt = line.text.replace(" ", "").lowercase()
                    if (txt.contains("50g") || txt.contains("能量") || txt.contains("蚂蚁")) continue

                    val num = parseMoney(line.text) ?: continue
                    if (num == 50.0 || num == 50.00) continue
                    if (line.text.replace(Regex("[^\\d]"), "").length > 7) continue

                    candidates.add(num)
                }
            }
            tong = candidates.lastOrNull()
            Log.d(TAG, "[PRICE] Final fallback: $tong from $candidates")
        }

        var bestScore = -10000
        for (line in lines) {
            val box = line.boundingBox ?: continue
            val ny = geo.nyContent(box)
            val nx = geo.nxContent(box)

            if (ny in TITLE_NY_RANGE && nx > TITLE_MIN_NX) {
                var score = scoreAsProductName(line.text)
                score += ((TITLE_NY_RANGE.endInclusive - ny) * 1000).toInt()

                if (score > bestScore) {
                    bestScore = score
                    tenSp = line.text
                }
            }
        }

        val normalized = tenSp.replace("原褲", "原神").replace("原裤", "原神")
        val (finalTenSp, embeddedPrice) = XianyuRuleTextExtractor.splitTitlePrice(normalized)
        val price = tong ?: embeddedPrice

        return OrderDraft(
            maDonHang = maDonHang,
            maVanChuyen = maVc,
            trangThaiDon = trangThai,
            tenSanPham = finalTenSp.take(100),
            giaHangCn = embeddedPrice,
            shipNoiDiaCn = null,
            tongCn = price,
            sharedImageUri = document.imageUri,
        )
    }

    private fun extractStatusSnippet(fullText: String): String {
        for (s in STATUS_SNIPPETS_ORDERED) {
            if (fullText.contains(s)) return s
        }
        return ""
    }

    /**
     * If order is "等待卖家发货" and there is no shipped / logistics cue, do not treat waybill regex as tracking.
     */
    private fun shouldParseTracking(fullText: String): Boolean {
        val shippedCue = SHIPPED_TRACKING_CUES.any { fullText.contains(it) }
        if (shippedCue) return true
        if (fullText.contains(WAIT_SELLER_SHIP)) return false
        return true
    }

    private fun labelProximityPx(layoutHints: LayoutHints, geo: ContentGeometry): Int {
        val median = layoutHints.metrics[XianyuLayoutKeys.METRIC_MEDIAN_LINE_HEIGHT_PX] ?: 48f
        val fromMedian = (median * 4f).toInt().coerceAtLeast(120)
        val fromContent = (geo.contentHeight * 0.18f).toInt().coerceAtLeast(80)
        return max(fromMedian, fromContent)
    }

    private fun scoreAsProductName(line: String): Int {
        val hanCount = line.count { it in '\u4e00'..'\u9fff' }
        val txt = line.lowercase()

        if (txt.replace(Regex("[^\\d]"), "").length > 10) return -10000

        val blacklist = listOf(
            "đường", "thành phố", "bằng tường", "sùng tả", "nanda", "bình dương", "khu tự trị",
            "自治区", "崇左市", "凭祥市", "凭样镇", "南大路",
            "订单", "成交", "50g", "¥", "￥", "复制",
            "确认收货", "自动确认",
        )
        if (blacklist.any { txt.contains(it) }) return -10000

        var score = hanCount * 100 + line.length
        if (txt.contains("原神") || txt.contains("genshin")) score += 5000
        if (txt.contains("散兵") || txt.contains("lang thang") || txt.contains("scaramouche")) score += 5000
        return score
    }

    private fun parseMoney(raw: String): Double? {
        val cleanedRaw = raw.replace(" ", "")
        val hasCurrencySign = cleanedRaw.contains("¥") || cleanedRaw.contains("￥") || cleanedRaw.contains("元")

        val match = Regex("([\\d.,]{1,8})").find(cleanedRaw) ?: return null
        val s = match.value.replace(",", ".").replace(Regex("[^\\d.]"), "")

        if (!hasCurrencySign && s.replace(".", "").length > 6) return null

        val parts = s.split(".")
        val cleaned = if (parts.size > 2) {
            parts.dropLast(1).joinToString("") + "." + parts.last()
        } else {
            s
        }

        val finalNum = cleaned.toDoubleOrNull()
        if (finalNum == 50.0 || finalNum == 50.00) return null

        return finalNum
    }

    private data class ContentGeometry(
        val contentLeft: Int,
        val contentTop: Int,
        val contentWidth: Int,
        val contentHeight: Int,
    ) {
        fun nyContent(box: Rect): Double =
            (box.centerY() - contentTop).toDouble() / contentHeight.coerceAtLeast(1)

        fun nxContent(box: Rect): Double =
            (box.centerX() - contentLeft).toDouble() / contentWidth.coerceAtLeast(1)

        companion object {
            fun from(document: OcrDocument, hints: LayoutHints): ContentGeometry {
                val sw = document.screen.widthPx.coerceAtLeast(1)
                val sh = document.screen.heightPx.coerceAtLeast(1)
                val r = hints.normalizedRegions[XianyuLayoutKeys.REGION_CONTENT_FRAME]
                return if (r != null) {
                    val left = (r.left * sw).toInt().coerceIn(0, sw - 1)
                    val top = (r.top * sh).toInt().coerceIn(0, sh - 1)
                    val right = (r.right * sw).toInt().coerceIn(left + 1, sw)
                    val bottom = (r.bottom * sh).toInt().coerceIn(top + 1, sh)
                    ContentGeometry(left, top, right - left, bottom - top)
                } else {
                    ContentGeometry(0, 0, sw, sh)
                }
            }
        }
    }

    companion object {
        private const val TAG = "PSM_PARSER"

        private val TRACK_REGEX = Regex("(?:运单号|物流单号|快递单号|单号|快递|快遞)[^0-9A-Za-z]*([A-Za-z0-9\\-\\s]{8,32})")
        private val ORDER_REGEX = Regex("(?:订单编号|订单号)[^0-9]*(\\d[\\d\\s]{10,24})")

        private const val WAIT_SELLER_SHIP = "等待卖家发货"
        private val SHIPPED_TRACKING_CUES = listOf("已发货", "运单号", "快递", "菜鸟")
        private val STATUS_SNIPPETS_ORDERED = listOf(
            WAIT_SELLER_SHIP,
            "已发货",
            "待发货",
            "等待买家收货",
            "交易关闭",
            "交易成功",
        )
        private val DISPLAY_PRICE_REGEX = Regex("[¥￥]\\s*([\\d\\s,.]{1,12})")

        /** Title band in **content-normalized** Y (0 = top of OCR union). */
        private val TITLE_NY_RANGE = 0.12..0.68

        private const val TITLE_MIN_NX = 0.08

        /** Price fallback band in content-normalized Y (legacy ~55–82% screen when content ≈ screen). */
        private val PRICE_FALLBACK_NY_RANGE = 0.45..0.92
    }
}

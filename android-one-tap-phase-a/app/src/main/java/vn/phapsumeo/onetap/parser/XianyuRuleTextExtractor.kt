package vn.phapsumeo.onetap.parser

import vn.phapsumeo.onetap.model.OrderDraft
import kotlin.math.abs

/**
 * Trích giá / mã đơn theo từ khóa + regex trên toàn bộ chữ OCR (rule-first),
 * ưu tiên 成交价 = tổng, 商品总价 = hàng, 运费 = ship.
 */
object XianyuRuleTextExtractor {

    private val RE_CHENG_JIAO =
        Regex("""成交价[^\d¥￥]*[¥￥]?\s*([\d\s.,]+)""")
    private val RE_SHANG_PIN =
        Regex("""商品总价[^\d¥￥]*[¥￥]?\s*([\d\s.,]+)""")
    private val RE_YUN_FEI =
        Regex("""运费[^\d¥￥]*[¥￥]?\s*([\d\s.,]+)""")
    private val RE_ORDER_LABEL = Regex("""订单编号|订单号""")
    /** Mã Xianyu dài hơn SĐT 11 số. Không nuốt 188… sau nhãn đơn. */
    private val RE_LONG_ORDER_DIGITS = Regex("""\d[\d\s]{15,24}""")
    private val CN_MOBILE = Regex("""1[3-9]\d{9}""")
    private val TRACK_REGEX =
        Regex("""(?:运单号|物流单号|快递单号|(?<!订)单号|快递|快遞)[^0-9A-Za-z]*([A-Za-z0-9\-\s]{8,32})""")

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

    private fun findOrderId(text: String): String? {
        val label = RE_ORDER_LABEL.find(text) ?: return null
        val tail = text.substring(label.range.last + 1).take(80)
        return RE_LONG_ORDER_DIGITS.findAll(tail)
            .map { it.value.replace(" ", "") }
            .firstOrNull { !CN_MOBILE.matches(it) }
    }

    private fun usableTracking(raw: String): String? {
        val t = raw.replace(" ", "").trim()
        if (t.length < 8 || CN_MOBILE.matches(t)) return null
        return t
    }

    fun parseMoneyToken(raw: String): Double? {
        val s = raw.replace(" ", "").replace(",", ".").trim()
        if (s.isEmpty()) return null
        val cleaned = s.replace(Regex("[^\\d.]"), "").trim()
        if (cleaned.isEmpty()) return null
        val parts = cleaned.split(".")
        val normalized = if (parts.size > 2) {
            parts.dropLast(1).joinToString("") + "." + parts.last()
        } else {
            cleaned
        }
        return normalized.toDoubleOrNull()
    }

    fun sumMatchesTotal(hang: Double?, ship: Double?, tong: Double?, eps: Double = 0.051): Boolean {
        if (hang == null || ship == null || tong == null) return false
        return abs(hang + ship - tong) <= eps
    }

    private fun shouldParseTracking(fullText: String): Boolean {
        val shippedCue = SHIPPED_TRACKING_CUES.any { fullText.contains(it) }
        if (shippedCue) return true
        if (fullText.contains(WAIT_SELLER_SHIP)) return false
        return true
    }

    /**
     * Mã đơn / MVĐ / trạng thái từ vùng metadata (+ title phụ để bắt trạng thái trên cùng).
     */
    fun mergeMetadata(draft: OrderDraft, metaText: String, auxiliaryText: String = ""): OrderDraft {
        val text = (metaText + "\n" + auxiliaryText).replace("\r", "")
        val ruleOrder = findOrderId(text)
        var maVc = draft.maVanChuyen
        if (shouldParseTracking(text)) {
            TRACK_REGEX.find(text)?.let { m ->
                usableTracking(m.groupValues[1])?.let { maVc = it }
            }
        }
        val st = STATUS_SNIPPETS_ORDERED.firstOrNull { text.contains(it) }.orEmpty()
        return draft.copy(
            maDonHang = if (!ruleOrder.isNullOrBlank()) ruleOrder else draft.maDonHang,
            maVanChuyen = maVc,
            trangThaiDon = st.ifBlank { draft.trangThaiDon },
        )
    }

    fun merge(draft: OrderDraft, rawText: String): OrderDraft {
        val text = rawText.replace("\r", "")
        val ruleTong = RE_CHENG_JIAO.find(text)?.groupValues?.getOrNull(1)?.let(::parseMoneyToken)
        val ruleHang = RE_SHANG_PIN.find(text)?.groupValues?.getOrNull(1)?.let(::parseMoneyToken)
        val ruleShip = RE_YUN_FEI.find(text)?.groupValues?.getOrNull(1)?.let(::parseMoneyToken)
        val ruleOrder = findOrderId(text)

        val mergedHang = ruleHang ?: draft.giaHangCn
        val mergedShip = ruleShip ?: draft.shipNoiDiaCn
        val mergedTong = ruleTong ?: draft.tongCn
        val verified = sumMatchesTotal(mergedHang, mergedShip, mergedTong)

        return draft.copy(
            maDonHang = if (!ruleOrder.isNullOrBlank()) ruleOrder else draft.maDonHang,
            giaHangCn = mergedHang,
            shipNoiDiaCn = mergedShip,
            tongCn = mergedTong,
            tongTienDaXacMinh = verified,
        )
    }
}

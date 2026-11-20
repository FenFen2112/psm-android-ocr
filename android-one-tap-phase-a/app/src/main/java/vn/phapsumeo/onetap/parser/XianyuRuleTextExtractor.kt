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
    /** Giá dính cuối tên, ví dụ `玩具¥13.1`. */
    private val RE_TITLE_PRICE = Regex("""[¥￥]\s*(\d{1,6}(?:[.,]\d{1,2})?)""")
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

    data class ProductRow(val name: String, val price: Double?)

    data class OrderRead(
        val products: List<ProductRow> = emptyList(),
        val ship: Double? = null,
        val paid: Double? = null,
        val goods: Double? = null,
    )

    /**
     * Mỗi dòng sản phẩm: tên + giá đang bán. Hai mức giá trên một dòng thì bỏ giá gạch (số lớn hơn).
     * 运费 và 实付款 không phải sản phẩm.
     */
    fun readOrder(text: String): OrderRead {
        var ship: Double? = null
        var paid: Double? = null
        var goods: Double? = null
        val products = mutableListOf<ProductRow>()
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val compact = line.replace(" ", "")
            when {
                isNoiseLine(compact) -> Unit
                compact.contains("运费") || compact.contains("运費") -> {
                    val (amount, consumed) = feeAmount(lines, i)
                    if (amount != null) ship = amount
                    i = consumed
                }
                compact.contains("实付") || compact.contains("實付") || compact.contains("成交价") || compact.contains("合计") -> {
                    val (amount, consumed) = feeAmount(lines, i)
                    if (amount != null) paid = amount
                    i = consumed
                }
                compact.contains("商品总价") -> {
                    val (amount, consumed) = feeAmount(lines, i)
                    if (amount != null) goods = amount
                    i = consumed
                }
                else -> {
                    val prices = yenAmounts(line)
                    val name = stripYen(line)
                    if (prices.isNotEmpty() && looksLikeProductName(name)) {
                        products += ProductRow(name, currentPrice(prices))
                    } else if (prices.isEmpty() && looksLikeProductName(line) && i + 1 < lines.size) {
                        val next = lines[i + 1]
                        val nextCompact = next.replace(" ", "")
                        val nextPrices = yenAmounts(next)
                        val nextIsFee = nextCompact.contains("运费") || nextCompact.contains("实付款") ||
                            nextCompact.contains("成交价") || nextCompact.contains("商品总价")
                        if (!nextIsFee && nextPrices.isNotEmpty() && !looksLikeProductName(stripYen(next))) {
                            products += ProductRow(line.trim(), currentPrice(nextPrices))
                            i += 1
                        }
                    }
                }
            }
            i += 1
        }
        val flat = lines.joinToString(" ")
        if (paid == null) {
            paid = Regex("""(?:实付款|實付款|实付|實付|成交价)[^\d]{0,8}(\d{1,6}(?:[.,]\d{1,2})?)""")
                .find(flat)
                ?.let { parseMoneyToken(it.groupValues[1]) }
        }
        if (ship == null) {
            ship = Regex("""运[费費][^\d]{0,8}(\d{1,6}(?:[.,]\d{1,2})?)""")
                .find(flat)
                ?.let { parseMoneyToken(it.groupValues[1]) }
        }
        return OrderRead(products, ship, paid, goods)
    }

    fun applyRead(draft: OrderDraft, read: OrderRead): OrderDraft {
        if (read.products.isEmpty() && read.ship == null && read.paid == null) return draft
        val prices = read.products.mapNotNull { it.price?.takeIf { price -> price > 0.0 } }
        val itemSum = prices.takeIf { it.isNotEmpty() }?.sum()
        val ship = read.ship?.takeIf { it > 0.0 } ?: draft.shipNoiDiaCn?.takeIf { it > 0.0 }
        val paid = read.paid?.takeIf { it > 0.0 }
        val tong = when {
            paid != null -> paid
            itemSum != null && ship != null -> itemSum + ship
            read.products.size <= 1 -> itemSum ?: draft.tongCn?.takeIf { it > 0.0 }
            else -> null
        }
        val title = if (read.products.isEmpty()) {
            draft.tenSanPham
        } else {
            read.products.map { shortProductName(it.name) }
                .filter { it.isNotEmpty() }
                .distinct()
                .joinToString(" + ")
                .take(120)
        }
        val hang = when {
            read.goods != null && read.goods > 0.0 -> read.goods
            read.products.size == 1 -> prices.firstOrNull()
            else -> itemSum
        }
        return draft.copy(
            tenSanPham = title,
            giaHangCn = hang?.takeIf { it > 0.0 },
            shipNoiDiaCn = ship,
            tongCn = tong?.takeIf { it > 0.0 },
            nhieuSanPham = read.products.size > 1,
            tongTienDaXacMinh = sumMatchesTotal(hang, ship, tong),
        )
    }

    /** Phí / thực trả trên cùng dòng, hoặc ở dòng số ngay bên dưới. */
    private fun feeAmount(lines: List<String>, index: Int): Pair<Double?, Int> {
        val onLine = labelAmount(lines[index])
        if (onLine != null) return onLine to index
        val next = lines.getOrNull(index + 1) ?: return null to index
        val nextAmount = loneAmount(next)
        if (nextAmount != null) return nextAmount to index + 1
        return null to index
    }

    /** Số trên dòng nhãn, có hoặc không có ký hiệu ¥. */
    private fun labelAmount(line: String): Double? {
        yenAmounts(line).firstOrNull()?.let { return it }
        val tail = Regex("""(\d{1,6}(?:[.,]\d{1,2})?)\s*$""").find(line) ?: return null
        return parseMoneyToken(tail.groupValues[1])?.takeIf { it > 0.0 && it < 100_000.0 }
    }

    /** Dòng chỉ có một số tiền, ví dụ `¥26.5` hoặc `26.5`. */
    private fun loneAmount(line: String): Double? {
        val match = Regex("""^[¥￥xX×]?\s*(\d{1,6}(?:[.,]\d{1,2})?)\s*$""").matchEntire(line.trim())
            ?: return null
        return parseMoneyToken(match.groupValues[1])?.takeIf { it > 0.0 && it < 100_000.0 }
    }

    private fun shortProductName(name: String): String {
        val cleaned = stripYen(name)
            .replace(Regex("""[xX×✕]\s*\d+\s*$"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
        return if (cleaned.length <= 28) cleaned else cleaned.take(28).trim()
    }

    /** Giá đang bán trên tên. Nhiều mức ¥ thì lấy số nhỏ hơn, bỏ giá gạch. */
    fun splitTitlePrice(title: String): Pair<String, Double?> {
        val amounts = yenAmounts(title)
        val amount = currentPrice(amounts) ?: return title.trim() to null
        return stripYen(title) to amount
    }

    private fun currentPrice(amounts: List<Double>): Double? =
        amounts.filter { it > 0.0 }.minOrNull()

    private fun yenAmounts(text: String): List<Double> =
        RE_TITLE_PRICE.findAll(text).mapNotNull { parseMoneyToken(it.groupValues[1]) }.filter { it > 0.0 }.toList()

    private fun stripYen(text: String): String =
        text.replace(RE_TITLE_PRICE, " ").replace(Regex("""\s+"""), " ").trim()

    private fun looksLikeProductName(name: String): Boolean {
        val t = name.trim()
        if (t.length < 2) return false
        val han = t.count { it in '\u4e00'..'\u9fff' }
        val latin = t.count { it.isLetter() }
        return han >= 2 || latin >= 4
    }

    private fun isNoiseLine(compact: String): Boolean {
        if (compact.contains("确认收货") || compact.contains("自动确认")) return true
        if (compact.contains("专人专拍")) return true
        if (compact.contains("订单编号") || compact.contains("订单号")) return true
        if (compact.contains("复制") && compact.length < 24) return true
        return Regex("""1[3-9]\d{9}""").containsMatchIn(compact)
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

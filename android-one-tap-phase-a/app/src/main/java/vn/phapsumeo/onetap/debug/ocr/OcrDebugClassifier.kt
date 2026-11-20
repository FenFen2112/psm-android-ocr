package vn.phapsumeo.onetap.debug.ocr

import vn.phapsumeo.onetap.extraction.core.OcrLine
import vn.phapsumeo.onetap.model.OrderDraft

/**
 * Lightweight line → entity coloring from [OrderDraft] and simple price/tab cues.
 */
object OcrDebugClassifier {

    fun classify(line: OcrLine, draft: OrderDraft): OcrDebugEntityType {
        val t = line.text
        if (t.isBlank()) return OcrDebugEntityType.UNKNOWN

        val compact = t.replace(Regex("\\s+"), "")

        if (draft.maDonHang.isNotBlank() && t.contains(draft.maDonHang.replace(" ", ""), ignoreCase = true)) {
            return OcrDebugEntityType.ORDER_ID
        }

        if (draft.maVanChuyen.isNotBlank() && t.contains(draft.maVanChuyen, ignoreCase = true)) {
            return OcrDebugEntityType.TRACKING
        }

        if (draft.tenTab.isNotBlank() && draft.tenTab.length >= 2 &&
            (t.contains(draft.tenTab, ignoreCase = true) || compact.contains(draft.tenTab.replace(Regex("\\s+"), ""), ignoreCase = true))
        ) {
            return OcrDebugEntityType.TAB
        }

        val title = draft.tenSanPham.trim()
        if (title.length >= 3) {
            val head = title.take(24).trim()
            if (head.isNotEmpty() && t.contains(head, ignoreCase = true)) {
                return OcrDebugEntityType.TITLE
            }
        }

        val priceish = t.contains('¥') || t.contains('￥') || t.contains('元') || t.contains("CNY", ignoreCase = true)
        if (priceish) {
            when {
                draft.tongCn != null && numberLooselyInText(t, draft.tongCn) -> OcrDebugEntityType.PRICE_TONG
                draft.giaHangCn != null && numberLooselyInText(t, draft.giaHangCn) -> OcrDebugEntityType.PRICE_HANG
                draft.shipNoiDiaCn != null && numberLooselyInText(t, draft.shipNoiDiaCn) -> OcrDebugEntityType.PRICE_SHIP
                else -> OcrDebugEntityType.PRICE_AMBIGUOUS
            }.let { return it }
        }

        return OcrDebugEntityType.UNKNOWN
    }

    private fun numberLooselyInText(text: String, value: Double): Boolean {
        val digitsLine = text.filter { it.isDigit() || it == '.' }.replace(".", "")
        if (digitsLine.isEmpty()) return false
        val intPart = kotlin.math.abs(value).toInt().toString()
        if (intPart.length >= 2 && text.contains(intPart)) return true
        val oneDec = "%.1f".format(kotlin.math.abs(value)).trimEnd('0').trimEnd('.')
        if (oneDec.length >= 2 && text.contains(oneDec)) return true
        return false
    }
}

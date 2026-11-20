package vn.phapsumeo.onetap.debug.ocr

import vn.phapsumeo.onetap.model.OrderDraft

/** Same weighting as legacy MainActivity auto-submit heuristic (for debug HUD only). */
object OcrDebugUiHeuristic {

    fun score01(draft: OrderDraft): Double {
        var s = 0.0
        if (draft.maVanChuyen.length >= 8 || draft.maDonHang.length >= 10) s += 0.35
        if (draft.tenSanPham.length >= 4) s += 0.25
        if (draft.tongCn != null && draft.tongCn > 0) s += 0.25
        if (draft.giaHangCn != null && draft.giaHangCn > 0) s += 0.10
        if (draft.shipNoiDiaCn != null && draft.shipNoiDiaCn >= 0) s += 0.05
        return s.coerceAtMost(1.0)
    }

    fun percent(draft: OrderDraft): Int = (score01(draft) * 100).toInt()
}

package vn.phapsumeo.onetap.gas

import org.json.JSONObject
import vn.phapsumeo.onetap.model.OrderDraft

object GasPayloadBuilder {
    fun buildPreviewJson(draft: OrderDraft): String {
        val obj = JSONObject()
            .put("linkGoc", "")
            .put("giaGoc", draft.giaGocForGas())
            .put("linkAnh", draft.sheetImageUris().joinToString("\n"))
            .put("maDonHang", draft.maDonHang)
            .put("maVanChuyen", draft.maVanChuyen)
            .put("trangThaiDon", draft.trangThaiDon)
            .put("tenTab", draft.tenTab)
            .put("tenSanPham", draft.tenSanPham)
            .put("tenSanPhamMulti", draft.nhieuSanPham)
        return obj.toString(2)
    }
}

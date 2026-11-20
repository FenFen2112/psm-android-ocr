package vn.phapsumeo.onetap.gas

import org.json.JSONObject
import vn.phapsumeo.onetap.model.OrderDraft

object GasPayloadBuilder {
    fun buildPreviewJson(draft: OrderDraft): String {
        val rows = draft.sheetRows().map { row ->
            JSONObject()
                .put("linkGoc", "")
                .put("giaGoc", row.giaGocForGas())
                .put("linkAnh", row.sharedImageUri)
                .put("maDonHang", row.maDonHang)
                .put("maVanChuyen", row.maVanChuyen)
                .put("trangThaiDon", row.trangThaiDon)
                .put("tenTab", row.tenTab)
                .put("tenSanPham", row.tenSanPham)
                .put("tenSanPhamMulti", false)
                .put("shipNoiDiaCn", row.shipForGas())
                .put("tongCn", row.tongForGas())
        }
        return if (rows.size == 1) rows[0].toString(2) else org.json.JSONArray(rows).toString(2)
    }
}

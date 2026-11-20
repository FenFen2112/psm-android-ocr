package vn.phapsumeo.onetap.debug.dataset

import org.json.JSONObject
import vn.phapsumeo.onetap.model.OrderDraft
import java.security.MessageDigest

/**
 * Privacy helpers: never persist raw content/file URIs or credentials in dataset lines.
 */
object OcrDatasetPrivacy {

    /** Short stable reference for correlating rows without exposing URI path or authority. */
    fun uriReference(uri: String?): String {
        if (uri.isNullOrBlank()) return "none"
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            val h = md.digest(uri.toByteArray(Charsets.UTF_8))
            val sb = StringBuilder("sha256:")
            for (i in 0 until minOf(8, h.size)) {
                sb.append("%02x".format(h[i.toInt()]))
            }
            sb.toString()
        } catch (_: Exception) {
            "unavailable"
        }
    }

    fun privacyBlock(uri: String?, textRedacted: Boolean): JSONObject =
        JSONObject().apply {
            put("uri_reference", uriReference(uri))
            put("text_redacted", textRedacted)
            put("note", "No GAS URL, HMAC, or API keys in this schema.")
        }

    /** Order draft without raw [OrderDraft.sharedImageUri]. */
    fun draftToJson(draft: OrderDraft): JSONObject =
        JSONObject().apply {
            put("maDonHang", draft.maDonHang)
            put("maVanChuyen", draft.maVanChuyen)
            put("trangThaiDon", draft.trangThaiDon)
            put("tenSanPham", draft.tenSanPham)
            put("tenSanPhamDichThamKhao", draft.tenSanPhamDichThamKhao)
            put("tongTienDaXacMinh", draft.tongTienDaXacMinh)
            put("giaHangCn", draft.giaHangCn ?: JSONObject.NULL)
            put("shipNoiDiaCn", draft.shipNoiDiaCn ?: JSONObject.NULL)
            put("tongCn", draft.tongCn ?: JSONObject.NULL)
            put("tenTab", draft.tenTab)
            put("sheetName", draft.sheetName)
            put("image_uri_reference", uriReference(draft.sharedImageUri))
        }

    fun fieldsChanged(parser: OrderDraft, user: OrderDraft): List<String> {
        val out = ArrayList<String>()
        if (parser.maDonHang != user.maDonHang) out.add("maDonHang")
        if (parser.maVanChuyen != user.maVanChuyen) out.add("maVanChuyen")
        if (parser.trangThaiDon != user.trangThaiDon) out.add("trangThaiDon")
        if (parser.tenSanPham != user.tenSanPham) out.add("tenSanPham")
        if (parser.tenSanPhamDichThamKhao != user.tenSanPhamDichThamKhao) out.add("tenSanPhamDichThamKhao")
        if (parser.tongTienDaXacMinh != user.tongTienDaXacMinh) out.add("tongTienDaXacMinh")
        if (parser.giaHangCn != user.giaHangCn) out.add("giaHangCn")
        if (parser.shipNoiDiaCn != user.shipNoiDiaCn) out.add("shipNoiDiaCn")
        if (parser.tongCn != user.tongCn) out.add("tongCn")
        if (parser.tenTab != user.tenTab) out.add("tenTab")
        if (parser.sheetName != user.sheetName) out.add("sheetName")
        return out
    }
}

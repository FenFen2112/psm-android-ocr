package vn.phapsumeo.onetap.model

data class OrderDraft(
    /** 订单号 / order id (Xianyu). */
    val maDonHang: String = "",
    /** 运单号 / logistics tracking (when shipped). */
    val maVanChuyen: String = "",
    /** Detected order status snippet (Chinese UI). */
    val trangThaiDon: String = "",
    val tenSanPham: String = "",
    /** Dịch tham khảo tiếng Việt (UI), không thay thế tên gốc. */
    val tenSanPhamDichThamKhao: String = "",
    val giaHangCn: Double? = null,
    val shipNoiDiaCn: Double? = null,
    val tongCn: Double? = null,
    val tenTab: String = "Trung Quốc",
    val sheetName: String = "Tháng 5",
    val sharedImageUri: String = "",
    /** Một URI cho mỗi ảnh sản phẩm. Ảnh đầu trùng [sharedImageUri]. */
    val productImageUris: List<String> = emptyList(),
    /** Hơn một dòng sản phẩm trên cùng một đơn. */
    val nhieuSanPham: Boolean = false,
    /** 商品总价 + 运费 khớp 成交价 (regex / kiểm tra UI). */
    val tongTienDaXacMinh: Boolean = false,
) {
    fun sheetImageUris(): List<String> {
        val all = productImageUris.filter { it.isNotBlank() }
        if (all.isNotEmpty()) return all
        return listOf(sharedImageUri).filter { it.isNotBlank() }
    }

    fun giaGocForGas(): String {
        val v = when {
            tongCn != null && tongCn > 0.0 -> tongCn
            giaHangCn != null && giaHangCn > 0.0 && shipNoiDiaCn != null && shipNoiDiaCn > 0.0 ->
                giaHangCn + shipNoiDiaCn
            giaHangCn != null && giaHangCn > 0.0 -> giaHangCn
            else -> return ""
        }
        return (kotlin.math.round(v * 100.0) / 100.0).toString()
    }
}

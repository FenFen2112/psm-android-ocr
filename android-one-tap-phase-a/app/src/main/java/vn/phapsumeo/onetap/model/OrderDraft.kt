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
    /** Ảnh sản phẩm đã cắt. Sheet chỉ dùng ảnh đầu, đại diện cho cả đơn. */
    val productImageUris: List<String> = emptyList(),
    /** Hơn một dòng sản phẩm trên cùng một đơn. Sheet vẫn là một dòng. */
    val nhieuSanPham: Boolean = false,
    /** 商品总价 + 运费 khớp 成交价 (regex / kiểm tra UI). */
    val tongTienDaXacMinh: Boolean = false,
) {
    /** Một ảnh đại diện. Công thức IMAGE của sheet không nhận nhiều link. */
    fun sheetImageUri(): String {
        return productImageUris.firstOrNull { it.isNotBlank() }
            ?: sharedImageUri.takeIf { it.isNotBlank() }
            .orEmpty()
    }

    /**
     * Ô giá sheet là 实付款. Không lấy giá từng món và không lấy tổng giá hàng (23).
     * Thiếu thực trả thì để trống, không ghi 0.
     */
    fun giaGocForGas(): String = moneyOrBlank(tongCn)

    private fun moneyOrBlank(value: Double?): String {
        if (value == null || value <= 0.0) return ""
        val rounded = kotlin.math.round(value * 100.0) / 100.0
        return if (rounded % 1.0 == 0.0) rounded.toInt().toString() else rounded.toString()
    }
}

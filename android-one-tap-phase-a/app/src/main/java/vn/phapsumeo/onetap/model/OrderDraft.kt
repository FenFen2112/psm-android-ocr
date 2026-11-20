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
    /** Một URI cho mỗi ảnh sản phẩm, cùng thứ tự với [giaTungMon]. */
    val productImageUris: List<String> = emptyList(),
    /** Giá đang bán từng món. Không cộng phí, không lấy giá gạch. */
    val giaTungMon: List<Double?> = emptyList(),
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

    /** Giá sheet của một món. Không dùng tổng đơn và không cộng phí vận chuyển. */
    fun giaGocForGas(): String = moneyOrBlank(giaHangCn)

    fun shipForGas(): String = moneyOrBlank(shipNoiDiaCn)

    fun tongForGas(): String = moneyOrBlank(tongCn)

    /**
     * Một dòng sheet cho mỗi sản phẩm. 运费 và 实付款 đi kèm đơn, không cộng vào giá món.
     */
    fun sheetRows(): List<OrderDraft> {
        val names = tenSanPham.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val prices = giaTungMon
        val photos = sheetImageUris()
        if (names.size <= 1 && prices.size <= 1) {
            val photo = photos.firstOrNull().orEmpty()
            return listOf(
                copy(
                    tenSanPham = names.firstOrNull() ?: tenSanPham.trim(),
                    giaHangCn = prices.firstOrNull() ?: giaHangCn,
                    sharedImageUri = photo,
                    productImageUris = listOf(photo).filter { it.isNotBlank() },
                    nhieuSanPham = false,
                ),
            )
        }
        return names.mapIndexed { index, name ->
            val photo = photos.getOrNull(index).orEmpty()
            copy(
                tenSanPham = name,
                giaHangCn = prices.getOrNull(index),
                giaTungMon = listOf(prices.getOrNull(index)),
                sharedImageUri = photo,
                productImageUris = listOf(photo).filter { it.isNotBlank() },
                nhieuSanPham = false,
            )
        }
    }

    private fun moneyOrBlank(value: Double?): String {
        if (value == null || value <= 0.0) return ""
        val rounded = kotlin.math.round(value * 100.0) / 100.0
        return if (rounded % 1.0 == 0.0) rounded.toInt().toString() else rounded.toString()
    }
}

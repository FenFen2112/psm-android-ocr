package vn.phapsumeo.onetap.util

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import vn.phapsumeo.onetap.model.OrderDraft

class RetryQueueStore(context: Context) {
    private val prefs = context.getSharedPreferences("psm_onetap_queue", Context.MODE_PRIVATE)

    fun enqueue(draft: OrderDraft, reason: String = "") {
        val arr = readArray()
        arr.put(
            JSONObject()
                .put("ts", System.currentTimeMillis())
                .put("reason", reason)
                .put("attempt", 0)
                .put("nextRetryAt", System.currentTimeMillis())
                .put("maDonHang", draft.maDonHang)
                .put("maVanChuyen", draft.maVanChuyen)
                .put("trangThaiDon", draft.trangThaiDon)
                .put("tenSanPham", draft.tenSanPham)
                .put("giaHangCn", draft.giaHangCn)
                .put("shipNoiDiaCn", draft.shipNoiDiaCn)
                .put("tongCn", draft.tongCn)
                .put("tenTab", draft.tenTab)
                .put("sharedImageUri", draft.sharedImageUri),
        )
        saveArray(arr)
    }

    fun all(): List<QueueItem> {
        val arr = readArray()
        val out = mutableListOf<QueueItem>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out += QueueItem(
                draft = OrderDraft(
                    maDonHang = o.optString("maDonHang", ""),
                    maVanChuyen = o.optString("maVanChuyen", ""),
                    trangThaiDon = o.optString("trangThaiDon", ""),
                    tenSanPham = o.optString("tenSanPham", ""),
                    giaHangCn = o.optDoubleOrNull("giaHangCn"),
                    shipNoiDiaCn = o.optDoubleOrNull("shipNoiDiaCn"),
                    tongCn = o.optDoubleOrNull("tongCn"),
                    tenTab = o.optString("tenTab", "Trung Quốc"),
                    sharedImageUri = o.optString("sharedImageUri", ""),
                ),
                attempt = o.optInt("attempt", 0),
                nextRetryAt = o.optLong("nextRetryAt", 0L),
                reason = o.optString("reason", ""),
            )
        }
        return out
    }

    fun replaceAll(items: List<QueueItem>) {
        val arr = JSONArray()
        items.forEach { item ->
            val d = item.draft
            arr.put(
                JSONObject()
                    .put("ts", System.currentTimeMillis())
                    .put("reason", item.reason)
                    .put("attempt", item.attempt)
                    .put("nextRetryAt", item.nextRetryAt)
                    .put("maDonHang", d.maDonHang)
                    .put("maVanChuyen", d.maVanChuyen)
                    .put("trangThaiDon", d.trangThaiDon)
                    .put("tenSanPham", d.tenSanPham)
                    .put("giaHangCn", d.giaHangCn)
                    .put("shipNoiDiaCn", d.shipNoiDiaCn)
                    .put("tongCn", d.tongCn)
                    .put("tenTab", d.tenTab)
                    .put("sharedImageUri", d.sharedImageUri),
            )
        }
        saveArray(arr)
    }

    fun clear() {
        prefs.edit().remove("queue_json").apply()
    }

    fun size(): Int = all().size

    fun dueCount(nowMs: Long = System.currentTimeMillis()): Int =
        all().count { it.nextRetryAt <= nowMs }

    private fun readArray(): JSONArray {
        val raw = prefs.getString("queue_json", "[]").orEmpty()
        return runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
    }

    private fun saveArray(arr: JSONArray) {
        prefs.edit().putString("queue_json", arr.toString()).apply()
    }

    private fun JSONObject.optDoubleOrNull(key: String): Double? {
        if (!has(key)) return null
        val v = opt(key) ?: return null
        return when (v) {
            is Number -> v.toDouble()
            is String -> v.toDoubleOrNull()
            else -> null
        }
    }

    data class QueueItem(
        val draft: OrderDraft,
        val attempt: Int,
        val nextRetryAt: Long,
        val reason: String,
    )
}

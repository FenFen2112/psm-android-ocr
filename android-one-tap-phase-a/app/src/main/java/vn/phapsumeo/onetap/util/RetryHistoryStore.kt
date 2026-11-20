package vn.phapsumeo.onetap.util

import android.content.Context
import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RetryHistoryStore(context: Context) {
    private val prefs = context.getSharedPreferences("psm_onetap_retry_history", Context.MODE_PRIVATE)
    private val formatter = SimpleDateFormat("HH:mm:ss dd/MM", Locale.getDefault())

    fun append(
        source: String,
        result: RetryQueueProcessor.ProcessResult,
        keepLast: Int = 25,
    ) {
        val arr = readArray()
        arr.put(
            JSONObject()
                .put("ts", System.currentTimeMillis())
                .put("source", source)
                .put("sent", result.sentCount)
                .put("due", result.dueCountBefore)
                .put("failed", result.failedCount)
                .put("dropped", result.droppedCount)
                .put("remain", result.remainingCount),
        )
        val sliced = JSONArray()
        val start = (arr.length() - keepLast).coerceAtLeast(0)
        for (i in start until arr.length()) sliced.put(arr.opt(i))
        saveArray(sliced)
    }

    fun renderRecent(limit: Int = 8, filterSource: String? = null): String {
        val arr = readArray()
        if (arr.length() == 0) return "Chưa có lịch sử retry."
        val lines = mutableListOf<String>()
        val start = (arr.length() - limit).coerceAtLeast(0)
        for (i in arr.length() - 1 downTo start) {
            val o = arr.optJSONObject(i) ?: continue
            val ts = o.optLong("ts", 0L)
            val source = o.optString("source", "unknown")
            if (!matchesFilter(source, filterSource)) continue
            val sent = o.optInt("sent", 0)
            val due = o.optInt("due", 0)
            val failed = o.optInt("failed", 0)
            val dropped = o.optInt("dropped", 0)
            val remain = o.optInt("remain", 0)
            lines += "[${formatter.format(Date(ts))}] $source | due=$due, sent=$sent, fail=$failed, drop=$dropped, remain=$remain"
        }
        if (lines.isEmpty()) return "Không có lịch sử cho bộ lọc đã chọn."
        return lines.joinToString("\n")
    }

    fun renderRecentStyled(limit: Int = 8, filterSource: String? = null): CharSequence {
        val arr = readArray()
        if (arr.length() == 0) return "Chưa có lịch sử retry."
        val out = SpannableStringBuilder()
        val start = (arr.length() - limit).coerceAtLeast(0)
        for (i in arr.length() - 1 downTo start) {
            val o = arr.optJSONObject(i) ?: continue
            val ts = o.optLong("ts", 0L)
            val source = o.optString("source", "unknown")
            if (!matchesFilter(source, filterSource)) continue
            val sent = o.optInt("sent", 0)
            val due = o.optInt("due", 0)
            val failed = o.optInt("failed", 0)
            val dropped = o.optInt("dropped", 0)
            val remain = o.optInt("remain", 0)
            val line = "[${formatter.format(Date(ts))}] $source | due=$due, sent=$sent, fail=$failed, drop=$dropped, remain=$remain"
            val lineStart = out.length
            out.append(line).append('\n')
            val color = when {
                dropped > 0 -> Color.parseColor("#B71C1C") // red
                sent > 0 && failed == 0 -> Color.parseColor("#1B5E20") // green
                failed > 0 -> Color.parseColor("#E65100") // orange
                else -> Color.parseColor("#37474F") // neutral
            }
            out.setSpan(
                ForegroundColorSpan(color),
                lineStart,
                lineStart + line.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        if (out.isEmpty()) return "Không có lịch sử cho bộ lọc đã chọn."
        if (out.isNotEmpty()) out.delete(out.length - 1, out.length)
        return out
    }

    fun clear() {
        prefs.edit().remove("retry_history_json").apply()
    }

    private fun readArray(): JSONArray {
        val raw = prefs.getString("retry_history_json", "[]").orEmpty()
        return runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
    }

    private fun saveArray(arr: JSONArray) {
        prefs.edit().putString("retry_history_json", arr.toString()).apply()
    }

    private fun matchesFilter(source: String, filterSource: String?): Boolean {
        if (filterSource.isNullOrBlank() || filterSource == "all") return true
        return source == filterSource
    }
}

package vn.phapsumeo.onetap.debug

import android.content.Context
import org.json.JSONObject
import java.io.File

/** NDJSON debug ingest (device: external/files + filesDir). Không log secret/token đầy đủ. */
object DebugNdjsonLog {
    private const val SESSION_ID = "418c16"

    // #region agent log
    fun append(
        context: Context,
        hypothesisId: String,
        location: String,
        message: String,
        data: Map<String, Any?> = emptyMap(),
    ) {
        try {
            val jo = JSONObject()
            jo.put("sessionId", SESSION_ID)
            jo.put("timestamp", System.currentTimeMillis())
            jo.put("hypothesisId", hypothesisId)
            jo.put("location", location)
            jo.put("message", message)
            val dataObj = JSONObject()
            data.forEach { (k, v) -> dataObj.put(k, v?.toString() ?: "null") }
            jo.put("data", dataObj)
            val line = jo.toString() + "\n"
            val dirs = listOfNotNull(context.getExternalFilesDir(null), context.filesDir).distinctBy { it.absolutePath }
            dirs.forEach { File(it, "debug-418c16.log").appendText(line) }
        } catch (_: Exception) {
            /* ignore */
        }
    }
    // #endregion
}

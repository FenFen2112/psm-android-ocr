package vn.phapsumeo.onetap.debug

import android.content.Context
import android.util.Log
import java.io.File
import org.json.JSONObject

/** NDJSON + Logcat for crop-debug session `418c16` (device: `filesDir/debug-418c16.log`). */
internal object OcrCropDebugLog {
    private const val TAG = "PSM_CROP_DEBUG"
    private const val FILE_NAME = "debug-418c16.log"

    fun append(
        context: Context?,
        hypothesisId: String,
        location: String,
        message: String,
        data: Map<String, Any?>,
    ) {
        val line = JSONObject().apply {
            put("sessionId", "418c16")
            put("hypothesisId", hypothesisId)
            put("location", location)
            put("message", message)
            put("timestamp", System.currentTimeMillis())
            put(
                "data",
                JSONObject().apply {
                    data.forEach { (k, v) ->
                        when (v) {
                            null -> put(k, JSONObject.NULL)
                            is Number -> put(k, v)
                            is Boolean -> put(k, v)
                            else -> put(k, v.toString())
                        }
                    }
                },
            )
        }.toString()
        Log.d(TAG, line)
        if (context != null) {
            try {
                File(context.filesDir, FILE_NAME).appendText(line + "\n")
            } catch (_: Exception) {
            }
        }
    }
}

package vn.phapsumeo.onetap.i18n

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Dịch tên sản phẩm sang tiếng Việt (tham khảo) qua Gemini nếu có API key.
 * Không gọi mạng nếu key trống.
 */
object TitleTranslator {

    private const val MODEL = "gemini-2.0-flash-lite"
    private const val TIMEOUT_MS = 20_000

    suspend fun translateProductTitleReference(apiKey: String, chineseTitle: String): String =
        withContext(Dispatchers.IO) {
            val key = apiKey.trim()
            val title = chineseTitle.trim()
            if (key.isEmpty() || title.isEmpty()) return@withContext ""

            val url =
                URL("https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent?key=$key")
            val body = JSONObject().apply {
                put(
                    "contents",
                    JSONArray().put(
                        JSONObject().put(
                            "parts",
                            JSONArray().put(
                                JSONObject().put(
                                    "text",
                                    "Translate the following e-commerce product title from Chinese to Vietnamese. " +
                                        "Output ONLY the Vietnamese translation, one or two short lines, no quotes, no Chinese.\n\n$title",
                                ),
                            ),
                        ),
                    ),
                )
            }
            var conn: HttpURLConnection? = null
            try {
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    doOutput = true
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                }
                conn.outputStream.use { os ->
                    os.write(body.toString().toByteArray(StandardCharsets.UTF_8))
                }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val resp = stream?.use { it.readBytes().toString(StandardCharsets.UTF_8) } ?: ""
                if (code !in 200..299) return@withContext ""

                val root = JSONObject(resp)
                val text = root.optJSONArray("candidates")
                    ?.optJSONObject(0)
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts")
                    ?.optJSONObject(0)
                    ?.optString("text")
                    ?.trim()
                    .orEmpty()
                return@withContext text.take(400)
            } catch (_: Exception) {
                return@withContext ""
            } finally {
                conn?.disconnect()
            }
        }
}

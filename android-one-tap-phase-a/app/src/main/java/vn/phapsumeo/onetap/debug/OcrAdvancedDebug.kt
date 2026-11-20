package vn.phapsumeo.onetap.debug

import android.content.Context
import android.graphics.*
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.*
import vn.phapsumeo.onetap.util.PrefsStore
import java.util.concurrent.TimeUnit

object OcrAdvancedDebug {
    private var basePath: String = ""
    private val history = LinkedList<JSONObject>()
    private const val MAX_HISTORY = 20
    private var server: DebugServer? = null
    private val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .writeTimeout(2, TimeUnit.SECONDS)
        .build()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var latestScreenshot: Bitmap? = null
    private var latestOverlay: Bitmap? = null
    private var latestTitle: Bitmap? = null
    private var latestPrice: Bitmap? = null

    fun init(context: Context) {
        Log.d("PSM_DEBUG", "OcrAdvancedDebug.init called")
        basePath = context.getExternalFilesDir(null)?.absolutePath ?: "/sdcard/PSM_DEBUG"
        listOf("screenshots", "title", "price", "overlay", "failed", "logs").forEach {
            File("$basePath/$it").mkdirs()
        }
        startDebugServer()
    }

    private fun startDebugServer() {
        if (server == null) {
            server = DebugServer(7478)
            try {
                server?.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                Log.d("PSM_DEBUG", "NanoHTTPD started successfully")
            } catch (e: Exception) {
                Log.e("PSM_DEBUG", "NanoHTTPD start failed", e)
            }
        }
    }

    fun logOcrRun(
        context: Context,
        fullBmp: Bitmap?,
        titleBmp: Bitmap?,
        priceBmp: Bitmap?,
        titleRaw: String,
        priceRaw: String,
        parsedTitle: String,
        parsedPrice: Double,
        confidence: Float,
        titleRect: Rect?,
        priceRect: Rect?
    ) {
        try {
            Log.d("PSM_DEBUG", "logOcrRun called: titleRaw='$titleRaw', priceRaw='$priceRaw'")
            val ts = System.currentTimeMillis()
        val success = parsedTitle.length > 5 && parsedPrice > 0 && confidence > 0.5f
        
        latestScreenshot = replaceHeld(latestScreenshot, copyIfAlive(fullBmp))
        latestTitle = replaceHeld(latestTitle, copyIfAlive(titleBmp))
        latestPrice = replaceHeld(latestPrice, copyIfAlive(priceBmp))

        val overlayBmp = buildDebugOverlay(fullBmp, titleRect, priceRect, parsedTitle, parsedPrice, confidence)
        latestOverlay = replaceHeld(latestOverlay, overlayBmp)

        val log = JSONObject().apply {
            put("timestamp", ts)
            put("title_raw", titleRaw)
            put("price_raw", priceRaw)
            put("parsed_title", parsedTitle)
            put("parsed_price", parsedPrice)
            put("confidence", confidence.toDouble())
            put("success", success)
            put("overlay_image", "/overlay/latest.png")
            put("title_crop", "/title/latest.png")
            put("price_crop", "/price/latest.png")
            put("screenshot", "/screenshot/latest.png")
        }

        saveJson(ts, log)
        saveBitmap(ts, "screenshots", fullBmp)
        saveBitmap(ts, "title", titleBmp)
        saveBitmap(ts, "price", priceBmp)
        saveBitmap(ts, "overlay", overlayBmp)

        if (!success) {
            moveFailedSample(ts, log)
        }

            synchronized(history) {
                history.addFirst(log)
                if (history.size > MAX_HISTORY) history.removeLast()
            }

            pushToPc(context, log)
        } catch (e: Exception) {
            Log.e("PSM_DEBUG", "logOcrRun failed", e)
        }
    }

    private fun ingestUrl(context: Context): String? {
        val target = PrefsStore(context).debugIngestTarget().trim().trimEnd('/')
        if (target.isBlank()) return null
        return if (target.startsWith("http://", ignoreCase = true) ||
            target.startsWith("https://", ignoreCase = true)
        ) {
            if (target.endsWith("/ingest", ignoreCase = true)) target else "$target/ingest"
        } else {
            val hostPort = if (target.contains(":")) target else "$target:7478"
            "http://$hostPort/ingest"
        }
    }

    private fun copyIfAlive(src: Bitmap?): Bitmap? {
        if (src == null || src.isRecycled) return null
        return src.copy(src.config ?: Bitmap.Config.ARGB_8888, false)
    }

    private fun replaceHeld(previous: Bitmap?, next: Bitmap?): Bitmap? {
        if (previous != null && previous !== next && !previous.isRecycled) previous.recycle()
        return next
    }

    private fun pushToPc(context: Context, log: JSONObject) {
        val url = ingestUrl(context) ?: return
        scope.launch {
            try {
                val payload = JSONObject().apply {
                    put("timestamp", System.currentTimeMillis())
                    put("data", log)
                }
                val body = payload.toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url(url)
                    .post(body)
                    .build()
                client.newCall(request).execute().use { res ->
                    Log.d("PSM_DEBUG", "Push to PC: ${res.isSuccessful}")
                }
            } catch (e: Exception) {
                Log.w("PSM_DEBUG", "Push to PC failed: ${e.message}")
            }
        }
    }

    private fun saveBitmap(ts: Long, folder: String, bmp: Bitmap?): String? {
        if (bmp == null) return null
        val file = File("$basePath/$folder", "ocr_$ts.png")
        return try {
            FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            file.absolutePath
        } catch (e: Exception) { null }
    }

    private fun saveJson(ts: Long, json: JSONObject) {
        val file = File("$basePath/logs", "ocr_$ts.json")
        try { file.writeText(json.toString(2)) } catch (e: Exception) {}
    }

    private fun buildDebugOverlay(
        base: Bitmap?,
        titleRect: Rect?,
        priceRect: Rect?,
        title: String,
        price: Double,
        conf: Float
    ): Bitmap? {
        if (base == null) return null
        val out = base.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val paint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 8f
            textSize = 50f
            typeface = Typeface.DEFAULT_BOLD
        }

        titleRect?.let {
            paint.color = Color.RED
            canvas.drawRect(it, paint)
            paint.style = Paint.Style.FILL
            canvas.drawText("TITLE (y:${it.top}): $title", it.left.toFloat(), it.top.toFloat() - 20, paint)
            paint.style = Paint.Style.STROKE
        }

        priceRect?.let {
            paint.color = Color.GREEN
            canvas.drawRect(it, paint)
            paint.style = Paint.Style.FILL
            canvas.drawText("PRICE (y:${it.top}): ¥$price (Conf: $conf)", it.left.toFloat(), it.bottom.toFloat() + 60, paint)
        }
        return out
    }

    private fun moveFailedSample(ts: Long, log: JSONObject) {
        val failedDir = File("$basePath/failed/ocr_$ts")
        failedDir.mkdirs()
        File(failedDir, "log.json").writeText(log.toString(2))
        // In real impl, we'd copy the actual files here
    }

    private class DebugServer(port: Int) : NanoHTTPD(port) {
        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri
            return when {
                uri == "/" || uri == "/index.html" -> newFixedLengthResponse(getDashboardHtml())
                uri == "/latest" -> newFixedLengthResponse(Response.Status.OK, "application/json", history.firstOrNull()?.toString() ?: "{}")
                uri == "/history" -> newFixedLengthResponse(Response.Status.OK, "application/json", JSONArray(history).toString())
                uri.startsWith("/overlay/") -> serveBitmap(latestOverlay)
                uri.startsWith("/title/") -> serveBitmap(latestTitle)
                uri.startsWith("/price/") -> serveBitmap(latestPrice)
                uri.startsWith("/screenshot/") -> serveBitmap(latestScreenshot)
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
            }
        }

        private fun serveBitmap(bmp: Bitmap?): Response {
            if (bmp == null) return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "No Image")
            val stream = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, stream)
            return newFixedLengthResponse(Response.Status.OK, "image/png", stream.toByteArray().inputStream(), stream.size().toLong())
        }

        private fun getDashboardHtml(): String {
            val latest = history.firstOrNull() ?: JSONObject()
            return """
                <html>
                <head>
                    <title>Android OCR Remote Debug</title>
                    <meta http-equiv="refresh" content="1">
                    <style>
                        body { font-family: sans-serif; background: #121212; color: #e0e0e0; padding: 20px; }
                        .grid { display: grid; grid-template-columns: 1fr 1fr; gap: 20px; }
                        .card { background: #1e1e1e; padding: 15px; border-radius: 8px; border: 1px solid #333; }
                        img { max-width: 100%; border: 1px solid #444; margin-bottom: 10px; }
                        pre { background: #000; padding: 10px; color: #00ff00; overflow: auto; }
                        .status { font-weight: bold; color: #ff9800; }
                    </style>
                </head>
                <body>
                    <h1>OCR Remote Debug Dashboard</h1>
                    <div class="grid">
                        <div class="card">
                            <h3>Latest Overlay</h3>
                            <img src="/overlay/latest.png?t=${System.currentTimeMillis()}">
                            <p class="status">Success: ${latest.optBoolean("success")}</p>
                            <p>Confidence: ${latest.optDouble("confidence")}</p>
                        </div>
                        <div class="card">
                            <h3>Crops</h3>
                            <p>Title:</p>
                            <img src="/title/latest.png?t=${System.currentTimeMillis()}">
                            <p>Price:</p>
                            <img src="/price/latest.png?t=${System.currentTimeMillis()}">
                        </div>
                    </div>
                    <div class="card" style="margin-top:20px">
                        <h3>Raw Data</h3>
                        <pre>${latest.toString(2)}</pre>
                    </div>
                </body>
                </html>
            """.trimIndent()
        }
    }
}

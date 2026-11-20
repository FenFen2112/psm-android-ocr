package vn.phapsumeo.onetap.gas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import vn.phapsumeo.onetap.model.OrderDraft

class GasClient(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun submitOrder(
        gasUrl: String,
        hmacSecret: String,
        draft: OrderDraft,
    ): JSONObject = withContext(Dispatchers.IO) {
        if (gasUrl.isBlank()) error("Thiếu GAS URL")
        if (hmacSecret.isBlank()) error("Thiếu HMAC secret")
        val imageUrl = ensureImageUrl(gasUrl, hmacSecret, draft.sharedImageUri)

        val businessPayload = JSONObject()
            .put("linkGoc", "")
            .put("giaGoc", draft.giaGocForGas())
            .put("linkAnh", imageUrl)
            .put("maDonHang", draft.maDonHang)
            .put("maVanChuyen", draft.maVanChuyen)
            .put("trangThaiDon", draft.trangThaiDon)
            .put("tenTab", draft.tenTab)
            .put("tenSanPham", draft.tenSanPham)
            .put("tenSanPhamMulti", false)

        val json = postSigned(gasUrl, hmacSecret, businessPayload)

        if (json.optString("status") != "success") {
            val msg = json.optString("message", "GAS từ chối request")
            throw IllegalStateException(msg)
        }
        json
    }

    private fun ensureImageUrl(gasUrl: String, hmacSecret: String, sharedImageUri: String): String {
        if (sharedImageUri.isBlank()) return ""
        if (sharedImageUri.startsWith("http://") || sharedImageUri.startsWith("https://")) return sharedImageUri

        val uri = runCatching { Uri.parse(sharedImageUri) }.getOrNull() ?: return ""
        val payload = buildUploadPayload(uri)
        val uploadReq = JSONObject()
            .put("action", "mat_than_upload_image")
            .put("imageBase64", payload.base64)
            .put("imageName", payload.fileName)
            .put("mimeType", payload.mimeType)
        val json = postSigned(gasUrl, hmacSecret, uploadReq)
        if (json.optString("status") != "success" || json.optString("imageUrl").isBlank()) {
            val msg = json.optString("message", "Upload ảnh thất bại")
            throw IllegalStateException(msg)
        }
        return json.optString("imageUrl")
    }

    private fun postSigned(gasUrl: String, hmacSecret: String, inner: JSONObject): JSONObject {
        val bodyStr = inner.toString()
        val ts = System.currentTimeMillis()
        val message = "$ts\n$bodyStr"
        val sig = HmacSigner.signHex(hmacSecret, message)
        val outer = JSONObject()
            .put("_ts", ts)
            .put("_sig", sig)
            .put("_body", bodyStr)
            .toString()

        val request = Request.Builder()
            .url(gasUrl)
            .post(outer.toRequestBody(jsonMediaType))
            .build()

        return client.newCall(request).execute().use { response ->
            val txt = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                return@use JSONObject().put("status", "error").put("message", "HTTP ${response.code}: $txt")
            }
            try {
                JSONObject(txt)
            } catch (_: Exception) {
                JSONObject().put("status", "error").put("message", "Non-JSON response: $txt")
            }
        }
    }

    private fun buildUploadPayload(uri: Uri): UploadPayload {
        val resolver = context.contentResolver
        val input = resolver.openInputStream(uri) ?: throw IllegalStateException("Không mở được ảnh share")
        val bitmap = BitmapFactory.decodeStream(input) ?: throw IllegalStateException("Ảnh không hợp lệ")
        input.close()

        val resized = resize(bitmap, 1080)
        var quality = 80
        var bytes = compressJpeg(resized, quality)
        while (bytes.size > 800 * 1024 && quality > 30) {
            quality -= 10
            bytes = compressJpeg(resized, quality)
        }

        val mime = "image/jpeg"
        val fileName = "mobile_share_${System.currentTimeMillis()}.jpg"
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return UploadPayload(base64 = b64, mimeType = mime, fileName = fileName)
    }

    private fun resize(src: Bitmap, maxSide: Int): Bitmap {
        val w = src.width
        val h = src.height
        val max = maxOf(w, h)
        if (max <= maxSide) return src
        val scale = maxSide.toFloat() / max.toFloat()
        val nw = (w * scale).toInt().coerceAtLeast(1)
        val nh = (h * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, nw, nh, true)
    }

    private fun compressJpeg(bitmap: Bitmap, quality: Int): ByteArray {
        val bos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, bos)
        return bos.toByteArray()
    }

    private data class UploadPayload(
        val base64: String,
        val mimeType: String,
        val fileName: String,
    )
}

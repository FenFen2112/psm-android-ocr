package vn.phapsumeo.onetap.gas

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object HmacSigner {
    fun signHex(secret: String, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val bytes = mac.doFinal(message.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

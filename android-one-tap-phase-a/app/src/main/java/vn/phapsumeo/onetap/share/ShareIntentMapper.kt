package vn.phapsumeo.onetap.share

import android.content.Intent
import android.net.Uri

object ShareIntentMapper {
    fun readSharedImage(intent: Intent?): Uri? {
        if (intent == null) return null
        if (intent.action != Intent.ACTION_SEND) return null
        val type = intent.type ?: return null
        if (!type.startsWith("image/")) return null
        @Suppress("DEPRECATION")
        return intent.getParcelableExtra(Intent.EXTRA_STREAM)
    }
}

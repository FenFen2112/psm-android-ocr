package vn.phapsumeo.onetap.util

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object RetryQueueNotifier {
    private const val channelId = "psm_queue_retry_channel"
    private const val channelName = "Retry queue nền"
    private const val channelDescription = "Thông báo kết quả gửi lại đơn tự động"

    fun notifyBackgroundResult(
        context: Context,
        result: RetryQueueProcessor.ProcessResult,
    ) {
        if (!shouldNotify(result)) return
        ensureChannel(context)
        if (!hasNotificationPermission(context)) return

        val title = when {
            result.sentCount > 0 -> "Queue nền đã gửi lại ${result.sentCount} đơn"
            result.droppedCount > 0 -> "Queue nền bỏ ${result.droppedCount} đơn quá số lần thử"
            else -> "Queue nền chưa gửi lại được đơn nào"
        }
        val body = buildString {
            append("Đến hạn: ${result.dueCountBefore}")
            append(" | Lỗi: ${result.failedCount}")
            append(" | Còn chờ: ${result.remainingCount}")
            if (result.droppedCount > 0) append(" | Bỏ: ${result.droppedCount}")
        }

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            NotificationManagerCompat.from(context).notify(1024, notification)
        }
    }

    private fun shouldNotify(result: RetryQueueProcessor.ProcessResult): Boolean {
        return result.sentCount > 0 || result.droppedCount > 0 || (result.dueCountBefore > 0 && result.failedCount > 0)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = channelDescription
        }
        manager.createNotificationChannel(channel)
    }

    private fun hasNotificationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
}

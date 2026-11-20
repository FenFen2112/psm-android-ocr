package vn.phapsumeo.onetap.util

import android.content.Context
import kotlin.math.min
import vn.phapsumeo.onetap.gas.GasClient

object RetryQueueProcessor {
    data class ProcessResult(
        val sentCount: Int,
        val remainingCount: Int,
        val dueCountBefore: Int,
        val failedCount: Int,
        val droppedCount: Int,
    )

    suspend fun process(
        context: Context,
        maxRetryAttempts: Int = 5,
        source: String = "unknown",
    ): ProcessResult {
        val prefs = PrefsStore(context)
        val gasUrl = prefs.gasUrl().trim()
        val secret = prefs.hmacSecret().trim()
        if (gasUrl.isBlank() || secret.isBlank()) {
            return ProcessResult(
                sentCount = 0,
                remainingCount = RetryQueueStore(context).size(),
                dueCountBefore = 0,
                failedCount = 0,
                droppedCount = 0,
            ).also { RetryHistoryStore(context).append(source, it) }
        }

        val queueStore = RetryQueueStore(context)
        val pending = queueStore.all()
        if (pending.isEmpty()) return ProcessResult(0, 0, 0, 0, 0).also { RetryHistoryStore(context).append(source, it) }

        val now = System.currentTimeMillis()
        val dueCount = pending.count { it.nextRetryAt <= now }
        val remain = mutableListOf<RetryQueueStore.QueueItem>()
        var sent = 0
        var failed = 0
        var dropped = 0

        for (item in pending) {
            if (item.nextRetryAt > now) {
                remain += item
                continue
            }
            try {
                GasClient(context).submitOrder(gasUrl, secret, item.draft)
                sent += 1
            } catch (e: Exception) {
                failed += 1
                val nextAttempt = item.attempt + 1
                if (nextAttempt > maxRetryAttempts) {
                    dropped += 1
                    continue
                }
                val waitSec = min(300, 5 * (1 shl min(6, nextAttempt)))
                remain += item.copy(
                    attempt = nextAttempt,
                    nextRetryAt = System.currentTimeMillis() + waitSec * 1000L,
                    reason = e.message.orEmpty(),
                )
            }
        }

        queueStore.replaceAll(remain)
        return ProcessResult(
            sentCount = sent,
            remainingCount = remain.size,
            dueCountBefore = dueCount,
            failedCount = failed,
            droppedCount = dropped,
        ).also { RetryHistoryStore(context).append(source, it) }
    }
}

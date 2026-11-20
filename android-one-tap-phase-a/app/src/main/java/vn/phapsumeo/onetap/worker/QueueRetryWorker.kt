package vn.phapsumeo.onetap.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import vn.phapsumeo.onetap.util.RetryQueueNotifier
import vn.phapsumeo.onetap.util.RetryQueueProcessor

class QueueRetryWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        return try {
            val result = RetryQueueProcessor.process(applicationContext, source = "worker")
            RetryQueueNotifier.notifyBackgroundResult(applicationContext, result)
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}

package vn.phapsumeo.onetap.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import vn.phapsumeo.onetap.ocr.preprocess.OcrPreprocessBenchmark
import vn.phapsumeo.onetap.ocr.preprocess.OcrPreprocessConfig
import vn.phapsumeo.onetap.ocr.preprocess.OcrPreprocessConfigs
import vn.phapsumeo.onetap.ocr.preprocess.OcrPreprocessOutput
import vn.phapsumeo.onetap.ocr.preprocess.OcrPreprocessPipeline

/**
 * OCR facade dùng ML Kit Chinese recognizer cho screenshot Xianyu.
 */
class OcrFacade {
    suspend fun extractText(
        context: Context,
        uri: Uri,
        preprocessConfig: OcrPreprocessConfig = OcrPreprocessConfigs.Default,
        benchmark: OcrPreprocessBenchmark = OcrPreprocessBenchmark.Noop,
    ): String {
        // For full-text extraction we intentionally avoid aggressive crop by default
        // to preserve parity with legacy parsing.
        val effectiveConfig = if (preprocessConfig == OcrPreprocessConfigs.Default) {
            preprocessConfig.copy(cropOptimizationEnabled = false)
        } else preprocessConfig

        // We decode into a bitmap when preprocessing is enabled because ML Kit needs the preprocessed pixels.
        val decoded = decodeBitmapFromUri(context, uri, effectiveConfig.maxSidePx)
        if (decoded == null) {
            // Fallback: legacy path (no preprocessing).
            val imageLegacy = InputImage.fromFilePath(context, uri)
            val recognizerLegacy = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            return suspendCancellableCoroutine { cont ->
                recognizerLegacy.process(imageLegacy)
                    .addOnSuccessListener { result ->
                        cont.resume(result.text.orEmpty())
                        recognizerLegacy.close()
                    }
                    .addOnFailureListener { e ->
                        cont.resumeWithException(e)
                        recognizerLegacy.close()
                    }
            }
        }

        val processed = preprocessIfEnabled(decoded, effectiveConfig, benchmark)
        val image = InputImage.fromBitmap(processed.processedBitmap, 0)
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        return suspendCancellableCoroutine { cont ->
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    cont.resume(result.text.orEmpty())
                    recognizer.close()
                    if (processed.processedBitmap !== decoded && !processed.processedBitmap.isRecycled) {
                        processed.processedBitmap.recycle()
                    }
                    if (!decoded.isRecycled) decoded.recycle()
                }
                .addOnFailureListener { e ->
                    cont.resumeWithException(e)
                    recognizer.close()
                    if (processed.processedBitmap !== decoded && !processed.processedBitmap.isRecycled) {
                        processed.processedBitmap.recycle()
                    }
                    if (!decoded.isRecycled) decoded.recycle()
                }
        }
    }

    suspend fun extractTextFromBitmap(
        bitmap: Bitmap,
        preprocessConfig: OcrPreprocessConfig = OcrPreprocessConfigs.Default,
        benchmark: OcrPreprocessBenchmark = OcrPreprocessBenchmark.Noop,
    ): String {
        val effectiveConfig = if (preprocessConfig == OcrPreprocessConfigs.Default) {
            preprocessConfig.copy(cropOptimizationEnabled = false)
        } else preprocessConfig
        val processed = preprocessIfEnabled(bitmap, effectiveConfig, benchmark)
        val image = InputImage.fromBitmap(processed.processedBitmap, 0)
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        return suspendCancellableCoroutine { cont ->
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    cont.resume(result.text.orEmpty())
                    recognizer.close()
                    if (processed.processedBitmap !== bitmap && !processed.processedBitmap.isRecycled) {
                        processed.processedBitmap.recycle()
                    }
                }
                .addOnFailureListener { e ->
                    cont.resumeWithException(e)
                    recognizer.close()
                    if (processed.processedBitmap !== bitmap && !processed.processedBitmap.isRecycled) {
                        processed.processedBitmap.recycle()
                    }
                }
        }
    }

    suspend fun extractLinesWithCoords(
        bitmap: Bitmap,
        preprocessConfig: OcrPreprocessConfig = OcrPreprocessConfigs.Default,
        benchmark: OcrPreprocessBenchmark = OcrPreprocessBenchmark.Noop,
    ): List<vn.phapsumeo.onetap.parser.OcrLine> {
        val processed = preprocessIfEnabled(bitmap, preprocessConfig, benchmark)
        val image = InputImage.fromBitmap(processed.processedBitmap, 0)
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        return suspendCancellableCoroutine { cont ->
            fun cleanup() {
                runCatching { recognizer.close() }
                if (processed.processedBitmap !== bitmap && !processed.processedBitmap.isRecycled) {
                    processed.processedBitmap.recycle()
                }
            }

            cont.invokeOnCancellation {
                runCatching { recognizer.close() }
            }

            recognizer.process(image)
                .addOnSuccessListener { result ->
                    val lines = result.textBlocks.flatMap { block ->
                        block.lines.map { line ->
                            val mapped = line.boundingBox?.let { processed.mapping.mapRectToOriginal(it) }
                            vn.phapsumeo.onetap.parser.OcrLine(line.text, mapped)
                        }
                    }
                    if (cont.isActive) {
                        cont.resume(lines)
                    }
                    cleanup()
                }
                .addOnFailureListener { e ->
                    if (cont.isActive) {
                        cont.resume(emptyList())
                    }
                    cleanup()
                }
        }
    }

    private suspend fun preprocessIfEnabled(
        bitmap: Bitmap,
        config: OcrPreprocessConfig,
        benchmark: OcrPreprocessBenchmark,
    ): OcrPreprocessOutput {
        return withContext(Dispatchers.Default) {
            OcrPreprocessPipeline.preprocess(bitmap, config, benchmark)
        }
    }

    private fun decodeBitmapFromUri(context: Context, uri: Uri, maxSidePx: Int): Bitmap? {
        return try {
            val inputStream1 = context.contentResolver.openInputStream(uri) ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            inputStream1.use { BitmapFactory.decodeStream(it, null, bounds) }
            val outW = bounds.outWidth
            val outH = bounds.outHeight
            if (outW <= 0 || outH <= 0) return null

            val maxSide = maxSidePx.coerceAtLeast(320)
            val maxIn = maxOf(outW, outH).coerceAtLeast(1)
            val factor = maxIn.toDouble() / maxSide.toDouble()
            var inSampleSize = 1
            if (factor > 1.0) {
                while (inSampleSize * 2 <= factor) inSampleSize *= 2
            }

            val inputStream2 = context.contentResolver.openInputStream(uri) ?: return null
            val opts = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            inputStream2.use { BitmapFactory.decodeStream(it, null, opts) }
        } catch (_: Exception) {
            null
        }
    }
}

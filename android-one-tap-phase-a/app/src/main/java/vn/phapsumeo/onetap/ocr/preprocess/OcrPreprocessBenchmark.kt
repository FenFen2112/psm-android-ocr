package vn.phapsumeo.onetap.ocr.preprocess

/**
 * Benchmark hooks for OCR preprocessing (debug/training only).
 *
 * Default is a no-op implementation to avoid any production logging overhead.
 */
interface OcrPreprocessBenchmark {
    fun onStage(
        stage: String,
        durationMs: Long,
        inW: Int,
        inH: Int,
        outW: Int,
        outH: Int,
    )

    fun onTotal(durationMs: Long, inputBytes: Long, outputBytes: Long)

    object Noop : OcrPreprocessBenchmark {
        override fun onStage(stage: String, durationMs: Long, inW: Int, inH: Int, outW: Int, outH: Int) = Unit
        override fun onTotal(durationMs: Long, inputBytes: Long, outputBytes: Long) = Unit
    }
}


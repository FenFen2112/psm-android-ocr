package vn.phapsumeo.onetap.ocr.preprocess

import android.graphics.Bitmap

data class OcrPreprocessOutput(
    val processedBitmap: Bitmap,
    val mapping: OcrCoordMapping,
    val appliedStages: List<String>,
)


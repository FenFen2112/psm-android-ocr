package vn.phapsumeo.onetap.ocr.preprocess

/**
 * OCR preprocessing configuration.
 *
 * The pipeline is geometry-aware only for:
 * - scaling (affects bbox mapping)
 * - crop optimization (affects bbox mapping)
 *
 * All other steps preserve geometry.
 */
data class OcrPreprocessConfig(
    val enabled: Boolean = true,

    // Scaling (performance + stability)
    val maxSidePx: Int = 1280,

    // Dark mode handling: if the image looks too dark, invert colors before further steps.
    val darkModeHandlingEnabled: Boolean = true,
    val darkModeMeanLuminanceThreshold: Double = 0.38,
    val darkModeAnalysisMaxSide: Int = 96,

    // Contrast enhancement
    val contrastEnabled: Boolean = true,
    val contrastFactor: Double = 1.15,
    val contrastBrightnessDelta: Double = 0.0,

    // Grayscale option
    val grayscaleEnabled: Boolean = true,

    // Crop optimization (reduce margins / noise)
    val cropOptimizationEnabled: Boolean = true,
    val cropAnalysisMaxSide: Int = 160,
    val cropPaddingRatio: Double = 0.03,
    val cropMinAreaFraction: Double = 0.50,
    val cropDarkThresholdFactor: Double = 0.75,

    // Denoise (box blur)
    val denoiseEnabled: Boolean = true,
    val denoiseRadiusPx: Int = 1,

    // Adaptive sharpening (unsharp mask)
    val adaptiveSharpenEnabled: Boolean = true,
    val adaptiveSharpenAmount: Double = 0.70,
    val adaptiveSharpenRadiusPx: Int = 1,

    // Performance safe execution
    val maxProcessingMs: Long = 250,
)

object OcrPreprocessConfigs {
    val Default: OcrPreprocessConfig = OcrPreprocessConfig()

    /**
     * Ảnh cắt vùng nhỏ (title/giá): grayscale + contrast, **tắt** cropOptimization để bbox mapping khớp pixel gốc.
     */
    val RegionCrop: OcrPreprocessConfig = OcrPreprocessConfig(
        enabled = true,
        grayscaleEnabled = true,
        contrastEnabled = true,
        contrastFactor = 1.28,
        contrastBrightnessDelta = 0.0,
        cropOptimizationEnabled = false,
        denoiseEnabled = true,
        adaptiveSharpenEnabled = true,
    )
}


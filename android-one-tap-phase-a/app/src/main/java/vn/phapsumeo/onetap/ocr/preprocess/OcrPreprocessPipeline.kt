package vn.phapsumeo.onetap.ocr.preprocess

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object OcrPreprocessPipeline {

    fun preprocess(
        inputBitmap: Bitmap,
        config: OcrPreprocessConfig = OcrPreprocessConfigs.Default,
        benchmark: OcrPreprocessBenchmark = OcrPreprocessBenchmark.Noop,
    ): OcrPreprocessOutput {
        if (!config.enabled) {
            val mapping = OcrCoordMapping(
                originalWidthPx = inputBitmap.width,
                originalHeightPx = inputBitmap.height,
                scaleX = 1.0,
                scaleY = 1.0,
                cropLeftPxInScaled = 0,
                cropTopPxInScaled = 0,
                cropWidthPx = inputBitmap.width,
                cropHeightPx = inputBitmap.height,
            )
            return OcrPreprocessOutput(processedBitmap = inputBitmap, mapping = mapping, appliedStages = emptyList())
        }

        val startAll = System.nanoTime()
        val inputW = inputBitmap.width
        val inputH = inputBitmap.height
        var elapsedBudgetMs = 0L

        fun timeMsSince(startNs: Long): Long = (System.nanoTime() - startNs) / 1_000_000L

        fun budgetCheck(stageStartNs: Long): Boolean {
            elapsedBudgetMs = timeMsSince(stageStartNs)
            return elapsedBudgetMs <= config.maxProcessingMs
        }

        // 1) Scaling
        var current = inputBitmap
        var ownsCurrent = false
        val stageStartScale = System.nanoTime()
        val maxSide = config.maxSidePx.coerceAtLeast(320)
        val maxIn = max(inputW, inputH).coerceAtLeast(1)
        val scale = min(1f, maxSide.toFloat() / maxIn.toFloat())
        val scaledW = (inputW * scale).roundToInt().coerceAtLeast(1)
        val scaledH = (inputH * scale).roundToInt().coerceAtLeast(1)

        val scaleX = scaledW.toDouble() / inputW.toDouble()
        val scaleY = scaledH.toDouble() / inputH.toDouble()

        val stageScaledNeeded = scaledW != inputW || scaledH != inputH
        if (stageScaledNeeded) {
            current = Bitmap.createScaledBitmap(inputBitmap, scaledW, scaledH, true)
            ownsCurrent = true
        }
        benchmark.onStage("scale", timeMsSince(stageStartScale), inputW, inputH, current.width, current.height)

        if (!budgetCheck(stageStartScale)) {
            val mapping = OcrCoordMapping(
                originalWidthPx = inputW,
                originalHeightPx = inputH,
                scaleX = scaleX,
                scaleY = scaleY,
                cropLeftPxInScaled = 0,
                cropTopPxInScaled = 0,
                cropWidthPx = current.width,
                cropHeightPx = current.height,
            )
            return OcrPreprocessOutput(current, mapping, listOf("scale_budget_break"))
        }

        // 2) Dark mode detection (analysis on a tiny thumbnail)
        val invertForDark = if (config.darkModeHandlingEnabled) {
            val analysisMax = config.darkModeAnalysisMaxSide.coerceIn(32, 192)
            val tw = min(analysisMax, current.width)
            val th = min(analysisMax, current.height)
            val thumb = Bitmap.createScaledBitmap(current, tw, th, true)
            val lum = computeMeanLuminance01(thumb)
            if (!thumb.isRecycled) thumb.recycle()
            lum < config.darkModeMeanLuminanceThreshold
        } else false

        // 3) Pixel processing: grayscale + invert + contrast
        val stageStartPixel = System.nanoTime()
        val processedBitmap = processPixels(current, config, invertForDark)
        if (ownsCurrent && processedBitmap !== current) {
            runCatching { current.recycle() }
        }
        current = processedBitmap
        ownsCurrent = current !== inputBitmap
        benchmark.onStage(
            "pixel_process",
            timeMsSince(stageStartPixel),
            current.width,
            current.height,
            current.width,
            current.height,
        )

        if (!budgetCheck(stageStartPixel)) {
            val mapping = OcrCoordMapping(
                originalWidthPx = inputW,
                originalHeightPx = inputH,
                scaleX = scaleX,
                scaleY = scaleY,
                cropLeftPxInScaled = 0,
                cropTopPxInScaled = 0,
                cropWidthPx = current.width,
                cropHeightPx = current.height,
            )
            return OcrPreprocessOutput(current, mapping, listOf("pixel_budget_break"))
        }

        // 4) Crop optimization (geometry change)
        var cropRectInScaled = Rect(0, 0, current.width, current.height)
        val applied = ArrayList<String>()
        if (config.cropOptimizationEnabled) {
            val stageStartCrop = System.nanoTime()
            val crop = findContentCropRect(current, config)
            if (crop != null && crop.width() >= 2 && crop.height() >= 2) {
                cropRectInScaled = crop
                if (crop != Rect(0, 0, current.width, current.height)) {
                    val cropped = Bitmap.createBitmap(current, crop.left, crop.top, crop.width(), crop.height())
                    if (ownsCurrent && cropped !== current) runCatching { current.recycle() }
                    current = cropped
                    ownsCurrent = true
                    applied += "crop"
                }
            }
            benchmark.onStage(
                "crop",
                timeMsSince(stageStartCrop),
                current.width,
                current.height,
                current.width,
                current.height,
            )
            if (!budgetCheck(stageStartCrop)) {
                // keep current (already cropped or not), but do not run blur/sharpen
                val mapping = OcrCoordMapping(
                    originalWidthPx = inputW,
                    originalHeightPx = inputH,
                    scaleX = scaleX,
                    scaleY = scaleY,
                    cropLeftPxInScaled = cropRectInScaled.left,
                    cropTopPxInScaled = cropRectInScaled.top,
                    cropWidthPx = cropRectInScaled.width(),
                    cropHeightPx = cropRectInScaled.height(),
                )
                val appliedStages = listOf("scale", "pixel_budget_break", "crop_budget_break")
                return OcrPreprocessOutput(current, mapping, appliedStages)
            }
        }

        // 5) Denoise + adaptive sharpening (blur-based; geometry-preserving)
        val stageStartBlur = System.nanoTime()
        val denoiseEnabled = config.denoiseEnabled && config.denoiseRadiusPx > 0
        val sharpenEnabled = config.adaptiveSharpenEnabled && config.adaptiveSharpenAmount > 0.0
        val radius = when {
            denoiseEnabled -> config.denoiseRadiusPx
            sharpenEnabled -> config.adaptiveSharpenRadiusPx.coerceAtLeast(1)
            else -> 0
        }

        if (denoiseEnabled || sharpenEnabled) {
            val origPixels = getPixelsARGB(current)
            val w = current.width
            val h = current.height
            val blurPixels = if (radius > 0) boxBlurARGB(origPixels, w, h, radius) else origPixels

            val finalPixels = if (sharpenEnabled) {
                unsharpMaskARGB(origPixels, blurPixels, config.adaptiveSharpenAmount)
            } else {
                blurPixels
            }

            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            out.setPixels(finalPixels, 0, w, 0, 0, w, h)
            if (ownsCurrent && out !== current) runCatching { current.recycle() }
            current = out
            ownsCurrent = true
            if (denoiseEnabled) applied += "denoise"
            if (sharpenEnabled) applied += "sharpen"
            benchmark.onStage("blur_sharpen", timeMsSince(stageStartBlur), w, h, w, h)
        } else {
            benchmark.onStage("blur_skip", 0, current.width, current.height, current.width, current.height)
        }

        val mapping = OcrCoordMapping(
            originalWidthPx = inputW,
            originalHeightPx = inputH,
            scaleX = scaleX,
            scaleY = scaleY,
            cropLeftPxInScaled = cropRectInScaled.left,
            cropTopPxInScaled = cropRectInScaled.top,
            cropWidthPx = cropRectInScaled.width(),
            cropHeightPx = cropRectInScaled.height(),
        )

        val totalMs = timeMsSince(startAll)
        val inBytes = inputW.toLong() * inputH.toLong() * 4L
        val outBytes = current.width.toLong() * current.height.toLong() * 4L
        benchmark.onTotal(totalMs, inBytes, outBytes)
        return OcrPreprocessOutput(processedBitmap = current, mapping = mapping, appliedStages = applied)
    }

    private fun processPixels(
        src: Bitmap,
        config: OcrPreprocessConfig,
        invertForDark: Boolean,
    ): Bitmap {
        if (!config.grayscaleEnabled && !config.contrastEnabled && !invertForDark) {
            return src
        }

        val w = src.width
        val h = src.height
        val pixels = getPixelsARGB(src)
        val out = IntArray(pixels.size)

        val contrast = if (config.contrastEnabled) config.contrastFactor else 1.0
        val brightness = config.contrastBrightnessDelta
        // new = contrast * old + (1-contrast)*128 + brightness
        val translate = (1.0 - contrast) * 128.0 + brightness

        for (i in pixels.indices) {
            val p = pixels[i]
            val a = (p ushr 24) and 0xFF
            var r = (p ushr 16) and 0xFF
            var g = (p ushr 8) and 0xFF
            var b = p and 0xFF

            if (invertForDark) {
                r = 255 - r
                g = 255 - g
                b = 255 - b
            }

            if (config.grayscaleEnabled) {
                val luma = (0.299 * r + 0.587 * g + 0.114 * b)
                val l = if (config.contrastEnabled) clamp01or255(luma * contrast + translate) else clamp01or255(luma)
                val v = l.toInt().coerceIn(0, 255)
                out[i] = (a shl 24) or (v shl 16) or (v shl 8) or v
            } else {
                val nr = if (config.contrastEnabled) clamp01or255(r * contrast + translate) else r.toDouble()
                val ng = if (config.contrastEnabled) clamp01or255(g * contrast + translate) else g.toDouble()
                val nb = if (config.contrastEnabled) clamp01or255(b * contrast + translate) else b.toDouble()
                val rr = nr.roundToInt().coerceIn(0, 255)
                val gg = ng.roundToInt().coerceIn(0, 255)
                val bb = nb.roundToInt().coerceIn(0, 255)
                out[i] = (a shl 24) or (rr shl 16) or (gg shl 8) or bb
            }
        }

        val outBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        outBmp.setPixels(out, 0, w, 0, 0, w, h)
        return outBmp
    }

    private fun findContentCropRect(bitmap: Bitmap, config: OcrPreprocessConfig): Rect? {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 10 || h < 10) return null

        val analysisMax = config.cropAnalysisMaxSide.coerceIn(64, 320)
        val tw = min(analysisMax, w)
        val th = min(analysisMax, h)
        val thumb = Bitmap.createScaledBitmap(bitmap, tw, th, true)

        val pixels = getPixelsARGB(thumb)
        val lum = DoubleArray(pixels.size)
        var sum = 0.0
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p ushr 16) and 0xFF
            val g = (p ushr 8) and 0xFF
            val b = p and 0xFF
            val l = 0.299 * r + 0.587 * g + 0.114 * b
            lum[i] = l
            sum += l
        }
        val mean = sum / lum.size.toDouble()
        val darkThreshold = mean * config.cropDarkThresholdFactor

        fun rowDarkFraction(row: Int): Double {
            var count = 0
            val start = row * tw
            val end = start + tw
            for (i in start until end) {
                if (lum[i] < darkThreshold) count++
            }
            return count.toDouble() / tw.toDouble()
        }

        fun colDarkFraction(col: Int): Double {
            var count = 0
            for (row in 0 until th) {
                val idx = row * tw + col
                if (lum[idx] < darkThreshold) count++
            }
            return count.toDouble() / th.toDouble()
        }

        // Require a small proportion of dark pixels to consider the row/col "content"
        val rowMin = 0.01
        val colMin = 0.01

        var minRow = -1
        var maxRow = -1
        for (r in 0 until th) {
            val f = rowDarkFraction(r)
            if (f >= rowMin) {
                if (minRow < 0) minRow = r
                maxRow = r
            }
        }

        var minCol = -1
        var maxCol = -1
        for (c in 0 until tw) {
            val f = colDarkFraction(c)
            if (f >= colMin) {
                if (minCol < 0) minCol = c
                maxCol = c
            }
        }

        thumb.recycle()
        if (minRow < 0 || minCol < 0 || maxRow < 0 || maxCol < 0) return null

        val cropTW = maxCol - minCol + 1
        val cropTH = maxRow - minRow + 1

        val cropAreaFrac = cropTW.toDouble() * cropTH.toDouble() / tw.toDouble() / th.toDouble()
        if (cropAreaFrac < 0.05) return null

        val scaledCropLeft = (minCol.toDouble() * w.toDouble() / tw.toDouble()).roundToInt()
        val scaledCropTop = (minRow.toDouble() * h.toDouble() / th.toDouble()).roundToInt()
        val scaledCropRight = (maxCol.toDouble() * w.toDouble() / tw.toDouble()).roundToInt()
        val scaledCropBottom = (maxRow.toDouble() * h.toDouble() / th.toDouble()).roundToInt()

        var left = scaledCropLeft
        var top = scaledCropTop
        var right = scaledCropRight
        var bottom = scaledCropBottom

        // Convert right/bottom as inclusive into Bitmap.createBitmap expects width/height.
        right = right.coerceIn(0, w - 1)
        bottom = bottom.coerceIn(0, h - 1)

        if (right <= left + 2 || bottom <= top + 2) return null

        val padX = ((right - left) * config.cropPaddingRatio).roundToInt().coerceAtLeast(2)
        val padY = ((bottom - top) * config.cropPaddingRatio).roundToInt().coerceAtLeast(2)

        left = (left - padX).coerceIn(0, w - 1)
        top = (top - padY).coerceIn(0, h - 1)
        right = (right + padX).coerceIn(left + 2, w)
        bottom = (bottom + padY).coerceIn(top + 2, h)

        val cropW = (right - left).coerceAtLeast(1)
        val cropH = (bottom - top).coerceAtLeast(1)
        val outAreaFrac = cropW.toDouble() * cropH.toDouble() / (w.toDouble() * h.toDouble())
        if (outAreaFrac < config.cropMinAreaFraction) {
            // too aggressive; fallback to full image
            return Rect(0, 0, w, h)
        }

        return Rect(left, top, right, bottom)
    }

    private fun computeMeanLuminance01(bitmap: Bitmap): Double {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = getPixelsARGB(bitmap)
        var sum = 0.0
        for (p in pixels) {
            val r = (p ushr 16) and 0xFF
            val g = (p ushr 8) and 0xFF
            val b = p and 0xFF
            sum += 0.299 * r + 0.587 * g + 0.114 * b
        }
        val mean = sum / pixels.size.toDouble()
        return (mean / 255.0).coerceIn(0.0, 1.0)
    }

    private fun getPixelsARGB(bitmap: Bitmap): IntArray {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        return pixels
    }

    private fun clamp01or255(v: Double): Double = v.coerceIn(0.0, 255.0)

    private fun boxBlurARGB(src: IntArray, w: Int, h: Int, radius: Int): IntArray {
        if (radius <= 0) return src
        val window = radius * 2 + 1
        val tmp = IntArray(w * h)
        val out = IntArray(w * h)

        // Horizontal pass
        for (y in 0 until h) {
            var sumR = 0
            var sumG = 0
            var sumB = 0

            for (i in -radius..radius) {
                val x = i.coerceIn(0, w - 1)
                val p = src[y * w + x]
                sumR += (p ushr 16) and 0xFF
                sumG += (p ushr 8) and 0xFF
                sumB += p and 0xFF
            }

            for (x in 0 until w) {
                val idx = y * w + x
                tmp[idx] = (0xFF shl 24) or ((sumR / window) shl 16) or ((sumG / window) shl 8) or (sumB / window)

                val xRemove = (x - radius).coerceIn(0, w - 1)
                val xAdd = (x + radius + 1).coerceIn(0, w - 1)
                val pRemove = src[y * w + xRemove]
                val pAdd = src[y * w + xAdd]
                sumR += ((pAdd ushr 16) and 0xFF) - ((pRemove ushr 16) and 0xFF)
                sumG += ((pAdd ushr 8) and 0xFF) - ((pRemove ushr 8) and 0xFF)
                sumB += (pAdd and 0xFF) - (pRemove and 0xFF)
            }
        }

        // Vertical pass
        for (x in 0 until w) {
            var sumR = 0
            var sumG = 0
            var sumB = 0

            for (i in -radius..radius) {
                val y = i.coerceIn(0, h - 1)
                val p = tmp[y * w + x]
                sumR += (p ushr 16) and 0xFF
                sumG += (p ushr 8) and 0xFF
                sumB += p and 0xFF
            }

            for (y in 0 until h) {
                val idx = y * w + x
                out[idx] = (0xFF shl 24) or ((sumR / window) shl 16) or ((sumG / window) shl 8) or (sumB / window)

                val yRemove = (y - radius).coerceIn(0, h - 1)
                val yAdd = (y + radius + 1).coerceIn(0, h - 1)
                val pRemove = tmp[yRemove * w + x]
                val pAdd = tmp[yAdd * w + x]
                sumR += ((pAdd ushr 16) and 0xFF) - ((pRemove ushr 16) and 0xFF)
                sumG += ((pAdd ushr 8) and 0xFF) - ((pRemove ushr 8) and 0xFF)
                sumB += (pAdd and 0xFF) - (pRemove and 0xFF)
            }
        }

        return out
    }

    private fun unsharpMaskARGB(orig: IntArray, blur: IntArray, amount: Double): IntArray {
        val n = orig.size
        val out = IntArray(n)
        val aMul = amount.coerceIn(0.0, 2.0)

        for (i in 0 until n) {
            val pO = orig[i]
            val pB = blur[i]
            val a = (pO ushr 24) and 0xFF

            val oR = (pO ushr 16) and 0xFF
            val oG = (pO ushr 8) and 0xFF
            val oB = pO and 0xFF

            val bR = (pB ushr 16) and 0xFF
            val bG = (pB ushr 8) and 0xFF
            val bB = pB and 0xFF

            val nR = clamp01or255(oR + aMul * (oR - bR))
            val nG = clamp01or255(oG + aMul * (oG - bG))
            val nB = clamp01or255(oB + aMul * (oB - bB))

            out[i] = (a shl 24) or (nR.roundToInt() shl 16) or (nG.roundToInt() shl 8) or nB.roundToInt()
        }

        return out
    }
}


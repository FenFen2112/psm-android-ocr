package vn.phapsumeo.onetap.extraction.grouping

import android.graphics.Rect
import vn.phapsumeo.onetap.extraction.core.BlockSpatialRelation
import vn.phapsumeo.onetap.extraction.core.LineSpatialRelation
import vn.phapsumeo.onetap.extraction.core.OcrBlock
import vn.phapsumeo.onetap.extraction.core.OcrLine
import vn.phapsumeo.onetap.extraction.core.ScreenMeta
import vn.phapsumeo.onetap.extraction.core.SpatialRelation

/**
 * Default implementation: union–find clustering on line bounding boxes, then reading-order sort.
 *
 * Heuristics (language-agnostic):
 * - **Same-row / fragment:** high vertical overlap + bounded horizontal gap (mixed Latin/CJK/VN on one UI row).
 * - **Column stack:** horizontal overlap + small vertical gap (stacked labels).
 * - **Noise glue:** centre distance cap relative to median line height (tolerate broken boxes).
 */
object DefaultOcrBlockGrouper : OcrBlockGrouper {

    override fun group(lines: List<OcrLine>, screen: ScreenMeta, config: OcrBlockGroupingConfig): GroupedOcrOutput {
        val indexed = lines.mapIndexed { idx, line -> idx to line }
            .filter { it.second.text.isNotBlank() }
        val originalIndices = indexed.map { it.first }.toIntArray()
        val filtered = indexed.map { it.second }

        val rects = filtered.map { it.boundingBox }
        val minSide = minOf(screen.widthPx, screen.heightPx).coerceAtLeast(1)
        val refH = referenceTextHeightPx(filtered, config.fallbackLineHeightPx)

        val n = filtered.size
        val uf = UnionFind(n)

        for (i in 0 until n) {
            val ri = rects[i] ?: continue
            for (j in i + 1 until n) {
                val rj = rects[j] ?: continue
                if (shouldMerge(ri, rj, refH, minSide, config)) {
                    uf.union(i, j)
                }
            }
        }

        val clusters = (0 until n).groupBy { uf.find(it) }.values.toList()

        val rawBlocks = clusters.map { clusterIdxs ->
            val clusterLines = clusterIdxs.map { filtered[it] }
            val clusterRects = clusterIdxs.mapNotNull { rects[it] }
            val union = GeometryUtils.unionAll(clusterRects)
            val sortedLines = clusterLines.sortedWith(
                compareBy<OcrLine> { it.boundingBox?.midY() ?: Int.MAX_VALUE }
                    .thenBy { it.boundingBox?.midX() ?: Int.MAX_VALUE },
            )
            OcrBlock(lines = sortedLines, boundingBox = union, readingOrder = 0)
        }

        val blocksSorted = rawBlocks.sortedWith(
            compareBy<OcrBlock> { it.boundingBox?.top ?: Int.MAX_VALUE }
                .thenBy { it.boundingBox?.left ?: Int.MAX_VALUE },
        ).mapIndexed { order, b -> b.copy(readingOrder = order) }

        val lineRelations = buildLineRelations(blocksSorted, filtered, refH)
        val blockRelations = buildBlockRelations(blocksSorted, refH, config)

        return GroupedOcrOutput(
            blocks = blocksSorted,
            lineSpatialRelations = lineRelations,
            blockSpatialRelations = blockRelations,
            screen = screen,
            originalLineIndices = originalIndices,
        )
    }

    private fun Rect.midX(): Int = (left + right) / 2
    private fun Rect.midY(): Int = (top + bottom) / 2

    private fun referenceTextHeightPx(lines: List<OcrLine>, fallback: Float): Float {
        val heights = lines.mapNotNull { it.boundingBox?.height()?.takeIf { h -> h > 0 } }
        if (heights.isEmpty()) return fallback
        val sorted = heights.sorted()
        return sorted[sorted.size / 2].toFloat().coerceAtLeast(8f)
    }

    private fun shouldMerge(a: Rect, b: Rect, refH: Float, minSide: Int, cfg: OcrBlockGroupingConfig): Boolean {
        val vOv = GeometryUtils.verticalOverlapRatio(a, b)
        val hOv = GeometryUtils.horizontalOverlapRatio(a, b)
        val hg = GeometryUtils.horizontalGap(a, b)
        val vg = GeometryUtils.verticalGap(a, b)
        val screenGapCap = minSide * cfg.sameRowMaxGapScreenFrac

        val sameRow = vOv >= cfg.sameRowVerticalOverlapMin &&
            hg <= kotlin.math.min(refH * cfg.sameRowMaxHorizontalGapFactor, screenGapCap + refH * 0.5f)
        val columnStack = hOv >= cfg.columnHorizontalOverlapMin &&
            vg <= refH * cfg.verticalStackMaxGapFactor
        val noiseGlue = GeometryUtils.centerDistance(a, b) <= refH * cfg.maxCenterDistanceFactor
        return sameRow || columnStack || noiseGlue
    }

    private fun buildLineRelations(
        blocks: List<OcrBlock>,
        filteredLines: List<OcrLine>,
        refH: Float,
    ): List<LineSpatialRelation> {
        val out = ArrayList<LineSpatialRelation>()
        val lineToFilteredIndex = HashMap<OcrLine, Int>(filteredLines.size)
        filteredLines.forEachIndexed { i, line -> lineToFilteredIndex[line] = i }

        for (block in blocks) {
            val sorted = block.lines.sortedWith(
                compareBy<OcrLine> { it.boundingBox?.midY() ?: Int.MAX_VALUE }
                    .thenBy { it.boundingBox?.midX() ?: Int.MAX_VALUE },
            )
            for (k in 0 until sorted.lastIndex) {
                val a = sorted[k]
                val b = sorted[k + 1]
                val ia = lineToFilteredIndex[a] ?: continue
                val ib = lineToFilteredIndex[b] ?: continue
                val ra = a.boundingBox
                val rb = b.boundingBox
                if (ra == null || rb == null) continue
                val hg = GeometryUtils.horizontalGap(ra, rb)
                val vg = GeometryUtils.verticalGap(ra, rb)
                val hOv = GeometryUtils.horizontalOverlapRatio(ra, rb)
                val vOv = GeometryUtils.verticalOverlapRatio(ra, rb)
                val rel = classifyRelation(ra, rb, hg, vg, hOv, vOv, refH)
                out += LineSpatialRelation(ia, ib, rel, hg, vg, hOv, vOv)
            }
        }
        return out
    }

    private fun buildBlockRelations(
        blocks: List<OcrBlock>,
        refH: Float,
        config: OcrBlockGroupingConfig,
    ): List<BlockSpatialRelation> {
        val out = ArrayList<BlockSpatialRelation>()
        val bounds = blocks.map { it.boundingBox }
        for (i in blocks.indices) {
            val bi = bounds[i] ?: continue
            for (j in i + 1 until blocks.size) {
                val bj = bounds[j] ?: continue
                val hg = GeometryUtils.horizontalGap(bi, bj)
                val vg = GeometryUtils.verticalGap(bi, bj)
                val hOv = GeometryUtils.horizontalOverlapRatio(bi, bj)
                val vOv = GeometryUtils.verticalOverlapRatio(bi, bj)
                val dist = GeometryUtils.centerDistance(bi, bj)
                val consecutive = j == i + 1
                val neighbour = dist <= refH * config.neighbourCenterDistanceFactor
                if (!consecutive && !neighbour) continue
                val rel = classifyRelation(bi, bj, hg, vg, hOv, vOv, refH)
                out += BlockSpatialRelation(i, j, rel, hg, vg, hOv, vOv)
            }
        }
        return out
    }

    private fun classifyRelation(
        a: Rect,
        b: Rect,
        @Suppress("UNUSED_PARAMETER") horizontalGapPx: Float,
        @Suppress("UNUSED_PARAMETER") verticalGapPx: Float,
        horizontalOverlapRatio: Float,
        verticalOverlapRatio: Float,
        refH: Float,
    ): SpatialRelation {
        if (GeometryUtils.intersectionArea(a, b) > 0) return SpatialRelation.OVERLAPS
        val sameLineFragment = verticalOverlapRatio >= 0.35f &&
            horizontalGapPx <= refH * 1.2f &&
            kotlin.math.abs(a.midY() - b.midY()) <= refH * 0.55f
        if (sameLineFragment) return SpatialRelation.SAME_LINE_FRAGMENT

        val cyA = a.midY().toFloat()
        val cyB = b.midY().toFloat()
        val cxA = a.midX().toFloat()
        val cxB = b.midX().toFloat()

        return when {
            cyA < cyB - 2f && horizontalOverlapRatio >= 0.1f -> SpatialRelation.ABOVE
            cyA > cyB + 2f && horizontalOverlapRatio >= 0.1f -> SpatialRelation.BELOW
            cxA + 2f < cxB && verticalOverlapRatio >= 0.1f -> SpatialRelation.LEFT_OF
            cxA - 2f > cxB && verticalOverlapRatio >= 0.1f -> SpatialRelation.RIGHT_OF
            else -> SpatialRelation.UNKNOWN
        }
    }

    private class UnionFind(n: Int) {
        private val parent = IntArray(n) { it }

        fun find(x: Int): Int {
            if (parent[x] != x) parent[x] = find(parent[x])
            return parent[x]
        }

        fun union(a: Int, b: Int) {
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) parent[ra] = rb
        }
    }
}

package vn.phapsumeo.onetap.extraction.grouping

import vn.phapsumeo.onetap.extraction.core.BlockSpatialRelation
import vn.phapsumeo.onetap.extraction.core.LineSpatialRelation
import vn.phapsumeo.onetap.extraction.core.OcrBlock
import vn.phapsumeo.onetap.extraction.core.OcrDocument
import vn.phapsumeo.onetap.extraction.core.ScreenMeta

/**
 * Result of spatial grouping: semantic blocks, pairwise spatial signals, and index bookkeeping.
 *
 * @param originalLineIndices For each **filtered** line index (same order as internal clustering list),
 *   the index in the original [List] passed to the grouper (noise lines removed).
 */
data class GroupedOcrOutput(
    val blocks: List<OcrBlock>,
    val lineSpatialRelations: List<LineSpatialRelation>,
    val blockSpatialRelations: List<BlockSpatialRelation>,
    val screen: ScreenMeta,
    val originalLineIndices: IntArray,
) {
    fun toDocument(imageUri: String = ""): OcrDocument =
        OcrDocument(screen = screen, blocks = blocks, imageUri = imageUri)
}

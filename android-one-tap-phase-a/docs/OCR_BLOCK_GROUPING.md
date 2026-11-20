# OCR block grouping (generic)

## Purpose

Turn flat `List<OcrLine>` into **semantic spatial blocks** plus **pairwise spatial metrics** for downstream entity extractors — **no Xianyu strings or regex**.

## Types (`extraction.core`)

| Type | Role |
|------|------|
| **`OcrLine`** | Text + optional `Rect` (unchanged canonical type). |
| **`OcrBlock`** | `lines`, merged `boundingBox`, `readingOrder` (global top-left sort). |
| **`SpatialRelation`** | `UNKNOWN`, `OVERLAPS`, `LEFT_OF`, `RIGHT_OF`, `ABOVE`, `BELOW`, `SAME_LINE_FRAGMENT`. |
| **`LineSpatialRelation`** | Two **filtered** line indices + relation + gap/overlap metrics. |
| **`BlockSpatialRelation`** | Two block indices + relation + metrics. |

## Algorithms (`extraction.grouping`)

- **`DefaultOcrBlockGrouper`** — Union–find on lines that have boxes:
  - **Same row / fragment:** vertical overlap ≥ `sameRowVerticalOverlapMin` and horizontal gap capped by `referenceHeight` and screen fraction.
  - **Column stack:** horizontal overlap ≥ `columnHorizontalOverlapMin` and vertical gap ≤ factor × reference height.
  - **Noise glue:** centre distance ≤ `maxCenterDistanceFactor` × median line height.
- **Reference height** — median of line box heights; fallback `fallbackLineHeightPx` when no boxes.
- **Noise** — blank lines dropped; lines **without** bbox are never spatially merged (each stays isolated unless alone in cluster).
- **Reading order** — blocks: sort by `top`, then `left`; lines inside block: `midY`, then `midX` (API 26-safe, no `Rect.centerY()`).
- **Relations** — consecutive lines within each block; block pairs that are **reading-consecutive** (`j == i+1`) or **spatial neighbours** (centre distance ≤ `neighbourCenterDistanceFactor` × ref height).

## Config

`OcrBlockGroupingConfig` — all thresholds are floats / geometry; tune per device class without code changes.

## Integration

- **`ExtractionFacades.documentFromLinesGrouped(...)`** → `GroupedOcrOutput` (blocks + relations + `originalLineIndices`).
- **`ExtractionFacades.documentFromLinesGroupedAsDocument(...)`** → `OcrDocument` via `GroupedOcrOutput.toDocument(imageUri)`.
- **`OcrDocument.allLines()`** — blocks sorted by `readingOrder`; **line order inside each block is preserved** (set by the grouper or the original producer such as ML Kit).

Callers that still need a **single flat string** for a legacy regex parser should use `document.allLines().joinToString("\n") { it.text }` — order may differ from raw ML Kit order after grouping.

## Files

- `extraction/core/SpatialRelation.kt`
- `extraction/grouping/OcrBlockGroupingConfig.kt`
- `extraction/grouping/GeometryUtils.kt`
- `extraction/grouping/OcrBlockGrouper.kt`
- `extraction/grouping/DefaultOcrBlockGrouper.kt`
- `extraction/grouping/GroupedOcrOutput.kt`
- `ExtractionFacades` (new entry points)

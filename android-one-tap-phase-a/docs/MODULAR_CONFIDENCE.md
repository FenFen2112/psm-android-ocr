# Modular confidence engine

## Overview

Weighted, **explainable** multi-axis scoring for OCR extraction. Output type: **`ExtractionConfidence`** (`extraction.confidence.model`).

## Dimensions

| Axis | Contributor | Notes |
|------|-------------|--------|
| `OCR_QUALITY` | `OcrQualityContributor` | Bbox coverage vs lines; single-char spam penalty |
| `FIELD_CONSISTENCY` | `FieldConsistencyContributor` | `tong` vs `hang`+`ship` when all present |
| `PRICE_DETECTION` | `PriceDetectionContributor` | Positive `tongCn` / partial hang |
| `TRACKING_DETECTION` | `TrackingDetectionContributor` | Length / digit density / non-alnum penalty |
| `TITLE_DETECTION` | `TitleDetectionContributor` | Length; digit-heavy title penalty |
| `LAYOUT_RELIABILITY` | `LayoutReliabilityContributor` | Uses `GroupedOcrOutput` when provided, else document blocks + bbox ratio |
| `MARKETPLACE_PATTERN` | `MarketplacePatternPlugin` | **Neutral** by default; **Xianyu** plugin adds weak CJK/commerce signals |

## API

- **`ModularConfidenceEngine.evaluate(ConfidenceEvaluationContext)`** → `ExtractionConfidence`
- **`ConfidenceWeights`** / `legacyBalanced()` — relative weights (normalized to sum 1 before scoring)
- **`DetailConfidenceEngine`** extends **`ConfidenceEngine`** with `evaluateDetailed()`; **`OrderDraftConfidenceEngine`** implements it
- **`ExtractionResult.confidenceDetail`** populated when profile uses `DetailConfidenceEngine` (e.g. Xianyu profile)

## Explainability & warnings

- Each axis → `DimensionContribution` (`score01`, `normalizedWeight`, `weightedContribution`, `reasons`)
- **`ExtractionConfidence.toDebugString()`** — human-readable dump for Logcat / file
- **`warnings`**: `LOW_OVERALL`, `WEAK_TRACKING`, `WEAK_PRICE`, `POOR_OCR_LAYOUT`, `FIELD_MISMATCH`, `MISSING_TITLE` (severities INFO / WARN / CRITICAL)
- **`isLowConfidence`**: overall &lt; 0.45 or ≥1 CRITICAL or ≥3 WARN (tunable later)

## Legacy bridge

- **`toLegacyPair()`** / **`toLegacyFieldMap()`** — compatible with existing pipeline `fieldConfidence` map keys

## Optional: pass grouping into confidence

```kotlin
ConfidenceEvaluationContext(draft, document, grouped = groupedOcrOutputOptional)
```

Improves **LAYOUT_RELIABILITY** when spatial grouping has run.

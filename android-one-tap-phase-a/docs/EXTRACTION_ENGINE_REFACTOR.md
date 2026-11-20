# Generic extraction engine — design & migration

This document matches the implemented **migration-safe** skeleton under `vn.phapsumeo.onetap.extraction.*`.  
`XianyuParser` remains the source of marketplace heuristics for now; it is invoked only through `LegacyXianyuEntityDetector`.

---

## 1. Package structure (implemented)

```
app/src/main/java/vn/phapsumeo/onetap/
├── extraction/
│   ├── ExtractionFacades.kt          # Stable entry: documentFromLines, extractXianyu
│   ├── api/
│   │   ├── LayoutAnalyzer.kt
│   │   ├── EntityDetector.kt
│   │   ├── ConfidenceEngine.kt
│   │   ├── MarketplaceProfile.kt
│   │   └── ExtractionPipeline.kt
│   ├── core/
│   │   ├── OcrLine.kt                # Canonical line + Rect?
│   │   ├── OcrBlock.kt
│   │   ├── ScreenMeta.kt
│   │   ├── OcrDocument.kt
│   │   ├── LayoutHints.kt
│   │   └── ExtractionResult.kt
│   ├── layout/
│   │   └── PassthroughLayoutAnalyzer.kt
│   ├── confidence/
│   │   └── OrderDraftConfidenceEngine.kt
│   ├── pipeline/
│   │   └── DefaultExtractionPipeline.kt
│   └── profile/xianyu/
│       ├── XianyuMarketplaceProfile.kt
│       └── LegacyXianyuEntityDetector.kt
├── parser/
│   ├── OcrLine.kt                    # typealias → extraction.core.OcrLine (backward compat)
│   └── XianyuParser.kt               # Unchanged logic; uses parser.OcrLine typealias
└── ocr/OcrFacade.kt                  # Still returns parser.OcrLine (same runtime type)
```

---

## 2. Interfaces (responsibilities)

| Type | Role |
|------|------|
| **`OcrLine`** | One text line + optional bbox in screenshot pixel space. |
| **`OcrBlock`** | Lines grouped (future: ML Kit block bounds). |
| **`ScreenMeta`** | `widthPx`, `heightPx`, optional `densityDpi` for normalization. |
| **`OcrDocument`** | `screen` + `blocks` + `imageUri` — pipeline input. |
| **`LayoutHints`** | Output of layout pass (normalized regions, reading order); phase-1 empty. |
| **`LayoutAnalyzer`** | `analyze(OcrDocument) → LayoutHints` — generic geometry/reading order, no SKU semantics. |
| **`EntityDetector`** | `detect(OcrDocument, LayoutHints) → OrderDraft` — marketplace-specific. |
| **`ConfidenceEngine`** | `score(OrderDraft, OcrDocument) → (overall, per-field map)` — extraction quality. |
| **`MarketplaceProfile`** | Bundles `LayoutAnalyzer` + `EntityDetector` + `ConfidenceEngine` + ids. |
| **`ExtractionPipeline`** | `run(OcrDocument, MarketplaceProfile) → ExtractionResult`. |
| **`ExtractionResult`** | `draft` + `overallConfidence` + `fieldConfidence` + `profileId`. |

---

## 3. Module responsibilities (per folder)

| Module | Owns | Must not own |
|--------|------|----------------|
| **`extraction.core`** | DTOs for OCR + pipeline I/O | GAS, UI, ML Kit |
| **`extraction.api`** | Contracts only | Implementations |
| **`extraction.layout`** | Generic layout algorithms | Xianyu strings |
| **`extraction.confidence`** | Reusable scoring | Parser regex |
| **`extraction.pipeline`** | Orchestration order | Business constants of Xianyu |
| **`extraction.profile.*`** | Wiring per marketplace | Other marketplaces’ code |
| **`parser`** (legacy) | `XianyuParser` heuristics until migrated | New marketplace detectors (add under `profile/`) |

---

## 4. Migration strategy

1. **Canonical `OcrLine`** lives in `extraction.core`. **`parser.OcrLine`** is a **typealias** — existing imports of `vn.phapsumeo.onetap.parser.OcrLine` keep compiling.
2. **`XianyuParser.parse` / `parseWithCoords`** unchanged — all current call sites (`MainActivity`, `BubbleOverlayService`, `OcrFacade`) behave as before.
3. **New code path** (optional):  
   `ExtractionFacades.documentFromLines(...)` → `ExtractionFacades.extractXianyu(document)`  
   Same draft as `parseWithCoords` because `LegacyXianyuEntityDetector` delegates to `XianyuParser`.
4. **Next migrations (later PRs)**  
   - Move regex/heuristics from `XianyuParser` into `profile/xianyu/XianyuEntityDetectorV2` implementing `EntityDetector` without touching `OrderDraft` / GAS.  
   - Implement `XianyuLayoutAnalyzer` implementing `LayoutAnalyzer` (replace `PassthroughLayoutAnalyzer` in profile).  
   - Switch `BubbleOverlayService` to `ExtractionFacades.extractXianyu` once parity tests pass.  
   - Deprecate `XianyuParser.object` methods when no callers remain.
5. **UI** (`MainActivity` confidence display): unchanged in this phase; can later call `ConfidenceEngine` or read `ExtractionResult.fieldConfidence`.

---

## 5. Implementation phases

| Phase | Scope | Status |
|-------|--------|--------|
| **P0** | Core DTOs + API interfaces + `DefaultExtractionPipeline` + Xianyu profile delegating to legacy parser + `parser.OcrLine` typealias | **Done** |
| **P1** | Unit tests: `extractXianyu(documentFromLines(lines, w, h, uri))` vs `XianyuParser.parseWithCoords` identical `OrderDraft` on golden fixtures | Planned |
| **P2** | `OcrFacade.extractBlocksWithCoords` returning real `List<OcrBlock>` from ML Kit blocks | Planned |
| **P3** | `XianyuLayoutAnalyzer` — move Y-ratio / 200px proximity out of parser into layout hints | Planned |
| **P4** | `XianyuEntityDetector` — replace `LegacyXianyuEntityDetector`; shrink or delete `XianyuParser` | Planned |
| **P5** | Second marketplace profile (e.g. Taobao) as new `profile/` package + registry | Planned |
| **P6** | Wire `MainActivity` / bubble to pipeline only; remove duplicate confidence math | Planned |

---

## 6. Usage example (non-UI)

```kotlin
import vn.phapsumeo.onetap.extraction.ExtractionFacades
import vn.phapsumeo.onetap.parser.XianyuParser // still valid for direct calls

val document = ExtractionFacades.documentFromLines(
    lines = ocrLines,
    screenWidth = bitmap.width,
    screenHeight = bitmap.height,
    imageUri = uriString,
)
val result = ExtractionFacades.extractXianyu(document)
// result.draft should match XianyuParser.parseWithCoords(ocrLines, uriString, bitmap.width, bitmap.height)
```

---

## 7. Backward compatibility checklist

- [x] `OcrLine` public API preserved via typealias in `parser` package  
- [x] `XianyuParser.parse` / `parseWithCoords` signatures unchanged  
- [x] No `MainActivity` / layout XML edits  
- [x] No removal of `XianyuParser`  
- [x] New types additive only  

---

*See also `docs/ARCHITECTURE_AUDIT.md` and `docs/PSM_ONETAP_HANDOFF.md`.*

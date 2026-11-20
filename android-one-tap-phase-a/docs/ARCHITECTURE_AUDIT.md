# Technical audit — PSM OneTap (Android / Kotlin)

**Scope:** Repository `android-one-tap-phase-a` as of audit date.  
**Constraint:** Analysis and recommendations only — **no code rewrites** in this document.

---

## 1. Current architecture overview

### 1.1 Layering (conceptual)

| Layer | Responsibility | Primary locations |
|-------|------------------|-------------------|
| **Presentation** | Single-activity form, buttons, preview image, queue/history UI | `MainActivity.kt`, `res/layout/activity_main.xml` |
| **Overlay / capture orchestration** | Foreground bubble service, drag-to-dismiss, tap → capture session | `overlay/BubbleOverlayService.kt` |
| **Capture** | MediaProjection permission UI, virtual display, one-frame bitmap | `capture/CaptureActivity.kt`, `capture/CaptureBridge.kt`, `capture/ScreenCaptureHelper.kt` |
| **OCR** | ML Kit Chinese recognizer — flat text or lines + bounding boxes | `ocr/OcrFacade.kt` |
| **Parsing** | Heuristic extraction of tracking, total price, product title from OCR output | `parser/XianyuParser.kt`, `model/OrderDraft.kt` |
| **Transport** | HMAC-signed JSON POST to user-configured GAS URL; optional image upload | `gas/GasClient.kt`, `gas/HmacSigner.kt`, `gas/GasPayloadBuilder.kt` |
| **Reliability** | In-memory + disk queue, exponential-ish backoff, periodic worker, notifications | `util/RetryQueueStore.kt`, `util/RetryQueueProcessor.kt`, `worker/QueueRetryWorker.kt`, `util/RetryQueueNotifier.kt`, `util/RetryHistoryStore.kt` |
| **Configuration** | URLs, secrets, API keys (stored only), bubble fast mode, sheet month label | `util/PrefsStore.kt` |
| **Share ingress** | `ACTION_SEND` image → URI | `share/ShareIntentMapper.kt` |
| **Debug (non-prod contract)** | NDJSON append to app storage | `debug/DebugNdjsonLog.kt`; additional logging may exist in `BubbleOverlayService` |

### 1.2 Two user-visible pipelines

**Pipeline A — Bubble (primary, coordinate-aware)**

```
User taps bubble
  → BubbleOverlayService.onBubbleClick
  → CaptureBridge.beginSession() + startActivity(CaptureActivity)
  → User grants MediaProjection (or denies)
  → CaptureBridge.deliverGrant(ProjectionGrant)
  → ScreenCaptureHelper.capture(Granted) → Bitmap (display metrics size)
  → [UI] updateBubbleFromCapture (thumbnail / blur — presentation only)
  → Heuristic crop rect → saveBitmapToCache → content Uri (FileProvider)
  → OcrFacade.extractLinesWithCoords(bitmap)
  → XianyuParser.parseWithCoords(lines, imageUri, bitmap.width, bitmap.height)
  → OrderDraft
  → [fast] GasClient.submitOrder OR [slow] Intent → MainActivity preview → auto-submit timer
```

**Pipeline B — Share image (secondary, no bbox from ML Kit path used)**

```
External app ACTION_SEND image/*
  → MainActivity reads URI via ShareIntentMapper
  → User taps "OCR từ ảnh share"
  → OcrFacade.extractText(context, uri)  // string only, no per-line boxes
  → XianyuParser.parse(raw, imageUri) → internally parseWithCoords(lines with Rect? = null, 1080, 2400)
  → fillDraft + optional auto-submit based on confidence
```

Pipeline B **degrades** coordinate logic: all lines have `boundingBox == null`, so price/title regions that depend on Y-ratios and proximity **do not apply** as designed for bubble.

---

## 2. Dependency map (compile-time + runtime)

### 2.1 Gradle (application module)

From `app/build.gradle.kts`:

- **AndroidX:** `core-ktx`, `appcompat`, `material`, `activity-ktx`, `lifecycle-runtime-ktx`, `work-runtime-ktx`
- **Kotlin:** `kotlinx-coroutines-android`
- **Google ML Kit:** `text-recognition-chinese:16.0.0`

No Retrofit/OkHttp/Koin/Hilt — networking is `HttpURLConnection` inside `GasClient`.

### 2.2 Module-level dependency graph (logical)

```
MainActivity
  ├── PrefsStore, RetryQueueStore, RetryHistoryStore
  ├── OcrFacade → ML Kit
  ├── XianyuParser → OrderDraft
  ├── GasClient → HttpURLConnection + HmacSigner
  ├── GasPayloadBuilder
  ├── ShareIntentMapper
  ├── WorkManager → QueueRetryWorker → RetryQueueProcessor → GasClient
  └── DebugNdjsonLog (optional)

BubbleOverlayService
  ├── WindowManager (system)
  ├── CaptureActivity → CaptureBridge
  ├── ScreenCaptureHelper → MediaProjection / ImageReader
  ├── OcrFacade
  ├── XianyuParser
  ├── GasClient (fast path)
  ├── RetryQueueStore (on fast failure)
  └── PrefsStore

CaptureActivity
  └── CaptureBridge (only)

GasClient
  ├── HmacSigner
  ├── OrderDraft (read fields)
  └── Android ContentResolver / BitmapFactory / JPEG encode / Base64
```

### 2.3 Data stores (SharedPreferences files)

| File key (prefs name) | Owner | Contents |
|------------------------|-------|----------|
| `psm_onetap_prefs` | `PrefsStore` | `gas_url`, `hmac_secret`, `gemini_api_key`, `openai_api_key`, `bubble_fast_mode`, `selected_month` |
| `psm_onetap_queue` | `RetryQueueStore` | `queue_json` — JSONArray of queue items |
| `psm_onetap_retry_history` | `RetryHistoryStore` | JSON array of summarized processor runs |
| `screen_capture_session` | `ScreenCaptureSession` | **Cleared on save**; memory-first; **not referenced** elsewhere in `src` (orphan / legacy) |

---

## 3. Technical debt

| ID | Area | Description |
|----|------|-------------|
| D1 | **Parser dual behavior** | `parse(raw)` forces `1080×2400` and null boxes — mismatches real devices and bypasses bbox pipeline. |
| D2 | **OrderDraft in queue** | `RetryQueueStore` serializes a subset of `OrderDraft` fields; **`sheetName` is not persisted** on enqueue/restore — defaults reapply on retry. |
| D3 | **Dead code path** | `ScreenCaptureSession` documents disk persistence but clears prefs; **no other class imports it** — confusion risk for future capture reuse. |
| D4 | **Debug hardcoding** | `DebugNdjsonLog` uses fixed session id and writes to predictable filenames under app storage — should not ship as silent telemetry without policy. |
| D5 | **BubbleOverlayService size** | Service mixes: WM UI, gesture logic, capture orchestration, OCR, parse, GAS submit, image effects, debug HTTP — **high cohesion debt**. |
| D6 | **Logging in parser** | `XianyuParser` emits `Log.d` per line in `parseWithCoords` — noisy on large screenshots; no log level guard. |
| D7 | **UI vs implementation** | README / toasts reference “preview 2s”; `MainActivity` bubble preview uses **4000 ms** — documentation and UX copy drift. |
| D8 | **Unused credentials** | Gemini/OpenAI keys stored in `PrefsStore` but **no call sites** — user expectation mismatch (“AI configured” but unused). |
| D9 | **Release build** | `isMinifyEnabled = false` — larger APK; no R8-driven dead code removal for production hardening. |

---

## 4. Scalability risks

| Risk | Why it matters |
|------|----------------|
| **Single-target parser** | `XianyuParser` encodes Xianyu-specific Chinese labels and **normalized Y-bands** — new marketplace or Xianyu UI refresh requires code changes, not config. |
| **ML Kit single language** | Chinese recognizer is correct for Xianyu but wrong or weak for other locales/apps — adding locales implies multiple recognizers or cloud OCR. |
| **O(n) queue processing** | `RetryQueueProcessor` iterates full queue each run — fine for tens of items; unbounded growth (if enqueue bugs) → slow UI + large prefs string. |
| **GAS as sole backend** | Execution time quotas, payload size, concurrency — large base64 uploads in `GasClient` can hit limits; no client-side circuit breaker beyond retries. |
| **No remote config** | Parser thresholds (200 px, 0.25–0.55, 0.55–0.82) are compile-time — cannot tune per user cohort without release. |
| **WorkManager 15 min** | Retry latency bounded below real-time needs; acceptable for “eventually consistent” but not for SLA-critical ops. |

---

## 5. Parser weaknesses (evidence-based)

All references are to `parser/XianyuParser.kt`.

| Weakness | Mechanism |
|----------|-----------|
| **Fixed proximity in px** | Price near-label search uses `abs(centerY - labelY) < 200` — scales poorly across DPI / font scaling / split-screen. |
| **Normalized vertical bands** | Product title restricted to `centerY ∈ (0.25, 0.55)`; price fallback `(0.55, 0.82)` — breaks on notched devices, different aspect ratios, or Xianyu layout shifts. |
| **Last-candidate price** | Fallback takes `candidates.lastOrNull()` in band — order of ML Kit lines is not guaranteed stable across SDK/ML Kit versions. |
| **Hardcoded game bias** | `scoreAsProductName` adds **+5000** for 原神 / genshin and 散兵 / scaramouche — systematically **suppresses** non-game titles in the same band (e.g. esports merchandise). |
| **Blacklist fragility** | Blacklist includes Vietnamese transliterations and Chinese address tokens — OCR noise or partial matches can zero out legitimate titles. |
| **Tracking regex only** | No validation checksum; long numeric strings from UI can still confuse if OCR merges lines. |
| **`giaHangCn` / `shipNoiDiaCn` unused in coord path** | `parseWithCoords` always sets them to **null** — `giaGocForGas()` falls back to `tongCn` only; item vs shipping breakdown not extracted here. |
| **`parseMoney` limitations** | Fullwidth digits not normalized; reliance on `¥`/`￥`/`元` for long-digit rejection — OCR variants can slip or drop valid prices. |
| **Per-line debug log** | Performance + privacy (full line text in logcat) on every capture. |

---

## 6. Production risks

| Risk | Severity | Notes |
|------|----------|------|
| **Secrets in SharedPreferences** | High | `hmac_secret`, URLs, optional API keys in **plain** `SharedPreferences` — root / backup / device transfer exposure. |
| **Auto-submit timers** | High | User may not read fields in 3s / 4s — financial / operational wrong submissions. |
| **Fast bubble submit** | High | No human confirmation — parser errors propagate to GAS immediately. |
| **MediaProjection UX** | Medium | System dialog every capture (Android 14 policy) — user fatigue; denial paths must stay clean (session filter mitigates stale grants). |
| **Overlay permission** | Medium | `SYSTEM_ALERT_WINDOW` — store policy / malware perception; must stay foreground with visible disclosure. |
| **GAS contract opacity** | Medium | Client assumes JSON shape and `mat_than_upload_image` behavior — server changes break app without version negotiation. |
| **Image upload size** | Medium | `GasClient` compresses until &lt; ~800KB — still large for slow networks; doubles memory (bitmap + bytes + base64 string). |
| **Queue item drop** | Medium | After `maxRetryAttempts` (default 5), items disappear from queue — data loss unless GAS logs. |
| **No certificate pinning** | Low–Med | Standard `HttpURLConnection` — MITM on hostile networks unless TLS-only trust is enough for threat model. |
| **Orphan ScreenCaptureSession** | Low | Misleading maintainers into thinking persisted projection exists. |

---

## 7. Suggested modular redesign (boundaries only)

**Goal:** Preserve behavior while isolating testable units and reducing blast radius of UI changes. No implementation steps mandated here.

### 7.1 Proposed modules (Gradle submodules or strict packages)

| Module / package | Public API (conceptual) | Depends on |
|-------------------|-------------------------|------------|
| **:core:model** | `OrderDraft`, future sealed `ParseResult` / `SubmitResult` | Kotlin stdlib only |
| **:core:ocr-api** | `interface OcrEngine { suspend fun recognize(bitmap): OcrDocument }` | `OcrDocument` = lines + boxes + raw text |
| **:ocr-mlkit** | ML Kit implementation of `OcrEngine` | ML Kit, `:core:ocr-api` |
| **:core:parse-api** | `interface OrderParser { fun parse(document: OcrDocument, meta: ScreenMeta): OrderDraft }` | `:core:model` |
| **:parser-xianyu** | `XianyuOrderParser` implementing `OrderParser` | `:core:parse-api`, `:core:model` |
| **:core:transport-api** | `interface OrderTransport { suspend fun submit(draft: OrderDraft): Result<Unit, TransportError> }` | `:core:model` |
| **:transport-gas** | `GasClient` behind interface; HMAC + upload strategy internal | `:core:transport-api`, Android APIs |
| **:feature:capture** | `CaptureController` — session, grant flow, single-frame policy | Android MediaProjection, `:core:model` (bitmap holder) |
| **:feature:bubble** | WindowManager UI + gestures only; delegates to `CaptureController`, `OcrEngine`, `OrderParser` | `:feature:capture`, `:core:ocr-api`, `:core:parse-api` |
| **:app** | DI graph, `MainActivity`, WorkManager wiring | All feature + infra modules |

### 7.2 Data flow diagrams (text)

**Bubble submit (target state)**

```
[Bubble UI Module]
        │ tap
        ▼
[Capture Module]──► Bitmap + ScreenMeta (w, h, density)
        │
        ▼
[OcrEngine]──► OcrDocument (lines + Rect?)
        │
        ▼
[OrderParser: Xianyu]──► OrderDraft
        │
        ├──► [Preview UI] optional human edit
        │
        ▼
[OrderTransport: GAS]──► HTTP result
        │
        └──► [RetryRepository] on failure
```

**Share pipeline (target state)**

```
[Share Ingress]──► Uri
        │
        ▼
[Bitmap decode or stream]──► Bitmap (or pass Uri to OcrEngine if supported)
        │
        ▼
[OcrEngine]──► OcrDocument  // must not fabricate 1080×2400
        │
        ▼
[OrderParser]──► OrderDraft   // same parser as bubble if document has boxes; else explicit "text-only strategy"
```

### 7.3 Boundaries to enforce

1. **`BubbleOverlayService` must not** reference `GasClient` directly — use `OrderTransport` interface (test doubles, mock server).
2. **`XianyuParser` must not** depend on Android `Log` or `Rect` from ML Kit directly — map to neutral `BoundingBox` DTO in `:core:ocr-api`.
3. **`RetryQueueStore` must** round-trip **entire** `OrderDraft` or a versioned `QueuedOrder` DTO — include `sheetName` and future fields.
4. **Configuration:** migrate secrets off plain prefs (EncryptedSharedPreferences / Keystore) in a dedicated `:infra:security` module when product requires.

---

## 8. Appendix — OCR bbox usage summary

| Step | Uses bbox? | How |
|------|--------------|-----|
| Tracking | **No** (regex on `fullText` concatenation) | Lines joined; boxes ignored for maVc. |
| Price (label path) | **Yes** | Find line containing 成交价/实付款/合计; filter other lines with `|centerY - labelY| < 200`. |
| Price (fallback) | **Yes** | Lines with box; `centerY/height` in (0.55, 0.82); parse numbers; take last. |
| Product title | **Yes** | Lines with box; `centerY` in (0.25, 0.55), `centerX/width > 0.10`; score text; vertical tie-break. |

---

## 9. Appendix — Confidence scoring (MainActivity)

`confidenceScore(draft: OrderDraft)` (`MainActivity.kt`):

- +0.35 if `maVanChuyen.length >= 8`
- +0.25 if `tenSanPham.length >= 4`
- +0.25 if `tongCn != null && tongCn > 0`
- +0.10 if `giaHangCn != null && giaHangCn > 0`
- +0.05 if `shipNoiDiaCn != null && shipNoiDiaCn >= 0`
- Capped at 1.0; threshold `autoSubmitThreshold = 0.72` when checkbox enabled.

**Gap:** Bubble path often leaves `giaHangCn`/`shipNoiDiaCn` null — confidence depends heavily on `tongCn` and title length, not title *quality* (parser score is separate and not fed into confidence).

---

## 10. Appendix — GAS transport (contract surface)

- **Envelope:** JSON with `_ts`, `_sig` (HMAC-SHA256 hex over `"$ts\n$bodyStr"`), `_body` (stringified inner JSON).
- **Submit inner fields (business):** `linkGoc`, `giaGoc`, `linkAnh`, `maVanChuyen`, `tenTab`, `tenSanPham`, `tenSanPhamMulti` (see `GasClient.submitOrder`).
- **Upload:** inner `action = mat_than_upload_image`, `imageBase64`, `imageName`, `mimeType` — response must include `status` and `imageUrl`.
- **Success:** outer parsed JSON `status == "success"`.

Server implementation is **out of repo** — treat as integration black box with version coupling risk.

---

*End of audit — implementation changes deferred to a separate design/PR phase.*

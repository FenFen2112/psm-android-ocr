# PSM OneTap — Tài liệu handoff (Android / Kotlin)

**Repo gốc (ví dụ trên máy bạn):** `D:\PSM\android-one-tap-phase-a`  
**Package:** `vn.phapsumeo.onetap`  
**App name:** PSM OneTap  

Tài liệu này tổng hợp flow, kiến trúc, stack và đường dẫn file để review (GPT / người mới vào dự án).

---

## 1. Bối cảnh dự án

| Mục | Chi tiết |
|-----|----------|
| **Loại** | Android native **Kotlin** (không Flutter / React Native) |
| **Mục tiêu** | Chụp màn hình Xianyu (bubble) hoặc **Share ảnh** → OCR → parse → gửi **Google Apps Script (GAS)** kèm HMAC; có queue retry và overlay bubble |
| **Backend** | GAS Web App URL do user nhập; không có server riêng trong repo |

---

## 2. Flow người dùng (đầu → cuối)

### A. Cấu hình lần đầu

1. Mở app → nhập **GAS Web App URL**, **HMAC secret**, chọn **tháng sheet** (spinner).
2. (Tuỳ chọn) nhập **Gemini API Key** / **OpenAI API Key** — **hiện chỉ lưu SharedPreferences**, không có gọi API LLM trong code Kotlin.
3. Bấm **Lưu cấu hình**.

### B. Bubble + chụp Xianyu

1. Bật quyền **hiển thị trên ứng dụng khác** (overlay).
2. **Cấp quyền chụp màn hình** (MediaProjection) → `BubbleOverlayService` chạy dạng foreground service.
3. Mở Xianyu tới màn cần lấy thông tin.
4. Chạm **bubble PSM** → bubble ẩn → `CaptureActivity` → token projection → **một frame** screenshot.
5. Crop vùng nhỏ → lưu cache → URI `content://…fileprovider/…` làm **ảnh đính kèm** khi gửi.
6. OCR **có bounding box** (`extractLinesWithCoords`) → `XianyuParser.parseWithCoords` → `OrderDraft`.
7. **Chế độ gửi nhanh:** gửi thẳng GAS. **Không gửi nhanh:** mở `MainActivity` preview, **đếm ngược** rồi auto bấm **Gửi thật lên GAS** (trừ khi hủy / sửa form).

### C. Share ảnh (không bubble)

1. App khác **Share** ảnh → chọn PSM (`ACTION_SEND`, `image/*`).
2. Trong `MainActivity`: **OCR từ ảnh share** (text phẳng) → **Phân tích text** → điền form → gửi GAS.

---

## 3. “AI” / LLM xuất hiện ở đâu?

| Thành phần | Trạng thái trong code |
|-------------|------------------------|
| **GPT / Gemini / Claude** | **Không** được gọi trong luồng xử lý; chỉ lưu key qua `PrefsStore` + ô nhập trên UI |
| **OCR** | **Google ML Kit** — Text Recognition **Chinese** (on-device) |
| **Parse** | Heuristic **`XianyuParser`** — regex, vùng theo % chiều cao màn, proximity quanh nhãn giá |

---

## 4. Kiến trúc & stack

| Lớp | Công nghệ / file chính |
|-----|-------------------------|
| **UI** | ViewBinding + Material — `MainActivity.kt`, `res/layout/activity_main.xml` |
| **OCR** | ML Kit `text-recognition-chinese` — `ocr/OcrFacade.kt` |
| **Parse** | `parser/XianyuParser.kt` — `OcrLine`, `parseWithCoords`, `parse()` fallback |
| **Capture** | MediaProjection + ImageReader — `capture/*` |
| **Gửi backend** | HTTP POST JSON có chữ ký HMAC — `gas/GasClient.kt`, `gas/HmacSigner.kt` |
| **Preview payload** | `gas/GasPayloadBuilder.kt` |
| **Upload ảnh** | Action GAS `mat_than_upload_image` (base64 JPEG) trong `GasClient` |
| **Lưu trữ** | **SharedPreferences** — prefs, queue, retry history (**không** SQLite) |
| **Job nền** | **WorkManager** — `worker/QueueRetryWorker.kt` (mặc định ~15 phút, có mạng) |
| **Share vào app** | `share/ShareIntentMapper.kt` |

**Dependencies:** xem `app/build.gradle.kts`.

---

## 5. OCR / API Xianyu / tracking

- **OCR:** ML Kit Chinese (`OcrFacade.kt`).
- **Không** gọi API Xianyu / Taobao; **không** scrape web.
- **Mã vận chuyển / mã đơn:** regex trên chuỗi OCR trong `XianyuParser.kt`.

---

## 6. Pipeline đơn (bubble) — thứ tự

```
BubbleOverlayService.onBubbleClick
  → CaptureBridge + CaptureActivity + ScreenCaptureHelper.capture
  → Bitmap full màn hình
  → Crop thumbnail → saveBitmapToCache → FileProvider URI
  → OcrFacade.extractLinesWithCoords(bitmap)
  → XianyuParser.parseWithCoords(lines, imageUri, bitmap.width, bitmap.height)
  → OrderDraft
  → GasClient.submitOrder (và upload ảnh nếu URI là content://)
```

---

## 7. Payload GAS / “prompt AI”

- **Không** có system prompt LLM trong app.
- JSON nghiệp vụ: ví dụ `linkGoc`, `giaGoc`, `linkAnh`, `maVanChuyen`, `tenTab`, `tenSanPham`, `tenSanPhamMulti` — build trong `GasPayloadBuilder.kt` và logic tương đương trong `GasClient.submitOrder`.

**`giaGoc`:** từ `OrderDraft.giaGocForGas()` (`model/OrderDraft.kt`) — ưu tiên `tongCn`, fallback tổng hàng + ship.

---

## 8. Queue, cache, confidence

| Khái niệm | Vị trí code |
|------------|-------------|
| **Queue retry** | `util/RetryQueueStore.kt`, `util/RetryQueueProcessor.kt`, nút trên `MainActivity` |
| **Lịch sử retry** | `util/RetryHistoryStore.kt` |
| **Thông báo nền** | `util/RetryQueueNotifier.kt` |
| **Worker định kỳ** | `worker/QueueRetryWorker.kt` + `MainActivity.ensurePeriodicRetryWorker()` |
| **Confidence + auto-submit** | `MainActivity.confidenceScore`, `evaluateConfidenceAndAutoSubmit`, `CountDownTimer` |
| **Ảnh bubble** | Cache app + `FileProvider` — `xml/file_paths.xml` |

**Lưu ý UX:** checkbox có thể ghi “preview 2s” trong khi code bubble preview dùng **4 giây** — nên đồng bộ label hoặc timer.

---

## 9. Cây thư mục / file quan trọng (relative từ root repo)

```
android-one-tap-phase-a/
├── app/build.gradle.kts
├── app/src/main/AndroidManifest.xml
├── app/src/main/res/layout/activity_main.xml
├── app/src/main/res/xml/file_paths.xml
│
└── app/src/main/java/vn/phapsumeo/onetap/
    ├── MainActivity.kt
    ├── model/OrderDraft.kt
    ├── ocr/OcrFacade.kt
    ├── parser/XianyuParser.kt
    ├── overlay/BubbleOverlayService.kt
    ├── capture/
    │   ├── CaptureActivity.kt
    │   ├── CaptureBridge.kt
    │   ├── ScreenCaptureHelper.kt
    │   └── ScreenCaptureSession.kt
    ├── gas/
    │   ├── GasClient.kt
    │   ├── GasPayloadBuilder.kt
    │   └── HmacSigner.kt
    ├── share/ShareIntentMapper.kt
    ├── util/
    │   ├── PrefsStore.kt
    │   ├── RetryQueueStore.kt
    │   ├── RetryQueueProcessor.kt
    │   ├── RetryHistoryStore.kt
    │   └── RetryQueueNotifier.kt
    ├── worker/QueueRetryWorker.kt
    └── debug/DebugNdjsonLog.kt
```

---

## 10. Gợi ý chủ đề review (cho GPT / audit)

1. Parser phụ thuộc **tỉ lệ màn** (`centerY / screenHeight`) — rủi ro khi đổi máy / Xianyu đổi layout.
2. `scoreAsProductName` có **cộng điểm lớn** cho vài keyword game — có thể lệch với đơn không liên quan.
3. `parse(raw)` fallback: `parseWithCoords` với **bbox null** và kích thước **1080×2400** cố định — luồng “dán text” kém ổn định hơn bubble.
4. **Gemini/OpenAI** trên UI nhưng chưa dùng — kỳ vọng user / legal surface.
5. Hợp đồng GAS: `_ts`, `_sig`, `_body`, upload base64 — hardening (replay, skew đồng hồ, giới hạn kích thước).

---

## 11. Shortcut Docling trên Desktop

File bạn nhắc: `C:\Users\PC\Desktop\Docling Chuyen sang MD.lnk` — đây là **shortcut Windows**; môi trường dev trong Cursor **không chạy** được `.lnk` thay cho bạn.

**Cách dùng:**

1. Mở file Markdown này trong repo: **`docs/PSM_ONETAP_HANDOFF.md`** (đã tạo sẵn).
2. Nếu Docling là CLI/GUI: trỏ input là file `.md` này (hoặc copy nội dung) theo đúng hướng dẫn của tool Docling trên máy bạn.
3. Hoặc import file `.md` vào vault / CMS của bạn — nội dung đã ở dạng Markdown chuẩn.

---

*Tạo để handoff / review; cập nhật khi đổi kiến trúc hoặc nối LLM thật.*

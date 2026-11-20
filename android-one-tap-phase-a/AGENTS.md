# Hướng dẫn cho AI / Agent — PSM OneTap (Android)

Tài liệu này giúp bạn cấu hình **Cursor Rules**, **Project instructions**, hoặc prompt cho các công cụ AI khi làm việc trong repo `android-one-tap-phase-a`.

---

## 1. Dự án là gì

- **Tên gói:** `vn.phapsumeo.onetap` — ứng dụng Android native (Kotlin) hỗ trợ nhập đơn nhanh từ màn hình **闲鱼 / Taobao-like** (chữ Trung).
- **Hai luồng chính:**
  1. **Share sheet:** người dùng chia sẻ ảnh (`ACTION_SEND`, `image/*`) → OCR → form preview → gửi **Google Apps Script (GAS)** qua HMAC.
  2. **Bubble overlay:** dịch vụ nổi chụp màn hình → tiền xử lý vùng (thẻ đơn + crop title/giá) → OCR đa vùng → parse → preview nhanh / gửi nhanh / queue retry.

Mục tiêu phase A/B-lite: giảm thao tác tay, parse rule-first (`商品总价`, `运费`, `成交价`, mã đơn, vận đơn…), gửi an toàn.

---

## 2. Stack kỹ thuật

| Thành phần | Chi tiết |
|-------------|----------|
| Ngôn ngữ | Kotlin 1.x, JVM 17 |
| UI | AndroidX, **ViewBinding**, `Activity` / `Service` |
| OCR | **ML Kit** Chinese Text Recognition (`OcrFacade.kt`) |
| Tiền xử lý OCR | `ocr/preprocess/OcrPreprocessPipeline` — grayscale, contrast, v.v.; có preset `OcrPreprocessConfigs.RegionCrop` cho ảnh crop nhỏ |
| Parse | Rule-based: `parser/XianyuRuleTextExtractor.kt`, `XianyuParser.kt` |
| Gửi đơn | `gas/GasClient.kt`, `HmacSigner.kt`, `GasPayloadBuilder.kt` |
| Lưu trữ local | `PrefsStore`, `RetryQueueStore`, WorkManager (`QueueRetryWorker`) |

`README.md` mô tả roadmap và tính năng đã có — đọc khi cần bối cảnh sản phẩm.

---

## 3. Thư mục & file quan trọng (khi sửa code)

```
app/src/main/java/vn/phapsumeo/onetap/
├── MainActivity.kt              # Share intent, preview, submit, debug UI
├── overlay/
│   ├── BubbleOverlayService.kt  # Chụp màn, gọi preprocessor + BubbleMultiRegionOcr, intent sang MainActivity
│   └── BubbleMultiRegionOcr.kt  # OCR title + price (không còn vùng meta OCR)
├── capture/layer/
│   ├── ImagePreprocessor.kt     # OrderCardCropPreprocessor: thẻ đơn, anchor thumbnail, crop theo tỉ lệ trong thẻ
│   └── ThumbnailCropDetector.kt # Phát hiện ô ảnh sản phẩm (pixel heuristic, không OCR)
├── debug/ocr/
│   └── OcrCropRegionsDebugPainter.kt  # Vẽ THUMB / TITLE / PRICE lên ảnh debug
├── ocr/OcrFacade.kt
├── parser/XianyuRuleTextExtractor.kt  # merge (giá), mergeMetadata (đơn/MVĐ/trạng thái — có thể dùng text price+title)
├── gas/
└── util/                        # Prefs, retry queue, …
```

- **Không** thêm lại OCR vùng **META** toàn thẻ trừ khi có spec rõ — đã bỏ để tránh nhiễu; metadata vẫn có thể merge từ **price + title**.
- Crop vùng OCR: neo **thumbnail** trong **card** đã detect, dùng **phân số theo kích thước thẻ** + inset theo `dp` quy đổi sang tỉ lệ — tránh rect pixel cố định trên toàn màn hình.

---

## 4. Quy ước khi chỉnh sửa

1. **Phạm vi:** chỉ sửa file liên quan trực tiếp task; không refactor rộng không được yêu cầu.
2. **OCR / parser:** thay đổi layout crop → cập nhật `ImagePreprocessor` + painter debug + (nếu cần) `BubbleMultiRegionOcr`; parser tiếng Trung giữ keyword ổn định.
3. **Tài nguyên Bitmap:** luôn `recycle()` các bitmap crop tạm sau OCR trong `BubbleOverlayService` (đã có pattern); không giữ reference lâu.
4. **Ngôn ngữ UI:** app có chuỗi tiếng Việt + Trung trong logic parse; không đổi ý nghĩa keyword Trung khi sửa regex.
5. **Build:** `compileSdk` / `targetSdk` 35, `minSdk` 26 — tránh API cũ hơn 26 trừ khi có fallback.

---

## 5. Debug & kiểm tra

- Log tag thường gặp: `PSM_BUBBLE`, `PSM_CARD_CROP`, `PSM_OCR_CROP_RECT`.
- PNG debug crop: vẽ qua `OcrCropRegionsDebugPainter` — **cam = THUMB**, **đỏ = TITLE**, **lục = PRICE**.
- Session debug NDJSON (nếu có): `debug-418c16.log` — chỉ xóa file log **của session** khi user/agent debug có yêu cầu.

---

## 6. Gợi ý câu lệnh cho “Project instructions” / Rules ngắn

Dán vào **Cursor → Project Rules** hoặc đầu prompt:

> Đây là app Android Kotlin (`vn.phapsumeo.onetap`): OCR ML Kit chữ Trung, parse rule Xianyu/Taobao, gửi GAS HMAC. Luồng bubble: `BubbleOverlayService` → `OrderCardCropPreprocessor` (crop theo thẻ + thumbnail) → `BubbleMultiRegionOcr`. Không thêm OCR meta full-card. Giữ ViewBinding, recycle bitmap. Đọc `AGENTS.md` và `README.md` cho chi tiết.

---

## 7. Tài liệu khác trong repo

- `README.md` — skeleton, roadmap, tính năng phase A/B.
- `docs/ARCHITECTURE_AUDIT.md` — nếu có, audit kiến trúc.

Cập nhật **AGENTS.md** khi thêm module lớn hoặc đổi contract GAS/OCR để AI luôn đồng bộ với code.

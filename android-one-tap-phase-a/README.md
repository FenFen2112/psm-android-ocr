# Android one-tap Phase A skeleton

Skeleton native app cho lộ trình `ANDROID_ONE_TAP_AUTOMATION_ROADMAP.md`.

## Mục tiêu phase A trong skeleton này

- Nhận ảnh từ Share sheet (`ACTION_SEND`, `image/*`).
- OCR ảnh share bằng ML Kit Chinese recognizer.
- Đổ dữ liệu vào màn preview form để sửa tay nhanh.
- Parser cơ bản cho text Trung (`商品总价`, `运费`, `成交价`, `运单号`, `订单编号`).
- Gửi thật tới GAS bằng HMAC envelope (tương thích flow web/extension).

## Cấu trúc chính

- `MainActivity`: nhận Share Intent, OCR, bind UI preview, submit GAS.
- `share/ShareIntentMapper.kt`: đọc URI và metadata từ Intent.
- `parser/XianyuParser.kt`: rule parse text cơ bản.
- `gas/HmacSigner.kt` + `gas/GasClient.kt`: ký và gửi request thật tới GAS.
- `gas/GasPayloadBuilder.kt`: dựng payload preview tương thích web/extension.
- `ocr/OcrFacade.kt`: OCR ML Kit (Chinese).

## Chạy thử

1. Mở folder này bằng Android Studio.
2. Sync Gradle.
3. Run app trên máy/emulator.
4. Từ app ảnh/screenshot -> Share -> chọn app `PSM OneTap`.

## Việc đã có thêm (phase B-lite)

- Confidence score hiển thị trực tiếp trên màn preview.
- Auto-submit sau 3 giây khi score vượt ngưỡng (có nút hủy countdown).
- Ảnh `content://` từ Share được resize + nén + upload qua action `mat_than_upload_image`, lấy `imageUrl` rồi submit đơn.
- Retry queue local: đơn gửi lỗi được lưu queue, có nút gửi lại queue và xóa queue, app tự thử gửi lại khi mở.
- Retry backoff: queue có `attempt` + `nextRetryAt`, chỉ retry khi đến hạn, giới hạn số lần thử để tránh loop vô tận.
- WorkManager nền: worker định kỳ (15 phút, cần mạng) tự retry queue ngay cả khi không mở màn hình nhập.
- Notification retry nền: worker gửi thông báo tóm tắt (gửi thành công/lỗi/bị drop vì quá số lần thử); Android 13+ xin quyền thông báo.
- Lịch sử retry trên UI: lưu các lần retry foreground/worker (due/sent/fail/drop/remain) và hiển thị nhanh ngay trong màn hình chính.
- Quản lý lịch sử retry: có nút xóa lịch sử riêng, đồng thời tô màu dòng log (xanh/cam/đỏ) để nhận biết trạng thái nhanh.
- Lọc lịch sử retry theo nguồn: `Tất cả` / `Trong app` (foreground) / `Worker nền`.
- Bubble nổi (overlay): có nút cấp quyền chụp màn hình + bật/tắt bong bóng; bấm bong bóng sẽ chụp màn hình, OCR/parse rồi mở app **preview 2 giây trước khi gửi** (có nút hủy), giúp giảm gửi sai.
- Bubble 2 chế độ: **An toàn** (preview 2s) hoặc **Gửi nhanh** (submit trực tiếp từ bong bóng, lỗi thì tự vào queue + mở app để kiểm tra).

## Việc cần làm tiếp (phase B -> C)

- Hoàn thiện confidence bằng weighted rules + telemetry thực tế.
- Tăng độ chính xác parse khi chụp từ bong bóng (mapping nhiều layout app hơn).

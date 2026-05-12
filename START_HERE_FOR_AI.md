# START HERE FOR AI

Dự án Android OCR / one-tap capture của PSM.

Ưu tiên:
- OCR ổn định
- crop đúng vùng
- overlay debug rõ
- export OCR crop/debug text

Không:
- hardcode pixel
- xoá debug logs
- sửa nhiều module cùng lúc
- upload build output
- upload keystore

Workflow:
save → build → install → OCR → inspect crop → tune → repeat

# START HERE FOR AI

## Priorities
1. OCR stability
2. Correct crop alignment
3. Fast debugging
4. Overlay observability

## Rules
- NEVER hardcode pixels
- ALWAYS use relative coordinates
- ALWAYS export OCR debug crops
- inspect crop before blaming OCR engine
- preserve debug logs

## Main Bottleneck
Layout anchoring and crop alignment,
NOT OCR engine quality.
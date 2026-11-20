package vn.phapsumeo.onetap.debug.dataset

/**
 * NDJSON / JSONL schema for PSM OneTap OCR training & debugging datasets.
 *
 * Each file line is one JSON object (no pretty-print). UTF-8 encoding.
 *
 * **Root fields (v1):**
 * - `schema`: literal `"psm.ocr_dataset"`
 * - `schema_version`: integer, currently [CURRENT_SCHEMA_VERSION]
 * - `record_id`: UUID v4 string, unique per line
 * - `parent_record_id`: optional UUID linking user_submit → capture
 * - `timestamp_ms`: wall clock epoch ms
 * - `kind`: `bubble_capture` | `share_ocr_capture` | `text_parse_only` | `user_submit` | `bubble_fast_submit`
 * - `marketplace`: e.g. `xianyu` (parser / profile source)
 * - `device`: screen px, density, Android SDK, manufacturer/model (no serial / IMEI)
 * - `privacy`: safe URI reference, text redaction flags (no GAS URL, secrets, or API keys)
 * - `capture`: full-frame dimensions, optional crop rect in capture space
 * - `ocr`: lines `{ text, bbox }[]`, optional `full_text` when not redacted
 * - `grouped_blocks`: `{ reading_order, bbox, line_count }[]`
 * - `parser_output`: structured fields (no raw image URI)
 * - `confidence`: overall, per-axis scores, truncated reasons
 * - `user_correction` (submit kinds only): parser vs user draft diff summary
 *
 * **Rotation:** active file `current.jsonl`; when size exceeds cap, renamed to `archive_<timestamp>.jsonl`.
 * **Retention:** oldest archive files deleted beyond [MAX_ARCHIVE_FILES].
 */
object OcrDatasetSchema {
    const val SCHEMA_NAME = "psm.ocr_dataset"
    const val CURRENT_SCHEMA_VERSION = 1

    const val KIND_BUBBLE_CAPTURE = "bubble_capture"
    const val KIND_SHARE_OCR_CAPTURE = "share_ocr_capture"
    const val KIND_TEXT_PARSE_ONLY = "text_parse_only"
    const val KIND_USER_SUBMIT = "user_submit"
    const val KIND_BUBBLE_FAST_SUBMIT = "bubble_fast_submit"

    const val MARKETPLACE_XIANYU = "xianyu"
}

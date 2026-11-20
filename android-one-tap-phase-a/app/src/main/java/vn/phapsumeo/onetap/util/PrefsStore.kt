package vn.phapsumeo.onetap.util

import android.content.Context

class PrefsStore(context: Context) {
    private val prefs = context.getSharedPreferences("psm_onetap_prefs", Context.MODE_PRIVATE)

    fun saveGasUrl(url: String) {
        prefs.edit().putString("gas_url", url).apply()
    }

    fun saveHmacSecret(secret: String) {
        prefs.edit().putString("hmac_secret", secret).apply()
    }

    fun gasUrl(): String = prefs.getString("gas_url", "").orEmpty()
    fun hmacSecret(): String = prefs.getString("hmac_secret", "").orEmpty()

    fun saveGeminiApiKey(key: String) {
        prefs.edit().putString("gemini_api_key", key).apply()
    }

    fun geminiApiKey(): String = prefs.getString("gemini_api_key", "").orEmpty()

    fun saveOpenAiApiKey(key: String) {
        prefs.edit().putString("openai_api_key", key).apply()
    }

    fun openAiApiKey(): String = prefs.getString("openai_api_key", "").orEmpty()

    fun saveDebugIngestTarget(target: String) {
        prefs.edit().putString("debug_ingest_target", target).apply()
    }

    fun debugIngestTarget(): String = prefs.getString("debug_ingest_target", "").orEmpty()

    fun saveBubbleFastMode(enabled: Boolean) {
        prefs.edit().putBoolean("bubble_fast_mode", enabled).apply()
    }

    fun bubbleFastMode(): Boolean = prefs.getBoolean("bubble_fast_mode", false)

    fun saveOcrDebugVisualization(enabled: Boolean) {
        prefs.edit().putBoolean("ocr_debug_visualization", enabled).apply()
    }

    fun ocrDebugVisualization(): Boolean = prefs.getBoolean("ocr_debug_visualization", false)

    fun developerMode(): Boolean = prefs.getBoolean("developer_mode", false)

    fun saveDeveloperMode(enabled: Boolean) {
        prefs.edit().putBoolean("developer_mode", enabled).apply()
    }

    fun ocrDebugSectionExpanded(): Boolean = prefs.getBoolean("ocr_debug_section_expanded", false)

    fun saveOcrDebugSectionExpanded(expanded: Boolean) {
        prefs.edit().putBoolean("ocr_debug_section_expanded", expanded).apply()
    }

    fun saveOcrDatasetLogging(enabled: Boolean) {
        prefs.edit().putBoolean("ocr_dataset_logging", enabled).apply()
    }

    fun ocrDatasetLogging(): Boolean = prefs.getBoolean("ocr_dataset_logging", false)

    fun saveOcrDatasetRedactText(enabled: Boolean) {
        prefs.edit().putBoolean("ocr_dataset_redact_text", enabled).apply()
    }

    fun ocrDatasetRedactText(): Boolean = prefs.getBoolean("ocr_dataset_redact_text", false)

    fun saveSelectedMonth(month: String) {
        prefs.edit().putString("selected_month", month).apply()
    }

    fun getSelectedMonth(): String = prefs.getString("selected_month", "Tháng 5").orEmpty()

    fun hasSeenOcrPrivacyNotice(): Boolean = prefs.getBoolean("ocr_privacy_notice_seen", false)

    fun markOcrPrivacyNoticeSeen() {
        prefs.edit().putBoolean("ocr_privacy_notice_seen", true).apply()
    }

    fun advancedSettingsExpanded(): Boolean = prefs.getBoolean("advanced_settings_expanded", false)

    fun saveAdvancedSettingsExpanded(expanded: Boolean) {
        prefs.edit().putBoolean("advanced_settings_expanded", expanded).apply()
    }
}

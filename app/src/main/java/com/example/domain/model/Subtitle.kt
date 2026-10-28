package com.example.domain.model

import androidx.compose.runtime.Immutable

@Immutable
data class Subtitle(
    val id: String,
    val url: String,
    val lang: String,
    val addonName: String,
    val addonLogo: String? = null,
    val isStreamProvided: Boolean = false,
    val headers: Map<String, String>? = null,
    /** Etichetta mostrata all'utente quando l'addon la dichiara (es. "Forzato"). */
    val label: String? = null,
    /**
     * Identificativo del FILE subtitle su OpenSubtitles v3 (`attributes.files[].file_id`).
     * Valorizzato SOLO per i risultati OpenSubtitles: consente di risolvere l'URL di
     * download temporaneo alla selezione (POST /api/v1/download) invece che durante la
     * ricerca, senza consumare quota download per risultati mai scelti.
     * Quando presente e [url] è vuoto, il sottotitolo è "da risolvere al download".
     */
    val fileId: Long? = null
) {
    fun getDisplayLanguage(): String = languageCodeToName(lang)

    companion object {
        private fun languageCodeToName(code: String): String {
            return when (code.lowercase().take(2)) {
                "it" -> "Italiano"
                "en" -> "English"
                "es" -> "Español"
                "fr" -> "Français"
                "de" -> "Deutsch"
                "pt" -> "Português"
                "ja" -> "日本語"
                "ko" -> "한국어"
                "zh" -> "中文"
                "ru" -> "Русский"
                "ar" -> "العربية"
                "hi" -> "हिन्दी"
                "pl" -> "Polski"
                "nl" -> "Nederlands"
                "sv" -> "Svenska"
                "no" -> "Norsk"
                "da" -> "Dansk"
                "fi" -> "Suomi"
                "el" -> "Ελληνικά"
                "tr" -> "Türkçe"
                "he" -> "עברית"
                "th" -> "ไทย"
                "vi" -> "Tiếng Việt"
                "id" -> "Bahasa Indonesia"
                "ms" -> "Bahasa Melayu"
                "ro" -> "Română"
                "hu" -> "Magyar"
                "cs" -> "Čeština"
                "sk" -> "Slovenčina"
                "uk" -> "Українська"
                "bg" -> "Български"
                "hr" -> "Hrvatski"
                "sr" -> "Српски"
                "sl" -> "Slovenščina"
                "et" -> "Eesti"
                "lv" -> "Latviešu"
                "lt" -> "Lietuvių"
                else -> code.uppercase()
            }
        }
    }
}

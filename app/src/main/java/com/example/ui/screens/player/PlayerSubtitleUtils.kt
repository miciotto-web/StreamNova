package com.example.ui.screens.player

import android.text.SpannableStringBuilder
import android.text.Spanned
import androidx.media3.common.MimeTypes
import androidx.media3.common.text.Cue

/**
 * Subtitle utility methods for language normalization, mime sniffing and cue merging.
 * Ported directly from NuvioTV PlayerSubtitleUtils.kt.
 */
internal object PlayerSubtitleUtils {

    internal val LANGUAGE_OVERRIDES = mapOf(
        "pt" to "pt",
        "pt-pt" to "pt",
        "pt_pt" to "pt",
        "por" to "pt",
        "pt-br" to "pt-br",
        "pt_br" to "pt-br",
        "br" to "pt-br",
        "pob" to "pt-br",
        "fre" to "fr",
        "ger" to "de",
        "deu" to "de",
        "dut" to "nl",
        "nld" to "nl",
        "chi" to "zh",
        "zho" to "zh",
        "jpn" to "ja",
        "kor" to "ko",
        "ara" to "ar",
        "hin" to "hi",
        "rus" to "ru",
        "pol" to "pl",
        "spa" to "es",
        "spl" to "es-419",
        "es-419" to "es-419",
        "es_419" to "es-419",
        "es-la" to "es-419",
        "es-lat" to "es-419",
        "fra" to "fr",
        "ita" to "it"
    )

    fun normalizeLanguageCode(lang: String): String {
        val code = lang.trim().lowercase()
        if (code.isBlank()) return ""

        val normalizedCode = code.replace('_', '-')
        val tokenized = normalizedCode
            .replace('-', ' ')
            .replace('.', ' ')
            .replace('/', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()

        fun containsAny(vararg values: String): Boolean = values.any { value ->
            tokenized.contains(value)
        }

        if (containsAny("portuguese", "portugues")) {
            if (containsAny("brazil", "brasil", "brazilian", "brasileiro", "pt br", "ptbr", "pob", "(br)")) {
                return "pt-br"
            }
            if (containsAny("portugal", "european", "europeu", "iberian", "pt pt", "ptpt")) {
                return "pt"
            }
            return "pt"
        }

        if (containsAny("spanish", "espanol", "español", "castellano")) {
            if (containsAny("latin", "latino", "latinoamerica", "latinoamericano", "lat am", "latam", "es 419", "es419", "la", "(419)")) {
                return "es-419"
            }
            return "es"
        }

        if (containsAny("bahasa indonesia", "indonesian", "indonesia")) {
            return "id"
        }
        if (containsAny("bahasa malaysia", "bahasa melayu", "malaysian")) {
            return "ms"
        }

        return LANGUAGE_OVERRIDES[code]?.lowercase()
            ?: LANGUAGE_OVERRIDES[normalizedCode]?.lowercase()
            ?: normalizedCode
    }

    fun matchesLanguageCode(language: String?, target: String): Boolean {
        if (language.isNullOrBlank()) return false
        val normalizedLanguage = normalizeLanguageCode(language)
        val normalizedTarget = normalizeLanguageCode(target)
        if (matchesNormalizedLanguage(normalizedLanguage, normalizedTarget)) {
            return true
        }

        val subtags = language.trim().lowercase()
            .replace('_', '-')
            .split('-', '.', '/', ' ')
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (subtags.size <= 1) {
            return false
        }
        for (subtag in subtags.drop(1)) {
            if (subtag.length != 3) continue
            val normalizedSubtag = normalizeLanguageCode(subtag)
            if (matchesNormalizedLanguage(normalizedSubtag, normalizedTarget)) {
                return true
            }
        }
        return false
    }

    private fun matchesNormalizedLanguage(
        normalizedLanguage: String,
        normalizedTarget: String
    ): Boolean {
        if (normalizedTarget == "pt") {
            return normalizedLanguage == "pt"
        }
        if (normalizedTarget == "es") {
            return normalizedLanguage == "es"
        }
        return normalizedLanguage == normalizedTarget ||
            normalizedLanguage.startsWith("$normalizedTarget-") ||
            normalizedLanguage.startsWith("${normalizedTarget}_")
    }

    fun detectTrackLanguageVariant(language: String?, name: String?, trackId: String?): String {
        val baseLang = normalizeLanguageCode(language ?: "")
        val haystack = listOfNotNull(name, language, trackId)
            .joinToString(" ")
            .lowercase()

        if (baseLang == "pt" || baseLang == "por") {
            val hasBrazilian = BRAZILIAN_TAGS.any { haystack.contains(it) }
            val hasEuropean = EUROPEAN_PT_TAGS.any { haystack.contains(it) }
            if (hasBrazilian && !hasEuropean) return "pt-br"
            if (hasEuropean && !hasBrazilian) return "pt"
            return baseLang
        }

        if (baseLang == "es" || baseLang == "spa") {
            val hasLatino = LATINO_TAGS.any { haystack.contains(it) }
            val hasCastilian = CASTILIAN_TAGS.any { haystack.contains(it) }
            if (hasLatino && !hasCastilian) return "es-419"
            if (hasCastilian && !hasLatino) return "es"
            return baseLang
        }

        if (baseLang == "ms" || baseLang == "msa" || baseLang == "may") {
            val hasIndonesian = INDONESIAN_TAGS.any { haystack.contains(it) }
            if (hasIndonesian) return "id"
            return baseLang
        }

        return baseLang
    }

    internal val BRAZILIAN_TAGS = listOf(
        "pt-br", "pt_br", "pob", "brazilian", "brazil", "brasil", "brasileiro", " br", "(br)"
    )
    internal val EUROPEAN_PT_TAGS = listOf(
        "pt-pt", "pt_pt", "iberian", "european", "portugal", "europeu", " eu", "(eu)"
    )
    internal val LATINO_TAGS = listOf(
        "es-419", "es_419", "es-la", "es-lat", "latino", "latinoamerica",
        "latinoamericano", "latam", "lat am", "latin america"
    )
    internal val CASTILIAN_TAGS = listOf(
        "es-es", "es_es", "castilian", "castellano", "spain", "españa", "espana", "iberian"
    )
    internal val INDONESIAN_TAGS = listOf(
        "indonesia", "indonesian", "bahasa indonesia"
    )

    fun mimeTypeFromUrl(url: String): String {
        val normalizedPath = url
            .substringBefore('#')
            .substringBefore('?')
            .trimEnd('/')
            .lowercase()

        return when {
            normalizedPath.endsWith(".srt") -> MimeTypes.APPLICATION_SUBRIP
            normalizedPath.endsWith(".vtt") || normalizedPath.endsWith(".webvtt") -> MimeTypes.TEXT_VTT
            normalizedPath.endsWith(".ass") || normalizedPath.endsWith(".ssa") -> MimeTypes.TEXT_SSA
            normalizedPath.endsWith(".ttml") || normalizedPath.endsWith(".dfxp") -> MimeTypes.APPLICATION_TTML
            else -> MimeTypes.APPLICATION_SUBRIP
        }
    }

    fun sniffSubtitleMimeType(rawText: String, sourceUrl: String = ""): String {
        val text = rawText.replace("\uFEFF", "").trimStart()
        if (text.isEmpty()) return mimeTypeFromUrl(sourceUrl)

        if (text.startsWith("WEBVTT", ignoreCase = true)) {
            return MimeTypes.TEXT_VTT
        }

        val head = text.take(4_000)
        if (
            head.startsWith("[Script Info]", ignoreCase = true) ||
            head.contains("[V4+ Styles]", ignoreCase = true) ||
            head.contains("[V4 Styles]", ignoreCase = true) ||
            Regex("""(?im)^\s*Dialogue:""").containsMatchIn(head)
        ) {
            return MimeTypes.TEXT_SSA
        }

        val lowerHead = head.lowercase()
        if (
            (lowerHead.startsWith("<?xml") || lowerHead.contains("<tt ")) &&
            (lowerHead.contains("ttml") || lowerHead.contains(":tt") || lowerHead.contains("<tt "))
        ) {
            return MimeTypes.APPLICATION_TTML
        }

        if (
            Regex(
                """(?m)^\d+\s*\r?\n\d{1,2}:\d{2}:\d{2}[,.]\d{1,3}\s*-->\s*\d{1,2}:\d{2}:\d{2}[,.]\d{1,3}"""
            ).containsMatchIn(text.take(800)) ||
            Regex(
                """(?m)^\d{1,2}:\d{2}:\d{2}[,.]\d{1,3}\s*-->\s*\d{1,2}:\d{2}:\d{2}[,.]\d{1,3}"""
            ).containsMatchIn(text.take(400))
        ) {
            return MimeTypes.APPLICATION_SUBRIP
        }

        return mimeTypeFromUrl(sourceUrl)
    }

    fun sidecarMimeCandidates(rawText: String, sourceUrl: String): List<String> {
        val sniffed = sniffSubtitleMimeType(rawText, sourceUrl)
        val fromUrl = mimeTypeFromUrl(sourceUrl)
        return linkedSetOf(
            sniffed,
            fromUrl,
            MimeTypes.APPLICATION_SUBRIP,
            MimeTypes.TEXT_VTT,
            MimeTypes.TEXT_SSA,
            MimeTypes.APPLICATION_TTML
        ).toList()
    }

    fun mergeOverlappingCues(cues: List<Cue>): List<Cue> {
        if (cues.size <= 1 || !cues.all { it.bitmap == null && it.line == Cue.DIMEN_UNSET }) {
            return cues
        }
        val validTexts = cues.mapNotNull { it.text }.filter { it.isNotBlank() }
        if (validTexts.isEmpty()) return emptyList()

        val hasSpanned = validTexts.any { it is Spanned }
        val mergedText: CharSequence = if (hasSpanned) {
            val builder = SpannableStringBuilder()
            for (i in validTexts.indices) {
                if (i > 0) builder.append('\n')
                builder.append(validTexts[i])
            }
            builder
        } else {
            validTexts.distinct().joinToString("\n")
        }
        return listOf(cues[0].buildUpon().setText(mergedText).build())
    }
}

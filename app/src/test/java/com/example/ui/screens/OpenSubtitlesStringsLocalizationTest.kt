package com.example.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Coerenza delle stringhe della sezione OpenSubtitles (Impostazioni → Sottotitoli):
 * tutte le lingue supportate (it/en/de/es/fr) devono definire lo stesso insieme di
 * chiavi `settings_opensubtitles*`, con `action_show_key`/`action_hide_key` del popup.
 */
class OpenSubtitlesStringsLocalizationTest {

  private val locales = listOf("values", "values-en", "values-de", "values-es", "values-fr")

  private val requiredKeys = listOf(
    "settings_opensubtitles_api_key",
    "settings_opensubtitles_key_hint",
    "settings_opensubtitles_verify_connection",
    "settings_opensubtitles_connected",
    "settings_opensubtitles_verify_error",
    "settings_opensubtitles_network_error",
    "settings_opensubtitles_http_error",
    "settings_opensubtitles_missing_desc",
    "settings_opensubtitles_key_not_valid_desc",
    "action_show_key",
    "action_hide_key"
  )

  private fun resRoot(): File? =
    listOf(File("src/main/res"), File("app/src/main/res")).firstOrNull { it.isDirectory }

  private fun stringsFile(locale: String) = File(resRoot(), "$locale/strings.xml")

  private fun readStrings(file: File): Map<String, String> =
    Regex("""<string\s+name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
      .findAll(file.readText())
      .associate { it.groupValues[1] to it.groupValues[2] }

  @Test
  fun ogniLinguaDefinisceTutteLeStringheOpenSubtitles() {
    val root = resRoot()
    assumeTrue("Cartella delle risorse non raggiungibile", root != null)

    locales.forEach { locale ->
      val strings = readStrings(stringsFile(locale))
      requiredKeys.forEach { key ->
        assertTrue(
          "Chiave '$key' mancante in $locale",
          strings.containsKey(key) && strings.getValue(key).isNotBlank()
        )
      }
    }
  }

  @Test
  fun nessunaStringaOpenSubtitlesContienePlaceholderDiFormatoErrati() {
    val root = resRoot()
    assumeTrue("Cartella delle risorse non raggiungibile", root != null)

    val default = readStrings(stringsFile("values"))
    val defaults = default.keys.filter { it.startsWith("settings_opensubtitles") }
    assertTrue(defaults.isNotEmpty())

    locales.drop(1).forEach { locale ->
      val strings = readStrings(stringsFile(locale))
      defaults.forEach { key ->
        assertEquals(
          "Set di placeholder non allineato per $key in $locale",
          Regex("""%\d+\$[sd]""").findAll(default.getValue(key)).map { it.value }.sorted().toList(),
          Regex("""%\d+\$[sd]""").findAll(strings[key].orEmpty()).map { it.value }.sorted().toList()
        )
      }
    }
  }

  @Test
  fun iMessaggiDiErroreNonCitanoEndpointNonUfficiali() {
    val root = resRoot()
    assumeTrue("Cartella delle risorse non raggiungibile", root != null)

    locales.forEach { locale ->
      val strings = readStrings(stringsFile(locale))
      requiredKeys.filter { it.startsWith("settings_opensubtitles") }.forEach { key ->
        val value = strings.getValue(key)
        assertTrue(
          "'$key' in $locale non deve contenere endpoint inventati",
          !value.contains("/api/") && !value.contains("http://")
        )
      }
    }
  }
}

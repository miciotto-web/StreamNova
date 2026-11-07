package com.example.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Coerenza delle stringhe della nuova "Aspetto dei sottotitoli" (dimensioni, sfondi,
 * opacità) nelle cinque lingue supportate.
 *
 * La stessa preferenza è mostrata sia nelle Impostazioni generali sia nel menu
 * Sottotitoli del player: una chiave mancante in una lingua produrrebbe un'etichetta
 * vuota in una delle due schermate.
 */
class SubtitleAppearanceStringsLocalizationTest {

  private val locales = listOf("values", "values-en", "values-de", "values-es", "values-fr")

  private val requiredKeys = listOf(
    "settings_subtitle_opacity",
    "player_subtitles_appearance",
    "sub_label_size",
    "sub_label_background",
    "sub_label_opacity",
    "sub_size_extra_small",
    "sub_size_small",
    "sub_size_medium",
    "sub_size_large",
    "sub_size_very_large",
    "sub_bg_none",
    "sub_bg_black",
    "sub_bg_white",
    "sub_bg_blue",
    "sub_opacity_full",
    "sub_opacity_high",
    "sub_opacity_medium",
    "sub_opacity_low"
  )

  private fun resRoot(): File? =
    listOf(File("src/main/res"), File("app/src/main/res")).firstOrNull { it.isDirectory }

  private fun stringsFile(locale: String) = File(resRoot(), "$locale/strings.xml")

  private fun readStrings(file: File): Map<String, String> =
    Regex("""<string\s+name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
      .findAll(file.readText())
      .associate { it.groupValues[1] to it.groupValues[2] }

  @Test
  fun ogniLinguaDefinisceTutteLeStringheDiAspetto() {
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
  fun lePercentualiDiOpacitaSonoIdenticheInTutteLeLingue() {
    val root = resRoot()
    assumeTrue("Cartella delle risorse non raggiungibile", root != null)

    val default = readStrings(stringsFile("values"))
    listOf(
      "sub_opacity_full" to "100%",
      "sub_opacity_high" to "75%",
      "sub_opacity_medium" to "50%",
      "sub_opacity_low" to "25%"
    ).forEach { (key, expected) ->
      assertEquals("Valore inatteso per $key nella lingua di default", expected, default[key])
      locales.drop(1).forEach { locale ->
        assertEquals(
          "Valore inatteso per $key in $locale",
          expected,
          readStrings(stringsFile(locale))[key]
        )
      }
    }
  }
}

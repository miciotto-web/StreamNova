package com.example.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Localizzazione del fallback TMDB per i generi e del relativo switch.
 *
 * Tutte le lingue supportate devono dichiarare le stesse chiavi con gli stessi
 * segnaposto: un messaggio presente solo in italiano lascerebbe le altre lingue mute
 * (o, peggio, con un `%1$s` non sostituito) proprio nei casi in cui serve spiegare
 * perché una categoria è vuota.
 *
 * Il test legge i file XML da disco; se la cartella delle risorse non è raggiungibile
 * dalla working directory del test viene saltato (non fallito).
 */
class TmdbGenreFallbackStringsTest {

  private val locales = listOf("values", "values-en", "values-de", "values-es", "values-fr")

  private fun resRoot(): File? =
    listOf(File("src/main/res"), File("app/src/main/res")).firstOrNull { it.isDirectory }

  private fun readStrings(file: File): Map<String, String> =
    Regex("""<string\s+name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
      .findAll(file.readText())
      .associate { it.groupValues[1] to it.groupValues[2] }

  private fun placeholders(value: String): List<String> =
    Regex("""%\d+\$[sd]""").findAll(value).map { it.value }.sorted().toList()

  private val requiredKeys = listOf(
    "settings_tmdb_genre_fallback",
    "settings_tmdb_genre_fallback_desc",
    "search_category_tmdb_fallback_reason",
    "search_category_desc_fallback",
    "search_category_tmdb_after_empty_movies",
    "search_category_tmdb_after_empty_series",
    "search_category_tmdb_disabled",
    "search_category_tmdb_unavailable",
    "search_category_empty_type",
    "search_category_failed_type",
    "search_category_type_movies",
    "search_category_type_series"
  )

  @Test
  fun tutteLeLingueDichiaranoLeChiaviDelFallback() {
    val root = resRoot()
    assumeTrue("Cartella delle risorse non raggiungibile", root != null)

    locales.forEach { locale ->
      val strings = readStrings(File(root, "$locale/strings.xml"))
      requiredKeys.forEach { key ->
        assertTrue("Stringa mancante $key in $locale", strings.containsKey(key))
        assertTrue("Stringa vuota $key in $locale", strings.getValue(key).isNotBlank())
      }
    }
  }

  @Test
  fun iSegnapostoSonoAllineatiTraLeLingue() {
    val root = resRoot()
    assumeTrue("Cartella delle risorse non raggiungibile", root != null)

    val default = readStrings(File(root, "values/strings.xml"))
    locales.filter { it != "values" }.forEach { locale ->
      val strings = readStrings(File(root, "$locale/strings.xml"))
      requiredKeys.forEach { key ->
        assertEquals(
          "Segnaposto non allineati per $key in $locale",
          placeholders(default.getValue(key)),
          placeholders(strings.getValue(key))
        )
      }
    }
  }

  @Test
  fun loSwitchUsaIlTestoRichiesto() {
    val root = resRoot()
    assumeTrue("Cartella delle risorse non raggiungibile", root != null)

    val default = readStrings(File(root, "values/strings.xml"))
    assertEquals(
      "Usa TMDB come fallback per i generi",
      default.getValue("settings_tmdb_genre_fallback")
    )
    assertTrue(
      "La descrizione deve spiegare quando TMDB viene usato",
      default.getValue("settings_tmdb_genre_fallback_desc")
        .contains("nessuno dei cataloghi configurati supporta il genere selezionato")
    )
  }
}

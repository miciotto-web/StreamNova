package com.example.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Coerenza delle risorse di localizzazione del sistema aggiornamenti: tutte le
 * lingue presenti devono definire lo stesso insieme di chiavi `settings_update*`
 * con gli stessi segnaposto di formattazione.
 *
 * Il test legge i file XML da disco; se la cartella delle risorse non è
 * raggiungibile dalla working directory del test viene saltato (non fallito).
 */
class UpdateStringsLocalizationTest {

  private val defaultLocale = "values"
  private val locales = listOf("values", "values-en", "values-de", "values-es", "values-fr")

  private fun resRoot(): File? =
    listOf(File("src/main/res"), File("app/src/main/res")).firstOrNull { it.isDirectory }

  private fun stringsFile(locale: String) = File(resRoot(), "$locale/strings.xml")

  private fun readStrings(file: File): Map<String, String> =
    Regex("""<string\s+name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
      .findAll(file.readText())
      .associate { it.groupValues[1] to it.groupValues[2] }

  private fun placeholders(value: String): List<String> =
    Regex("""%\d+\$[sd]""").findAll(value).map { it.value }.sorted().toList()

  @Test
  fun everyLocaleDefinesTheSameUpdateStrings() {
    val root = resRoot()
    assumeTrue("Cartella delle risorse non raggiungibile", root != null)

    val default = readStrings(stringsFile(defaultLocale))
    val updateKeys = default.keys.filter { it.startsWith("settings_update") }.toSortedSet()
    assertTrue("Nessuna stringa settings_update* nel locale predefinito", updateKeys.isNotEmpty())

    locales.forEach { locale ->
      val strings = readStrings(stringsFile(locale))
      val localeKeys = strings.keys.filter { it.startsWith("settings_update") }.toSortedSet()
      assertEquals(
        "Chiavi settings_update* non allineate in $locale",
        updateKeys,
        localeKeys
      )
    }
  }

  @Test
  fun updateStringsKeepTheSameFormatPlaceholdersAcrossLocales() {
    val root = resRoot()
    assumeTrue("Cartella delle risorse non raggiungibile", root != null)

    val default = readStrings(stringsFile(defaultLocale))
    val updateKeys = default.keys.filter { it.startsWith("settings_update") }

    locales.filter { it != defaultLocale }.forEach { locale ->
      val strings = readStrings(stringsFile(locale))
      updateKeys.forEach { key ->
        val expected = placeholders(default.getValue(key))
        val actual = placeholders(strings[key].orEmpty())
        assertEquals(
          "Segnaposto non allineati per $key in $locale",
          expected,
          actual
        )
      }
    }
  }

  @Test
  fun promptStringsOfTheStartupPopupAreLocalized() {
    val root = resRoot()
    assumeTrue("Cartella delle risorse non raggiungibile", root != null)

    // Il popup del controllo automatico deve parlare la lingua dell'utente: titolo e
    // scelte «Aggiorna» / «Più tardi» non possono restare in italiano per chi usa
    // l'app in un'altra lingua.
    val required = listOf(
      "settings_update_prompt_title",
      "settings_update_prompt_update",
      "settings_update_prompt_later"
    )

    locales.forEach { locale ->
      val strings = readStrings(stringsFile(locale))
      required.forEach { key ->
        assertTrue("Stringa mancante $key in $locale", strings.containsKey(key))
        assertTrue("Stringa vuota $key in $locale", strings.getValue(key).isNotBlank())
      }
    }
  }

  @Test
  fun integrityAndInstallerErrorsAreLocalized() {
    val root = resRoot()
    assumeTrue("Cartella delle risorse non raggiungibile", root != null)

    val required = listOf(
      "settings_update_prompt_title",
      "settings_update_prompt_update",
      "settings_update_prompt_later",
      "settings_update_dl_error_size",
      "settings_update_dl_error_checksum",
      "settings_update_install_failed_file",
      "settings_update_install_failed_permission",
      "settings_update_install_failed_generic",
      "settings_update_changelog",
      "settings_update_mismatch",
      "settings_update_error_rate_limited",
      "settings_update_error_not_found",
      "settings_update_error_network",
      "settings_update_error_invalid_release",
      "settings_update_error_no_compatible_apk"
    )

    locales.forEach { locale ->
      val strings = readStrings(stringsFile(locale))
      required.forEach { key ->
        assertTrue("Stringa mancante $key in $locale", strings.containsKey(key))
        assertTrue("Stringa vuota $key in $locale", strings.getValue(key).isNotBlank())
      }
    }
  }
}

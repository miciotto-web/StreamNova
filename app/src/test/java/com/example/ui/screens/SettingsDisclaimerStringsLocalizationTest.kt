package com.example.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Coerenza delle stringhe del disclaimer ufficiale (Impostazioni → Informazioni
 * legali): tutte le lingue supportate (it/en/de/es/fr) devono definire lo stesso
 * insieme di chiavi `settings_disclaimer*`.
 *
 * La lingua italiana porta il TESTO UFFICIALE del documento: i paragrafi chiave
 * (non ospite/possesso, pirateria, responsabilità dell'utente, natura di
 * strumento tecnologico) devono restare verbatim, senza abbreviazioni.
 * Le altre lingue non devono attenuare le clausole su pirateria, responsabilità
 * dell'utente e diritti d'autore: il test verifica la presenza dei concetti
 * chiave in ogni lingua.
 */
class SettingsDisclaimerStringsLocalizationTest {

  private val locales = listOf("values", "values-en", "values-de", "values-es", "values-fr")

  private val requiredKeys = listOf(
    "settings_disclaimer_entry",
    "settings_disclaimer_title",
    "settings_disclaimer_p1",
    "settings_disclaimer_p2",
    "settings_disclaimer_p3",
    "settings_disclaimer_p4",
    "settings_disclaimer_p5",
    "settings_disclaimer_p6",
    "settings_disclaimer_p7"
  )

  /** Concetti che NESSUNA lingua può perdere o attenuare (pirateria, responsabilità). */
  private val mandatoryConceptsPerLocale = mapOf(
    // p3: clausola sulla pirateria.
    "settings_disclaimer_p3" to listOf(
      "values" to "pirateria",
      "values-en" to "piracy",
      "values-de" to "Piraterie",
      "values-es" to "piratería",
      "values-fr" to "piratage"
    ),
    // p4: responsabilità SOLO dell'utente.
    "settings_disclaimer_p4" to listOf(
      "values" to "unico responsabile",
      "values-en" to "solely responsible",
      "values-de" to "allein verantwortlich",
      "values-es" to "único responsable",
      "values-fr" to "unique responsable"
    ),
    // p7: chiusura sul ruolo di strumento tecnologico e responsabilità in capo all'utente.
    "settings_disclaimer_p7" to listOf(
      "values" to "responsabilit",
      "values-en" to "responsibility",
      "values-de" to "Verantwortung",
      "values-es" to "responsabilidad",
      "values-fr" to "responsabilité"
    )
  )

  private fun resRoot(): File? =
    listOf(File("src/main/res"), File("app/src/main/res")).firstOrNull { it.isDirectory }

  private fun stringsFile(locale: String) = File(resRoot(), "$locale/strings.xml")

  private fun readStrings(file: File): Map<String, String> =
    Regex("""<string\s+name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
      .findAll(file.readText())
      .associate { it.groupValues[1] to it.groupValues[2] }

  @Test
  fun ogniLinguaDefinisceTutteLeStringheDisclaimer() {
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
  fun ilTestoItalianoPortaIlDocumentoUfficialeSenzaAbbreviazioni() {
    val root = resRoot()
    assumeTrue("Cartella delle risorse non raggiungibile", root != null)

    val strings = readStrings(stringsFile("values"))
    // Frasi chiave del testo ufficiale (sottostringhe senza apostrofi: nel file le
    // apostrofi sono escaped come \' e i segmenti <b> sono markup, non testo).
    assertTrue(
      "p1: definizione di contenitore/interfaccia tecnologica assente",
      strings.getValue("settings_disclaimer_p1")
        .contains("contenitore e interfaccia tecnologica")
    )
    assertTrue(
      "p2: clausola di non ospite/possesso/controllo assente",
      strings.getValue("settings_disclaimer_p2")
        .contains("non ospita, non possiede, non produce, non distribuisce e non controlla")
    )
    assertTrue(
      "p3: clausola sulla pirateria assente o attenuata",
      strings.getValue("settings_disclaimer_p3").contains("pirateria")
    )
    assertTrue(
      "p4: clausola di responsabilità esclusiva dell'utente assente",
      strings.getValue("settings_disclaimer_p4").contains("unico responsabile delle sorgenti")
    )
    assertTrue(
      "p7: chiusura «strumento tecnologico» assente",
      strings.getValue("settings_disclaimer_p7").contains("strumento tecnologico")
    )
    assertTrue(
      "p7: responsabilità in capo all'utente assente",
      strings.getValue("settings_disclaimer_p7").contains("rimane in capo")
    )
  }

  @Test
  fun nessunaLinguaAttenuaLeClausoleSuPirateriaOResponsabilita() {
    val root = resRoot()
    assumeTrue("Cartella delle risorse non raggiungibile", root != null)

    mandatoryConceptsPerLocale.forEach { (key, expectations) ->
      locales.forEach { locale ->
        val expected = expectations.first { it.first == locale }.second
        val value = readStrings(stringsFile(locale)).getValue(key)
        assertTrue(
          "'$key' in $locale non contiene il concetto obbligatorio '$expected'",
          value.contains(expected, ignoreCase = true)
        )
      }
    }
  }

  @Test
  fun nessunaStringaDisclaimerContienePlaceholderDiFormato() {
    val root = resRoot()
    assumeTrue("Cartella delle risorse non raggiungibile", root != null)

    // Il testo del disclaimer è un documento fisso: nessuna chiave deve portare
    // argomenti di formato (una traduzione con %s segnalerebbe una stringa riadattata).
    locales.forEach { locale ->
      readStrings(stringsFile(locale)).filterKeys { it.startsWith("settings_disclaimer") }
        .forEach { (key, value) ->
          assertTrue(
            "'$key' in $locale non deve contenere placeholder di formato",
            !Regex("""%(\d+\$)?[sdf]""").containsMatchIn(value)
          )
        }
    }
  }

  @Test
  fun ilMarkupDeiSegmentiInEvidenzaECoevoleInTutteLeLingue() {
    val root = resRoot()
    assumeTrue("Cartella delle risorse non raggiungibile", root != null)

    // p2, p4 e p7 portano il markup <b>: deve aprirsi e chiudersi in modo bilanciato
    // e il tag deve restare APERTO e CHIUSO (uno span valido da renderizzare).
    locales.forEach { locale ->
      val strings = readStrings(stringsFile(locale))
      listOf("settings_disclaimer_p2", "settings_disclaimer_p4", "settings_disclaimer_p7")
        .forEach { key ->
          val value = strings.getValue(key)
          assertEquals(
            "Markup <b> sbilanciato per '$key' in $locale",
            Regex("""<b>""").findAll(value).count(),
            Regex("""</b>""").findAll(value).count()
          )
          assertTrue(
            "'$key' in $locale deve contenere il markup dei segmenti in evidenza",
            value.contains("<b>")
          )
        }
    }
  }
}

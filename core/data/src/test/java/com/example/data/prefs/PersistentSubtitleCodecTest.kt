package com.example.data.prefs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifica il codec JSON usato per conservare le selezioni OpenSubtitles.
 *
 * Il codec è separato dal DataStore proprio per poter essere verificato in una JVM
 * pura: il formato è già presente sui dispositivi e non deve regredire.
 */
class PersistentSubtitleCodecTest {

  private fun selection(
    contentId: String = "tmdb:123",
    fileId: Long = 42L,
    lang: String = "it",
    label: String? = "Italiano",
    localFilePath: String? = "/data/user/0/app/files/online_subtitles/os_42.srt",
    timestamp: Long = 1_700_000_000_000L
  ) = PersistentSubtitleSelection(
    contentId = contentId,
    fileId = fileId,
    lang = lang,
    label = label,
    localFilePath = localFilePath,
    timestamp = timestamp
  )

  @Test
  fun roundTripConservaTuttiICampi() {
    val selections = mapOf(
      "tmdb:123" to selection(),
      "tt0944947::season:1:episode:5" to selection(
        contentId = "tt0944947::season:1:episode:5",
        fileId = 987654321L,
        lang = "en",
        label = "English [SDH]",
        localFilePath = "/data/user/0/app/files/online_subtitles/os_987654321.vtt",
        timestamp = 1_800_000_000_123L
      )
    )

    val decoded = PersistentSubtitleCodec.decode(PersistentSubtitleCodec.encode(selections))

    assertEquals(selections, decoded)
  }

  @Test
  fun roundTripGestisceCaratteriSpecialiInEtichettaEPercorso() {
    val label = "Italiano \"forzato\"\nseconda riga\\path"
    val path = "/data/user/0/app/files/online_subtitles/sub \"speciale\".srt"
    val original = mapOf(
      "tmdb:42" to selection(label = label, localFilePath = path)
    )

    val decoded = PersistentSubtitleCodec.decode(PersistentSubtitleCodec.encode(original))

    assertEquals(label, decoded.getValue("tmdb:42").label)
    assertEquals(path, decoded.getValue("tmdb:42").localFilePath)
  }

  @Test
  fun mappaVuotaProduceOggettoVuoto() {
    assertEquals("{}", PersistentSubtitleCodec.encode(emptyMap()))
    assertTrue(PersistentSubtitleCodec.decode("{}").isEmpty())
    assertTrue(PersistentSubtitleCodec.decode("").isEmpty())
    assertTrue(PersistentSubtitleCodec.decode(null).isEmpty())
  }

  @Test
  fun unRecordIncompletoNonInvalidaGliAltri() {
    val json = """
      {
        "tmdb:1":{"fileId":10,"lang":"it","label":"Italiano","localFilePath":"","timestamp":1000},
        "tmdb:2":{"fileId":20,"lang":"en"},
        "tmdb:3":{"fileId":30,"lang":"fr","label":"Français","localFilePath":"/tmp/a.srt","timestamp":3000}
      }
    """.trimIndent()

    val decoded = PersistentSubtitleCodec.decode(json)

    assertEquals(setOf("tmdb:1", "tmdb:3"), decoded.keys)
    assertEquals(10L, decoded.getValue("tmdb:1").fileId)
    assertEquals(30L, decoded.getValue("tmdb:3").fileId)
    assertNull(decoded["tmdb:2"])
  }

  @Test
  fun decodificaIlFormatoGiaPresenteSulDispositivo() {
    // Formato scritto dalle versioni precedenti: nessun campo nuovo, path assoluto.
    val json = "{\"tmdb:550\":{\"fileId\":424242,\"lang\":\"it\"," +
      "\"label\":\"Italiano\",\"localFilePath\":\"/data/user/0/com.example/files/online_subtitles/os_424242.srt\"," +
      "\"timestamp\":1730000000000}}"

    val decoded = PersistentSubtitleCodec.decode(json).getValue("tmdb:550")

    assertEquals(424242L, decoded.fileId)
    assertEquals("it", decoded.lang)
    assertEquals("Italiano", decoded.label)
    assertEquals(
      "/data/user/0/com.example/files/online_subtitles/os_424242.srt",
      decoded.localFilePath
    )
  }

  @Test
  fun campoLocalFilePathVuotoDiventaNull() {
    val json = "{\"tmdb:7\":{\"fileId\":7,\"lang\":\"de\",\"label\":\"\",\"localFilePath\":\"\",\"timestamp\":7}}"

    val decoded = PersistentSubtitleCodec.decode(json).getValue("tmdb:7")

    assertNull(decoded.label)
    assertNull(decoded.localFilePath)
  }

  @Test
  fun jsonCorrottoRestituisceMappaVuotaSenzaEccezioni() {
    assertTrue(PersistentSubtitleCodec.decode("{non-json").isEmpty())
    assertTrue(PersistentSubtitleCodec.decode("{\"a\":{\"fileId\":1}").isEmpty())
  }
}

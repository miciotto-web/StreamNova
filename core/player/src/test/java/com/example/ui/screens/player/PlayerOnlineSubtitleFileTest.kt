package com.example.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * Regressione della validazione del file di sottotitolo scaricato da OpenSubtitles.
 *
 * Un file non valido (percorso errato, file vuoto, pagina HTML al posto del
 * sottotitolo) era la CAUSA del fallimento della traccia esterna: allegarlo al player
 * faceva fallire il caricamento e il player mostrava un errore di riproduzione.
 */
class PlayerOnlineSubtitleFileTest {

  private fun tempFile(name: String, content: ByteArray): File {
    val dir = File(System.getProperty("java.io.tmpdir"), "streamnova-subtitle-test")
    dir.mkdirs()
    val file = File(dir, name)
    file.writeBytes(content)
    return file
  }

  @Test
  fun fileSrtValidoEAccettato() {
    val file = tempFile(
      "valid.srt",
      "1\n00:00:01,000 --> 00:00:03,000\nCiao\n\n".toByteArray()
    )

    assertEquals(PlayerOnlineSubtitleFile.Validity.OK, PlayerOnlineSubtitleFile.validate(file))
  }

  @Test
  fun fileWebVttValidoEAccettato() {
    val file = tempFile(
      "valid.vtt",
      "WEBVTT\n\n00:00:01.000 --> 00:00:03.000\nCiao\n".toByteArray()
    )

    assertEquals(PlayerOnlineSubtitleFile.Validity.OK, PlayerOnlineSubtitleFile.validate(file))
  }

  @Test
  fun fileAssValidoEAccettato() {
    val file = tempFile(
      "valid.ass",
      "[Script Info]\nTitle: prova\n\n[V4+ Styles]\n".toByteArray()
    )

    assertEquals(PlayerOnlineSubtitleFile.Validity.OK, PlayerOnlineSubtitleFile.validate(file))
  }

  @Test
  fun fileVuotoERifiutato() {
    val file = tempFile("empty.srt", ByteArray(0))

    assertEquals(PlayerOnlineSubtitleFile.Validity.EMPTY, PlayerOnlineSubtitleFile.validate(file))
  }

  @Test
  fun fileInesistenteERifiutato() {
    val file = File(System.getProperty("java.io.tmpdir"), "streamnova-does-not-exist.srt")

    assertEquals(PlayerOnlineSubtitleFile.Validity.MISSING, PlayerOnlineSubtitleFile.validate(file))
  }

  @Test
  fun paginaHtmlERifiutata() {
    val file = tempFile(
      "html.srt",
      "<!DOCTYPE html><html><body><p>Not a subtitle</p></body></html>".toByteArray()
    )

    assertEquals(
      PlayerOnlineSubtitleFile.Validity.NOT_A_SUBTITLE,
      PlayerOnlineSubtitleFile.validate(file)
    )
  }

  @Test
  fun percorsoLocaleEstrattoDalUriFile() {
    assertEquals(
      "/data/user/0/com.example/cache/os_42.srt",
      PlayerOnlineSubtitleFile.localPathOf("file:///data/user/0/com.example/cache/os_42.srt")
    )
  }

  @Test
  fun uriNonLocaleNonProduceUnPercorso() {
    assertNull(PlayerOnlineSubtitleFile.localPathOf("https://subs.test/download/os_42.srt"))
    assertNull(PlayerOnlineSubtitleFile.localPathOf("file://"))
  }
}

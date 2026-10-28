package com.example.data.streaming.extractors

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Test offline della decifratura del payload Filemoon.
 *
 * I fixture sono sintetici: nessuna rete, nessun token reale, hostname fittizi
 * `cdn.example`. La chiave AES-256 è costruita a mano, divisa in due metà da
 * 16 byte e collocata nelle posizioni 1-based indicate (le altre parti sono
 * filler da 24 byte, come in produzione). Il `version` usato dai test è
 * quello randomizzato (1..20) restituito dall'API reale.
 */
class FilemoonCryptoTest {

  @Test
  fun coppiaPrevistaDallaVersione() {
    val playback = buildFixture(PLAINTEXT, version = "17", posA = 17, posB = 14)
    val extractor = FilemoonExtractor()

    val prima = extractor.candidateKeyPartPairs(playback.keyParts, playback.version).first()

    assertEquals(listOf(playback.keyParts[16], playback.keyParts[13]), prima)
    assertEquals(PLAINTEXT, extractor.decryptPlayback(playback))
  }

  @Test
  fun versioniDiverse() {
    val extractor = FilemoonExtractor()
    // (versione, posizione della seconda parte 1-based = 31 - versione)
    val casi = listOf(1 to 30, 5 to 26, 16 to 15, 20 to 11)

    for ((versione, seconda) in casi) {
      val playback = buildFixture(
        PLAINTEXT,
        version = versione.toString(),
        posA = versione,
        posB = seconda
      )
      assertEquals("versione $versione", PLAINTEXT, extractor.decryptPlayback(playback))
    }
  }

  @Test
  fun fallbackSenzaVersioneConOrdineDiscendente() {
    // Senza `version` la chiave reale è part[20] || part[11]: l'ordine
    // discendente deve essere tentato, non solo quello ascendente.
    val playback = buildFixture(PLAINTEXT, version = null, posA = 20, posB = 11)
    val candidates = FilemoonExtractor().candidateKeyPartPairs(playback.keyParts, null)

    assertTrue(
      "coppia discendente assente",
      candidates.contains(listOf(playback.keyParts[19], playback.keyParts[10]))
    )
    assertEquals(PLAINTEXT, FilemoonExtractor().decryptPlayback(playback))
  }

  @Test
  fun fallbackSuVersioneNonUtilizzabile() {
    val playback = buildFixture(PLAINTEXT, version = "abc", posA = 13, posB = 18)

    assertEquals(PLAINTEXT, FilemoonExtractor().decryptPlayback(playback))
  }

  /**
   * Fallback sulle parti da 16 byte in assenza di `version`, con le due parti
   * in una posizione che NON è una coppia `(n, 31-n)`: l'unico modo per
   * decifrare è provare le parti lunghe 16 byte in entrambi gli ordini.
   *
   * Layout sintetico (in produzione le parti sono sempre in `n` e `31-n`):
   * serve a isolare la logica del fallback dal giro esaustivo sulle versioni.
   */
  @Test
  fun fallbackPartiDaSediciByteInEntrambiGliOrdini() {
    val playback = buildFixture(PLAINTEXT, version = null, posA = 9, posB = 5)
    val extractor = FilemoonExtractor()

    val candidates = extractor.candidateKeyPartPairs(playback.keyParts, null)

    assertTrue(
      "coppia discendente (9,5) assente",
      candidates.contains(listOf(playback.keyParts[8], playback.keyParts[4]))
    )
    assertTrue(
      "coppia ascendente (5,9) assente",
      candidates.contains(listOf(playback.keyParts[4], playback.keyParts[8]))
    )
    assertEquals(PLAINTEXT, extractor.decryptPlayback(playback))
  }

  @Test
  fun candidatiLimitatiEDeduplicati() {
    val playback = buildFixture(PLAINTEXT, version = "17", posA = 17, posB = 14)

    val candidates = FilemoonExtractor().candidateKeyPartPairs(playback.keyParts, playback.version)

    assertTrue("nessun candidato", candidates.isNotEmpty())
    assertEquals("candidati duplicati", candidates.distinct().size, candidates.size)
    assertTrue("candidati troppi: ${candidates.size}", candidates.size <= 64)
  }

  @Test
  fun partiDiDimensioniNonValide() {
    // Parti da 10 byte: la chiave concatenata non è una lunghezza AES valida.
    val playback = buildFixture(
      PLAINTEXT,
      version = "17",
      posA = 17,
      posB = 14,
      realPartSize = 10
    )

    val error = attesaIOException { FilemoonExtractor().decryptPlayback(playback) }

    assertTrue("messaggio inatteso: ${error.message}", error.message!!.contains("chiave"))
  }

  @Test
  fun payloadMalformato() {
    val playback = buildFixture(
      PLAINTEXT,
      version = "17",
      posA = 17,
      posB = 14,
      rawPayload = "!!!non-base64!!!"
    )

    val error = attesaIOException { FilemoonExtractor().decryptPlayback(playback) }

    assertTrue(
      "messaggio inatteso: ${error.message}",
      error.message!!.contains("payload", ignoreCase = true) &&
        error.message!!.contains("base64url", ignoreCase = true)
    )
  }

  @Test
  fun tagGcmNonValido() {
    // L'ultimo byte del payload è il tag GCM: alterarlo invalida la decifratura.
    val playback = buildFixture(
      PLAINTEXT,
      version = "17",
      posA = 17,
      posB = 14,
      corruptTag = true
    )

    val error = attesaIOException { FilemoonExtractor().decryptPlayback(playback) }

    assertTrue(
      "messaggio inatteso: ${error.message}",
      error.message!!.contains("decifrabil", ignoreCase = true)
    )
  }

  @Test
  fun rispostaSenzaSources() {
    val playback = buildFixture(SENZA_SOURCES, version = "17", posA = 17, posB = 14)

    val error = attesaIOException { FilemoonExtractor().decryptPlayback(playback) }

    assertTrue(
      "messaggio inatteso: ${error.message}",
      error.message!!.contains("sources", ignoreCase = true)
    )
  }

  @Test
  fun payloadCasualeSenzaStruttura() {
    // La sola parola "sources" non è una struttura JSON valida.
    val playback = buildFixture("sources", version = "17", posA = 17, posB = 14)

    val error = attesaIOException { FilemoonExtractor().decryptPlayback(playback) }

    assertTrue(
      "messaggio inatteso: ${error.message}",
      error.message!!.contains("sources", ignoreCase = true)
    )
  }

  @Test
  fun urlFirmatoConEscapeUnicode() {
    // Nel payload reale gli '&' dei token CDN arrivano come \u0026.
    val payload = """{"sources":[{"mime_type":"application/vnd.apple.mpegurl","url":"https://cdn.example/master.m3u8?t=abc\u0026s=1792590249"}]}"""

    assertEquals(
      "https://cdn.example/master.m3u8?t=abc&s=1792590249",
      FilemoonExtractor().pickMediaUrl(payload)
    )
  }

  @Test
  fun escapeSlashNellUrl() {
    val payload = """{"sources":[{"mime_type":"application/vnd.apple.mpegurl","url":"https:\/\/cdn.example\/master.m3u8"}]}"""

    assertEquals(
      "https://cdn.example/master.m3u8",
      FilemoonExtractor().pickMediaUrl(payload)
    )
  }

  @Test
  fun prioritaHlsSuMp4() {
    val payload = """
      {"sources":[
        {"mime_type":"video/mp4","url":"https://cdn.example/video.mp4"},
        {"mime_type":"application/vnd.apple.mpegurl","url":"https://cdn.example/master.m3u8"}
      ]}
    """.trimIndent()

    assertEquals(
      "https://cdn.example/master.m3u8",
      FilemoonExtractor().pickMediaUrl(payload)
    )
  }

  @Test
  fun parteDellaChiaveNonBase64() {
    val playback = buildFixture(
      PLAINTEXT,
      version = "17",
      posA = 17,
      posB = 14,
      brokenPart = 17
    )

    val error = attesaIOException { FilemoonExtractor().decryptPlayback(playback) }

    assertTrue(
      "messaggio inatteso: ${error.message}",
      error.message!!.contains("base64url", ignoreCase = true)
    )
  }

  @Test
  fun ivNonValido() {
    // IV di 8 byte: valido base64url ma non una lunghezza GCM utilizzabile.
    val playback = buildFixture(PLAINTEXT, version = "17", posA = 17, posB = 14)
    val ivCorto = playback.copy(
      iv = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(8) { 0x01 })
    )

    val error = attesaIOException { FilemoonExtractor().decryptPlayback(ivCorto) }

    assertTrue("messaggio inatteso: ${error.message}", error.message!!.contains("IV"))
  }

  // --- helper --------------------------------------------------------------

  /**
   * Payload cifrato sintetico: la chiave da 32 byte è spezzata in due metà da
   * 16 byte poste alle posizioni 1-based [posA] e [posB] di un array di 30
   * parti; le altre sono filler da 24 byte.
   */
  private fun buildFixture(
    plaintext: String,
    version: String?,
    posA: Int,
    posB: Int,
    realPartSize: Int = 16,
    fillerSize: Int = 24,
    corruptTag: Boolean = false,
    brokenPart: Int? = null,
    rawPayload: String? = null
  ): PlaybackPayload {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(KEY, "AES"), GCMParameterSpec(128, IV))
    val encrypted = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
    val encoder = Base64.getUrlEncoder().withoutPadding()
    val parts = MutableList(30) { encoder.encodeToString(ByteArray(fillerSize) { FILLER }) }
    parts[posA - 1] = encoder.encodeToString(KEY.copyOfRange(0, realPartSize))
    parts[posB - 1] = encoder.encodeToString(KEY.copyOfRange(realPartSize, realPartSize * 2))
    brokenPart?.let { parts[it - 1] = "!!!!" }
    val payloadBytes = if (corruptTag) {
      encrypted.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() + 1).toByte() }
    } else {
      encrypted
    }
    return PlaybackPayload(
      version = version,
      keyParts = parts,
      iv = encoder.encodeToString(IV),
      payload = rawPayload ?: encoder.encodeToString(payloadBytes)
    )
  }

  /** Esegue il blocco e restituisce l'[IOException] attesa. */
  private fun attesaIOException(block: () -> Unit): IOException {
    try {
      block()
    } catch (e: IOException) {
      return e
    } catch (e: Exception) {
      fail("attesa IOException, ottenuta ${e.javaClass.simpleName}: ${e.message}")
    }
    fail("attesa IOException, nessuna eccezione")
    error("irraggiungibile")
  }

  private companion object {
    /** Chiave AES-256 sintetica (32 byte). */
    val KEY = ByteArray(32) { (it * 7 + 3).toByte() }
    /** IV AES-GCM sintetico (12 byte). */
    val IV = ByteArray(12) { (it * 5 + 1).toByte() }
    /** Byte di riempimento delle parti non utili. */
    const val FILLER: Byte = 0x5A
    /** Plaintext realistico con una sorgente HLS firmata. */
    val PLAINTEXT =
      """{"sources":[{"quality":"h","label":"720p","mime_type":"application/vnd.apple.mpegurl",""" +
        """"url":"https://cdn.example/master.m3u8?t=abc&s=1792590249","bitrate_kbps":1980}],""" +
        """"tracks":[],"poster_url":"https://img.example/poster.jpg"}"""
    /** Plaintext valido ma privo dell'array `sources`. */
    const val SENZA_SOURCES = """{"tracks":[],"poster_url":"https://img.example/x.jpg"}"""
  }
}

package com.example.data.streaming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Test della normalizzazione della richiesta di riproduzione: userinfo URL →
 * `Authorization: Basic` (es. link Easynews/EasyStream con credenziali
 * nell'URL, che OkHttp scarterebbe lasciando la richiesta senza credenziali).
 *
 * Regole verificate (allineate a `extractUserInfoAuth` di NuvioTV e alla
 * convenzione dei browser):
 *  - credenziali nell'URL → header Basic + URL pulito (path/query/fragment
 *    byte-per-byte);
 *  - `Authorization` già dichiarato dall'addon → conserva l'header dell'addon,
 *    pulisce solo l'URL;
 *  - URL senza userinfo → richiesta IDENTICA (nessuna trasformazione);
 *  - URL malformato o non HTTP → richiesta identica (nessun valore inventato).
 */
class PlaybackRequestNormalizerTest {

  private fun basicOf(value: String): String =
    "Basic " + Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))

  // ── userinfo → Basic ────────────────────────────────────────────────────────

  @Test
  fun credenzialiNellUrlDiventanoHeaderBasic() {
    val url = "https://user:pass@video.easynews.com/2222/4321/abc123.mp4/Title.S01E01.mp4"
    val normalized = PlaybackRequestNormalizer.normalize(url, emptyMap())

    assertEquals(basicOf("user:pass"), normalized.headers["Authorization"])
    assertEquals(
      "l'userinfo deve essere rimosso dall'URL, il resto intatto",
      "https://video.easynews.com/2222/4321/abc123.mp4/Title.S01E01.mp4",
      normalized.url
    )
  }

  @Test
  fun pathQueryEFragmentRestanoIntatti() {
    val url = "https://user:pass@proxy.example:8443/stream?token=abc.def&quality=1080#seg"
    val normalized = PlaybackRequestNormalizer.normalize(url, emptyMap())

    assertEquals(
      "https://proxy.example:8443/stream?token=abc.def&quality=1080#seg",
      normalized.url
    )
  }

  @Test
  fun passwordConCaratteriPercentEncodedVieneDecodificata() {
    val url = "https://user:p%40ssw0rd%2Bx@host.example/dl/file.mp4"
    val normalized = PlaybackRequestNormalizer.normalize(url, emptyMap())

    assertEquals(basicOf("user:p@ssw0rd+x"), normalized.headers["Authorization"])
    assertEquals("https://host.example/dl/file.mp4", normalized.url)
  }

  @Test
  fun userinfoSenzaPasswordDiventaUserConPasswordVuota() {
    val url = "https://onlyuser@host.example/file.mp4"
    val normalized = PlaybackRequestNormalizer.normalize(url, emptyMap())

    assertEquals(basicOf("onlyuser:"), normalized.headers["Authorization"])
    assertEquals("https://host.example/file.mp4", normalized.url)
  }

  @Test
  fun headerAltriRestanoInvariatiEAutorizzazioneAggiuntaInCoda() {
    val url = "https://user:pass@host.example/file.mp4"
    val headers = mapOf("user-agent" to "UA-proxy", "Referer" to "https://ref.example/")
    val normalized = PlaybackRequestNormalizer.normalize(url, headers)

    assertEquals("UA-proxy", normalized.headers["user-agent"])
    assertEquals("https://ref.example/", normalized.headers["Referer"])
    assertEquals(basicOf("user:pass"), normalized.headers["Authorization"])
    assertEquals(3, normalized.headers.size)
  }

  // ── Authorization già dichiarato: l'addon vince ─────────────────────────────

  @Test
  fun autorizzazioneGiaDichiarataNonVieneSovrascritta() {
    val url = "https://user:pass@host.example/file.mp4"
    val headers = mapOf("Authorization" to "Basic ZGVtbjpkZW1v")
    val normalized = PlaybackRequestNormalizer.normalize(url, headers)

    assertEquals("Basic ZGVtbjpkZW1v", normalized.headers["Authorization"])
    assertEquals(
      "l'URL resta pulito anche quando l'header è dell'addon",
      "https://host.example/file.mp4",
      normalized.url
    )
    assertEquals(1, normalized.headers.size)
  }

  @Test
  fun autorizzazioneCaseInsensitiveNonVieneDuplicata() {
    val url = "https://user:pass@host.example/file.mp4"
    val headers = mapOf("authorization" to "Basic ZGVtbjpkZW1v")
    val normalized = PlaybackRequestNormalizer.normalize(url, headers)

    // La chiave dell'addon resta quella dichiarata (l'HTTP è case-insensitive
    // sui nomi header): conta che ci sia UN solo header Authorization e che il
    // valore sia quello dell'addon, non quello derivato dall'userinfo.
    val authEntry = normalized.headers.entries
      .firstOrNull { it.key.equals("Authorization", ignoreCase = true) }
    assertEquals("Basic ZGVtbjpkZW1v", authEntry?.value)
    assertTrue(
      "nessuna seconda chiave Authorization con case diverso",
      normalized.headers.keys.count { it.equals("Authorization", ignoreCase = true) } == 1
    )
  }

  // ── URL senza userinfo: nessuna trasformazione ─────────────────────────────

  @Test
  fun urlSenzaUserinfoRestaBytePerByte() {
    val url = "https://proxy.example/playback?token=eyJhbGciOi.eyJ1&quality=1080"
    val headers = mapOf("user-agent" to "UA")
    val normalized = PlaybackRequestNormalizer.normalize(url, headers)

    assertEquals(url, normalized.url)
    assertEquals(headers, normalized.headers)
  }

  @Test
  fun urlConChiocciolaNelPathNonEUserinfo() {
    // La @ nel path/query non è userinfo: nessuna trasformazione, nessun header.
    val url = "https://proxy.example/stream?redirect=https://other@example.com/x"
    val normalized = PlaybackRequestNormalizer.normalize(url, emptyMap())

    assertEquals(url, normalized.url)
    assertTrue(normalized.headers.isEmpty())
  }

  @Test
  fun urlNullOVuotoRestaNullo() {
    assertNull(PlaybackRequestNormalizer.normalize(null, emptyMap()).url)
    assertEquals("", PlaybackRequestNormalizer.normalize("", emptyMap()).url)
    assertEquals("   ", PlaybackRequestNormalizer.normalize("   ", emptyMap()).url)
  }

  @Test
  fun urlNonHttpNonVieneTrasformato() {
    val url = "magnet:?xt=urn:btih:abc"
    val normalized = PlaybackRequestNormalizer.normalize(url, emptyMap())
    assertEquals(url, normalized.url)
    assertTrue(normalized.headers.isEmpty())
  }

  @Test
  fun urlMalformatoRestaInvariatoSenzaHeader() {
    // URI non analizzabile (spazio non codificato): nessuna trasformazione,
    // nessuna credenziale inventata.
    val url = "https://user:pass@host.example/a b.mp4"
    val normalized = PlaybackRequestNormalizer.normalize(url, emptyMap())

    assertEquals(url, normalized.url)
    assertTrue(normalized.headers.isEmpty())
  }

  @Test
  fun mappaHeaderOriginaleNonVieneMutata() {
    val url = "https://user:pass@host.example/file.mp4"
    val headers = LinkedHashMap<String, String>()
    headers["Referer"] = "https://ref.example/"
    PlaybackRequestNormalizer.normalize(url, headers)

    assertEquals(
      "la mappa passata dal chiamante resta intatta",
      mapOf("Referer" to "https://ref.example/"),
      headers
    )
  }

  // ── helper interni ──────────────────────────────────────────────────────────

  @Test
  fun estrazioneCredenzialiDalSoloAuthority() {
    assertEquals(
      "user:pass",
      PlaybackRequestNormalizer.extractUserinfoCredentials("https://user:pass@host/x")
    )
    assertNull(
      PlaybackRequestNormalizer.extractUserinfoCredentials("https://host/x")
    )
    assertNull(PlaybackRequestNormalizer.extractUserinfoCredentials(null))
    assertNull(PlaybackRequestNormalizer.extractUserinfoCredentials(""))
  }

  @Test
  fun basicHeaderSegueRfc7617() {
    assertEquals(basicOf("u:p"), PlaybackRequestNormalizer.basicAuthHeader("u:p"))
    assertEquals(basicOf("u:"), PlaybackRequestNormalizer.basicAuthHeader("u"))
    assertEquals(basicOf("u:1:2"), PlaybackRequestNormalizer.basicAuthHeader("u:1:2"))
  }
}

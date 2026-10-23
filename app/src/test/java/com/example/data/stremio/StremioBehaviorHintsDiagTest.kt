package com.example.data.stremio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifica la diagnostica temporanea della struttura delle chiavi `behaviorHints`
 * ([StremioAddonRepository.tvVooBehaviorHintsDiag]).
 *
 * Requisito centrale: la riga prodotta espone SOLO nomi di chiavi e conteggi,
 * mai valori (header, cookie, token), URL, titoli o identificativi di contenuto,
 * né il JSON grezzo.
 */
class StremioBehaviorHintsDiagTest {

  private val token = "SECRET-TOKEN-VALUE"
  private val headerValue = "https://secret-ref.example/"

  @Test
  fun nonEsponeValoriHeaderUrlTitoliNeJsonGrezzo() {
    val body = """
      {"streams":[
        {
          "name":"Sorgente",
          "title":"Titolo del canale",
          "url":"https://cdn.example/live/index.m3u8",
          "behaviorHints":{
            "filename":"file.mkv",
            "proxyHeaders":{
              "request":{"Authorization":"Bearer $token","Referer":"$headerValue"},
              "response":{"Content-Type":"application/vnd.apple.mpegurl"}
            }
          }
        }
      ]}
    """.trimIndent()

    val line = StremioAddonRepository.tvVooBehaviorHintsDiag(body)

    assertTrue(line != null)
    // Contenuto consentito: solo struttura e conteggi.
    assertTrue(line!!.contains("behaviorHints=present"))
    assertTrue(line.contains("proxyHeaders=present"))
    assertTrue(line.contains("requestCount=2"))
    assertTrue(line.contains("bhKeys=[filename, proxyHeaders]"))
    assertTrue(line.contains("phKeys=[request, response]"))

    // Nessun valore sensibile, URL, titolo o JSON grezzo.
    assertFalse(line.contains(token))
    assertFalse(line.contains(headerValue))
    assertFalse(line.contains("secret-ref.example"))
    assertFalse(line.contains("cdn.example"))
    assertFalse(line.contains("index.m3u8"))
    assertFalse(line.contains("Titolo del canale"))
    assertFalse(line.contains("Sorgente"))
    assertFalse(line.contains("Bearer"))
    assertFalse(line.contains("Authorization"))
    assertFalse(line.contains("Content-Type"))
    assertFalse(line.contains("\"streams\""))
  }

  @Test
  fun behaviorHintsAssente() {
    val body = """{"streams":[{"url":"https://cdn.example/live/index.m3u8"}]}"""

    val line = StremioAddonRepository.tvVooBehaviorHintsDiag(body)

    assertTrue(line != null)
    assertTrue(line!!.contains("behaviorHints=absent"))
    assertTrue(line.contains("bhKeys=[]"))
    assertTrue(line.contains("proxyHeaders=absent"))
    assertTrue(line.contains("requestCount=n/a"))
  }

  @Test
  fun chiaveNonProtocolloDentroBehaviorHintsVieneMostrataSoloComeNome() {
    val body = """{"streams":[{"behaviorHints":{"headers":{"User-Agent":"$token"}}}]}"""

    val line = StremioAddonRepository.tvVooBehaviorHintsDiag(body)

    assertTrue(line != null)
    assertTrue(line!!.contains("behaviorHints=present"))
    assertTrue(line.contains("bhKeys=[headers]"))
    assertTrue(line.contains("proxyHeaders=absent"))
    assertTrue(line.contains("requestCount=n/a"))
    assertFalse(line.contains(token))
    assertFalse(line.contains("User-Agent"))
  }

  @Test
  fun proxyHeadersSenzaRequest() {
    val body = """{"streams":[{"behaviorHints":{"proxyHeaders":{"response":{}}}}]}"""

    val line = StremioAddonRepository.tvVooBehaviorHintsDiag(body)

    assertTrue(line != null)
    assertTrue(line!!.contains("proxyHeaders=present"))
    assertTrue(line.contains("phKeys=[response]"))
    assertTrue(line.contains("requestCount=n/a"))
  }

  @Test
  fun piuStreamVengonoConsumatiSenzaErroreEsiIspezionaIlPrimo() {
    val body = """
      {"streams":[
        {"behaviorHints":{"proxyHeaders":{"request":{"X-Token":"$token"}}}},
        {"behaviorHints":{"filename":"second.mkv"}}
      ]}
    """.trimIndent()

    val line = StremioAddonRepository.tvVooBehaviorHintsDiag(body)

    assertTrue(line != null)
    assertTrue(line!!.contains("behaviorHints=present"))
    assertTrue(line.contains("bhKeys=[proxyHeaders]"))
    assertTrue(line.contains("requestCount=1"))
    assertFalse(line.contains(token))
    assertFalse(line.contains("second.mkv"))
  }

  @Test
  fun streamsVuotoONonValidoRestituisceRigaONull() {
    val emptyStreams = StremioAddonRepository.tvVooBehaviorHintsDiag("""{"streams":[]}""")
    assertTrue(emptyStreams != null)
    assertTrue(emptyStreams!!.contains("behaviorHints=absent"))

    assertNull(StremioAddonRepository.tvVooBehaviorHintsDiag("non-json"))
    assertNull(StremioAddonRepository.tvVooBehaviorHintsDiag("[1,2,3]"))
  }
}

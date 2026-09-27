package com.example.data.streaming

import com.example.data.streaming.extractors.ExtractorHttp
import com.example.data.streaming.providers.Cb01Provider
import com.example.data.streaming.providers.EurostreamingProvider
import com.example.data.streaming.providers.HosterResolver
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test dei cataloghi italiani su **pagine reali** (richiedono rete):
 * verificano la ricerca, la scelta dell'articolo, l'estrazione dei link hoster
 * e il parsing del blocco episodio per le Serie TV.
 */
class CatalogProviderTest {

  @Test
  fun cb01TrovaArticoloELinkHosterReali(): Unit = runBlocking {
    val provider = Cb01Provider()

    // 1) ricerca reale (stessa usata dal provider)
    val searchUrl = "https://cb01uno.top/?s=Inception%202010"
    val searchHtml = ExtractorHttp.get(searchUrl, referer = "https://cb01uno.top/")
    val articleUrl = provider.pickArticle(searchHtml, "Inception", "https://cb01uno.top")
    assertTrue("articolo CB01 non trovato", articleUrl != null && articleUrl.contains("inception"))

    // 2) parsing dell'articolo: i link hoster devono esserci
    val articleHtml = ExtractorHttp.get(articleUrl!!, referer = "https://cb01uno.top/")
    val links = provider.parseHosterLinks(articleHtml)
    assertTrue("nessun link hoster nell'articolo CB01: $links", links.isNotEmpty())
    assertTrue("manca lo shortener stayonline o il link Mixdrop",
      links.any { it.second.contains("stayonline.pro", ignoreCase = true) || it.second.contains("mixdrop", ignoreCase = true) })

    // 3) risoluzione dello shortener stayonline (POST id=...) verso l'hoster finale
    val mixdrop = links.firstOrNull { it.first.equals("Mixdrop", ignoreCase = true) }
    if (mixdrop != null) {
      val resolved = HosterResolver.resolveShortLink(mixdrop.second, articleUrl!!)
      assertTrue("shortener stayonline non risolto: $resolved",
        resolved != null && (resolved.contains("drop") || resolved.contains("uprot")))
      println("CB01 shortener OK -> $resolved")
    }
    println("CB01 OK -> articolo=$articleUrl link=${links.size}")
  }

  @Test
  fun eurostreamingTrovaBloccoEpisodioSerieReale(): Unit = runBlocking {
    val provider = EurostreamingProvider()
    // Il mirror può rispondere 500 in modo transient: si riprova una volta
    var page: String? = null
    repeat(2) { attempt ->
      if (page == null) {
        page = runCatching {
          ExtractorHttp.get("https://eurostream.mom/breaking-bad-36/", referer = "https://eurostream.mom/")
        }.getOrNull()
        if (page == null && attempt == 0) {
          println("Eurostreaming: primo tentativo fallito, riprovo...")
          delay(1_500)
        }
      }
    }
    assertTrue("pagina serie non raggiungibile (ripetuti errori HTTP)", page != null)
    assertTrue("pagina serie vuota", page!!.length > 10_000)

    val segment = provider.episodeSegment(page, 1, 1)
    assertTrue("blocco episodio S1:E1 non trovato", segment != null)
    assertTrue("blocco senza link hoster", segment!!.contains("uprot.net", ignoreCase = true))
    assertTrue("blocco senza etichetta MaxStream", segment.contains("MaxStream", ignoreCase = true))

    val links = provider.parseHosterLinks(segment)
    assertTrue("nessun link hoster nell'episodio: $links", links.isNotEmpty())
    println("Eurostreaming OK -> S1E1 link=" + links.joinToString { it.first + "->" + it.second })
  }
}

package com.example.data.streaming.extractors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parsing offline di [MixdropExtractor]: nessuna richiesta di rete.
 *
 * I casi coprono le due sintassi storiche del player (`MDCore.file` e
 * `MDCore['file']`), le varianti di spaziatura/virgolette e i documenti
 * malformati o privi di sorgenti.
 */
class MixdropExtractorTest {

  private val extractor = MixdropExtractor()
  private val base = "https://mxdrop.top/e/testcode"

  @Test
  fun estraeUrlDaSintassiConPunto() {
    val html = """
      <script>
        MDCore.file = "https://cdn.example.test/media/master.m3u8";
      </script>
    """.trimIndent()

    assertEquals(
      listOf("https://cdn.example.test/media/master.m3u8"),
      extractor.findMediaUrls(html, base)
    )
  }

  @Test
  fun estraeUrlDaSintassiConParentesiQuadre() {
    val html = """
      <script>
        MDCore['file'] = 'https://cdn.example.test/media/movie.mp4';
      </script>
    """.trimIndent()

    assertEquals(
      listOf("https://cdn.example.test/media/movie.mp4"),
      extractor.findMediaUrls(html, base)
    )
  }

  @Test
  fun accettaSpaziaturaEVirgoletteCoerenti() {
    val html = """
      <script>
        MDCore . vfile   =   'https://cdn.example.test/a/master.m3u8' ;
        MDCore [ "wurl" ] = "https://cdn.example.test/b/master.m3u8";
      </script>
    """.trimIndent()

    assertEquals(
      listOf(
        "https://cdn.example.test/a/master.m3u8",
        "https://cdn.example.test/b/master.m3u8"
      ),
      extractor.findMediaUrls(html, base)
    )
  }

  @Test
  fun risolveUrlRelativiEProtocolRelative() {
    val html = """
      <video src="/media/video.mp4"></video>
      <script>MDCore.file = "//cdn.example.test/media/master.m3u8";</script>
    """.trimIndent()

    assertEquals(
      listOf("https://mxdrop.top/media/video.mp4", "https://cdn.example.test/media/master.m3u8"),
      extractor.findMediaUrls(html, base)
    )
  }

  @Test
  fun htmlMalformatoNonProduceEccezioni() {
    val html = """
      <script>
        MDCore.file = ;
        MDCore['file'] = "senza-chiusura
        MDCore['file'] = 'https://cdn.example.test/ok/master.m3u8';
      </script>
    """.trimIndent()

    val urls = extractor.findMediaUrls(html, base)
    assertEquals(listOf("https://cdn.example.test/ok/master.m3u8"), urls)
  }

  @Test
  fun htmlSenzaSorgentiRestituisceListaVuota() {
    val html = "<html><body><p>File non disponibile</p></body></html>"
    assertTrue(extractor.findMediaUrls(html, base).isEmpty())
  }

  @Test
  fun estraeUrlProtocolRelativeDaVariabileFurl() {
    // I mirror attivi usano MDCore.furl con URL che inizia con "//".
    val html = """<script>MDCore.furl = "//cdn.example.test/media/film.mp4";</script>"""

    assertEquals(
      listOf("https://cdn.example.test/media/film.mp4"),
      extractor.findMediaUrls(html, base)
    )
  }

  @Test
  fun decodificaScriptPackedConDizionarioEUrlProtocolRelative() {
    // Formato Dean Edwards reale dei mirror Mixdrop: il dizionario è il
    // letterale prima di `.split('|')` e la sorgente è protocol-relative.
    // Regressione: il vecchio parser usava il payload come dizionario e
    // cercava solo URL assoluti, quindi non trovava mai la sorgente.
    val html = """
      <script>
        eval(function(p,a,c,k,e,d){e=function(c){return c};if(!''.replace(/^/,String)){while(c--){d[c]=k[c]||c}k=[function(e){return d[e]}];e=function(){return'\\w+'};c=1};while(c--){if(k[c]){p=p.replace(new RegExp('\\b'+e(c)+'\\b','g'),k[c])}}return p}('0.1="//2.3/4";',10,5,'MDCore|wurl|cdn|example|video.mp4'.split('|'),0,{}))
      </script>
    """.trimIndent()

    assertEquals(
      listOf("https://cdn.example/video.mp4"),
      extractor.findMediaUrls(html, base)
    )
  }

  @Test
  fun riconosceLaPaginaFileRimossoSenzaScambiarlaPerUnaChallenge() {
    // Pagina reale dei mirror Mixdrop attivi quando il file non esiste più:
    // contiene il meta csrf e lo script reCAPTCHA, quindi senza questo
    // riconoscimento verrebbe classificata come "protetta da token".
    val html = """
      <html><head>
        <meta name="csrf" content="2af430fa52f6bf2abae35ac921eef73c">
        <script src="https://www.google.com/recaptcha/api.js?render=6Let"></script>
      </head><body>
        <h2>WE ARE SORRY</h2>
        <p>We can't find the video you are looking for.</p>
      </body></html>
    """.trimIndent()

    assertTrue(extractor.isFileMissingPage(html))
    assertTrue("nessun URL media deve essere inventato", extractor.findMediaUrls(html, base).isEmpty())
  }

  @Test
  fun unaPaginaConSorgenteNonVieneClassificataComeRimossa() {
    val html = """
      <script>MDCore.file = "https://cdn.example.test/media/master.m3u8";</script>
    """.trimIndent()

    assertTrue(!extractor.isFileMissingPage(html))
    assertEquals(
      listOf("https://cdn.example.test/media/master.m3u8"),
      extractor.findMediaUrls(html, base)
    )
  }

  @Test
  fun unaSolaDichiarazioneNonVaInIndexOutOfBounds() {
    // Regressione: la vecchia regex aveva un solo gruppo ma il codice leggeva
    // groupValues[2], sollevando IndexOutOfBoundsException su questa pagina.
    val html = """<script>MDCore.file = "https://cdn.example.test/one/master.m3u8";</script>"""

    val urls = extractor.findMediaUrls(html, base)
    assertEquals(listOf("https://cdn.example.test/one/master.m3u8"), urls)
  }
}

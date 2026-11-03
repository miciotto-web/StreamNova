package com.aistudio.streamnova.mobile.ui

/**
 * URL dell'iframe YouTube embedded (mai `youtu.be`/`watch`, che aprirebbero
 * l'app esterna). Gli stessi parametri della TV disabilitano fullscreen nativo,
 * sottotitoli e annotazioni.
 */
internal fun trailerEmbedUrl(youtubeKey: String): String =
  "https://www.youtube.com/embed/$youtubeKey?autoplay=1&enablejsapi=1&fs=0&rel=0" +
    "&playsinline=1&cc_load_policy=0&cc_lang_pref=&iv_load_policy=3"

/**
 * HTML autonomo dell'overlay trailer: un solo IFrame standard a tutto schermo.
 * La pagina viene caricata con base `https://localhost`, senza header Referer
 * spoofati verso YouTube (che li rifiuta).
 */
internal fun trailerEmbedHtml(youtubeKey: String): String = """
<!DOCTYPE html>
<html>
<head>
    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; background-color: #000; }
        html, body { width: 100%; height: 100%; overflow: hidden; background: #000; }
        iframe { width: 100%; height: 100%; border: none; }
    </style>
</head>
<body>
    <iframe
        id="trailerPlayer"
        src="${trailerEmbedUrl(youtubeKey)}"
        allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture"
        allowfullscreen>
    </iframe>
</body>
</html>
""".trimIndent()

/** Base URL neutra usata da `loadDataWithBaseURL` per la pagina dell'overlay. */
internal const val TRAILER_BASE_URL = "https://localhost"

/**
 * true se l'URL può essere caricato nella WebView del trailer.
 *
 * Accetta esclusivamente i domini dell'embed e dei suoi asset multimediali.
 * Qualsiasi altro link viene bloccato: il trailer resta sempre dentro
 * StreamNova e nessun Intent può aprire browser o app esterne.
 */
internal fun isAllowedTrailerUrl(url: String): Boolean {
  val host = url.substringAfter("://").substringBefore("/").substringAfter("@").lowercase()
  return host == "localhost" ||
    host == "youtube.com" ||
    host.endsWith(".youtube.com") ||
    host == "youtube-nocookie.com" ||
    host.endsWith(".youtube-nocookie.com") ||
    host == "googlevideo.com" ||
    host.endsWith(".googlevideo.com") ||
    host == "ytimg.com" ||
    host.endsWith(".ytimg.com") ||
    host == "google.com" ||
    host.endsWith(".google.com")
}

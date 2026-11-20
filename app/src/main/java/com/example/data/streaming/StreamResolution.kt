package com.example.data.streaming

/**
 * Normalizzazione delle denominazioni di risoluzione usate dai provider.
 *
 * I provider non sono coerenti: lo stesso flusso può arrivare come `4K`, `UHD`,
 * `2160p`, `QHD`, `1440p`, `Full HD`, `FHD`, `1080p`, `HD` o `720p`. La
 * gerarchia automatica deve confrontare **altezze numeriche**, non stringhe
 * diverse tra loro: qui ogni etichetta viene ricondotta all'altezza (o alla
 * larghezza equivalente) che rappresenta.
 *
 * Regola fondamentale: una risoluzione non identificabile vale [UNKNOWN]
 * (0). Non viene mai promossa a una qualità superiore per assenza di dati.
 */
object StreamResolution {

  /** Altezza convenzionale per una risoluzione non identificabile. */
  const val UNKNOWN = 0

  const val HEIGHT_2160 = 2160
  const val HEIGHT_1440 = 1440
  const val HEIGHT_1080 = 1080
  const val HEIGHT_720 = 720
  const val HEIGHT_480 = 480

  /**
   * Soglie per classificare una dimensione misurata come 4K/UHD.
   *
   * Coerenti con le dimensioni reali dei video 4K (UHD 3840x2160, DCI 4096x2160)
   * e con le versione widescreen crop/pillar che conservano la larghezza 4K
   * (es. 3840x1600). Le soglie precedenti (larghezza >= 3000) etichettavano
   * "4K" anche varianti QHD ultrawide (es. 3440x1440): il 1440p non è 4K.
   */
  const val MIN_WIDTH_4K = 3800
  const val MIN_HEIGHT_4K = 2100

  /** Etichetta canonica per un'altezza numerica. */
  fun labelForHeight(height: Int): String = when {
    height >= HEIGHT_2160 -> "4K"
    height >= HEIGHT_1440 -> "1440p"
    height >= HEIGHT_1080 -> "1080p"
    height >= HEIGHT_720 -> "720p"
    height >= HEIGHT_480 -> "480p"
    else -> "Auto"
  }

  /**
   * Altezza convenzionale dichiarata da un'etichetta di qualità.
   *
   * @return 2160/1440/1080/720/480, oppure [UNKNOWN] se il testo non contiene
   *         una risoluzione riconoscibile.
   */
  fun heightOf(label: String?): Int {
    val text = label?.trim()?.lowercase().orEmpty()
    if (text.isEmpty()) return UNKNOWN
    return when {
      // 4K/UHD/2160p (il numero può essere seguito dal suffisso "p"/"i").
      text.contains("2160") || containsAnyToken(text, "4k", "uhd") -> HEIGHT_2160
      // 1440p/QHD
      text.contains("1440") || containsAnyToken(text, "qhd") -> HEIGHT_1440
      // 1080p / Full HD / FHD
      text.contains("1080") || containsAnyToken(text, "fhd") ||
        text.contains("full hd") ||
        text.contains("fullhd") -> HEIGHT_1080
      // 720p / HD (solo token isolato: "fhd"/"qhd"/"uhd" sono già stati gestiti)
      text.contains("720") || containsAnyToken(text, "hd") -> HEIGHT_720
      // 480p / SD
      text.contains("480") || containsAnyToken(text, "sd") -> HEIGHT_480
      else -> UNKNOWN
    }
  }

  /**
   * Altezza effettiva di una sorgente: prima `verifiedHeight` (dato misurato),
   * poi la qualità dichiarata, poi la risoluzione originale. Nessuna inferenza
   * oltre questi campi.
   */
  fun heightOf(source: StreamSource): Int =
    source.verifiedHeight
      ?: heightOf(source.declaredQuality)
        .takeIf { it != UNKNOWN }
      ?: heightOf(source.quality)

  /**
   * Altezza convenzionale di una variante HLS dalla coppia `RESOLUTION=WxH`.
   *
   * Le versioni widescreen dichiarano un'altezza molto inferiore a quella
   * nominale (es. 1920x800 per un 1080p): vale la dimensione più alta tra
   * larghezza e altezza. Il 4K richiede dimensioni reali 4K
   * ([MIN_WIDTH_4K]/[MIN_HEIGHT_4K]): un'ultrawide QHD (3440x1440) resta 1440p.
   */
  fun heightOfResolution(width: Int?, height: Int?): Int {
    val w = width ?: 0
    val h = height ?: 0
    return when {
      w >= MIN_WIDTH_4K || h >= MIN_HEIGHT_4K -> HEIGHT_2160
      w >= 2400 || h >= 1400 -> HEIGHT_1440
      w >= 1800 || h >= 1000 -> HEIGHT_1080
      w >= 1100 || h >= 650 -> HEIGHT_720
      w >= 700 -> HEIGHT_480
      else -> UNKNOWN
    }
  }

  /**
   * Etichetta unica per una risoluzione **misurata** (HUD del player, menu
   * Qualità, report diagnostico): un solo punto per le soglie, niente mappe
   * duplicate che derivano "4K" dall'altezza 1440.
   *
   * `w`/`h` sono le dimensioni reali del video (variante selezionata o traccia
   * attiva). Un'altezza non classificabile restituisce "SD" (niente etichette
   * inventate).
   */
  fun labelForMeasured(width: Int, height: Int): String {
    val w = width.coerceAtLeast(0)
    val h = height.coerceAtLeast(0)
    return when {
      h <= 0 && w <= 0 -> "Auto"
      w >= MIN_WIDTH_4K || h >= MIN_HEIGHT_4K -> "4K"
      w >= 2400 || h >= 1400 -> "1440p"
      w >= 1800 || h >= 1000 -> "1080p"
      w >= 1100 || h >= 650 -> "720p"
      else -> "SD"
    }
  }

  private fun containsAnyToken(text: String, vararg tokens: String): Boolean =
    tokens.any { token -> TOKEN_CACHE.getOrPut(token) { tokenRegex(token) }.containsMatchIn(text) }

  private fun tokenRegex(token: String): Regex =
    Regex("(?<![a-z0-9])${Regex.escape(token)}(?![a-z0-9])")

  private val TOKEN_CACHE = java.util.concurrent.ConcurrentHashMap<String, Regex>()
}

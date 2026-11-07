package com.example.data.prefs

/** Layout con cui la Home disegna le card della riga «Continua a guardare». */
enum class ContinueWatchingLayout {
  /** Card 16:9 full-bleed: immagine + testi sovrapposti (layout storico). */
  CARD,

  /** Card orizzontale larga: immagine a sinistra, testi a destra. */
  WIDE,

  /** Locandina 2:3 verticale con testi sovrapposti in basso. */
  POSTER;

  companion object {
    /** Lettura tollerante del valore persistito: un valore ignoto vale il default storico. */
    fun fromStorage(raw: String?): ContinueWatchingLayout =
      values().firstOrNull { it.name == raw } ?: CARD
  }
}

/**
 * Ordinamento della riga «Continua a guardare».
 *
 * Sono esposte solo modalità realmente implementate da
 * [com.example.data.repository.ContinueWatchingRowPolicy]; ampliare l'enum richiede di
 * implementare il criterio nel policy (nessuna voce solo grafica).
 */
enum class ContinueWatchingSort {
  /** Comportamento storico: visione più recente in testa. */
  DEFAULT,

  /** Titolo in ordine alfabetico (A-Z). */
  TITLE,

  /** Voto TMDB decrescente. */
  RATING;

  companion object {
    fun fromStorage(raw: String?): ContinueWatchingSort =
      values().firstOrNull { it.name == raw } ?: DEFAULT
  }
}

/**
 * Preferenze della riga «Continua a guardare» della Home.
 *
 * I default coincidono con il comportamento dell'app PRIMA dell'introduzione della
 * sezione Impostazioni: riga visibile, card 16:9, backdrop (non miniatura episodio),
 * nessuna sfocatura, ripresa dall'episodio guardato più di recente, episodi futuri
 * inclusi (nessun filtro sulle date) e ordinamento per visione più recente.
 *
 * Le preferenze sono persistite da [AppSettingsRepository]: nessuna chiave duplicata e
 * una sola sorgente di verità in memoria.
 */
data class ContinueWatchingPreferences(
  val showRow: Boolean = true,
  val layout: ContinueWatchingLayout = ContinueWatchingLayout.CARD,
  val useEpisodeThumbnails: Boolean = false,
  val blurUnwatched: Boolean = false,
  val nextEpisodeFromFurthest: Boolean = false,
  val showUnairedEpisodes: Boolean = true,
  val sort: ContinueWatchingSort = ContinueWatchingSort.DEFAULT
)

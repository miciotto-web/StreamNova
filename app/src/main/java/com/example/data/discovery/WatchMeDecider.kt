package com.example.data.discovery

import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.search.SmartSearchEngine
import kotlin.math.min
import kotlin.random.Random

/**
 * Filtri opzionali di "Non so cosa guardare".
 *
 * Tutti i campi sono opzionali: `null`/`false` = nessun vincolo. La selezione
 * NON è un random puro: pesa gusti, qualità e stato di visione.
 */
data class WatchMeFilters(
  val type: MediaType? = null,
  val genre: String? = null,
  /** Durata massima per i film (minuti). Null = nessun limite. */
  val maxDurationMinutes: Int? = null,
  val minRating: Float? = 7.0f,
  val onlyWatchlist: Boolean = false,
  val onlyUnwatched: Boolean = true
) {
  companion object {
    /**
     * Default pensato per un suggerimento utile: voto minimo 7, solo titoli mai
     * visti. I filtri restano modificabili nel dialog.
     */
    fun default(): WatchMeFilters = WatchMeFilters(minRating = 7.0f, onlyUnwatched = true)
  }
}

/**
 * Profilo dei gusti derivato dalla libreria dell'utente.
 *
 * @param favoriteGenres conteggio dei generi nei preferiti/completati (normalizzati).
 * @param favoriteDirectors registi dei titoli preferiti (normalizzati).
 * @param watchedIds id dei titoli già visti (esclusi dai suggerimenti).
 */
data class TasteProfile(
  val favoriteGenres: Map<String, Int> = emptyMap(),
  val favoriteDirectors: Set<String> = emptySet(),
  val watchedIds: Set<String> = emptySet()
) {
  val isEmpty: Boolean
    get() = favoriteGenres.isEmpty() && favoriteDirectors.isEmpty() && watchedIds.isEmpty()

  /** Generi preferiti ordinati per frequenza (max [limit]). */
  fun topGenres(limit: Int = 5): List<String> =
    favoriteGenres.entries.sortedByDescending { it.value }.take(limit).map { it.key }
}

/** Costruzione del profilo gusti da preferiti, cronologia e completati. */
object TasteProfileBuilder {

  fun build(items: List<MediaItem>): TasteProfile {
    // I gusti si derivano da TUTTO ciò che l'utente ha scelto o guardato (i
    // preferiti sono una scelta esplicita). Gli "id visti", invece, includono
    // SOLO i titoli con visione reale: un preferito mai aperto deve poter essere
    // proposto (soprattutto con "solo watchlist").
    val interacted = items.filter {
      it.isFavorite || it.isWatched || it.lastWatchedAt != null || it.currentProgressMs > 0L
    }
    val actuallyWatched = items.filter {
      it.isWatched || it.lastWatchedAt != null || it.currentProgressMs > 0L
    }
    val genres = mutableMapOf<String, Int>()
    val directors = mutableSetOf<String>()
    interacted.forEach { item ->
      item.genres.forEach { genre ->
        val key = SmartSearchEngine.normalize(genre)
        if (key.isNotBlank()) genres[key] = (genres[key] ?: 0) + 1
      }
      val director = SmartSearchEngine.normalize(item.director)
      if (director.isNotBlank() && isRealDirectorName(director)) directors += director
    }
    return TasteProfile(
      favoriteGenres = genres,
      favoriteDirectors = directors,
      watchedIds = actuallyWatched.map { it.id }.toSet()
    )
  }

  /**
   * Esclude i placeholder storici dei modelli demo ("produzione internazionale",
   * "showrunner internazionale"): non sono registi reali e non devono influenzare
   * il ranking.
   */
  private fun isRealDirectorName(normalized: String): Boolean =
    normalized.isNotBlank() &&
      !normalized.contains("internazionale") &&
      !normalized.contains("regista") &&
      normalized.length > 2
}

/** Suggerimento con punteggio e motivazioni verificabili. */
data class WatchMeSuggestion(
  val item: MediaItem,
  val score: Int,
  val reasons: List<String>,
  /** `true` quando il titolo proviene dalla watchlist dell'utente. */
  val fromWatchlist: Boolean
)

/**
 * "Non so cosa guardare": selezione intelligente (e riproducibile) di un titolo.
 *
 * Il punteggio combina:
 *  - **affinità di gusti** (generi e registi preferiti, dalla libreria);
 *  - **watchlist** (premiata: è già una scelta dell'utente);
 *  - **voto** (normalizzato 0..10);
 *  - **freschezza** (titoli recenti leggermente favoriti);
 *  - **non visto** (un titolo già visto non viene mai proposto se il filtro è attivo).
 *
 * La scelta finale avviene tra i migliori [TOP_POOL] con un random PESATO e
 * seminato ([seed]): stessa libreria + stessi filtri + stesso seed = stesso
 * risultato (test deterministici), ma con un seed diverso il suggerimento cambia
 * — non è un semplice massimo fisso, che proporrebbe sempre lo stesso titolo.
 */
object WatchMeDecider {

  /** Dimensione del gruppo tra cui avviene l'estrazione pesata. */
  const val TOP_POOL = 8

  /** Tetto massimo di candidati restituiti da [candidates]. */
  const val MAX_CANDIDATES = 60

  fun pick(
    items: List<MediaItem>,
    filters: WatchMeFilters = WatchMeFilters.default(),
    taste: TasteProfile = TasteProfile(),
    seed: Long = System.currentTimeMillis()
  ): WatchMeSuggestion? = candidates(items, filters, taste, seed).firstOrNull()

  /**
   * Candidati ordinati per punteggio (poi estrazione pesata per [pick]).
   * Il filtro di durata si applica ai film; per le serie non esiste un dato
   * affidabile di durata totale, quindi il vincolo non le esclude.
   */
  fun candidates(
    items: List<MediaItem>,
    filters: WatchMeFilters = WatchMeFilters.default(),
    taste: TasteProfile = TasteProfile(),
    seed: Long = System.currentTimeMillis()
  ): List<WatchMeSuggestion> {
    val eligible = items
      .filter { matchesFilters(it, filters, taste) }
      .map { score(it, filters, taste) }
      .sortedWith(
        compareByDescending<WatchMeSuggestion> { it.score }
          .thenByDescending { it.item.rating }
          .thenBy { it.item.title.lowercase() }
      )
      .take(MAX_CANDIDATES)

    if (eligible.isEmpty()) return emptyList()
    // Estrazione pesata tra i migliori: score alto = più probabile, non certo.
    val pool = eligible.take(TOP_POOL)
    val random = Random(seed)
    val weights = pool.map { (it.score.coerceAtLeast(1)) }
    val total = weights.sum()
    var roll = random.nextInt(total)
    var chosenIndex = 0
    for (index in weights.indices) {
      roll -= weights[index]
      if (roll < 0) {
        chosenIndex = index
        break
      }
    }
    return listOf(pool[chosenIndex]) + eligible.filterIndexed { index, _ -> index != chosenIndex }
  }

  /** "Scopri qualcosa di nuovo": titolo con voto alto e mai visto, casuale. */
  fun discover(
    items: List<MediaItem>,
    taste: TasteProfile = TasteProfile(),
    seed: Long = System.currentTimeMillis()
  ): WatchMeSuggestion? = pick(
    items = items,
    filters = WatchMeFilters(
      minRating = 7.0f,
      onlyUnwatched = true
    ),
    taste = taste,
    seed = seed
  )

  fun matchesFilters(
    item: MediaItem,
    filters: WatchMeFilters,
    taste: TasteProfile
  ): Boolean {
    if (filters.type != null && item.type != filters.type) return false
    if (filters.onlyWatchlist && !item.isFavorite) return false
    if (filters.onlyUnwatched) {
      if (item.currentProgressMs > 0L || item.lastWatchedAt != null ||
        item.isWatched || item.id in taste.watchedIds
      ) {
        return false
      }
    }
    if (filters.minRating != null && item.rating < filters.minRating) return false
    if (filters.type == MediaType.FILM && filters.maxDurationMinutes != null) {
      if (item.durationMinutes > filters.maxDurationMinutes) return false
    }
    val genre = filters.genre
    if (!genre.isNullOrBlank()) {
      val normalized = SmartSearchEngine.normalize(genre)
      if (item.genres.none {
          SmartSearchEngine.normalize(it).let { g -> g.contains(normalized) || normalized.contains(g) }
        }
      ) {
        return false
      }
    }
    return true
  }

  /** Punteggio + motivazioni (ogni voce è un dato reale del titolo/profilo). */
  fun score(
    item: MediaItem,
    filters: WatchMeFilters,
    taste: TasteProfile
  ): WatchMeSuggestion {
    val reasons = mutableListOf<String>()
    var score = 0

    val rating = item.rating.coerceIn(0f, 10f)
    score += (rating * 6).toInt()
    if (rating >= 8f) reasons += "Voto alto (${"%.1f".format(rating)})"

    val matchedGenres = item.genres.filter { genre ->
      val key = SmartSearchEngine.normalize(genre)
      taste.favoriteGenres.keys.any { it.contains(key) || key.contains(it) }
    }
    if (matchedGenres.isNotEmpty()) {
      val bonus = min(30, matchedGenres.size * 12)
      score += bonus
      reasons += "Genere che guardi spesso: ${matchedGenres.joinToString(", ")}"
    }

    if (taste.favoriteDirectors.isNotEmpty()) {
      val director = SmartSearchEngine.normalize(item.director)
      if (director.isNotBlank() && taste.favoriteDirectors.any { it == director }) {
        score += 18
        reasons += "Regista che segui (${item.director})"
      }
    }

    if (item.isFavorite) {
      score += 22
      reasons += "È nella tua watchlist"
    }

    if (filters.onlyUnwatched && item.currentProgressMs == 0L && item.lastWatchedAt == null) {
      score += 8
      reasons += "Mai visto"
    }

    val currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
    if (item.year >= currentYear - 2) {
      score += 5
      reasons += "Uscita recente (${item.year})"
    }

    if (item.genres.isNotEmpty() && reasons.isEmpty()) {
      reasons += "Titolo coerente con i filtri scelti"
    }

    return WatchMeSuggestion(
      item = item,
      score = score,
      reasons = reasons,
      fromWatchlist = item.isFavorite
    )
  }
}

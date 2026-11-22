package com.example.data.search

import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import java.text.Normalizer
import java.util.Locale
import kotlin.math.max

/** Ordinamento dei risultati di ricerca (esplicito, scelto dall'utente). */
enum class SearchSort(val label: String) {
  RELEVANCE("Pertinenza"),
  RATING_DESC("Voto (alto → basso)"),
  YEAR_DESC("Anno (nuovi prima)"),
  YEAR_ASC("Anno (vecchi prima)"),
  TITLE_ASC("Titolo (A → Z)")
}

/** Ruolo richiesto nella ricerca per persona (attore o regista). */
enum class PersonRole { ACTOR, DIRECTOR }

/** Persona citata in una query (`attore:Nome` / `regista:Nome`). */
data class PersonQuery(val name: String, val role: PersonRole)

/**
 * Filtri combinabili della Ricerca cinematografica evoluta.
 *
 * Tutti i campi sono opzionali: `null`/vuoto = nessun vincolo. I filtri si
 * applicano IN AND; i generi inclusi sono in OR tra loro.
 *
 * @param genres generi che il titolo deve avere (almeno uno).
 * @param excludedGenres generi che il titolo non deve avere.
 * @param language codice ISO 639-1 della lingua originale. Applicato SOLO ai
 *        titoli con [MediaItem.originalLanguage] noto: un metadato mancante non
 *        è una prova contraria e non fa scartare il risultato.
 * @param onlyUnwatched esclude i titoli con progresso o visione registrata.
 * @param onlyWatchlist limita ai preferiti/watchlist.
 */
data class SearchRefinement(
  val genres: Set<String> = emptySet(),
  val excludedGenres: Set<String> = emptySet(),
  val yearFrom: Int? = null,
  val yearTo: Int? = null,
  val minRating: Float? = null,
  val type: MediaType? = null,
  val language: String? = null,
  val sort: SearchSort = SearchSort.RELEVANCE,
  val onlyUnwatched: Boolean = false,
  val onlyWatchlist: Boolean = false
) {
  /**
   * Unisce i filtri scritti nella query con quelli scelti nel pannello.
   *
   * I generi si sommano; per i valori singoli (anno, lingua, tipo) ha precedenza
   * la query; il voto minimo più alto vince; i flag booleani sono in OR (una
   * restrizione richiesta in un punto resta attiva).
   */
  fun mergedWith(panel: SearchRefinement): SearchRefinement = SearchRefinement(
    genres = genres + panel.genres,
    excludedGenres = excludedGenres + panel.excludedGenres,
    yearFrom = yearFrom ?: panel.yearFrom,
    yearTo = yearTo ?: panel.yearTo,
    minRating = listOfNotNull(minRating, panel.minRating).maxOrNull(),
    type = type ?: panel.type,
    language = language ?: panel.language,
    sort = if (panel.sort != SearchSort.RELEVANCE) panel.sort else sort,
    onlyUnwatched = onlyUnwatched || panel.onlyUnwatched,
    onlyWatchlist = onlyWatchlist || panel.onlyWatchlist
  )

  /** `true` quando almeno un filtro è attivo (escluso l'ordinamento). */
  val hasActiveFilters: Boolean
    get() = genres.isNotEmpty() || excludedGenres.isNotEmpty() ||
      yearFrom != null || yearTo != null || minRating != null ||
      type != null || !language.isNullOrBlank() || onlyUnwatched || onlyWatchlist

  /** Etichetta compatta dei filtri attivi ("Azione · 2010-2020 · voto ≥ 7"). */
  fun describe(): String = buildList {    if (type != null) add(if (type == MediaType.FILM) "Film" else "Serie TV")
    if (genres.isNotEmpty()) add(genres.joinToString("/"))
    if (excludedGenres.isNotEmpty()) add("esclusi ${excludedGenres.joinToString("/")}")
    if (yearFrom != null || yearTo != null) {
      add("${yearFrom ?: "…"}-${yearTo ?: "…"}")
    }
    if (minRating != null) add("voto ≥ $minRating")
    if (!language.isNullOrBlank()) add("lingua $language")
    if (onlyWatchlist) add("solo watchlist")
    if (onlyUnwatched) add("solo non visti")
    if (sort != SearchSort.RELEVANCE) add("ordina: ${sort.label}")
  }.joinToString(" · ")
}

/** Query interpretata: testo libero + filtri + eventuale persona/saga. */
data class ParsedSearchQuery(
  val text: String,
  val refinement: SearchRefinement = SearchRefinement(),
  val personQuery: PersonQuery? = null,
  /** Nome della saga/collezione citata (`saga:Harry Potter`). */
  val sagaQuery: String? = null
)

/**
 * Motore della ricerca evoluta (puro, testabile).
 *
 * Responsabilità:
 *  - **parsing** di filtri inline nella query (`genere:azione`, `anno:2010-2020`,
 *    `voto:7`, `tipo:film`, `-genere:horror`, `attore:...`, `regista:...`);
 *  - **filtri combinabili** su metadati reali;
 *  - **ordinamento** esplicito (pertinenza/voto/anno/titolo);
 *  - **fuzzy matching** per titoli scritti male (normalizzazione accenti,
 *    bigrammi) usato per riordinare e per recuperare corrispondenze locali;
 *  - **deduplica** per identità (tmdbId, id, titolo+anno+tipo).
 *
 * Il motore non fa rete: lavora sui risultati già ottenuti dal ViewModel.
 */
object SmartSearchEngine {

  /** Soglia sotto la quale una corrispondenza fuzzy non è proposta. */
  const val LOCAL_FUZZY_MIN_SCORE = 0.62

  /** Soglia usata per considerare "forte" una corrispondenza fuzzy nel ranking. */
  const val STRONG_FUZZY_SCORE = 0.55

  private val FILTER_TOKEN = Regex(
    """^(-)?(genere|genre|anno|year|voto|rating|tipo|type|lingua|lang|attore|actor|regista|director|saga|collezione|collection|ordina|sort):(.+)$""",
    RegexOption.IGNORE_CASE
  )

  /**
   * Interpreta la query: i token filtro vengono rimossi dal testo libero e
   * applicati alla [SearchRefinement]; valori non riconosciuti restano testo.
   */
  fun parse(rawQuery: String): ParsedSearchQuery {
    val tokens = tokenize(rawQuery)
    var text = mutableListOf<String>()
    var genres = linkedSetOf<String>()
    var excludedGenres = linkedSetOf<String>()
    var yearFrom: Int? = null
    var yearTo: Int? = null
    var minRating: Float? = null
    var type: MediaType? = null
    var language: String? = null
    var sort = SearchSort.RELEVANCE
    var person: PersonQuery? = null
    var saga: String? = null

    tokens.forEach { token ->
      val match = FILTER_TOKEN.matchEntire(token)
      if (match == null) {
        text += token
        return@forEach
      }
      val excluded = match.groupValues[1].isNotBlank()
      val key = match.groupValues[2].lowercase(Locale.ROOT)
      val value = match.groupValues[3].trim().trim('"')
      if (value.isBlank()) {
        text += token
        return@forEach
      }
      when (key) {
        "genere", "genre" -> {
          val target = if (excluded) excludedGenres else genres
          value.split(',', '/').map { it.trim() }.filter { it.isNotBlank() }.forEach { target += it }
        }
        "anno", "year" -> {
          val (from, to) = parseYearRange(value)
          if (from != null) yearFrom = from
          if (to != null) yearTo = to
        }
        "voto", "rating" -> minRating = value.replace(',', '.').toFloatOrNull()
        "tipo", "type" -> type = when (value.lowercase(Locale.ROOT)) {
          "film", "movie", "movies" -> MediaType.FILM
          "serie", "serie tv", "tv", "show", "shows" -> MediaType.SERIE_TV
          else -> null
        }
        "lingua", "lang" -> language = value.lowercase(Locale.ROOT).take(2)
        "ordina", "sort" -> sort = when (value.lowercase(Locale.ROOT)) {
          "voto", "rating" -> SearchSort.RATING_DESC
          "anno", "year", "nuovi" -> SearchSort.YEAR_DESC
          "anno-asc", "vecchi" -> SearchSort.YEAR_ASC
          "titolo", "title", "alfabetico" -> SearchSort.TITLE_ASC
          else -> SearchSort.RELEVANCE
        }
        "attore", "actor" -> if (!excluded) person = PersonQuery(value, PersonRole.ACTOR)
        "regista", "director" -> if (!excluded) person = PersonQuery(value, PersonRole.DIRECTOR)
        "saga", "collezione", "collection" -> if (!excluded) saga = value
      }
    }

    return ParsedSearchQuery(
      text = text.joinToString(" ").trim(),
      refinement = SearchRefinement(
        genres = genres,
        excludedGenres = excludedGenres,
        yearFrom = yearFrom,
        yearTo = yearTo,
        minRating = minRating,
        type = type,
        language = language,
        sort = sort
      ),
      personQuery = person,
      sagaQuery = saga
    )
  }

  /** Applica SOLO i filtri (nessun riordino per testo). */
  fun applyFilters(items: List<MediaItem>, refinement: SearchRefinement): List<MediaItem> =
    items.filter { matches(it, refinement) }

  /** `true` se il titolo rispetta tutti i filtri attivi. */
  fun matches(item: MediaItem, refinement: SearchRefinement): Boolean {
    if (refinement.type != null && item.type != refinement.type) return false
    if (refinement.onlyWatchlist && !item.isFavorite) return false
    if (refinement.onlyUnwatched && (item.currentProgressMs > 0L || item.lastWatchedAt != null)) {
      return false
    }
    if (refinement.yearFrom != null && item.year < refinement.yearFrom) return false
    if (refinement.yearTo != null && item.year > refinement.yearTo) return false
    if (refinement.minRating != null && item.rating < refinement.minRating) return false

    if (refinement.genres.isNotEmpty()) {
      val itemGenres = item.genres.map { normalize(it) }
      val wanted = refinement.genres.map { normalize(it) }
      if (wanted.none { g -> itemGenres.any { it.contains(g) || g.contains(it) } }) return false
    }
    if (refinement.excludedGenres.isNotEmpty()) {
      val itemGenres = item.genres.map { normalize(it) }
      val banned = refinement.excludedGenres.map { normalize(it) }
      if (banned.any { g -> itemGenres.any { it.contains(g) || g.contains(it) } }) return false
    }
    // Lingua: vincolo applicato solo quando il metadato è noto su entrambi i lati.
    val language = refinement.language
    if (!language.isNullOrBlank() && item.originalLanguage.isNotBlank()) {
      if (!item.originalLanguage.equals(language, ignoreCase = true)) return false
    }
    return true
  }

  /**
   * Raffina i risultati: dedup → filtri → ordinamento.
   *
   * Con [SearchSort.RELEVANCE] e un testo non vuoto i risultati vengono
   * riordinati per similarità fuzzy (titolo originale e localizzato); senza
   * testo l'ordine di ingresso resta stabile (ordine dei cataloghi).
   */
  fun refine(
    items: List<MediaItem>,
    queryText: String,
    refinement: SearchRefinement
  ): List<MediaItem> {
    val deduped = dedupe(items)
    val filtered = applyFilters(deduped, refinement)
    return sort(filtered, queryText, refinement.sort)
  }

  /** Ordinamento esplicito; RELEVANCE usa il punteggio fuzzy come chiave. */
  fun sort(
    items: List<MediaItem>,
    queryText: String,
    sort: SearchSort
  ): List<MediaItem> {
    val query = queryText.trim()
    return when (sort) {
      SearchSort.RELEVANCE -> if (query.isBlank()) {
        items
      } else {
        items
          .map { item -> item to relevanceScore(query, item) }
          .sortedWith(
            compareByDescending<Pair<MediaItem, Double>> { it.second }
              .thenByDescending { it.first.rating }
          )
          .map { it.first }
      }
      SearchSort.RATING_DESC -> items.sortedWith(
        compareByDescending<MediaItem> { it.rating }.thenBy { normalize(it.title) }
      )
      SearchSort.YEAR_DESC -> items.sortedWith(
        compareByDescending<MediaItem> { it.year }.thenBy { normalize(it.title) }
      )
      SearchSort.YEAR_ASC -> items.sortedWith(
        compareBy<MediaItem> { it.year }.thenBy { normalize(it.title) }
      )
      SearchSort.TITLE_ASC -> items.sortedBy { normalize(it.title) }
    }
  }

  /**
   * Migliori corrispondenze fuzzy nel catalogo locale: usate per recuperare i
   * titoli che la ricerca `contains` non trova per un errore di battitura
   * (es. "interstelar" → "Interstellar").
   */
  fun fuzzyMatches(
    items: List<MediaItem>,
    query: String,
    minScore: Double = LOCAL_FUZZY_MIN_SCORE,
    limit: Int = 20
  ): List<MediaItem> {
    val clean = query.trim()
    if (clean.length < 3) return emptyList()
    return items
      .map { item -> item to relevanceScore(clean, item) }
      .filter { it.second >= minScore }
      .sortedByDescending { it.second }
      .take(limit)
      .map { it.first }
  }

  /** Punteggio di pertinenza 0..1 (titolo localizzato e originale). */
  fun relevanceScore(query: String, item: MediaItem): Double {
    val scoreTitle = fuzzyScore(query, item.title)
    val scoreOriginal = if (item.originalTitle.isNotBlank()) {
      fuzzyScore(query, item.originalTitle)
    } else {
      0.0
    }
    val base = max(scoreTitle, scoreOriginal)
    // Piccolo bonus per cast/regista (ricerca per nome già digitato).
    val inCast = if (item.cast.any { normalize(it).contains(normalize(query)) }) 0.05 else 0.0
    return (base + inCast).coerceAtMost(1.0)
  }

  /**
   * Similarità fuzzy tra query e titolo.
   *
   * Ordine delle regole: uguaglianza → prefisso → contenimento → tutti i token
   * presenti → similarità di bigrammi (Dice). Gli accenti e la punteggiatura non
   * contano; le maiuscole nemmeno.
   */
  fun fuzzyScore(query: String, title: String): Double {
    val q = normalize(query)
    val t = normalize(title)
    if (q.isBlank() || t.isBlank()) return 0.0
    if (q == t) return 1.0
    if (t.startsWith(q)) return 0.92
    if (t.contains(q)) return 0.85
    val queryTokens = q.split(' ').filter { it.isNotBlank() }
    val titleTokens = t.split(' ').filter { it.isNotBlank() }
    if (queryTokens.isNotEmpty() && queryTokens.all { token ->
        titleTokens.any { it.startsWith(token) || it.contains(token) }
      }
    ) {
      return 0.78
    }
    return diceCoefficient(q.replace(" ", ""), t.replace(" ", ""))
  }

  /** Coefficiente di Sørensen–Dice sui bigrammi (0..1). */
  internal fun diceCoefficient(a: String, b: String): Double {
    if (a.isEmpty() || b.isEmpty()) return 0.0
    if (a == b) return 1.0
    if (a.length < 2 || b.length < 2) return 0.0
    val aBigrams = a.windowed(2).groupingBy { it }.eachCount()
    val bBigrams = b.windowed(2).groupingBy { it }.eachCount()
    val intersection = aBigrams.keys.sumOf { key ->
      minOf(aBigrams[key] ?: 0, bBigrams[key] ?: 0)
    }
    val total = (aBigrams.values.sum() + bBigrams.values.sum()).toDouble()
    return if (total == 0.0) 0.0 else (2.0 * intersection) / total
  }

  /**
   * Deduplica tra catalogs: prima `tmdbId`+tipo, poi id, poi titolo+anno+tipo.
   * L'ordine di ingresso viene preservato (usa la prima occorrenza).
   */
  fun dedupe(items: List<MediaItem>): List<MediaItem> {
    val seen = HashSet<String>()
    return items.filter { item ->
      val keys = buildList {
        item.tmdbId?.let { add("tmdb:$it:${item.type}") }
        add("id:${item.id}")
        add("title:${normalize(item.title)}:${item.year}:${item.type}")
      }
      keys.none { it in seen }.also { isNew ->
        if (isNew) seen += keys
      }
    }
  }

  /** Normalizza per confronti: minuscolo, senza accenti, solo lettere/numeri/spazi. */
  fun normalize(text: String): String {
    val stripped = Normalizer.normalize(text, Normalizer.Form.NFD)
      .replace(Regex("""\p{Mn}+"""), "")
    return stripped.lowercase(Locale.ROOT)
      .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
      .trim()
      .replace(Regex("""\s+"""), " ")
  }

  /** Tokenizza rispettando le virgolette ("nome cognome"). */
  private fun tokenize(raw: String): List<String> {
    val tokens = mutableListOf<String>()
    val builder = StringBuilder()
    var inQuotes = false
    raw.forEach { char ->
      when {
        char == '"' -> inQuotes = !inQuotes
        char.isWhitespace() && !inQuotes -> {
          if (builder.isNotBlank()) tokens += builder.toString()
          builder.clear()
        }
        else -> builder.append(char)
      }
    }
    if (builder.isNotBlank()) tokens += builder.toString()
    return tokens
  }

  /** "2010", "2010-2020", ">=2010", "<=2020". */
  private fun parseYearRange(value: String): Pair<Int?, Int?> {
    val clean = value.trim()
    if (clean.contains('-')) {
      val parts = clean.split('-', limit = 2)
      return parts.getOrNull(0)?.trim()?.toIntOrNull() to
        parts.getOrNull(1)?.trim()?.toIntOrNull()
    }
    if (clean.startsWith(">=")) return clean.removePrefix(">=").toIntOrNull() to null
    if (clean.startsWith("<=")) return null to clean.removePrefix("<=").toIntOrNull()
    val single = clean.toIntOrNull() ?: return null to null
    return single to single
  }
}

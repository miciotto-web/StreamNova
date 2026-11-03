package com.example.data.repository

import android.util.Log
import com.example.data.api.TmdbApiClient
import com.example.data.api.TmdbCatalogMapper
import com.example.data.api.TmdbMovieDto
import com.example.data.api.TmdbTvDto
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.prefs.AppSettingsRepository
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Provider TMDB delle NOVITÀ DEI PROVIDER per la Home di StreamNova.
 *
 * Dopo il ripristino di Cinemeta, le righe stock (Popolari / In evidenza / Novità)
 * non sono più alimentate da TMDB: [StremioCatalogRepository] interroga i cataloghi
 * ufficiali di Cinemeta. Questo oggetto resta la sorgente delle 10 sezioni
 * `Novità <provider> Film/Serie` (Netflix, HBO / Max, Disney+, Apple TV+,
 * Paramount+), che TMDB può verificare con `with_watch_providers` per l'Italia.
 *
 * [loadHomeSections] è la vecchia pipeline TMDB completa (stock + extra): resta
 * disponibile per i test di compatibilità ma NON è più chiamata dalla Home.
 *
 * MAPPATURA RIGHE PROVIDER → ENDPOINT TMDB
 * - Novità provider Film (5 righe) . `discover/movie?with_watch_providers=<id>&watch_region=IT`
 *                                    `&with_watch_monetization_types=flatrate`
 *                                    `&primary_release_date.gte/lte&sort_by=primary_release_date.desc`
 * - Novità provider Serie (5 righe) . `discover/tv?with_watch_providers=<id>&watch_region=IT`
 *                                    `&with_watch_monetization_types=flatrate`
 *                                    `&first_air_date.gte/lte&sort_by=first_air_date.desc`
 *
 * Provider e ordine (verificati per l'Italia): Netflix, HBO / Max, Disney+,
 * Apple TV+, Paramount+. Ogni riga ha id/section id STABILI.
 *
 * PROVIDER E FINESTRA DELLE NOVITÀ
 * - Gli ID dei provider sono quelli restituiti da TMDB per l'Italia
 *   (`/watch/providers/{movie,tv}?watch_region=IT`), NON gli ID globali/US:
 *   Netflix = 8, HBO Max = **1899**, Disney Plus = 337, Apple TV (Apple TV+) = 350,
 *   Paramount Plus = 531.
 * - "Novità" significa: film con data di uscita (`primary_release_date`) negli ultimi
 *   [PROVIDER_MOVIES_WINDOW_DAYS] giorni (365 per HBO / Max, Apple TV+ e Paramount+,
 *   che hanno cataloghi italiani più piccoli); serie con prima messa in onda
 *   (`first_air_date`) negli ultimi [PROVIDER_SERIES_WINDOW_DAYS] giorni. Entrambe le
 *   liste sono ordinate per data DECRESCENTE, quindi un titolo vecchio ma popolare
 *   non può comparire solo per la sua popolarità.
 * - LIMITE TMDB DOCUMENTATO: Discover non espone la data in cui un titolo è stato
 *   aggiunto al catalogo del provider. La finestra è quindi sulla data di
 *   uscita/trasmissione, non sull'effettiva disponibilità editoriale. Se un provider
 *   ha pochi titoli nella finestra la riga è più corta (o assente); NON si usa
 *   Cinemeta come fallback.
 *
 * CACHE E AFFIDABILITÀ
 * - Ogni risposta passa da [MediaRepository.fetchMoviesWithCache] /
 *   [MediaRepository.fetchTvWithCache]: cache Room con TTL 12h.
 * - Un endpoint che fallisce non azzera le altre righe: ogni catalogo è isolato.
 * - Senza credenziale TMDB non parte alcuna richiesta e le righe provider restano
 *   vuote (Cinemeta, la cache locale e «Continua a guardare» restano disponibili).
 */
object TmdbHomeCatalogProvider {

  private const val TAG = "TmdbHomeCatalog"

  /** Riga extra "Serie TV in onda": id STABILE, usato come key di focus nella Home. */
  const val SECTION_ON_AIR_SERIES = "tmdb_on_air_series"

  /** Riga extra "Novità in digitale": id STABILE. */
  const val SECTION_DIGITAL_NEW = "tmdb_digital_new"

  /** Riga extra "Novità HBO": id STABILE. */
  const val SECTION_HBO_NEW = "tmdb_hbo_new"

  /** Regione delle date di uscita per le novità digitali. */
  const val REGION_ITALY = "IT"

  /** HBO (rete premium statunitense) verificata su TMDB. */
  const val HBO_NETWORK_ID = 49

  /** HBO Max: è la rete TMDB su cui vive anche il catalogo del rebrand "Max". */
  const val HBO_MAX_NETWORK_ID = 3186

  /**
   * Insieme di network per "Novità HBO": HBO + HBO Max.
   *
   * TMDB NON espone una rete separata chiamata "Max": il contenuto del servizio
   * rinominato resta sulla rete 3186 (HBO Max). Non viene inventato alcun ID.
   */
  const val HBO_NETWORK_IDS = "$HBO_NETWORK_ID|$HBO_MAX_NETWORK_ID"

  /** Tipo di uscita TMDB usato per le novità digitali: 4 = Digital. */
  const val DIGITAL_RELEASE_TYPE = "4"

  /** Finestra delle novità digitali italiane, in giorni. */
  const val DIGITAL_WINDOW_DAYS = 45

  /** Finestra delle novità HBO (prime visioni), in giorni. */
  const val HBO_NEW_WINDOW_DAYS = 365

  /**
   * ID provider TMDB VERIFICATI per l'Italia tramite
   * `/watch/providers/movie?watch_region=IT` e `/watch/providers/tv?watch_region=IT`.
   *
   * Attenzione: [PRIME_VIDEO] in Italia è 119, non 9 (ID usato negli Stati Uniti).
   * [APPLE_TV_PLUS] è il servizio in abbonamento "Apple TV" (350); 2 è invece
   * "Apple TV Store" (noleggio/acquisto), escluso dal filtro flatrate.
   */
  object ItalyProviderIds {
    const val NETFLIX = 8
    const val DISNEY_PLUS = 337
    const val PRIME_VIDEO = 119
    const val APPLE_TV_PLUS = 350
    const val PARAMOUNT_PLUS = 531

    /**
     * HBO Max / Max: provider 1899 verificato su
     * `/watch/providers/{movie,tv}?watch_region=IT`. È distinto da
     * "HBO Max Amazon Channel" (1825), escluso perché canale aggiuntivo.
     */
    const val HBO_MAX = 1899
  }

  /** Monetizzazione TMDB delle righe "Novità provider": solo abbonamento. */
  const val PROVIDER_MONETIZATION_FLATRATE = "flatrate"

  /** Finestra delle novità FILM dei provider, in giorni (data di uscita). */
  const val PROVIDER_MOVIES_WINDOW_DAYS = 90

  /**
   * Finestra estesa delle novità FILM, usata da Apple TV+, Paramount+ e HBO / Max.
   *
   * Su TMDB i loro cataloghi italiani hanno pochi titoli nella finestra standard:
   * 365 giorni restituiscono una riga utile senza ricorrere al catalogo completo
   * né a fallback esterni. Le altre righe Provider restano a 90 giorni.
   */
  const val PROVIDER_MOVIES_EXTENDED_WINDOW_DAYS = 365

  /** Finestra delle novità SERIE TV dei provider, in giorni (prima messa in onda). */
  const val PROVIDER_SERIES_WINDOW_DAYS = 180

  // Id STABILI delle righe "Novità provider": sono anche le key di focus nella Home
  // (`section_<id>`) e il catalogId della sezione.
  const val SECTION_NETFLIX_MOVIES_NEW = "tmdb_netflix_movies_new"
  const val SECTION_NETFLIX_SERIES_NEW = "tmdb_netflix_series_new"
  const val SECTION_DISNEY_MOVIES_NEW = "tmdb_disney_movies_new"
  const val SECTION_DISNEY_SERIES_NEW = "tmdb_disney_series_new"
  const val SECTION_PRIME_MOVIES_NEW = "tmdb_prime_movies_new"
  const val SECTION_PRIME_SERIES_NEW = "tmdb_prime_series_new"
  const val SECTION_APPLE_MOVIES_NEW = "tmdb_apple_movies_new"
  const val SECTION_APPLE_SERIES_NEW = "tmdb_apple_series_new"
  const val SECTION_PARAMOUNT_MOVIES_NEW = "tmdb_paramount_movies_new"
  const val SECTION_PARAMOUNT_SERIES_NEW = "tmdb_paramount_series_new"

  /**
   * Righe HBO / Max usate dalla Home dopo il ripristino di Cinemeta.
   * Id distinti dalla vecchia riga network "Novità HBO" ([SECTION_HBO_NEW]).
   */
  const val SECTION_HBO_MAX_MOVIES_NEW = "tmdb_hbo_max_movies_new"
  const val SECTION_HBO_MAX_SERIES_NEW = "tmdb_hbo_max_series_new"

  const val CATALOG_NETFLIX_MOVIES_NEW = "tmdb_movies_netflix_new"
  const val CATALOG_NETFLIX_SERIES_NEW = "tmdb_series_netflix_new"
  const val CATALOG_DISNEY_MOVIES_NEW = "tmdb_movies_disney_new"
  const val CATALOG_DISNEY_SERIES_NEW = "tmdb_series_disney_new"
  const val CATALOG_PRIME_MOVIES_NEW = "tmdb_movies_prime_new"
  const val CATALOG_PRIME_SERIES_NEW = "tmdb_series_prime_new"
  const val CATALOG_APPLE_MOVIES_NEW = "tmdb_movies_apple_new"
  const val CATALOG_APPLE_SERIES_NEW = "tmdb_series_apple_new"
  const val CATALOG_PARAMOUNT_MOVIES_NEW = "tmdb_movies_paramount_new"
  const val CATALOG_PARAMOUNT_SERIES_NEW = "tmdb_series_paramount_new"
  const val CATALOG_HBO_MAX_MOVIES_NEW = "tmdb_movies_hbo_max_new"
  const val CATALOG_HBO_MAX_SERIES_NEW = "tmdb_series_hbo_max_new"

  /** Id dei cataloghi stock: identificano la sezione, non un addon. */
  const val CATALOG_POPULAR_MOVIES = "tmdb_movies_popular"
  const val CATALOG_TOP_RATED_MOVIES = "tmdb_movies_top_rated"
  const val CATALOG_NEW_MOVIES = "tmdb_movies_new"
  const val CATALOG_POPULAR_SERIES = "tmdb_series_popular"
  const val CATALOG_TOP_RATED_SERIES = "tmdb_series_top_rated"
  const val CATALOG_NEW_SERIES = "tmdb_series_new"

  const val CATALOG_ON_AIR_SERIES = "tmdb_series_on_air"
  const val CATALOG_DIGITAL_NEW = "tmdb_movies_digital_new"
  const val CATALOG_HBO_NEW = "tmdb_series_hbo_new"

  private const val MOVIE_TOP_RATED_CACHE_KEY = "top_rated_movies_p1"
  private const val MOVIE_POPULAR_CACHE_KEY = "popular_movies_p1"
  private const val TV_TOP_RATED_CACHE_KEY = "top_rated_tv_p1"
  private const val TV_POPULAR_CACHE_KEY = "popular_tv_p1"

  /**
   * Credenziale TMDB. Iniettabile nei test: nessun test dipende da una chiave reale
   * o dalla rete (il servizio viene puntato a un server locale).
   */
  internal var apiKeyProvider: () -> String = { MediaRepository.getEffectiveApiKey() }

  /** Lingua TMDB (formato regionale, es. `it-IT`). Iniettabile nei test. */
  internal var languageProvider: () -> String = { AppSettingsRepository.tmdbLanguage() }

  /** Orologio delle finestre temporali. Iniettabile per rendere deterministici i test. */
  internal var clockProvider: () -> Long = { System.currentTimeMillis() }

  /** Ripristina i collaboratori di produzione (solo test). */
  internal fun resetOverrides() {
    apiKeyProvider = { MediaRepository.getEffectiveApiKey() }
    languageProvider = { AppSettingsRepository.tmdbLanguage() }
    clockProvider = { System.currentTimeMillis() }
  }

  /**
   * Carica le sezioni TMDB della Home per i [mediaTypes] richiesti.
   *
   * Restituisce le sezioni STOCK (Popolari / In evidenza / Novità) con il rispettivo
   * [HomeCatalogSlot] e le sezioni EXTRA (Serie in onda, Digitale, HBO) senza slot.
   * Le sezioni senza contenuti e gli endpoint falliti vengono omessi: le altre righe
   * restano disponibili.
   */
  suspend fun loadHomeSections(mediaTypes: List<MediaType>): List<CatalogSection> =
    withContext(Dispatchers.IO) {
      if (mediaTypes.isEmpty()) return@withContext emptyList()
      val apiKey = apiKeyProvider().trim()
      if (apiKey.isBlank()) {
        Log.w(TAG, "Credenziale TMDB assente: cataloghi Home nativi non caricati")
        return@withContext emptyList()
      }

      val language = languageProvider()
      val labels = labelsFor(language)
      val now = clockProvider()
      val today = formatDate(now, "yyyy-MM-dd")
      val compactToday = formatDate(now, "yyyyMMdd")
      val currentYear = calendarOf(now).get(Calendar.YEAR)
      val digitalSince = formatDate(addDays(now, -DIGITAL_WINDOW_DAYS), "yyyy-MM-dd")
      val hboSince = formatDate(addDays(now, -HBO_NEW_WINDOW_DAYS), "yyyy-MM-dd")
      val providerSeriesSince =
        formatDate(addDays(now, -PROVIDER_SERIES_WINDOW_DAYS), "yyyy-MM-dd")
      val wantsMovies = MediaType.FILM in mediaTypes
      val wantsSeries = MediaType.SERIE_TV in mediaTypes

      // Ordine deterministico: le coroutine partono in parallelo ma i risultati vengono
      // raccolti nell'ordine con cui sono stati pianificati, non in ordine di arrivo.
      val loaders = mutableListOf<suspend () -> CatalogSection?>()

      if (wantsMovies) {
        loaders += {
          loadSpec(
            label = "Film popolari",
            spec = SectionSpec(
              id = "tmdb_home_movies_popular",
              title = labels.popularMovies,
              catalogId = CATALOG_POPULAR_MOVIES,
              mediaType = MediaType.FILM,
              slot = HomeCatalogSlot.POPULAR
            )
          ) {
            MediaRepository.fetchMoviesWithCache(MOVIE_POPULAR_CACHE_KEY) {
              TmdbApiClient.service.getPopularMovies(apiKey, language, page = 1)
            }.results.let(::mapMovies)
          }
        }
        loaders += {
          loadSpec(
            label = "Film in evidenza",
            spec = SectionSpec(
              id = "tmdb_home_movies_top_rated",
              title = labels.topRatedMovies,
              catalogId = CATALOG_TOP_RATED_MOVIES,
              mediaType = MediaType.FILM,
              slot = HomeCatalogSlot.TOP_RATED
            )
          ) {
            MediaRepository.fetchMoviesWithCache(MOVIE_TOP_RATED_CACHE_KEY) {
              TmdbApiClient.service.getTopRatedMovies(apiKey, language, page = 1)
            }.results.let { mapMovies(it, top10 = true) }
          }
        }
        loaders += {
          loadSpec(
            label = "Novità film (cinema IT)",
            spec = SectionSpec(
              id = "tmdb_home_movies_new",
              title = labels.newMovies,
              catalogId = CATALOG_NEW_MOVIES,
              mediaType = MediaType.FILM,
              slot = HomeCatalogSlot.NEW
            )
          ) {
            MediaRepository.fetchMoviesWithCache("home_tmdb_now_playing_it_p1") {
              TmdbApiClient.service.getNowPlayingMovies(
                apiKey = apiKey,
                language = language,
                region = REGION_ITALY,
                page = 1
              )
            }.results.let(::mapMovies)
          }
        }
      }

      if (wantsSeries) {
        loaders += {
          loadSpec(
            label = "Serie popolari",
            spec = SectionSpec(
              id = "tmdb_home_series_popular",
              title = labels.popularSeries,
              catalogId = CATALOG_POPULAR_SERIES,
              mediaType = MediaType.SERIE_TV,
              slot = HomeCatalogSlot.POPULAR
            )
          ) {
            MediaRepository.fetchTvWithCache(TV_POPULAR_CACHE_KEY) {
              TmdbApiClient.service.getPopularTv(apiKey, language, page = 1)
            }.results.let(::mapSeries)
          }
        }
        loaders += {
          loadSpec(
            label = "Serie in evidenza",
            spec = SectionSpec(
              id = "tmdb_home_series_top_rated",
              title = labels.topRatedSeries,
              catalogId = CATALOG_TOP_RATED_SERIES,
              mediaType = MediaType.SERIE_TV,
              slot = HomeCatalogSlot.TOP_RATED
            )
          ) {
            MediaRepository.fetchTvWithCache(TV_TOP_RATED_CACHE_KEY) {
              TmdbApiClient.service.getTopRatedTv(apiKey, language, page = 1)
            }.results.let { mapSeries(it, top10 = true) }
          }
        }
        loaders += {
          loadSpec(
            label = "Novità serie TV",
            spec = SectionSpec(
              id = "tmdb_home_series_new",
              title = labels.newSeries,
              catalogId = CATALOG_NEW_SERIES,
              mediaType = MediaType.SERIE_TV,
              slot = HomeCatalogSlot.NEW
            )
          ) {
            MediaRepository.fetchTvWithCache("home_tmdb_new_series_${currentYear}_p1") {
              TmdbApiClient.service.discoverTvByFirstAirDateYear(
                apiKey = apiKey,
                year = currentYear,
                language = language,
                page = 1
              )
            }.results.let(::mapSeries)
          }
        }
        loaders += {
          loadSpec(
            label = "Serie TV in onda",
            spec = SectionSpec(
              id = SECTION_ON_AIR_SERIES,
              title = labels.onAirSeries,
              catalogId = CATALOG_ON_AIR_SERIES,
              mediaType = MediaType.SERIE_TV,
              slot = null
            )
          ) {
            MediaRepository.fetchTvWithCache("home_tmdb_on_the_air_p1") {
              TmdbApiClient.service.getOnTheAirTv(apiKey, language, page = 1)
            }.results.let(::mapSeries)
          }
        }
        loaders += {
          loadSpec(
            label = "Novità HBO",
            spec = SectionSpec(
              id = SECTION_HBO_NEW,
              title = labels.hboNew,
              catalogId = CATALOG_HBO_NEW,
              mediaType = MediaType.SERIE_TV,
              slot = null
            )
          ) {
            MediaRepository.fetchTvWithCache("home_tmdb_hbo_new_${compactToday}_p1") {
              TmdbApiClient.service.discoverTvByNetworks(
                apiKey = apiKey,
                networkIds = HBO_NETWORK_IDS,
                firstAirDateGte = hboSince,
                firstAirDateLte = today,
                language = language,
                page = 1
              )
            }.results.let(::mapSeries)
          }
        }
      }

      if (wantsMovies) {
        loaders += {
          loadSpec(
            label = "Novità in digitale (IT)",
            spec = SectionSpec(
              id = SECTION_DIGITAL_NEW,
              title = labels.digitalNew,
              catalogId = CATALOG_DIGITAL_NEW,
              mediaType = MediaType.FILM,
              slot = null
            )
          ) {
            MediaRepository.fetchMoviesWithCache("home_tmdb_digital_it_${compactToday}_p1") {
              TmdbApiClient.service.discoverMoviesByDigitalRelease(
                apiKey = apiKey,
                region = REGION_ITALY,
                releaseType = DIGITAL_RELEASE_TYPE,
                releasedAfter = digitalSince,
                releasedBefore = today,
                language = language,
                page = 1
              )
            }.results.let(::mapMovies)
          }
        }
      }

      // Righe "Novità <provider>" (10 sezioni native, Film e Serie separate).
      val providerRows = providerNewRows(labels)
      if (wantsMovies) {
        providerRows.forEach { row ->
          loaders += {
            // La finestra è quella della singola riga: 90 giorni per tutti i provider,
            // 365 solo per Apple TV+ e Paramount+ (vedi ProviderNewRow.movieWindowDays).
            val providerMoviesSince =
              formatDate(addDays(now, -row.movieWindowDays), "yyyy-MM-dd")
            loadSpec(
              label = row.movieTitle,
              spec = SectionSpec(
                id = row.movieSectionId,
                title = row.movieTitle,
                catalogId = row.movieCatalogId,
                mediaType = MediaType.FILM,
                slot = null
              )
            ) {
              MediaRepository.fetchMoviesWithCache(
                "home_tmdb_provider_${row.slug}_movies_" +
                  "${row.movieWindowDays}d_${compactToday}_p1"
              ) {
                TmdbApiClient.service.discoverNewMoviesByProvider(
                  apiKey = apiKey,
                  providerId = row.providerId.toString(),
                  region = REGION_ITALY,
                  monetizationTypes = PROVIDER_MONETIZATION_FLATRATE,
                  releasedAfter = providerMoviesSince,
                  releasedBefore = today,
                  page = 1
                )
              }.results.let(::mapMovies)
            }
          }
        }
      }
      if (wantsSeries) {
        providerRows.forEach { row ->
          loaders += {
            loadSpec(
              label = row.seriesTitle,
              spec = SectionSpec(
                id = row.seriesSectionId,
                title = row.seriesTitle,
                catalogId = row.seriesCatalogId,
                mediaType = MediaType.SERIE_TV,
                slot = null
              )
            ) {
              MediaRepository.fetchTvWithCache(
                "home_tmdb_provider_${row.slug}_series_${compactToday}_p1"
              ) {
                TmdbApiClient.service.discoverNewTvByProvider(
                  apiKey = apiKey,
                  providerId = row.providerId.toString(),
                  region = REGION_ITALY,
                  monetizationTypes = PROVIDER_MONETIZATION_FLATRATE,
                  firstAirDateGte = providerSeriesSince,
                  firstAirDateLte = today,
                  page = 1
                )
              }.results.let(::mapSeries)
            }
          }
        }
      }

      val loaded = loaders.map { loader -> async { loader() } }.awaitAll().filterNotNull()

      // Le righe STOCK mantengono l'ordine di pianificazione (Film poi Serie).
      // Le righe EXTRA seguono l'ordine editoriale richiesto, indipendente da quello
      // con cui i loader sono stati accodati: Serie in onda, Novità digitali,
      // Novità HBO, poi le 10 righe provider (Film e Serie alternate per provider).
      val stock = loaded.filter { it.homeSlot != null }
      val extraOrder = listOf(SECTION_ON_AIR_SERIES, SECTION_DIGITAL_NEW, SECTION_HBO_NEW) +
        providerRows.flatMap { listOf(it.movieSectionId, it.seriesSectionId) }
      val extras = loaded.filter { it.homeSlot == null }
        .sortedBy { section ->
          extraOrder.indexOf(section.id).let { index ->
            if (index >= 0) index else Int.MAX_VALUE
          }
        }
      stock + extras
    }

  /**
   * Variante con isolamento TOTALE: un errore imprevisto del provider non deve
   * interrompere la risoluzione della Home. Gli errori dei singoli cataloghi sono
   * già isolati in [loadHomeSections].
   */
  suspend fun loadHomeSectionsSafely(mediaTypes: List<MediaType>): List<CatalogSection> =
    try {
      loadHomeSections(mediaTypes)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.w(TAG, "Cataloghi Home TMDB non disponibili: ${e.message}")
      emptyList()
    }

  /**
   * Carica SOLO le 10 righe "Novità <provider> Film/Serie" usate dalla Home
   * dopo il ripristino di Cinemeta come sorgente dei cataloghi stock.
   *
   * Provider e ordine editoriale:
   * Netflix, HBO / Max, Disney+, Apple TV+, Paramount+ (Film e Serie alternate).
   *
   * Ogni riga è verificata su TMDB per l'Italia:
   * `with_watch_providers=<id>&watch_region=IT&with_watch_monetization_types=flatrate`.
   * I titoli sono ordinati per data DECRESCENTE (`primary_release_date.desc` per i
   * film, `first_air_date.desc` per le serie) e filtrati con la finestra della riga.
   * Le sezioni vuote o fallite vengono omesse senza azzerare le altre.
   */
  suspend fun loadProviderNewSections(mediaTypes: List<MediaType>): List<CatalogSection> =
    withContext(Dispatchers.IO) {
      if (mediaTypes.isEmpty()) return@withContext emptyList()
      val apiKey = apiKeyProvider().trim()
      if (apiKey.isBlank()) {
        Log.w(TAG, "Credenziale TMDB assente: righe Novità provider non caricate")
        return@withContext emptyList()
      }

      val language = languageProvider()
      val labels = labelsFor(language)
      val now = clockProvider()
      val today = formatDate(now, "yyyy-MM-dd")
      val compactToday = formatDate(now, "yyyyMMdd")
      val providerSeriesSince =
        formatDate(addDays(now, -PROVIDER_SERIES_WINDOW_DAYS), "yyyy-MM-dd")
      val rows = providerNoveltyRows(labels)
      val wantsMovies = MediaType.FILM in mediaTypes
      val wantsSeries = MediaType.SERIE_TV in mediaTypes

      // Ogni voce conserva l'id della sezione: l'ordine finale è quello editoriale
      // dei provider, non l'ordine di completamento delle chiamate di rete.
      val loaders = mutableListOf<Pair<String, suspend () -> CatalogSection?>>()

      if (wantsMovies) {
        rows.forEach { row ->
          loaders += row.movieSectionId to {
            val providerMoviesSince =
              formatDate(addDays(now, -row.movieWindowDays), "yyyy-MM-dd")
            loadSpec(
              label = row.movieTitle,
              spec = SectionSpec(
                id = row.movieSectionId,
                title = row.movieTitle,
                catalogId = row.movieCatalogId,
                mediaType = MediaType.FILM,
                slot = null
              )
            ) {
              MediaRepository.fetchMoviesWithCache(
                "home_tmdb_provider_${row.slug}_movies_" +
                  "${row.movieWindowDays}d_${compactToday}_p1"
              ) {
                TmdbApiClient.service.discoverNewMoviesByProvider(
                  apiKey = apiKey,
                  providerId = row.providerId.toString(),
                  region = REGION_ITALY,
                  monetizationTypes = PROVIDER_MONETIZATION_FLATRATE,
                  releasedAfter = providerMoviesSince,
                  releasedBefore = today,
                  page = 1
                )
              }.results.let(::mapMovies)
            }
          }
        }
      }

      if (wantsSeries) {
        rows.forEach { row ->
          loaders += row.seriesSectionId to {
            loadSpec(
              label = row.seriesTitle,
              spec = SectionSpec(
                id = row.seriesSectionId,
                title = row.seriesTitle,
                catalogId = row.seriesCatalogId,
                mediaType = MediaType.SERIE_TV,
                slot = null
              )
            ) {
              MediaRepository.fetchTvWithCache(
                "home_tmdb_provider_${row.slug}_series_${compactToday}_p1"
              ) {
                TmdbApiClient.service.discoverNewTvByProvider(
                  apiKey = apiKey,
                  providerId = row.providerId.toString(),
                  region = REGION_ITALY,
                  monetizationTypes = PROVIDER_MONETIZATION_FLATRATE,
                  firstAirDateGte = providerSeriesSince,
                  firstAirDateLte = today,
                  page = 1
                )
              }.results.let(::mapSeries)
            }
          }
        }
      }

      val loaded = loaders.map { (_, loader) -> async { loader() } }.awaitAll().filterNotNull()
      val editorialOrder = rows.flatMap { listOf(it.movieSectionId, it.seriesSectionId) }
      loaded.sortedBy { section ->
        editorialOrder.indexOf(section.id).let { index ->
          if (index >= 0) index else Int.MAX_VALUE
        }
      }
    }

  /**
   * Variante isolata delle righe Novità provider: un errore imprevisto non deve
   * interrompere la Home (le righe Cinemeta restano caricate).
   */
  suspend fun loadProviderNewSectionsSafely(mediaTypes: List<MediaType>): List<CatalogSection> =
    try {
      loadProviderNewSections(mediaTypes)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.w(TAG, "Righe Novità provider non disponibili: ${e.message}")
      emptyList()
    }

  /**
   * Pagina successiva del catalogo "Popolari Film" per la schermata Film.
   * La numerazione è quella TMDB (1-based): [page] deve essere >= 2.
   */
  suspend fun paginateMovies(page: Int): List<MediaItem> = withContext(Dispatchers.IO) {
    if (page < 2) return@withContext emptyList()
    val apiKey = apiKeyProvider().trim()
    if (apiKey.isBlank()) return@withContext emptyList()
    try {
      val response = MediaRepository.fetchMoviesWithCache("home_tmdb_movies_popular_p$page") {
        TmdbApiClient.service.getPopularMovies(apiKey, languageProvider(), page)
      }
      mapMovies(response.results)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.w(TAG, "Paginazione Film TMDB fallita (page=$page): ${e.message}")
      emptyList()
    }
  }

  /**
   * Pagina successiva del catalogo "Popolari Serie" per la schermata Serie TV.
   * La numerazione è quella TMDB (1-based): [page] deve essere >= 2.
   */
  suspend fun paginateSeries(page: Int): List<MediaItem> = withContext(Dispatchers.IO) {
    if (page < 2) return@withContext emptyList()
    val apiKey = apiKeyProvider().trim()
    if (apiKey.isBlank()) return@withContext emptyList()
    try {
      val response = MediaRepository.fetchTvWithCache("home_tmdb_series_popular_p$page") {
        TmdbApiClient.service.getPopularTv(apiKey, languageProvider(), page)
      }
      mapSeries(response.results)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.w(TAG, "Paginazione Serie TMDB fallita (page=$page): ${e.message}")
      emptyList()
    }
  }

  // ── Mapping ────────────────────────────────────────────────────────────────

  private fun mapMovies(results: List<TmdbMovieDto>, top10: Boolean = false): List<MediaItem> =
    results.mapIndexed { index, dto ->
      TmdbCatalogMapper.movieToMediaItem(
        dto,
        isTop10 = top10 && index < StremioCatalogRepository.HOME_TOP_10_SIZE
      )
    }.distinctBy { it.id }

  private fun mapSeries(results: List<TmdbTvDto>, top10: Boolean = false): List<MediaItem> =
    results
      .filterNot { TmdbCatalogMapper.isBlockedTv(it.id, it.name) }
      .mapIndexed { index, dto ->
        TmdbCatalogMapper.tvToMediaItem(
          dto,
          isTop10 = top10 && index < StremioCatalogRepository.HOME_TOP_10_SIZE
        )
      }
      .distinctBy { it.id }

  // ── Sezioni ────────────────────────────────────────────────────────────────

  private data class SectionSpec(
    val id: String,
    val title: String,
    val catalogId: String,
    val mediaType: MediaType,
    val slot: HomeCatalogSlot?
  )

  private suspend fun loadSpec(
    label: String,
    spec: SectionSpec,
    items: suspend () -> List<MediaItem>
  ): CatalogSection? = try {
    val loaded = items().filter { it.id.isNotBlank() }.distinctBy { it.id }
    if (loaded.isEmpty()) {
      null
    } else {
      CatalogSection(
        id = spec.id,
        title = spec.title,
        // Nessun addon: la provenienza è il client TMDB nativo.
        addonId = null,
        addonName = null,
        catalogId = spec.catalogId,
        mediaType = spec.mediaType,
        origin = CatalogSourceOrigin.TMDB_NATIVE,
        items = loaded,
        homeSlot = spec.slot
      )
    }
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    Log.w(TAG, "Catalogo TMDB \"$label\" non disponibile: ${e.message}")
    null
  }

  // ── Date ───────────────────────────────────────────────────────────────────

  private fun calendarOf(epochMillis: Long): Calendar =
    Calendar.getInstance().apply { timeInMillis = epochMillis }

  private fun addDays(epochMillis: Long, days: Int): Long =
    calendarOf(epochMillis).apply { add(Calendar.DAY_OF_YEAR, days) }.timeInMillis

  private fun formatDate(epochMillis: Long, pattern: String): String =
    SimpleDateFormat(pattern, Locale.US).format(Date(epochMillis))

  // ── Etichette delle righe ──────────────────────────────────────────────────

  internal data class HomeLabels(
    val popularMovies: String,
    val topRatedMovies: String,
    val newMovies: String,
    val popularSeries: String,
    val topRatedSeries: String,
    val newSeries: String,
    val onAirSeries: String,
    val digitalNew: String,
    val hboNew: String,
    val hboMaxMoviesNew: String,
    val hboMaxSeriesNew: String,
    val netflixMoviesNew: String,
    val netflixSeriesNew: String,
    val disneyMoviesNew: String,
    val disneySeriesNew: String,
    val primeMoviesNew: String,
    val primeSeriesNew: String,
    val appleMoviesNew: String,
    val appleSeriesNew: String,
    val paramountMoviesNew: String,
    val paramountSeriesNew: String
  )

  /**
   * Definizione delle 10 righe provider usate dalla vecchia Home TMDB
   * ([loadHomeSections], mantenuta per compatibilità dei test).
   *
   * L'ORDINE è: Netflix Film, Netflix Serie, Disney+ Film, Disney+ Serie,
   * Prime Video Film, Prime Video Serie, Apple TV+ Film, Apple TV+ Serie,
   * Paramount+ Film, Paramount+ Serie.
   *
   * La Home di produzione usa [providerNoveltyRows] (HBO / Max al posto di
   * Prime Video) tramite [loadProviderNewSections].
   */
  internal data class ProviderNewRow(
    val slug: String,
    val providerId: Int,
    val movieSectionId: String,
    val movieCatalogId: String,
    val seriesSectionId: String,
    val seriesCatalogId: String,
    val movieTitle: String,
    val seriesTitle: String,
    /** Finestra FILM in giorni: 90 di default, 365 per HBO / Max, Apple TV+ e Paramount+. */
    val movieWindowDays: Int = PROVIDER_MOVIES_WINDOW_DAYS
  )

  internal fun providerNewRows(labels: HomeLabels): List<ProviderNewRow> = listOf(
    ProviderNewRow(
      slug = "netflix",
      providerId = ItalyProviderIds.NETFLIX,
      movieSectionId = SECTION_NETFLIX_MOVIES_NEW,
      movieCatalogId = CATALOG_NETFLIX_MOVIES_NEW,
      seriesSectionId = SECTION_NETFLIX_SERIES_NEW,
      seriesCatalogId = CATALOG_NETFLIX_SERIES_NEW,
      movieTitle = labels.netflixMoviesNew,
      seriesTitle = labels.netflixSeriesNew
    ),
    ProviderNewRow(
      slug = "disney",
      providerId = ItalyProviderIds.DISNEY_PLUS,
      movieSectionId = SECTION_DISNEY_MOVIES_NEW,
      movieCatalogId = CATALOG_DISNEY_MOVIES_NEW,
      seriesSectionId = SECTION_DISNEY_SERIES_NEW,
      seriesCatalogId = CATALOG_DISNEY_SERIES_NEW,
      movieTitle = labels.disneyMoviesNew,
      seriesTitle = labels.disneySeriesNew
    ),
    ProviderNewRow(
      slug = "prime",
      providerId = ItalyProviderIds.PRIME_VIDEO,
      movieSectionId = SECTION_PRIME_MOVIES_NEW,
      movieCatalogId = CATALOG_PRIME_MOVIES_NEW,
      seriesSectionId = SECTION_PRIME_SERIES_NEW,
      seriesCatalogId = CATALOG_PRIME_SERIES_NEW,
      movieTitle = labels.primeMoviesNew,
      seriesTitle = labels.primeSeriesNew
    ),
    ProviderNewRow(
      slug = "apple",
      providerId = ItalyProviderIds.APPLE_TV_PLUS,
      movieSectionId = SECTION_APPLE_MOVIES_NEW,
      movieCatalogId = CATALOG_APPLE_MOVIES_NEW,
      seriesSectionId = SECTION_APPLE_SERIES_NEW,
      seriesCatalogId = CATALOG_APPLE_SERIES_NEW,
      movieTitle = labels.appleMoviesNew,
      seriesTitle = labels.appleSeriesNew,
      movieWindowDays = PROVIDER_MOVIES_EXTENDED_WINDOW_DAYS
    ),
    ProviderNewRow(
      slug = "paramount",
      providerId = ItalyProviderIds.PARAMOUNT_PLUS,
      movieSectionId = SECTION_PARAMOUNT_MOVIES_NEW,
      movieCatalogId = CATALOG_PARAMOUNT_MOVIES_NEW,
      seriesSectionId = SECTION_PARAMOUNT_SERIES_NEW,
      seriesCatalogId = CATALOG_PARAMOUNT_SERIES_NEW,
      movieTitle = labels.paramountMoviesNew,
      seriesTitle = labels.paramountSeriesNew,
      movieWindowDays = PROVIDER_MOVIES_EXTENDED_WINDOW_DAYS
    )
  )

  /**
   * Righe "Novità <provider>" usate dalla Home quando i cataloghi stock sono
   * Cinemeta. Ordine editoriale richiesto:
   * Netflix, HBO / Max, Disney+, Apple TV+, Paramount+ (Film e Serie alternate).
   *
   * HBO / Max usa il provider TMDB 1899 con `watch_region=IT`: la riga mostra solo
   * titoli che TMDB associa davvero al servizio in Italia, non produzioni HBO
   * generiche. La finestra film è estesa a 365 giorni perché il catalogo italiano
   * ha pochi titoli nella finestra standard.
   */
  internal fun providerNoveltyRows(labels: HomeLabels): List<ProviderNewRow> = listOf(
    ProviderNewRow(
      slug = "netflix",
      providerId = ItalyProviderIds.NETFLIX,
      movieSectionId = SECTION_NETFLIX_MOVIES_NEW,
      movieCatalogId = CATALOG_NETFLIX_MOVIES_NEW,
      seriesSectionId = SECTION_NETFLIX_SERIES_NEW,
      seriesCatalogId = CATALOG_NETFLIX_SERIES_NEW,
      movieTitle = labels.netflixMoviesNew,
      seriesTitle = labels.netflixSeriesNew
    ),
    ProviderNewRow(
      slug = "hbo_max",
      providerId = ItalyProviderIds.HBO_MAX,
      movieSectionId = SECTION_HBO_MAX_MOVIES_NEW,
      movieCatalogId = CATALOG_HBO_MAX_MOVIES_NEW,
      seriesSectionId = SECTION_HBO_MAX_SERIES_NEW,
      seriesCatalogId = CATALOG_HBO_MAX_SERIES_NEW,
      movieTitle = labels.hboMaxMoviesNew,
      seriesTitle = labels.hboMaxSeriesNew,
      movieWindowDays = PROVIDER_MOVIES_EXTENDED_WINDOW_DAYS
    ),
    ProviderNewRow(
      slug = "disney",
      providerId = ItalyProviderIds.DISNEY_PLUS,
      movieSectionId = SECTION_DISNEY_MOVIES_NEW,
      movieCatalogId = CATALOG_DISNEY_MOVIES_NEW,
      seriesSectionId = SECTION_DISNEY_SERIES_NEW,
      seriesCatalogId = CATALOG_DISNEY_SERIES_NEW,
      movieTitle = labels.disneyMoviesNew,
      seriesTitle = labels.disneySeriesNew
    ),
    ProviderNewRow(
      slug = "apple",
      providerId = ItalyProviderIds.APPLE_TV_PLUS,
      movieSectionId = SECTION_APPLE_MOVIES_NEW,
      movieCatalogId = CATALOG_APPLE_MOVIES_NEW,
      seriesSectionId = SECTION_APPLE_SERIES_NEW,
      seriesCatalogId = CATALOG_APPLE_SERIES_NEW,
      movieTitle = labels.appleMoviesNew,
      seriesTitle = labels.appleSeriesNew,
      movieWindowDays = PROVIDER_MOVIES_EXTENDED_WINDOW_DAYS
    ),
    ProviderNewRow(
      slug = "paramount",
      providerId = ItalyProviderIds.PARAMOUNT_PLUS,
      movieSectionId = SECTION_PARAMOUNT_MOVIES_NEW,
      movieCatalogId = CATALOG_PARAMOUNT_MOVIES_NEW,
      seriesSectionId = SECTION_PARAMOUNT_SERIES_NEW,
      seriesCatalogId = CATALOG_PARAMOUNT_SERIES_NEW,
      movieTitle = labels.paramountMoviesNew,
      seriesTitle = labels.paramountSeriesNew,
      movieWindowDays = PROVIDER_MOVIES_EXTENDED_WINDOW_DAYS
    )
  )

  /** Etichette nella lingua dell'app (le righe extra le mostrano come titolo). */
  internal fun labelsFor(language: String): HomeLabels =
    when (language.substringBefore("-").trim().lowercase()) {
      "en" -> HomeLabels(
        popularMovies = "Popular Movies",
        topRatedMovies = "Featured Movies",
        newMovies = "New Movies",
        popularSeries = "Popular TV Shows",
        topRatedSeries = "Featured TV Shows",
        newSeries = "New TV Shows",
        onAirSeries = "On Air TV Shows",
        digitalNew = "New on Digital",
        hboNew = "New on HBO",
        hboMaxMoviesNew = "New on HBO / Max Movies",
        hboMaxSeriesNew = "New on HBO / Max TV Shows",
        netflixMoviesNew = "New on Netflix Movies",
        netflixSeriesNew = "New on Netflix TV Shows",
        disneyMoviesNew = "New on Disney+ Movies",
        disneySeriesNew = "New on Disney+ TV Shows",
        primeMoviesNew = "New on Prime Video Movies",
        primeSeriesNew = "New on Prime Video TV Shows",
        appleMoviesNew = "New on Apple TV+ Movies",
        appleSeriesNew = "New on Apple TV+ TV Shows",
        paramountMoviesNew = "New on Paramount+ Movies",
        paramountSeriesNew = "New on Paramount+ TV Shows"
      )
      "es" -> HomeLabels(
        popularMovies = "Películas populares",
        topRatedMovies = "Destacadas: Películas",
        newMovies = "Novedades: Películas",
        popularSeries = "Series populares",
        topRatedSeries = "Destacadas: Series",
        newSeries = "Novedades: Series",
        onAirSeries = "Series en emisión",
        digitalNew = "Novedades digitales",
        hboNew = "Novedades HBO",
        hboMaxMoviesNew = "Novedades HBO / Max: Películas",
        hboMaxSeriesNew = "Novedades HBO / Max: Series",
        netflixMoviesNew = "Novedades Netflix: Películas",
        netflixSeriesNew = "Novedades Netflix: Series",
        disneyMoviesNew = "Novedades Disney+: Películas",
        disneySeriesNew = "Novedades Disney+: Series",
        primeMoviesNew = "Novedades Prime Video: Películas",
        primeSeriesNew = "Novedades Prime Video: Series",
        appleMoviesNew = "Novedades Apple TV+: Películas",
        appleSeriesNew = "Novedades Apple TV+: Series",
        paramountMoviesNew = "Novedades Paramount+: Películas",
        paramountSeriesNew = "Novedades Paramount+: Series"
      )
      "fr" -> HomeLabels(
        popularMovies = "Films populaires",
        topRatedMovies = "À la une : Films",
        newMovies = "Nouveautés Films",
        popularSeries = "Séries populaires",
        topRatedSeries = "À la une : Séries",
        newSeries = "Nouveautés Séries",
        onAirSeries = "Séries en diffusion",
        digitalNew = "Nouveautés numériques",
        hboNew = "Nouveautés HBO",
        hboMaxMoviesNew = "Nouveautés HBO / Max : Films",
        hboMaxSeriesNew = "Nouveautés HBO / Max : Séries",
        netflixMoviesNew = "Nouveautés Netflix : Films",
        netflixSeriesNew = "Nouveautés Netflix : Séries",
        disneyMoviesNew = "Nouveautés Disney+ : Films",
        disneySeriesNew = "Nouveautés Disney+ : Séries",
        primeMoviesNew = "Nouveautés Prime Video : Films",
        primeSeriesNew = "Nouveautés Prime Video : Séries",
        appleMoviesNew = "Nouveautés Apple TV+ : Films",
        appleSeriesNew = "Nouveautés Apple TV+ : Séries",
        paramountMoviesNew = "Nouveautés Paramount+ : Films",
        paramountSeriesNew = "Nouveautés Paramount+ : Séries"
      )
      "de" -> HomeLabels(
        popularMovies = "Beliebte Filme",
        topRatedMovies = "Empfohlen: Filme",
        newMovies = "Neue Filme",
        popularSeries = "Beliebte Serien",
        topRatedSeries = "Empfohlen: Serien",
        newSeries = "Neue Serien",
        onAirSeries = "Aktuelle Serien",
        digitalNew = "Neu digital",
        hboNew = "Neu auf HBO",
        hboMaxMoviesNew = "Neu auf HBO / Max: Filme",
        hboMaxSeriesNew = "Neu auf HBO / Max: Serien",
        netflixMoviesNew = "Neu auf Netflix: Filme",
        netflixSeriesNew = "Neu auf Netflix: Serien",
        disneyMoviesNew = "Neu auf Disney+: Filme",
        disneySeriesNew = "Neu auf Disney+: Serien",
        primeMoviesNew = "Neu auf Prime Video: Filme",
        primeSeriesNew = "Neu auf Prime Video: Serien",
        appleMoviesNew = "Neu auf Apple TV+: Filme",
        appleSeriesNew = "Neu auf Apple TV+: Serien",
        paramountMoviesNew = "Neu auf Paramount+: Filme",
        paramountSeriesNew = "Neu auf Paramount+: Serien"
      )
      else -> HomeLabels(
        popularMovies = "Popolari Film",
        topRatedMovies = "In evidenza Film",
        newMovies = "Novità Film",
        popularSeries = "Popolari Serie",
        topRatedSeries = "In evidenza Serie",
        newSeries = "Novità Serie TV",
        onAirSeries = "Serie TV in onda",
        digitalNew = "Novità in digitale",
        hboNew = "Novità HBO",
        hboMaxMoviesNew = "Novità HBO / Max Film",
        hboMaxSeriesNew = "Novità HBO / Max Serie",
        netflixMoviesNew = "Novità Netflix Film",
        netflixSeriesNew = "Novità Netflix Serie",
        disneyMoviesNew = "Novità Disney+ Film",
        disneySeriesNew = "Novità Disney+ Serie",
        primeMoviesNew = "Novità Prime Video Film",
        primeSeriesNew = "Novità Prime Video Serie",
        appleMoviesNew = "Novità Apple TV+ Film",
        appleSeriesNew = "Novità Apple TV+ Serie",
        paramountMoviesNew = "Novità Paramount+ Film",
        paramountSeriesNew = "Novità Paramount+ Serie"
      )
    }
}

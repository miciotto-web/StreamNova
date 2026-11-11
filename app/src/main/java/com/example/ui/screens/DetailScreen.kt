package com.example.ui.screens

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.example.R
import com.example.data.model.CastMember
import com.example.data.model.Episode
import com.example.data.model.ExtendedMediaDetails
import com.example.data.model.MediaDetailUiState
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.model.ProductionCompany
import com.example.data.model.WatchProgress
import androidx.compose.ui.text.style.TextAlign
import com.example.data.model.SeasonEpisodesUiState
import com.example.data.model.toEpisode
import com.example.data.repository.EpisodeResumeResolver
import com.example.data.repository.MediaRepository
import com.example.data.repository.SimklRepository
import com.example.data.stremio.StremioCatalogMapper
import com.example.data.util.DetailFactsMapper
import com.example.ui.components.EpisodeContextMenu
import com.example.ui.components.MediaActionBar
import com.example.ui.components.MediaActionBarFocus
import com.example.ui.components.ProviderBadge
import com.example.ui.components.ProviderConstants
import com.example.ui.components.QualityBadge
import com.example.ui.components.SpoilerBlurredImage
import com.example.ui.components.StreamingProvider
import com.example.ui.components.TvActionButton
import com.example.ui.components.TvFocusableBox
import com.example.ui.components.TvFocusBringIntoViewSpec
import com.example.ui.components.TvFocusRow
import com.example.ui.components.TvFocusAttachment
import com.example.ui.components.guard
import com.example.ui.components.tvFocusRequester
import com.example.ui.theme.NovaAccent
import com.example.ui.theme.NovaBackground
import com.example.ui.theme.NovaCardBg
import com.example.ui.theme.NovaCyan
import com.example.ui.theme.NovaCyanBright
import com.example.ui.theme.NovaGold
import com.example.ui.theme.NovaSurface
import com.example.ui.theme.NovaSurfaceVariant
import com.example.ui.theme.NovaTextMuted
import com.example.ui.theme.NovaTextPrimary
import com.example.ui.theme.NovaTextSecondary
import com.example.ui.util.FocusTrace
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Ombra proiettata di supporto per il titolo: garantisce leggibilità su artwork chiari.
private val DetailTitleShadow = TextStyle(
  shadow = Shadow(color = Color.Black.copy(alpha = 0.7f), blurRadius = 8f)
)

enum class DetailTab(val titleRes: Int) {
  EPISODI(R.string.detail_tab_episodes),
  PRODUZIONE(R.string.detail_tab_production),
  /**
   * Scheda informativa con i metadati TMDB (stato, rilascio, durata, certificazione,
   * paese). Per le serie TV usa solo i campi equivalenti realmente disponibili e
   * un'etichetta neutra («Dettagli»), mai dati cinematografici travestiti.
   */
  DETTAGLI_FILM(R.string.detail_tab_movie_details),
}

/**
 * Ordine delle schede della scheda Dettagli.
 *
 * Le schede preesistenti mantengono il loro ordine (Episodi solo per le serie, poi
 * Produzione); «Dettagli Film» si aggiunge subito dopo Produzione, quindi le due
 * sezioni restano adiacenti e raggiungibili con una sola pressione di D-pad.
 */
internal fun detailTabs(
  isTvSeries: Boolean,
  includeMovieDetails: Boolean = true
): List<DetailTab> = buildList {
  if (isTvSeries) add(DetailTab.EPISODI)
  add(DetailTab.PRODUZIONE)
  // I canali LIVE degli addon Stremio non hanno metadati TMDB: la scheda non viene
  // aggiunta (le schede preesistenti restano invariate).
  if (includeMovieDetails) add(DetailTab.DETTAGLI_FILM)
}

/**
 * Numero massimo di righe della sinossi nella scheda di dettaglio (configurabile nel
 * componente): oltre questo limite il testo viene troncato con ellissi e i pulsanti
 * Riproduci / Preferiti / ecc. restano visibili e raggiungibili con il D-pad.
 */
internal const val DETAIL_SYNOPSIS_MAX_LINES = 4

/**
 * La sinossi ridotta della scheda riceve il focus (e con OK apre il dialog del testo
 * completo) solo quando esiste davvero un testo da mostrare: per una sinossi vuota o
 * nulla la card non è renderizzata e nessun dialog è raggiungibile.
 */
internal fun hasFocusableSynopsis(synopsis: String?): Boolean = !synopsis.isNullOrBlank()

/**
 * Macchina a stati del dialog del testo completo della sinossi.
 *
 * INCAPACITÀ DEL DIALOG di cambiare lo stato del contenuto: il dialog espone solo
 * apertura e chiusura (Back o icona); chi lo apre e lo chiude non tocca riproduzione
 * né preferiti. Alla chiusura richiede il ripristino del focus sulla sinossi ridotta
 * della scheda ([refocusRequestPending], consumato una sola volta dall'effetto UI).
 */
internal class DetailSynopsisDialogState {
  var isDialogOpen by mutableStateOf(false)
    private set
  var refocusRequestPending by mutableStateOf(false)
    private set

  fun open() {
    isDialogOpen = true
  }

  fun dismiss() {
    if (isDialogOpen) {
      isDialogOpen = false
      // Focus verso la sinossi della scheda al ritorno dal dialog.
      refocusRequestPending = true
    }
  }

  fun consumeRefocusRequest() {
    refocusRequestPending = false
  }
}

private fun matchesSeries(stateSeriesId: String?, media: MediaItem): Boolean {
  if (stateSeriesId == null) return true
  if (stateSeriesId.equals(media.id, ignoreCase = true)) return true
  val mediaTmdb = media.tmdbId
  if (mediaTmdb != null && mediaTmdb > 0) {
    if (stateSeriesId == mediaTmdb.toString() || stateSeriesId == "tmdb_tv_$mediaTmdb") return true
  }
  val stateTmdb = stateSeriesId.removePrefix("tmdb_tv_").toIntOrNull()
  if (stateTmdb != null && stateTmdb > 0 && stateTmdb == media.tmdbId) return true
  return false
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun DetailScreen(
  media: MediaItem,
  allMedia: List<MediaItem>,
  onBackClick: () -> Unit,
  onPlayClick: (MediaItem, Episode?) -> Unit,
  onToggleFavorite: (String) -> Unit,
  onProviderClick: ((StreamingProvider) -> Unit)? = null,
  onMediaClick: ((MediaItem) -> Unit)? = null,
  detailUiState: MediaDetailUiState? = null,
  selectedSeasonNumber: Int = 1,
  seasonEpisodesUiState: SeasonEpisodesUiState? = null,
  onSeasonChange: ((MediaItem, Int) -> Unit)? = null,
  onEpisodeClick: ((MediaItem, Episode) -> Unit)? = null,
  onRetry: (() -> Unit)? = null,
  onToggleWatched: ((MediaItem) -> Unit)? = null,
  /**
   * Azioni del menu contestuale della riga episodio (pressione prolungata del tasto
   * centrale). Tutte nullable: se assenti il long-press non apre nulla, così le anteprime
   * e i chiamanti storici restano invariati.
   *
   *  - [onEpisodeWatchedChange]: marca l'episodio come visto (`true`) o da vedere (`false`);
   *  - [onMarkSeasonWatched]: marca TUTTA la stagione attiva come vista;
   *  - [onBrowseEpisodeSources]: riapre la scelta delle fonti per quell'episodio senza
   *    avviare la riproduzione.
   */
  onEpisodeWatchedChange: ((MediaItem, Episode, Boolean) -> Unit)? = null,
  onMarkSeasonWatched: ((MediaItem, Int, List<Episode>) -> Unit)? = null,
  onBrowseEpisodeSources: ((MediaItem, Episode) -> Unit)? = null,
  /**
   * «Serie già vista» accanto ai preferiti (solo serie TV): agisce sull'INTERA serie.
   */
  onToggleSeriesWatched: ((MediaItem) -> Unit)? = null,
  onPlayTrailer: ((MediaItem) -> Unit)? = null,
  /**
   * Selezione MANUALE della sorgente (modalità Debrid): non-null solo quando il motore
   * attivo è Debrid TorBox e il contenuto supporta la scelta della fonte. Riceve il
   * media corrente e l'episodio attivo (null per i film). Con null il pulsante
   * «Seleziona fonte» non è composto e la catena di focus resta quella storica.
   */
  onSelectSourceClick: ((MediaItem, Episode?) -> Unit)? = null,
  /**
   * Token che avanza a ogni chiusura del selettore delle sorgenti aperto sopra questa
   * scheda (Back o «Annulla», i percorsi già supportati). La scheda resta composta sotto
   * l'overlay, quindi nessun effetto di focus ri-parte alla chiusura: senza questo segnale
   * il fallback di focus di Compose (nodo focalizzato smontato) atterra sulla sinossi,
   * primo nodo focusabile della scheda. Al avanzamento del token il focus torna su
   * «Riproduci»; un token già avanzato PRIMA dell'ingresso in composizione non deve
   * strappare il focus (rientri da Consigliati/Cast/Casa di produzione) e viene quindi
   * ignorato confrontandolo con l'ultimo valore visto da questa composizione.
   */
  sourceDialogDismissedToken: Int = 0,
  /**
   * Token che avanza a ogni chiusura del trailer interno aperto da questa scheda (BACK del
   * telecomando o pulsante «Chiudi»). Il trailer è un overlay composto sopra la scheda,
   * che resta quindi montata sotto di esso: nessun effetto di focus ri-parte alla chiusura
   * e, senza questo segnale, il fallback di Compose lascerebbe il cursore sulla sinossi.
   * Al avanzamento del token il focus torna su «Riproduci», con lo stesso protocollo del
   * token delle sorgenti ma su un canale DISTINTO: i due ripristini non si consumano a
   * vicenda e la chiusura del selettore fonte (Fase 4) resta invariata. Un token già
   * avanzato PRIMA dell'ingresso in composizione viene ignorato, come per le sorgenti.
   */
  trailerDismissedToken: Int = 0,
  /**
   * Token che avanza a OGNI chiusura del player (BACK del telecomando). Il player è un
   * OVERLAY composto sopra il NavHost: la scheda Dettaglio resta composta sotto di esso e
   * al ritorno nessuno dei suoi effetti di focus ri-parte. Smontando il nodo focalizzato
   * del player, il fallback di focus di Compose atterra sul PRIMO nodo focusabile della
   * scheda (la sinossi), non sul pulsante «Riproduci».
   *
   * Al avanzamento di questo token la scheda riprende ESCLUSIVAMENTE il focus su
   * «Riproduci», con lo stesso protocollo di conferma reale (`onFocusChanged`) dei token
   * del selettore sorgenti e del trailer, su un canale DEDICATO: un token avanzato PRIMA
   * dell'ingresso in composizione (scheda aperta dopo la chiusura del player) viene
   * ignorato e non strappa il focus al ripristino del cast/produzione.
   */
  playerClosedToken: Int = 0,
  onCastMemberClick: ((CastMember) -> Unit)? = null,
  onCompanyClick: ((ProductionCompany) -> Unit)? = null,
  targetCastMemberId: Int? = null,
  onClearTargetCastMember: (() -> Unit)? = null,
  targetCompanyId: Int? = null,
  onClearTargetCompany: (() -> Unit)? = null,
  /**
   * true quando [media] è un CANALE LIVE di un addon Stremio (es. TvVoo): il
   * canale non ha stagioni, episodi né cast, quindi la scheda mostra solo
   * logo/titolo e le sorgenti, senza tab Episodi/Produzione.
   */
  isLiveChannel: Boolean = false,
  /**
   * Preferenza «Sfoca non visti» (Impostazioni → Continua a guardare) estesa alla lista
   * episodi della serie: le miniature degli episodi MAI visti sono sfocate anti-spoiler
   * (stesso criterio della riga Home, vedi [EpisodeRowItem]).
   */
  blurUnwatchedEpisodes: Boolean = false,
  modifier: Modifier = Modifier
) {
  // Resolve active media & extended details depending on UI State
  val (displayMedia, extendedDetails) = when (detailUiState) {
    is MediaDetailUiState.Success -> detailUiState.media to detailUiState.details
    is MediaDetailUiState.Loading -> (detailUiState.baseMedia ?: media) to null
    is MediaDetailUiState.Error -> (detailUiState.baseMedia ?: media) to null
    else -> media to null
  }



  val isTvSeries = displayMedia.type == MediaType.SERIE_TV && !isLiveChannel
  // «Dettagli Film» è sempre accanto a «Produzione»: l'ordine delle schede esistenti
  // resta invariato (Episodi, Produzione) e la nuova scheda si aggiunge in coda.
  val tabs = detailTabs(isTvSeries, includeMovieDetails = !isLiveChannel)

  // Tab selezionato PERSISTENTE nel backstack di Navigation (rememberSaveable):
  // tornando con Back da una scheda aperta dai Consigliati, la scheda originale rientra
  // nel composable con il tab «Altro come questo» (o quello attivo prima del click)
  // al posto di ripartire dal tab iniziale.
  var selectedTab by rememberSaveable(displayMedia.id) {
    val initialTab = if (targetCompanyId != null) {
      tabs.indexOf(DetailTab.PRODUZIONE).coerceAtLeast(0)
    } else 0
    mutableIntStateOf(initialTab)
  }

  LaunchedEffect(targetCompanyId) {
    if (targetCompanyId != null) {
      val prodIdx = tabs.indexOf(DetailTab.PRODUZIONE)
      if (prodIdx >= 0) selectedTab = prodIdx
    }
  }







  // Calculate available seasons
  val availableSeasons = remember(displayMedia, extendedDetails, selectedSeasonNumber) {
    val knownSeasons = buildSet {
      addAll(displayMedia.seasonEpisodesCount.keys.filter { it >= 0 })
      addAll(extendedDetails?.seasonEpisodesCount?.keys.orEmpty().filter { it >= 0 })
      addAll(displayMedia.episodes.map { it.seasonNumber }.filter { it >= 0 })
      if (displayMedia.lastWatchedSeason == 0 || selectedSeasonNumber == 0) add(0)
    }
    val maxSeasonNumber = knownSeasons.filter { it > 0 }.maxOrNull()
    val totalCount = maxOf(
      extendedDetails?.seasonsCount ?: displayMedia.seasonsCount ?: maxSeasonNumber ?: 1,
      maxSeasonNumber ?: 1
    ).coerceAtLeast(1)
    val numberedSeasons = (1..totalCount).toList()
    if (0 in knownSeasons) listOf(0) + numberedSeasons else numberedSeasons
  }

  var localSelectedSeason by remember(displayMedia.id) {
    val initial = displayMedia.lastWatchedSeason?.takeIf { it in availableSeasons }
      ?: availableSeasons.firstOrNull { it > 0 }
      ?: availableSeasons.firstOrNull()
      ?: 1
    mutableIntStateOf(initial)
  }

  val activeSeason = if (selectedSeasonNumber in availableSeasons) {
    selectedSeasonNumber
  } else {
    localSelectedSeason
  }

  val seasonRowState = rememberLazyListState()
  val seasonFocusRequesters = remember(displayMedia.id, availableSeasons) {
    availableSeasons.associateWith { FocusRequester() }
  }
  val seasonFocusAttachments = remember(displayMedia.id, availableSeasons) {
    availableSeasons.associateWith { TvFocusAttachment() }
  }
  val firstEpisodeFocusRequester = remember(displayMedia.id) { FocusRequester() }
  val firstEpisodeFocusAttachment = remember(displayMedia.id) { TvFocusAttachment() }
  val selectedSeasonRequester = seasonFocusRequesters[activeSeason]
  val selectedSeasonAttachment = seasonFocusAttachments[activeSeason]
  val episodeFocusTarget = firstEpisodeFocusAttachment.guard(firstEpisodeFocusRequester)
    ?: FocusRequester.Default
  val selectedSeasonFocusTarget = selectedSeasonAttachment.guard(selectedSeasonRequester)
    ?: FocusRequester.Default

  LaunchedEffect(activeSeason, availableSeasons) {
    val selectedIndex = availableSeasons.indexOf(activeSeason)
    if (selectedIndex >= 0) seasonRowState.animateScrollToItem(selectedIndex)
  }

  val isMatchingSeries = matchesSeries(
    when (seasonEpisodesUiState) {
      is SeasonEpisodesUiState.Loading -> seasonEpisodesUiState.seriesId
      is SeasonEpisodesUiState.Success -> seasonEpisodesUiState.seriesId
      is SeasonEpisodesUiState.Error -> seasonEpisodesUiState.seriesId
      else -> null
    },
    displayMedia
  )

  var simklWatchedEpisodes by remember(displayMedia.tmdbId, activeSeason) { mutableStateOf<Set<Int>>(emptySet()) }
  val simklAccount by SimklRepository.accountState.collectAsState()
  val simklWatchedRevision by SimklRepository.watchedRevision.collectAsState()
  LaunchedEffect(displayMedia.tmdbId, activeSeason, simklAccount.isLoggedIn, simklWatchedRevision) {
    simklWatchedEpisodes = emptySet()
    if (!simklAccount.isLoggedIn) return@LaunchedEffect
    val tmdbId = displayMedia.tmdbId ?: return@LaunchedEffect
    simklWatchedEpisodes = SimklRepository.getWatchedEpisodes(tmdbId, activeSeason)
  }

  val selectedSeasonDetails = (seasonEpisodesUiState as? SeasonEpisodesUiState.Success)
    ?.takeIf { it.seasonNumber == activeSeason && isMatchingSeries }
    ?.season

  val isSeasonLoading = seasonEpisodesUiState is SeasonEpisodesUiState.Loading && seasonEpisodesUiState.seasonNumber == activeSeason && isMatchingSeries
  val seasonErrorMessage = if (seasonEpisodesUiState is SeasonEpisodesUiState.Error && seasonEpisodesUiState.seasonNumber == activeSeason && isMatchingSeries) {
    seasonEpisodesUiState.message
  } else null

  // Filter or resolve episodes for the currently active season
  val episodesForSeason = remember(displayMedia, activeSeason, seasonEpisodesUiState) {
    val rawList = if (seasonEpisodesUiState is SeasonEpisodesUiState.Success && seasonEpisodesUiState.seasonNumber == activeSeason && isMatchingSeries) {
      seasonEpisodesUiState.season.episodes.map { epItem ->
        val saved = MediaRepository.getEpisodeProgress(displayMedia.id, epItem.seasonNumber, epItem.episodeNumber)
        epItem.toEpisode(displayMedia.id, saved, displayMedia.imdbId)
      }
    } else {
      val eps = displayMedia.episodes.filter { it.seasonNumber == activeSeason }
      if (eps.isNotEmpty()) {
        eps
      } else {
        (1..8).map { epIndex ->
          Episode(
            id = "${displayMedia.id}_s${activeSeason}e${epIndex}",
            seasonNumber = activeSeason,
            episodeNumber = epIndex,
            title = "Episodio $epIndex",
            synopsis = "Episodio $epIndex della Stagione $activeSeason della serie ${displayMedia.title}.",
            durationMinutes = displayMedia.durationMinutes.takeIf { it > 0 } ?: 55,
            thumbnailUrl = displayMedia.backdropUrl,
            thumbnailRes = displayMedia.backdropRes,
            videoUrl = displayMedia.videoUrl,
            rating = displayMedia.rating,
            seriesImdbId = displayMedia.imdbId
          )
        }
      }
    }
    rawList.map { ep ->
      val saved = MediaRepository.getEpisodeProgress(displayMedia.id, ep.seasonNumber, ep.episodeNumber)
      if (saved > 0L) ep.copy(currentProgressMs = saved) else ep
    }
  }

  // Menu contestuale della riga episodio (pressione prolungata del tasto centrale).
  // Aperto SOLO se almeno un'azione è disponibile: anteprime e chiamanti storici senza
  // callback restano identici. Il menu vive in un Dialog separato ed è chiuso da ogni
  // azione o da BACK.
  val episodeMenuAvailable = onEpisodeWatchedChange != null ||
    onMarkSeasonWatched != null ||
    onBrowseEpisodeSources != null
  var episodeContextMenu by remember(displayMedia.id) { mutableStateOf<Episode?>(null) }

  // Cast members from TMDB extended details or fallback from media item
  val castMembers = remember(displayMedia, extendedDetails) {
    val director = extendedDetails?.directorMember
      ?: if (displayMedia.director.isNotBlank()) {
        CastMember(
          id = -1,
          name = displayMedia.director,
          character = if (displayMedia.type == MediaType.SERIE_TV && !isLiveChannel) "Creatore" else "Regista",
          profileUrl = null
        )
      } else null
    val rawCast = extendedDetails?.cast?.takeIf { it.isNotEmpty() }
      ?: displayMedia.cast.mapIndexed { index, name ->
        CastMember(id = index + 1000, name = name, character = "Cast principale", profileUrl = null)
      }
    if (director != null) {
      listOf(director) + rawCast.filter { it.id != director.id && !it.name.equals(director.name, ignoreCase = true) }
    } else {
      rawCast
    }
  }







  Box(
    modifier = modifier
      .fillMaxSize()
      .background(NovaBackground)
  ) {
    // 1. Backdrop immersivo (16:9) con vignette gradient a doppio asse.
    // L'artwork resta confinato alla fascia superiore: la vignette verticale lo fonde
    // al 100% nel background della schermata, raccordandolo con trama, cast, episodi e sorgenti.
    val backdropUrl = extendedDetails?.backdropUrl ?: displayMedia.backdropUrl
    val vignetteColor = MaterialTheme.colorScheme.background
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .height(500.dp)
        .align(Alignment.TopCenter)
    ) {
      if (!backdropUrl.isNullOrBlank()) {
        AsyncImage(
          model = ImageRequest.Builder(LocalContext.current)
            .data(backdropUrl)
            .crossfade(350)
            .build(),
          contentDescription = displayMedia.title,
          contentScale = ContentScale.Crop,
          modifier = Modifier.fillMaxSize()
        )
      } else {
        val backdropRes = displayMedia.backdropRes
        if (backdropRes != null) {
        Image(
          painter = painterResource(id = backdropRes),
          contentDescription = displayMedia.title,
          contentScale = ContentScale.Crop,
          modifier = Modifier.fillMaxSize()
        )
        } else {
          Box(
            modifier = Modifier
              .fillMaxSize()
              .background(
                Brush.verticalGradient(
                  colors = listOf(Color(0xFF0F172A), NovaBackground)
                )
              )
          )
        }
      }

      // Vignette a doppio asse:
      // - Orizzontale (sinistra -> destra): semitrasparenza scura sul lato sinistro per
      //   garantire contrasto e leggibilità immediata di titolo e metadati.
      // - Verticale (basso -> alto): fusione al 100% nel background della schermata.
      Box(
        modifier = Modifier
          .fillMaxSize()
          .background(
            Brush.horizontalGradient(
              colors = listOf(
                Color.Black.copy(alpha = 0.70f),
                Color.Black.copy(alpha = 0.30f),
                Color.Transparent
              ),
              startX = 0f,
              endX = Float.POSITIVE_INFINITY
            )
          )
          .background(
            Brush.verticalGradient(
              colors = listOf(
                Color.Transparent,
                Color.Transparent,
                vignetteColor.copy(alpha = 0.6f),
                vignetteColor
              ),
              startY = 0f,
              endY = Float.POSITIVE_INFINITY
            )
          )
      )
    }

   // Main Scrollable Content
   val listState = rememberLazyListState()

    // Traccia SOLO DEBUG della vita della scheda: distingue "il Dettaglio è ancora composto
    // sotto il player" da "la destinazione è stata smontata/poppata".
    DisposableEffect(displayMedia.id) {
      FocusTrace.log("DETAIL", "compose mount media=${displayMedia.id}")
      onDispose { FocusTrace.log("DETAIL", "compose dispose media=${displayMedia.id}") }
    }

    // Catena D-pad della barra azioni: Riproduci -> Cuore -> Occhio -> Trailer
    // (-> Seleziona fonte quando il pulsante è presente, cioè in modalità Debrid).
    // "Riproduci" e' il PRIMO (e unico) nodo focusabile della zona superiore:
    // header, logo, titolo, badge e backdrop sono puramente visivi e non
    // ricevono mai il focus D-pad.
    val actionFocus = remember(displayMedia.id) { MediaActionBarFocus() }

    // Scroll guard: la viewport resta CONGELATA finche' l'utente non preme
    // attivamente SU/GIU' per uscire dalla riga dei pulsanti.
    // `isActionBarFocused` blocca qualunque scroll (anche quello innescato dalla
    // ricerca del focus) mentre il cursore e' su Riproduci/Preferiti/Visto/Trailer,
    // cosi' i movimenti DESTRA/SINISTRA non spostano di un pixel backdrop e header.
    var isBringIntoViewAllowed by remember { mutableStateOf(false) }
    var isActionBarFocused by remember { mutableStateOf(false) }
    // true quando il focus e' atterrato con certezza su "Riproduci".
    var isPlayFocused by remember { mutableStateOf(false) }

    val density = LocalDensity.current
    val tvFocusSpec = remember(density) {
      TvFocusBringIntoViewSpec(
        parentFraction = 0.3f,
        topHeaderOffsetPx = with(density) { 64.dp.toPx() },
        bottomMarginPx = with(density) { 48.dp.toPx() }
      )
    }
    val guardedBringIntoViewSpec = remember(tvFocusSpec) {
      object : BringIntoViewSpec {
        override fun calculateScrollDistance(
          offset: Float,
          size: Float,
          containerSize: Float
        ): Float {
          // Focus dentro la pulsantiera (o nell'item di testa della LazyColumn che
          // ospita titolo + barra azioni): mai scrollare.
          if (isActionBarFocused) return 0f
          // Scroll consentito solo dopo una pressione di SU/GIU' (vedi key handler).
          if (!isBringIntoViewAllowed) return 0f
          return tvFocusSpec.calculateScrollDistance(offset, size, containerSize)
        }
      }
    }

    // Rientro del focus sulla pulsantiera (risalita dalle sezioni inferiori):
    // il guard di `calculateScrollDistance` tiene fermo il bring-into-view mentre
    // `isActionBarFocused` e' true, quindi lo scroll fino in cima va fatto qui in modo
    // esplicito. L'animazione programmatica (`animateScrollToItem`) NON passa dal
    // BringIntoViewSpec, quindi non viene bloccata dal guard: la testata completa
    // (logo/titolo, badge, trama e pulsantiera) torna interamente visibile a offset 0.
    LaunchedEffect(isActionBarFocused) {
      if (isActionBarFocused &&
        (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0)
      ) {
        listState.animateScrollToItem(0, 0)
      }
    }

    // Focus iniziale: IMMEDIATAMENTE e ESCLUSIVAMENTE su "Riproduci", con vista
    // ferma (offset di scroll = 0). `requestFocus()` non lancia eccezioni se il nodo
    // non e' ancora agganciato al sistema di focus (no-op silenzioso), quindi si
    // ritenta finche' `onPlayFocusChanged` non conferma che il focus e' sul pulsante.
    // Anche questo flag è persistente nel backstack: dopo il Back dai Consigliati resta
    // true (l'utente aveva navigato prima di aprire la scheda consigliata), così
    // l'incoronazione di "Riproduci" non strappa il focus ripristinato sulla card.
    var hasUserNavigatedAway by rememberSaveable(displayMedia.id) { mutableStateOf(false) }
    val isTargetingSubItem = targetCastMemberId != null ||
      targetCompanyId != null

    // Dialog del testo completo della sinossi + ripristino del focus sulla sinossi
    // ridotta della scheda dopo la chiusura (Back / icona di chiusura).
    val synopsisDialogState = remember(displayMedia.id) { DetailSynopsisDialogState() }
    val synopsisFocusRequester = remember(displayMedia.id) { FocusRequester() }
    // Sinossi completa dell'opera: usata sia dalla card ridotta (troncata a max righe)
    // sia dal dialog del testo completo. A livello di funzione perché la serve a entrambi.
    val synopsisText = extendedDetails?.overview?.takeIf { it.isNotBlank() } ?: displayMedia.synopsis
    LaunchedEffect(synopsisDialogState.refocusRequestPending) {
      if (!synopsisDialogState.refocusRequestPending) return@LaunchedEffect
      synopsisDialogState.consumeRefocusRequest()
      try {
        synopsisFocusRequester.requestFocus()
      } catch (_: Exception) {}
    }

    // Focus iniziale: IMMEDIATAMENTE, ESCLUSIVAMENTE e TASSATIVAMENTE su "Riproduci" (Punto 2).
    // Finché l'utente non naviga attivamente con il D-pad, il focus resta ancorato su "Riproduci".
    LaunchedEffect(displayMedia.id, detailUiState, isTargetingSubItem) {
      if (isTargetingSubItem || hasUserNavigatedAway) {
        return@LaunchedEffect
      }
      isBringIntoViewAllowed = false
      isActionBarFocused = false
      isPlayFocused = false
      listState.scrollToItem(0, 0)
      repeat(20) {
        withFrameNanos { } // attende composizione + layout del nodo focusabile
        try {
          actionFocus.play.requestFocus()
        } catch (_: Exception) {
          // FocusRequester non ancora inizializzato: riprova al frame successivo.
        }
        if (isPlayFocused) {
          listState.scrollToItem(0, 0)
          return@LaunchedEffect
        }
        delay(32)
      }
      listState.scrollToItem(0, 0)
    }

    // Ritorno dal selettore delle sorgenti (chiuso con Back o «Annulla»): la scheda è
    // rimasta composta sotto l'overlay, quindi l'effetto di focus iniziale qui sopra non
    // ri-parte e il fallback di focus di Compose lascerebbe il cursore sulla sinossi.
    // Al avanzamento del token il focus torna ESCLUSIVAMENTE su «Riproduci», con lo
    // stesso protocollo del focus iniziale (ritenti per frame finché onPlayFocusChanged
    // non conferma l'atterraggio). Solo i token che avanzano MENTRE questa composizione
    // è viva sono consumati: un token ereditato dall'ingresso non tocca il focus.
    var lastSeenSourceDismissedToken by remember(displayMedia.id) {
      mutableStateOf(sourceDialogDismissedToken)
    }
    LaunchedEffect(sourceDialogDismissedToken) {
      if (sourceDialogDismissedToken == lastSeenSourceDismissedToken) return@LaunchedEffect
      lastSeenSourceDismissedToken = sourceDialogDismissedToken
      repeat(20) {
        withFrameNanos { } // attende composizione + smontaggio dell'overlay e layout del nodo
        try {
          actionFocus.play.requestFocus()
        } catch (_: Exception) {
          // FocusRequester non ancora inizializzato: riprova al frame successivo.
        }
        if (isPlayFocused) {
          listState.scrollToItem(0, 0)
          return@LaunchedEffect
        }
        delay(32)
      }
      listState.scrollToItem(0, 0)
    }

    // Ritorno dal TRAILER interno (Back del telecomando o pulsante «Chiudi»): stesso
    // scenario strutturale del selettore delle sorgenti — il trailer è un overlay di
    // MainActivity composto sopra il NavHost, questa scheda resta viva sotto di esso e
    // nessuno dei suoi effetti di focus ri-parte. Smontando il pulsante di chiusura del
    // trailer, il fallback di Compose lasciava il cursore sulla sinossi.
    // Il segnale è un token DEDICATO (non quello delle sorgenti): ogni chiusura avvenuta
    // mentre questa composizione è viva riporta il focus ESCLUSIVAMENTE su «Riproduci»,
    // film o serie TV. Un token ereditato all'ingresso (apertura della scheda dopo una
    // chiusura precedente) viene ignorato, come per le sorgenti.
    var lastSeenTrailerDismissedToken by remember(displayMedia.id) {
      mutableStateOf(trailerDismissedToken)
    }
    LaunchedEffect(trailerDismissedToken) {
      if (trailerDismissedToken == lastSeenTrailerDismissedToken) return@LaunchedEffect
      lastSeenTrailerDismissedToken = trailerDismissedToken
      repeat(20) {
        withFrameNanos { } // attende composizione + smontaggio dell'overlay e layout del nodo
        try {
          actionFocus.play.requestFocus()
        } catch (_: Exception) {
          // FocusRequester non ancora inizializzato: riprova al frame successivo.
        }
        if (isPlayFocused) {
          listState.scrollToItem(0, 0)
          return@LaunchedEffect
        }
        delay(32)
      }
      listState.scrollToItem(0, 0)
    }

    // Ritorno dal PLAYER: stesso scenario strutturale del trailer e del selettore sorgenti
    // (overlay di MainActivity sopra il NavHost, scheda viva sotto di esso). Alla chiusura
    // del player il nodo focalizzato dell'overlay viene smontato e il fallback di focus di
    // Compose atterra sul primo nodo focusabile della scheda (la sinossi): la scheda non è
    // mai stata smontata, quindi il suo effetto di focus iniziale non ri-parte.
    //
    // Al segnale il focus torna su «Riproduci» con l'attesa legata alla COMPOSIZIONE
    // (`withFrameNanos`, nessun ritardo arbitrario) e con la conferma REALE del focus
    // (`onPlayFocusChanged`): `requestFocus()` da solo può essere scartato mentre l'overlay
    // viene smontato. Canale DEDICATO (non quello delle sorgenti o del trailer): un rientro
    // dal player non consuma i loro ripristini e viceversa. Un token ereditato all'ingresso
    // in composizione non tocca il focus (rientri da Consigliati/Cast/Produzione).
    var lastSeenPlayerClosedToken by remember(displayMedia.id) {
      mutableStateOf(playerClosedToken)
    }
    LaunchedEffect(playerClosedToken) {
      if (playerClosedToken == lastSeenPlayerClosedToken) return@LaunchedEffect
      lastSeenPlayerClosedToken = playerClosedToken
      FocusTrace.log(
        "DETAIL",
        "playerClosedToken=$playerClosedToken: ripristino focus su Riproduci " +
          "(media=${displayMedia.id}, playFocusedBefore=$isPlayFocused)"
      )
      var attempts = 0
      while (!isPlayFocused && attempts < 20) {
        attempts++
        withFrameNanos { } // smontaggio dell'overlay + layout del nodo della scheda
        try {
          actionFocus.play.requestFocus()
        } catch (_: Exception) {
          // FocusRequester non ancora agganciato: si ritenta al frame successivo.
        }
        withFrameNanos { } // lascia applicare la richiesta e arrivare onFocusChanged
      }
      FocusTrace.log(
        "DETAIL",
        "playerClosedToken=$playerClosedToken: tentativi=$attempts " +
          "isPlayFocused=$isPlayFocused"
      )
      if (isPlayFocused) {
        // La pulsantiera torna interamente in vista (offset 0) con il focus confermato.
        listState.scrollToItem(0, 0)
      }
    }
   CompositionLocalProvider(LocalBringIntoViewSpec provides guardedBringIntoViewSpec) {
   LazyColumn(
     state = listState,
     modifier = Modifier
       .fillMaxSize()
       .onPreviewKeyEvent { event ->
         if (event.type == KeyEventType.KeyDown) {
           val isNavKey = event.key == Key.DirectionDown ||
             event.key == Key.DirectionUp ||
             event.key == Key.DirectionRight ||
             event.key == Key.DirectionLeft
           if (isNavKey && isPlayFocused) {
             hasUserNavigatedAway = true
           }
           // Sbloccano lo scroll SOLO SU/GIU' (uscita verticale dalla pulsantiera).
           // LEFT/RIGHT e ogni altro tasto non muovono la viewport: il bring-into-view
           // resta azzerato, quindi lo scorrimento orizzontale tra i pulsanti non
           // nasconde mai backdrop e header.
           isBringIntoViewAllowed = event.key == Key.DirectionDown ||
             event.key == Key.DirectionUp
         }
         false
       },
     contentPadding = PaddingValues(top = 28.dp, bottom = 48.dp)
   ) {
      // Loading Shimmer or Error Banner
      if (detailUiState is MediaDetailUiState.Loading) {
        item {
          Row(
            modifier = Modifier
              .padding(start = 32.dp, bottom = 12.dp)
              .background(NovaSurfaceVariant.copy(alpha = 0.8f), RoundedCornerShape(20.dp))
              .border(1.dp, NovaCyan.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
              .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            CircularProgressIndicator(
              modifier = Modifier.size(14.dp),
              color = NovaCyanBright,
              strokeWidth = 2.dp
            )
            Text(
              text = stringResource(R.string.detail_loading_tmdb),
              color = NovaCyanBright,
              fontSize = 12.sp,
              fontWeight = FontWeight.Medium
            )
          }
        }
      } else if (detailUiState is MediaDetailUiState.Error) {
        item {
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 32.dp, vertical = 8.dp)
              .background(Color(0xFF3B1515).copy(alpha = 0.9f), RoundedCornerShape(12.dp))
              .border(1.dp, Color(0xFFEF4444).copy(alpha = 0.6f), RoundedCornerShape(12.dp))
              .padding(14.dp)
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween,
              modifier = Modifier.fillMaxWidth()
            ) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
              ) {
                Icon(
                  imageVector = Icons.Default.Warning,
                  contentDescription = null,
                  tint = Color(0xFFEF4444),
                  modifier = Modifier.size(20.dp)
                )
                Text(
                  text = stringResource(R.string.detail_partial_error, detailUiState.message ?: ""),
                  color = Color.White,
                  fontSize = 12.sp,
                  fontWeight = FontWeight.Medium
                )
              }
              if (onRetry != null) {
                TvActionButton(
                  text = stringResource(R.string.action_retry),
                  icon = Icons.Default.Refresh,
                  isPrimary = true,
                  onClick = onRetry
                )
              }
            }
          }
          Spacer(modifier = Modifier.height(10.dp))
        }
      }

      // Title & Metadata & Action buttons
      item {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .padding(start = 32.dp, end = 260.dp)
        ) {
          // Streaming Provider Row: badge PURAMENTE VISIVO (nessun focus D-pad),
          // cosi' nulla sopra la pulsantiera puo' catturare il focus. La
          // selezione del provider resta disponibile dall'Hub in Home.
          val providerObj = ProviderConstants.ALL.firstOrNull { it.id == displayMedia.provider?.lowercase() }
          if (providerObj != null) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              ProviderBadge(provider = providerObj)
            }
          }

          Spacer(modifier = Modifier.height(10.dp))

          // Clear logo originale (TMDB images.logos, priorita' it -> en): sostituisce il
          // poster rettangolare come titolo visivo. Se assente, titolo testuale display.
          val clearLogoUrl = extendedDetails?.logoUrl?.takeIf { it.isNotBlank() }
            ?: displayMedia.logoUrl?.takeIf { it.isNotBlank() }
          if (clearLogoUrl != null) {
            SubcomposeAsyncImage(
              model = ImageRequest.Builder(LocalContext.current)
                .data(clearLogoUrl)
                .crossfade(true)
                .build(),
              contentDescription = displayMedia.title,
              contentScale = ContentScale.Fit,
              alignment = Alignment.CenterStart,
              modifier = Modifier
                .heightIn(min = 80.dp, max = 100.dp)
                .widthIn(max = 480.dp),
              error = {
                Text(
                  text = displayMedia.title,
                  color = NovaTextPrimary,
                  fontSize = 36.sp,
                  fontWeight = FontWeight.Black,
                  letterSpacing = 0.5.sp,
                  style = DetailTitleShadow
                )
              }
            )
          } else {
            Text(
              text = displayMedia.title,
              color = NovaTextPrimary,
              fontSize = 36.sp,
              fontWeight = FontWeight.Black,
              letterSpacing = 0.5.sp,
              style = DetailTitleShadow
            )
          }

          // Tagline if available
          if (!extendedDetails?.tagline.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
              text = "\"${extendedDetails?.tagline}\"",
              color = NovaCyanBright.copy(alpha = 0.85f),
              fontSize = 14.sp,
              fontWeight = FontWeight.Normal
            )
          }

          Spacer(modifier = Modifier.height(8.dp))

          // Badge compatti: Anno • Durata/Stagioni • Classificazione età • Rating TMDB
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            val displayYear = extendedDetails?.releaseYear ?: displayMedia.year
            if (displayYear > 0) {
              QualityBadge(text = displayYear.toString())
            }

            val durationText = if (displayMedia.type == MediaType.SERIE_TV && !isLiveChannel) {
              val sCount = extendedDetails?.seasonsCount ?: displayMedia.seasonsCount ?: 1
              val epCount = extendedDetails?.episodesCount
              if (epCount != null && epCount > 0) {
                stringResource(R.string.detail_seasons_episodes_format, sCount, epCount)
              } else {
                stringResource(R.string.hero_seasons_count, sCount)
              }
            } else {
              val durMins = extendedDetails?.durationMinutes?.takeIf { it > 0 } ?: displayMedia.durationMinutes
              val h = durMins / 60
              val m = durMins % 60
              if (h > 0) "${h}h ${m}m" else "${m}m"
            }
            QualityBadge(text = durationText)

            val ageRating = extendedDetails?.ageRating ?: displayMedia.ageRating
            ageRating?.let { QualityBadge(text = "$it+") }

            val tmdbRating = extendedDetails?.rating?.takeIf { it > 0f }
              ?: displayMedia.tmdbRating?.takeIf { it > 0f }
            tmdbRating?.let { rating ->
              QualityBadge(
                text = String.format(Locale.US, "%.1f", rating),
                leadingIcon = Icons.Default.Star,
                leadingIconTint = NovaGold
              )
            }
          }

          Spacer(modifier = Modifier.height(8.dp))

          // Genre tags/chips
          val displayGenres = extendedDetails?.genres?.takeIf { it.isNotEmpty() } ?: displayMedia.genres
          if (displayGenres.isNotEmpty()) {
            Row(
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              displayGenres.take(5).forEach { genre ->
                Box(
                  modifier = Modifier
                    .background(NovaSurfaceVariant.copy(alpha = 0.8f), RoundedCornerShape(14.dp))
                    .border(1.dp, NovaCyan.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 10.dp, vertical = 3.dp)
                ) {
                  Text(
                    text = genre,
                    color = NovaCyanBright,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                  )
                }
              }
            }
          }

          Spacer(modifier = Modifier.height(14.dp))

          // Sinossi della scheda (Overview): limitata a DETAIL_SYNOPSIS_MAX_LINES righe con
          // ellissi, così i pulsanti Riproduci / Preferiti / Visto / Trailer restano sempre
          // visibili e raggiungibili col D-pad anche su sinossi molto lunghe. La card
          // ridotta riceve il focus e con OK apre il dialog del testo completo; senza
          // testo non è renderizzata e nessun elemento resta focalizzabile.
          // Resa grafica locale: sfondo 100% trasparente (lascia visibile l'immagine di sfondo),
          // nessun riempimento navy e nessuna ombra hardware (evita la fascia scura e l'alone blu
          // eccessivo generati su elementi ampi e trasparenti da TvFocusableBox), bordo ciano
          // sottile e nitido (1.5 dp con gradiente NovaCyan -> NovaCyanBright) e testo ciano a fuoco.
          if (hasFocusableSynopsis(synopsisText)) {
            var isSynopsisFocused by remember(displayMedia.id) { mutableStateOf(false) }
            var isConfirmKeyDown by remember { mutableStateOf(false) }
            val synopsisShape = remember { RoundedCornerShape(12.dp) }

            Box(
              modifier = Modifier
                .padding(vertical = 2.dp)
                .zIndex(if (isSynopsisFocused) 10f else 1f)
                .focusRequester(synopsisFocusRequester)
                .focusProperties { down = actionFocus.play }
                .onFocusChanged { isSynopsisFocused = it.isFocused }
                .onKeyEvent { keyEvent ->
                  val keyCode = keyEvent.nativeKeyEvent.keyCode
                  val isConfirm = keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                    keyCode == KeyEvent.KEYCODE_ENTER ||
                    keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                  if (isConfirm) {
                    when (keyEvent.type) {
                      KeyEventType.KeyDown -> {
                        isConfirmKeyDown = true
                        true
                      }
                      KeyEventType.KeyUp -> {
                        if (isConfirmKeyDown) {
                          isConfirmKeyDown = false
                          synopsisDialogState.open()
                          true
                        } else {
                          false
                        }
                      }
                      else -> false
                    }
                  } else {
                    false
                  }
                }
                .focusable()
                .clickable(
                  interactionSource = remember { MutableInteractionSource() },
                  indication = null
                ) {
                  synopsisDialogState.open()
                }
                .then(
                  if (isSynopsisFocused) {
                    Modifier.border(
                      width = 1.5.dp,
                      brush = Brush.horizontalGradient(
                        listOf(NovaCyan, NovaCyanBright)
                      ),
                      shape = synopsisShape
                    )
                  } else {
                    Modifier
                  }
                )
                .clip(synopsisShape)
            ) {
              Text(
                text = synopsisText,
                color = if (isSynopsisFocused) NovaCyanBright else NovaTextSecondary,
                fontSize = 14.sp,
                lineHeight = 22.sp,
                maxLines = DETAIL_SYNOPSIS_MAX_LINES,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(horizontal = 10.dp, vertical = 8.dp)
              )
            }
          }

          Spacer(modifier = Modifier.height(24.dp))

          // Action Buttons: "Riproduci" / "Guarda ora" + "+ La mia lista"
          Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            val resumeEpisode = if (displayMedia.type == MediaType.SERIE_TV && !isLiveChannel) {
              // L'ultima visione salvata (o S1E1 per un titolo mai avviato) è autorevole:
              // se la stagione non è ancora caricata l'episodio viene ricostruito con gli
              // stessi numeri, mai sostituito da S1E1 (bug "riprende la prima puntata").
              val resolved = EpisodeResumeResolver.resolveWithLookup(
                media = displayMedia,
                extraEpisodes = episodesForSeason,
                progressOf = { season, episode ->
                  MediaRepository.getEpisodeProgress(displayMedia.id, season, episode)
                },
                durationOf = { season, episode ->
                  MediaRepository.getEpisodeDurationMs(displayMedia.id, season, episode)
                }
              )
              resolved
            } else null

            val playButtonLabel = if (displayMedia.type == MediaType.SERIE_TV && !isLiveChannel) {
              val ep = resumeEpisode
              if (ep != null && ep.currentProgressMs > 0L) {
                stringResource(R.string.detail_resume_episode_format, ep.seasonNumber, ep.episodeNumber)
              } else if (ep != null) {
                stringResource(R.string.detail_play_episode_format, ep.seasonNumber, ep.episodeNumber)
              } else {
                stringResource(R.string.detail_play_episode_format, 1, 1)
              }
            } else if (displayMedia.currentProgressMs > 0L) {
              stringResource(R.string.home_section_continue_watching)
            } else {
              stringResource(R.string.action_play)
            }

            // Barra azioni: pillola Riproduci + Cuore (preferiti) + Occhio (visto) +
            // Trailer YouTube interno (mai Intent verso l'app esterna) +
            // Seleziona fonte (solo in modalità Debrid, via onSelectSourceClick).
            // SU dalla barra: sale verso la sinossi se presente, altrimenti resta bloccato.
            MediaActionBar(
              playLabel = playButtonLabel,
              onPlay = { onPlayClick(displayMedia, resumeEpisode) },
              isFavorite = displayMedia.isFavorite,
              onToggleFavorite = { onToggleFavorite(displayMedia.id) },
              isWatched = displayMedia.isWatched,
              onToggleWatched = { onToggleWatched?.invoke(displayMedia) },
              // «Serie già vista»: solo serie TV reali (non canali live) e solo quando il
              // chiamante espone l'azione. `completedAt` distingue la serie conclusa da
              // una serie in corso con l'episodio corrente finito.
              onToggleSeriesWatched = onToggleSeriesWatched
                ?.takeIf { isTvSeries }
                ?.let { callback -> { callback(displayMedia) } },
              isSeriesWatched = displayMedia.completedAt != null,
              onPlayTrailer = { onPlayTrailer?.invoke(displayMedia) },
              // Selezione manuale: il target è lo stesso episodio del pulsante
              // Riproduci (resume), così il dialog mostra il contesto SxE corretto;
              // per i film resumeEpisode è null.
              onSelectSource = onSelectSourceClick?.let { callback ->
                { callback(displayMedia, resumeEpisode) }
              },
              focus = actionFocus,
              upTarget = if (hasFocusableSynopsis(synopsisText)) synopsisFocusRequester else FocusRequester.Cancel,
              onFocusChanged = { focused ->
                isActionBarFocused = focused
              },
              onPlayFocusChanged = { focused ->
                isPlayFocused = focused
                FocusTrace.log("DETAIL", "Riproduci focus REALE=$focused token=$playerClosedToken")
              }
            )
          }
        }

        Spacer(modifier = Modifier.height(28.dp))
      }

      // Horizontal Carousel of Main Cast (Foto profilo, attore e ruolo)
      if (castMembers.isNotEmpty()) {
        item {
          CastCarousel(
            cast = castMembers,
            onMemberClick = onCastMemberClick,
            targetMemberId = targetCastMemberId,
            onFocusRestored = onClearTargetCastMember
          )
          Spacer(modifier = Modifier.height(24.dp))
        }
      }

      // Tabs Header: "Episodi", "Consigliati", "Dettagli Tecnici"
      item {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp),
          horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
          tabs.forEachIndexed { index, tab ->
            val isSelected = selectedTab == index
            // La scheda dei metadati è pensata per i film: per una serie TV mantiene
            // la stessa posizione ma con l'etichetta neutra «Dettagli».
            val tabTitleRes = if (tab == DetailTab.DETTAGLI_FILM && isTvSeries) {
              R.string.detail_tab_series_details
            } else {
              tab.titleRes
            }
            TvFocusableBox(
              shape = RoundedCornerShape(8.dp),
              onClick = { selectedTab = index }
            ) { isFocused ->
              Column(
                modifier = Modifier
                  .background(
                    if (isFocused) NovaCyan.copy(alpha = 0.2f) else Color.Transparent,
                    RoundedCornerShape(8.dp)
                  )
                  .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
              ) {
                Text(
                  text = stringResource(tabTitleRes),
                  color = if (isSelected || isFocused) NovaCyanBright else NovaTextSecondary,
                  fontSize = 16.sp,
                  fontWeight = if (isSelected || isFocused) FontWeight.Bold else FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Box(
                  modifier = Modifier
                    .width(40.dp)
                    .height(3.dp)
                    .background(
                      if (isSelected) NovaCyan else Color.Transparent,
                      RoundedCornerShape(2.dp)
                    )
                )
              }
            }
          }
        }
        Spacer(modifier = Modifier.height(16.dp))
      }

      // Tab Content
      val currentTab = tabs.getOrNull(selectedTab) ?: tabs.first()

      when (currentTab) {
        DetailTab.EPISODI -> {
          // Season Selection Section
          item {
            Column(
              modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp, vertical = 4.dp)
            ) {
              // Header title
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 12.dp)
              ) {
                Icon(
                  imageVector = Icons.Default.Tv,
                  contentDescription = null,
                  tint = NovaCyanBright,
                  modifier = Modifier.size(18.dp)
                )
                Text(
                  text = stringResource(R.string.detail_season_selection),
                  color = NovaTextMuted,
                  fontSize = 13.sp,
                  fontWeight = FontWeight.Bold,
                  letterSpacing = 1.sp
                )
              }

              // Horizontal scrollable row of season pills
              TvFocusRow {
                LazyRow(
                  state = seasonRowState,
                  horizontalArrangement = Arrangement.spacedBy(14.dp),
                  contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                  items(availableSeasons, key = { "season_$it" }, contentType = { "season_chip" }) { seasonNum ->
                    val isSeasonSelected = seasonNum == activeSeason
                    val epCount = extendedDetails?.seasonEpisodesCount?.get(seasonNum)
                      ?: displayMedia.seasonEpisodesCount[seasonNum]
                    val seasonRequester = seasonFocusRequesters.getValue(seasonNum)
                    val seasonAttachment = seasonFocusAttachments.getValue(seasonNum)

                    TvFocusableBox(
                      modifier = Modifier
                        .tvFocusRequester(seasonRequester, seasonAttachment)
                        .focusProperties { down = episodeFocusTarget },
                      shape = RoundedCornerShape(50),
                      focusedScale = 1.05f,
                      onClick = {
                        localSelectedSeason = seasonNum
                        onSeasonChange?.invoke(displayMedia, seasonNum)
                      }
                    ) { isFocused ->
                      Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier
                          .background(
                            color = when {
                              isFocused -> NovaCyan.copy(alpha = 0.35f)
                              isSeasonSelected -> NovaCyan.copy(alpha = 0.22f)
                              else -> NovaSurfaceVariant
                            },
                            shape = RoundedCornerShape(50)
                          )
                          .border(
                            width = if (isFocused) 2.dp else if (isSeasonSelected) 1.5.dp else 1.dp,
                            color = when {
                              isFocused -> NovaCyanBright
                              isSeasonSelected -> NovaCyan
                              else -> Color(0xFF1E293B)
                            },
                            shape = RoundedCornerShape(50)
                          )
                          .padding(horizontal = 18.dp, vertical = 10.dp)
                      ) {
                        Text(
                          text = if (seasonNum == 0) {
                            stringResource(R.string.detail_specials)
                          } else {
                            stringResource(R.string.detail_season_format, seasonNum)
                          },
                          color = when {
                            isFocused -> NovaCyanBright
                            isSeasonSelected -> NovaCyanBright
                            else -> NovaTextSecondary
                          },
                          fontSize = 15.sp,
                          fontWeight = if (isSeasonSelected || isFocused) FontWeight.Bold else FontWeight.Medium
                        )

                        // Badge with episode count
                        val badgeText = epCount?.let { stringResource(R.string.detail_episodes_count_badge, it) } ?: "..."
                        Box(
                          modifier = Modifier
                            .background(
                              if (isSeasonSelected || isFocused) NovaCyan.copy(alpha = 0.35f) else Color.Black.copy(alpha = 0.45f),
                              RoundedCornerShape(6.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                          Text(
                            text = badgeText,
                            color = if (isSeasonSelected || isFocused) NovaCyanBright else NovaTextMuted,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                          )
                        }
                      }
                    }
                  }
                }
              }

              Spacer(modifier = Modifier.height(14.dp))

              // Current season status bar
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .background(NovaCardBg.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                  .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(8.dp))
                  .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
              ) {
                val currentSeasonCount = extendedDetails?.seasonEpisodesCount?.get(activeSeason)
                  ?: displayMedia.seasonEpisodesCount[activeSeason]
                  ?: episodesForSeason.size

                Text(
                  text = if (activeSeason == 0) {
                    "${stringResource(R.string.detail_specials)} • ${stringResource(R.string.detail_episodes_count_badge, currentSeasonCount)}"
                  } else {
                    stringResource(R.string.detail_season_episodes_status, activeSeason, currentSeasonCount)
                  },
                  color = NovaTextPrimary,
                  fontSize = 14.sp,
                  fontWeight = FontWeight.SemiBold
                )
                Text(
                  text = stringResource(R.string.detail_audio_info),
                  color = NovaCyanBright,
                  fontSize = 12.sp,
                  fontWeight = FontWeight.Medium
                )
              }

              selectedSeasonDetails?.name
                ?.takeIf {
                  val normalized = it.trim()
                  it.isNotBlank() && activeSeason != 0 &&
                    !normalized.equals("Season $activeSeason", ignoreCase = true) &&
                    !normalized.equals("Stagione $activeSeason", ignoreCase = true)
                }
                ?.let { seasonName ->
                  Text(
                    text = seasonName,
                    color = NovaTextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 10.dp)
                  )
                }
              selectedSeasonDetails?.airDate?.takeIf { it.isNotBlank() }?.let { airDate ->
                Text(
                  text = stringResource(R.string.detail_season_air_date_format, airDate),
                  color = NovaTextMuted,
                  fontSize = 12.sp,
                  modifier = Modifier.padding(top = 8.dp)
                )
              }
              selectedSeasonDetails?.overview?.takeIf { it.isNotBlank() }?.let { overview ->
                Text(
                  text = overview,
                  color = NovaTextSecondary,
                  fontSize = 13.sp,
                  lineHeight = 19.sp,
                  maxLines = 3,
                  overflow = TextOverflow.Ellipsis,
                  modifier = Modifier.padding(top = 6.dp)
                )
              }

              Spacer(modifier = Modifier.height(8.dp))
            }
          }

          if (isSeasonLoading) {
            item {
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(horizontal = 32.dp, vertical = 24.dp)
                  .background(NovaSurfaceVariant.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                  .border(1.dp, NovaCyan.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                  .padding(24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
              ) {
                CircularProgressIndicator(
                  modifier = Modifier.size(24.dp),
                  color = NovaCyanBright,
                  strokeWidth = 2.5.dp
                )
                Spacer(modifier = Modifier.width(14.dp))
                Text(
                  text = stringResource(R.string.detail_loading_episodes, activeSeason),
                  color = NovaCyanBright,
                  fontSize = 14.sp,
                  fontWeight = FontWeight.Medium
                )
              }
            }
          } else if (seasonErrorMessage != null) {
            item {
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(horizontal = 32.dp, vertical = 16.dp)
                  .background(Color(0xFF7F1D1D).copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                  .border(1.dp, Color(0xFFEF4444).copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                  .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
              ) {
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(10.dp),
                  modifier = Modifier.weight(1f)
                ) {
                  Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = Color(0xFFFCA5A5),
                    modifier = Modifier.size(20.dp)
                  )
                  Text(
                    text = seasonErrorMessage,
                    color = Color(0xFFFCA5A5),
                    fontSize = 13.sp
                  )
                }
                TvFocusableBox(
                  shape = RoundedCornerShape(8.dp),
                  onClick = { onSeasonChange?.invoke(displayMedia, activeSeason) }
                ) { isFocused ->
                  Row(
                    modifier = Modifier
                      .background(
                        if (isFocused) NovaCyan else Color(0xFFEF4444).copy(alpha = 0.3f),
                        RoundedCornerShape(8.dp)
                      )
                      .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                  ) {
                    Icon(
                      imageVector = Icons.Default.Refresh,
                      contentDescription = null,
                      tint = if (isFocused) Color.Black else Color.White,
                      modifier = Modifier.size(16.dp)
                    )
                    Text(
                      text = stringResource(R.string.action_retry),
                      color = if (isFocused) Color.Black else Color.White,
                      fontSize = 12.sp,
                      fontWeight = FontWeight.Bold
                    )
                  }
                }
              }
            }
          } else {
            itemsIndexed(episodesForSeason, key = { _, episode -> episode.id }, contentType = { _, _ -> "episode" }) { index, episode ->
              EpisodeRowItem(
                episode = episode,
                isSimklWatched = episode.episodeNumber in simklWatchedEpisodes,
                fallbackBackdropUrl = displayMedia.backdropUrl,
                fallbackBackdropRes = displayMedia.backdropRes,
                blurUnwatched = blurUnwatchedEpisodes,
                onLongPress = if (episodeMenuAvailable) {
                  {
                    // UN SOLO menu alla volta: una seconda pressione prolungata mentre è
                    // già aperto non lo sostituisce né lo riapre.
                    if (episodeContextMenu == null) episodeContextMenu = episode
                  }
                } else null,
                onClick = {
                  onEpisodeClick?.invoke(displayMedia, episode) ?: onPlayClick(displayMedia, episode)
                },
                modifier = Modifier
                  .padding(horizontal = 32.dp, vertical = 6.dp)
                  .then(
                    if (index == 0) {
                      Modifier
                        .tvFocusRequester(firstEpisodeFocusRequester, firstEpisodeFocusAttachment)
                        .focusProperties { up = selectedSeasonFocusTarget }
                    } else {
                      Modifier
                    }
                  )
              )
            }
          }
        }
        DetailTab.PRODUZIONE -> {
          item {
            ProductionSection(
              companies = extendedDetails?.productionCompanies ?: emptyList(),
              onCompanyClick = onCompanyClick,
              targetCompanyId = targetCompanyId,
              onFocusRestored = onClearTargetCompany,


              modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp)
            )
          }
        }
        DetailTab.DETTAGLI_FILM -> {
          item {
            MovieDetailsSection(
              details = extendedDetails,
              media = displayMedia,
              isTvSeries = isTvSeries,
              modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp)
            )
          }
        }
      }
    }

    // Menu contestuale della riga episodio (pressione prolungata): vive qui, fuori dalla
    // LazyColumn, così il Dialog non dipende dal riciclo degli item. Ogni azione chiude
    // PRIMA il menu e poi invoca il callback: `onDismiss` trova lo stato già nullo e non
    // riporta il focus sulla riga mentre la schermata sta navigando/aggiornandosi.
    val episodeMenuSnapshot = episodeContextMenu
    if (episodeMenuSnapshot != null) {
      val episodeWatched = episodeMenuSnapshot.isCompleted ||
        episodeMenuSnapshot.episodeNumber in simklWatchedEpisodes
      EpisodeContextMenu(
        media = displayMedia,
        episode = episodeMenuSnapshot,
        isWatched = episodeWatched,
        onToggleWatched = {
          episodeContextMenu = null
          // Badge «Visto su SIMKL» aggiornato SUBITO: senza questo passaggio la
          // miniatura continuava a mostrarlo finché la cache SIMKL non si riallineava.
          if (simklAccount.isLoggedIn) {
            simklWatchedEpisodes = if (episodeWatched) {
              simklWatchedEpisodes - episodeMenuSnapshot.episodeNumber
            } else {
              simklWatchedEpisodes + episodeMenuSnapshot.episodeNumber
            }
          }
          onEpisodeWatchedChange?.invoke(displayMedia, episodeMenuSnapshot, !episodeWatched)
        },
        onBrowseSources = {
          episodeContextMenu = null
          onBrowseEpisodeSources?.invoke(displayMedia, episodeMenuSnapshot)
        },
        onMarkSeasonWatched = {
          episodeContextMenu = null
          onMarkSeasonWatched?.invoke(
            displayMedia,
            episodeMenuSnapshot.seasonNumber,
            episodesForSeason
          )
        },
        onDismiss = { episodeContextMenu = null }
      )
    }

    // Dialog del testo completo della sinossi: sola lettura (nessuna riproduzione,
    // nessun toggle preferiti/visto), chiusura con Back o icona; al ritorno il focus
    // torna sulla sinossi ridotta della scheda (vedi DetailSynopsisDialogState).
    if (synopsisDialogState.isDialogOpen) {
      FullSynopsisDialog(
        title = displayMedia.title,
        text = synopsisText,
        synopsisDialogState = synopsisDialogState
      )
    }
  }
  }
}

/**
 * Dialog TV del testo completo della sinossi: superficie e stile coerenti con i dialog
 * StreamNova (NovaSurface + bordo ciano). Il corpo è scorrevole (mouse/rotella e D-pad
 * SU/GIÙ con focus esplicito); il Back chiude il dialog via onDismissRequest e non
 * tocca né la navigazione della scheda né lo stato del contenuto.
 */
@Composable
private fun FullSynopsisDialog(
  title: String,
  text: String,
  synopsisDialogState: DetailSynopsisDialogState
) {
  val scrollState = rememberScrollState()
  val coroutineScope = rememberCoroutineScope()
  val density = LocalDensity.current
  val scrollStepPx = with(density) { 36.dp.toPx() }
  val bodyFocusRequester = remember { FocusRequester() }
  LaunchedEffect(Unit) {
    try { bodyFocusRequester.requestFocus() } catch (_: Exception) {}
  }

  Dialog(onDismissRequest = { synopsisDialogState.dismiss() }) {
    BackHandler(enabled = synopsisDialogState.isDialogOpen) {
      synopsisDialogState.dismiss()
    }
    Column(
      modifier = Modifier
        .width(640.dp)
        .background(NovaSurface, RoundedCornerShape(20.dp))
        .border(1.5.dp, NovaCyan, RoundedCornerShape(20.dp))
        .padding(28.dp)
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          text = title,
          color = NovaTextPrimary,
          fontSize = 18.sp,
          fontWeight = FontWeight.Bold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier
            .weight(1f, fill = false)
            .padding(end = 16.dp)
        )
        Icon(
          imageVector = Icons.Default.Close,
          contentDescription = stringResource(R.string.action_close),
          tint = NovaTextSecondary,
          modifier = Modifier
            .size(22.dp)
            .clickable { synopsisDialogState.dismiss() }
        )
      }

      Spacer(modifier = Modifier.height(14.dp))

      Column(
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(max = 380.dp)
          .verticalScroll(scrollState)
          .focusRequester(bodyFocusRequester)
          .focusable()
          .onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown) {
              when (event.key) {
                Key.DirectionDown -> {
                  coroutineScope.launch { scrollState.animateScrollBy(scrollStepPx) }
                  true
                }
                Key.DirectionUp -> {
                  coroutineScope.launch { scrollState.animateScrollBy(-scrollStepPx) }
                  true
                }
                else -> false
              }
            } else {
              false
            }
          }
      ) {
        Text(
          text = text,
          color = NovaTextSecondary,
          fontSize = 15.sp,
          lineHeight = 24.sp
        )
      }
    }
  }
}

@Composable
fun CastCarousel(
  cast: List<CastMember>,
  onMemberClick: ((CastMember) -> Unit)? = null,
  targetMemberId: Int? = null,
  onFocusRestored: (() -> Unit)? = null,
  modifier: Modifier = Modifier
) {
  Column(modifier = modifier.fillMaxWidth()) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp)
    ) {
      Icon(
        imageVector = Icons.Default.Person,
        contentDescription = null,
        tint = NovaCyanBright,
        modifier = Modifier.size(18.dp)
      )
      Text(
        text = stringResource(R.string.detail_main_cast),
        color = NovaTextMuted,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp
      )
    }

    Spacer(modifier = Modifier.height(8.dp))

    val castState = rememberLazyListState()
    TvFocusRow {
      LazyRow(
        state = castState,
        contentPadding = PaddingValues(horizontal = 32.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp)
      ) {
        items(cast, key = { "${it.id}_${it.name}" }, contentType = { "cast_member" }) { member ->
          CastMemberCard(
            member = member,
            onClick = { onMemberClick?.invoke(member) },
            targetMemberId = targetMemberId,
            onFocusRestored = onFocusRestored
          )
        }
      }
    }
  }
}

@Composable
fun CastMemberCard(
  member: CastMember,
  onClick: () -> Unit,
  targetMemberId: Int? = null,
  onFocusRestored: (() -> Unit)? = null,
  modifier: Modifier = Modifier
) {
  val focusRequester = remember { FocusRequester() }
  var isFocused by remember { mutableStateOf(false) }

  LaunchedEffect(targetMemberId) {
    if (targetMemberId != null && targetMemberId == member.id) {
      try {
        focusRequester.requestFocus()
        onFocusRestored?.invoke()
      } catch (_: Exception) {
        delay(32)
        try {
          focusRequester.requestFocus()
          onFocusRestored?.invoke()
        } catch (_: Exception) {}
      }
    }
  }

  val scale by animateFloatAsState(
    targetValue = if (isFocused) 1.08f else 1f,
    animationSpec = tween(durationMillis = 180),
    label = "castMemberScale"
  )

  // Nessuna cornice esterna: il contenitore resta trasparente e l'unico focus ring
  // e' il bordo applicato direttamente sul cerchio dell'avatar.
  Column(
    modifier = modifier
      .width(112.dp)
      .focusRequester(focusRequester)
      .zIndex(if (isFocused) 10f else 1f)
      .graphicsLayer {
        scaleX = scale
        scaleY = scale
      }
      .onFocusChanged { isFocused = it.isFocused }
      .onKeyEvent { keyEvent ->
        val isConfirm = keyEvent.key == Key.Enter ||
          keyEvent.key == Key.NumPadEnter ||
          keyEvent.key == Key.DirectionCenter
        if (isConfirm && keyEvent.type == KeyEventType.KeyUp) {
          onClick()
          true
        } else {
          false
        }
      }
      .focusable()
      .padding(vertical = 4.dp),
    horizontalAlignment = Alignment.CenterHorizontally
  ) {
    // Profile photo - cerchio perfetto per TV (96dp), senza cornici o rettangoli esterni
    Box(
      modifier = Modifier
        .size(96.dp)
        .clip(CircleShape)
        .background(Color(0xFF0F172A), CircleShape)
        .border(
          width = if (isFocused) 2.5.dp else 1.dp,
          color = if (isFocused) NovaAccent else Color(0xFF334155).copy(alpha = 0.6f),
          shape = CircleShape
        ),
      contentAlignment = Alignment.Center
    ) {
      if (!member.profileUrl.isNullOrBlank()) {
        AsyncImage(
          model = ImageRequest.Builder(LocalContext.current)
            .data(member.profileUrl)
            .crossfade(true)
            .build(),
          contentDescription = member.name,
          contentScale = ContentScale.Crop,
          modifier = Modifier
            .fillMaxSize()
            .clip(CircleShape)
        )
      } else {
        Icon(
          imageVector = Icons.Default.Person,
          contentDescription = null,
          tint = NovaTextMuted,
          modifier = Modifier.size(44.dp)
        )
      }
    }

    Spacer(modifier = Modifier.height(10.dp))

    Text(
      text = member.name,
      color = if (isFocused) NovaCyanBright else Color.White,
      fontSize = 13.sp,
      fontWeight = FontWeight.Bold,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      textAlign = TextAlign.Center,
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 2.dp)
    )

    if (member.character.isNotBlank()) {
      Spacer(modifier = Modifier.height(2.dp))
      Text(
        text = if (member.character == "Cast principale") stringResource(R.string.cast_main_role) else member.character,
        color = NovaTextSecondary,
        fontSize = 11.sp,
        fontWeight = FontWeight.Normal,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 2.dp)
      )
    }
  }
}

@Composable
fun EpisodeRowItem(
  episode: Episode,
  onClick: () -> Unit,
  isSimklWatched: Boolean = false,
  fallbackBackdropUrl: String? = null,
  fallbackBackdropRes: Int? = null,
  /**
   * Preferenza «Sfoca non visti» attiva: la miniatura degli episodi MAI visti (nessun
   * progresso, non completati, non segnati su SIMKL) è sfocata anti-spoiler. Episodi
   * iniziati (in visione) o completati restano sempre nitidi, come nella riga
   * «Continua a guardare» ([ContinueWatchingRowPolicy]: `unwatched = progress <= 0`).
   */
  blurUnwatched: Boolean = false,
  /**
   * Pressione prolungata del tasto centrale: apre il menu contestuale dell'episodio
   * (visto / da vedere, risfoglia fonti, stagione già vista). `null` = nessun menu.
   */
  onLongPress: (() -> Unit)? = null,
  modifier: Modifier = Modifier
) {
  // `Modifier.blur` usa RenderEffect e su API < 31 non ha effetto: sui TV box reali
  // (Fire OS 7/8 = Android 9/11, molti Google TV con Android 10/11) la miniatura restava
  // nitida. La sfocatura è quindi calcolata sulla bitmap (SpoilerBlurredImage), identica
  // su emulatore e dispositivi reali.
  val isEpisodeUnseen = !isSimklWatched && !episode.isCompleted && episode.currentProgressMs <= 0L
  val blurThumbnail = blurUnwatched && isEpisodeUnseen
  TvFocusableBox(
    modifier = modifier.fillMaxWidth(),
    shape = RoundedCornerShape(12.dp),
    focusedScale = 1.02f,
    onClick = onClick,
    onLongPress = onLongPress
  ) { isFocused ->
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .background(
          color = if (isFocused) NovaSurfaceVariant else NovaCardBg,
          shape = RoundedCornerShape(12.dp)
        )
        .border(
          width = if (isFocused) 2.dp else 1.dp,
          color = if (isFocused) NovaCyanBright else Color(0xFF1E293B),
          shape = RoundedCornerShape(12.dp)
        )
        .padding(14.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      // 16:9 Thumbnail container
      Box(
        modifier = Modifier
          .width(220.dp)
          .height(124.dp)
          .clip(RoundedCornerShape(12.dp))
          .background(Color(0xFF0F172A))
          .border(
            width = if (isFocused) 1.5.dp else 1.dp,
            color = if (isFocused) NovaCyan else Color(0xFF334155),
            shape = RoundedCornerShape(12.dp)
          )
      ) {
        val thumbUrl = episode.thumbnailUrl ?: fallbackBackdropUrl
        val thumb = episode.thumbnailRes ?: fallbackBackdropRes
        when {
          !thumbUrl.isNullOrBlank() -> {
            if (blurThumbnail) {
              SpoilerBlurredImage(
                data = thumbUrl,
                contentDescription = episode.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
              )
            } else {
              AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                  .data(thumbUrl)
                  .crossfade(true)
                  .build(),
                contentDescription = episode.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
              )
            }
          }
          thumb != null -> {
            if (blurThumbnail) {
              SpoilerBlurredImage(
                data = thumb,
                contentDescription = episode.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
              )
            } else {
              Image(
                painter = painterResource(id = thumb),
                contentDescription = episode.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
              )
            }
          }
          else -> {
            Box(
              modifier = Modifier
                .fillMaxSize()
                .background(
                  Brush.verticalGradient(
                    colors = listOf(Color(0xFF1E293B), Color(0xFF0F172A))
                  )
                )
            )
          }
        }

        if (isSimklWatched) {
          Row(
            modifier = Modifier
              .align(Alignment.TopEnd)
              .padding(6.dp)
              .background(Color(0xFF059669), RoundedCornerShape(6.dp))
              .padding(horizontal = 7.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
          ) {
            Icon(Icons.Default.Check, contentDescription = "Visto su SIMKL", tint = Color.White, modifier = Modifier.size(13.dp))
            Text("Visto", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
          }
        }

        // Play icon overlay
        Box(
          modifier = Modifier
            .align(Alignment.Center)
            .size(34.dp)
            .background(Color.Black.copy(alpha = if (isFocused) 0.75f else 0.5f), CircleShape)
            .border(
              width = 1.dp,
              color = if (isFocused) NovaCyanBright else Color.White.copy(alpha = 0.3f),
              shape = CircleShape
            ),
          contentAlignment = Alignment.Center
        ) {
          Icon(
            imageVector = Icons.Default.PlayArrow,
            contentDescription = null,
            tint = if (isFocused) NovaCyanBright else Color.White,
            modifier = Modifier.size(20.dp)
          )
        }

        // Progress bar at bottom
        if (episode.currentProgressMs > 0) {
          LinearProgressIndicator(
            progress = { episode.progressFraction },
            modifier = Modifier
              .align(Alignment.BottomCenter)
              .fillMaxWidth()
              .height(3.dp),
            color = NovaCyan,
            trackColor = Color.Black.copy(alpha = 0.6f)
          )
        }
      }

      Spacer(modifier = Modifier.width(18.dp))

      // Content Column: Episode Title, Badges, Rating, Duration, Synopsis
      Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(6.dp)
      ) {
        // Row with Episode badge (e.g. E01), Title, Rating, Duration, Completed badge
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f, fill = false)
          ) {
            // Episode number badge (e.g. "E01")
            Box(
              modifier = Modifier
                .background(
                  if (isFocused) NovaCyan.copy(alpha = 0.35f) else NovaCyan.copy(alpha = 0.18f),
                  RoundedCornerShape(6.dp)
                )
                .border(
                  width = 1.dp,
                  color = if (isFocused) NovaCyanBright else NovaCyan.copy(alpha = 0.4f),
                  shape = RoundedCornerShape(6.dp)
                )
                .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
              Text(
                text = "E%02d".format(episode.episodeNumber),
                color = NovaCyanBright,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
              )
            }

            Text(
              text = episode.title,
              color = if (isFocused) NovaCyanBright else NovaTextPrimary,
              fontSize = 16.sp,
              fontWeight = FontWeight.Bold,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
          }

          // Metadata Badges (Duration, Watched status)
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
          ) {
            // Duration
            Text(
              text = stringResource(R.string.detail_duration_min, episode.durationMinutes),
              color = NovaTextMuted,
              fontSize = 12.sp,
              fontWeight = FontWeight.Medium
            )

            // Watched / in progress badge
            if (episode.isCompleted) {
              Box(
                modifier = Modifier
                  .background(Color(0xFF10B981).copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                  .padding(horizontal = 6.dp, vertical = 2.dp)
              ) {
                Text(
                  text = stringResource(R.string.detail_watched_badge),
                  color = Color(0xFF10B981),
                  fontSize = 11.sp,
                  fontWeight = FontWeight.Bold
                )
              }
            } else if (episode.currentProgressMs > 0) {
              // Minuti mancanti dai millisecondi reali dell'episodio (fallback runtime TMDB
              // solo se la durata in millisecondi non e' nota).
              val remainingMins = WatchProgress.remainingMinutes(
                totalDurationMs = episode.totalDurationMs,
                resumePositionMs = episode.currentProgressMs,
                runtimeMinutes = episode.durationMinutes
              )
              if (remainingMins != null) {
                Box(
                  modifier = Modifier
                    .background(NovaCyan.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                  Text(
                    text = stringResource(R.string.badge_minutes_remaining, remainingMins),
                    color = NovaCyanBright,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                  )
                }
              }
            }
          }
        }

        // Air date if available
        if (!episode.airDate.isNullOrBlank()) {
          Text(
            text = stringResource(R.string.detail_air_date_format, episode.airDate),
            color = NovaTextMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
          )
        }

        // Synopsis / Overview
        Text(
          text = episode.synopsis,
          color = NovaTextSecondary,
          fontSize = 13.sp,
          lineHeight = 19.sp,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis
        )
      }
    }
  }
}

/**
 * Scheda «Dettagli Film»: elenco etichetta/valore con separatore sottile, senza card
 * né decorazioni aggiuntive rispetto al riferimento TV.
 *
 * Fonti dei dati (tutte TMDB, nessun valore fisso):
 *  - stato → [ExtendedMediaDetails.status];
 *  - anno di uscita/prima messa in onda → data ISO stretta del dettaglio o, in
 *    caricamento, la data già nota del [MediaItem] (mai un anno di comodo);
 *  - durata → [ExtendedMediaDetails.runtimeMinutes] (runtime del film o durata di un
 *    episodio per le serie);
 *  - certificazione → [ExtendedMediaDetails.certification] (grezza, es. `PG-13`);
 *  - paese → [ExtendedMediaDetails.originCountry].
 *
 * Una riga senza dato non viene composta. Ogni riga è focalizzabile: il D-pad può
 * scorrere l'elenco e la riga a fuoco ha un'evidenza discreta (bordo/sfondo ciano),
 * necessaria per l'accessibilità TV.
 */
@Composable
fun MovieDetailsSection(
  details: ExtendedMediaDetails?,
  media: MediaItem,
  isTvSeries: Boolean,
  modifier: Modifier = Modifier
) {
  val status = DetailFactsMapper.status(details?.status)
  val statusLabel = status?.let { stringResource(it.labelRes()) }
  val releaseYear = DetailFactsMapper.releaseYear(details?.releaseDate ?: media.releaseDate)
  val duration = DetailFactsMapper.formatDuration(details?.runtimeMinutes)
  val certification = details?.certification?.takeIf { it.isNotBlank() }
  val originCountry = details?.originCountry?.takeIf { it.isNotBlank() }

  val statusRow = statusLabel?.let { stringResource(R.string.detail_info_status) to it }
  val releaseLabel = stringResource(
    if (isTvSeries) R.string.detail_info_first_air else R.string.detail_info_release
  )
  val runtimeLabel = stringResource(
    if (isTvSeries) R.string.detail_info_episode_runtime else R.string.detail_info_runtime
  )
  val certificationLabel = stringResource(R.string.detail_info_certification)
  val originCountryLabel = stringResource(R.string.detail_info_origin_country)

  val rows = listOfNotNull(
    statusRow,
    releaseYear?.let { releaseLabel to it.toString() },
    duration?.let { runtimeLabel to it },
    certification?.let { certificationLabel to it },
    originCountry?.let { originCountryLabel to it }
  )

  Column(modifier = modifier.fillMaxWidth()) {
    Text(
      text = stringResource(
        if (isTvSeries) R.string.detail_series_details_title else R.string.detail_movie_details_title
      ),
      color = NovaTextPrimary,
      fontSize = 22.sp,
      fontWeight = FontWeight.Bold,
      letterSpacing = 0.2.sp
    )
    Spacer(modifier = Modifier.height(6.dp))

    if (rows.isEmpty()) {
      Text(
        text = stringResource(R.string.detail_info_empty),
        color = NovaTextMuted,
        fontSize = 14.sp,
        modifier = Modifier.padding(vertical = 12.dp)
      )
    } else {
      rows.forEachIndexed { index, (label, value) ->
        MovieDetailFactRow(
          label = label,
          value = value,
          showDivider = index < rows.lastIndex
        )
      }
    }
  }
}

/** Riga informativa focalizzabile della scheda «Dettagli Film». */
@Composable
private fun MovieDetailFactRow(
  label: String,
  value: String,
  showDivider: Boolean
) {
  var isFocused by remember { mutableStateOf(false) }
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(6.dp))
      .onFocusChanged { isFocused = it.isFocused }
      .focusable()
      .background(if (isFocused) NovaCyan.copy(alpha = 0.12f) else Color.Transparent)
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 10.dp, vertical = 14.dp),
      verticalAlignment = Alignment.Top
    ) {
      Text(
        text = label,
        color = if (isFocused) NovaCyanBright else NovaTextPrimary,
        fontSize = 15.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.weight(0.28f)
      )
      Text(
        text = value,
        color = NovaTextPrimary,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        modifier = Modifier.weight(0.72f)
      )
    }
    if (showDivider) {
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 10.dp)
          .height(1.dp)
          .background(Color.White.copy(alpha = 0.12f))
      )
    }
  }
}

/** Etichetta localizzata dello stato TMDB normalizzato. */
private fun DetailFactsMapper.TitleStatus.labelRes(): Int = when (this) {
  DetailFactsMapper.TitleStatus.RELEASED -> R.string.detail_status_released
  DetailFactsMapper.TitleStatus.POST_PRODUCTION -> R.string.detail_status_post_production
  DetailFactsMapper.TitleStatus.IN_PRODUCTION -> R.string.detail_status_in_production
  DetailFactsMapper.TitleStatus.PLANNED -> R.string.detail_status_planned
  DetailFactsMapper.TitleStatus.CANCELED -> R.string.detail_status_canceled
  DetailFactsMapper.TitleStatus.RUMORED -> R.string.detail_status_rumored
  DetailFactsMapper.TitleStatus.RETURNING_SERIES -> R.string.detail_status_returning_series
  DetailFactsMapper.TitleStatus.ENDED -> R.string.detail_status_ended
  DetailFactsMapper.TitleStatus.PILOT -> R.string.detail_status_pilot
}

@Composable
fun ProductionSection(
  companies: List<ProductionCompany>,
  onCompanyClick: ((ProductionCompany) -> Unit)? = null,
  targetCompanyId: Int? = null,
  onFocusRestored: (() -> Unit)? = null,
  modifier: Modifier = Modifier
) {
  Column(
    modifier = modifier.fillMaxWidth()
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      modifier = Modifier.padding(bottom = 12.dp)
    ) {
      Text(
        text = stringResource(R.string.production_section_title),
        color = NovaTextMuted,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp
      )
    }

    if (companies.isEmpty()) {
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .background(NovaCardBg, RoundedCornerShape(12.dp))
          .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(12.dp))
          .padding(24.dp),
        contentAlignment = Alignment.Center
      ) {
        Text(
          text = stringResource(R.string.production_empty),
          color = NovaTextSecondary,
          fontSize = 14.sp
        )
      }
    } else {
      val companiesState = rememberLazyListState()
      TvFocusRow {
        LazyRow(
          state = companiesState,
          contentPadding = PaddingValues(vertical = 4.dp),
          horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
          items(companies, key = { "${it.id}_${it.name}" }, contentType = { "company" }) { company ->
            ProductionCompanyCard(
              company = company,
              onClick = { onCompanyClick?.invoke(company) },
              targetCompanyId = targetCompanyId,
              onFocusRestored = onFocusRestored
            )
          }
        }
      }
    }
  }
}

/**
 * Badge casa di produzione: container CHIARO (come da riferimento TV) con angoli arrotondati,
 * cosi' sia i loghi neri (Columbia, Pascal, TSG) sia quelli a colori (Marvel) restano nitidi
 * su sfondo scuro. Il logo viene renderizzato con colori e grafica ORIGINALI (nessun filtro).
 * Al focus D-pad: bordo/glow cyan consueto + badge passa a bianco pieno.
 */
@Composable
fun ProductionCompanyCard(
  company: ProductionCompany,
  onClick: () -> Unit,
  targetCompanyId: Int? = null,
  onFocusRestored: (() -> Unit)? = null,
  modifier: Modifier = Modifier
) {
  val focusRequester = remember { FocusRequester() }

  LaunchedEffect(targetCompanyId) {
    if (targetCompanyId != null && targetCompanyId == company.id) {
      try {
        focusRequester.requestFocus()
        onFocusRestored?.invoke()
      } catch (_: Exception) {
        delay(32)
        try {
          focusRequester.requestFocus()
          onFocusRestored?.invoke()
        } catch (_: Exception) {}
      }
    }
  }

  TvFocusableBox(
    shape = RoundedCornerShape(12.dp),
    focusedScale = 1.08f,
    onClick = onClick,
    modifier = modifier.focusRequester(focusRequester)
  ) { isFocused ->
    Box(
      modifier = Modifier
        .width(180.dp)
        .height(88.dp)
        .background(
          color = if (isFocused) Color.White else Color(0xFFEEEEEE),
          shape = RoundedCornerShape(12.dp)
        )
        .border(
          width = if (isFocused) 2.dp else 1.dp,
          color = if (isFocused) NovaCyanBright else Color(0xFFD7DCE5),
          shape = RoundedCornerShape(12.dp)
        )
        .padding(8.dp),
      contentAlignment = Alignment.Center
    ) {
      if (!company.logoUrl.isNullOrBlank()) {
        AsyncImage(
          model = ImageRequest.Builder(LocalContext.current)
            .data(company.logoUrl)
            .crossfade(true)
            .build(),
          contentDescription = company.name,
          contentScale = ContentScale.Fit,
          modifier = Modifier.fillMaxSize()
        )
      } else {
        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.Center
        ) {
          Text(
            text = company.name,
            color = if (isFocused) NovaCyan else Color(0xFF0F172A),
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
          )
          if (!company.originCountry.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
              text = company.originCountry,
              color = NovaTextMuted,
              fontSize = 11.sp,
              fontWeight = FontWeight.Medium
            )
          }
        }
      }
    }
  }
}

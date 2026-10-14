package com.example.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.example.data.repository.MediaRepository
import com.example.data.repository.SimklRepository
import com.example.data.stremio.StremioCatalogMapper
import com.example.ui.components.MediaActionBar
import com.example.ui.components.MediaActionBarFocus
import com.example.ui.components.ProviderBadge
import com.example.ui.components.ProviderConstants
import com.example.ui.components.PosterMediaCard
import com.example.ui.components.QualityBadge
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
import com.example.ui.theme.NovaSurfaceVariant
import com.example.ui.theme.NovaTextMuted
import com.example.ui.theme.NovaTextPrimary
import com.example.ui.theme.NovaTextSecondary
import java.util.Locale
import kotlinx.coroutines.delay

// Ombra proiettata di supporto per il titolo: garantisce leggibilità su artwork chiari.
private val DetailTitleShadow = TextStyle(
  shadow = Shadow(color = Color.Black.copy(alpha = 0.7f), blurRadius = 8f)
)

enum class DetailTab(val titleRes: Int) {
  EPISODI(R.string.detail_tab_episodes),
  PRODUZIONE(R.string.detail_tab_production),
  ALTRO_COME_QUESTO(R.string.detail_tab_more_like_this),


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
  onPlayTrailer: ((MediaItem) -> Unit)? = null,
  onCastMemberClick: ((CastMember) -> Unit)? = null,
  onCompanyClick: ((ProductionCompany) -> Unit)? = null,
  targetCastMemberId: Int? = null,
  onClearTargetCastMember: (() -> Unit)? = null,
  targetCompanyId: Int? = null,
  onClearTargetCompany: (() -> Unit)? = null,
  modifier: Modifier = Modifier
) {
  // Resolve active media & extended details depending on UI State
  val (displayMedia, extendedDetails) = when (detailUiState) {
    is MediaDetailUiState.Success -> detailUiState.media to detailUiState.details
    is MediaDetailUiState.Loading -> (detailUiState.baseMedia ?: media) to null
    is MediaDetailUiState.Error -> (detailUiState.baseMedia ?: media) to null
    else -> media to null
  }



  val isTvSeries = displayMedia.type == MediaType.SERIE_TV
  val tabs = if (isTvSeries) {
    listOf(DetailTab.EPISODI, DetailTab.PRODUZIONE, DetailTab.ALTRO_COME_QUESTO)
  } else {
    listOf(DetailTab.PRODUZIONE, DetailTab.ALTRO_COME_QUESTO)
  }

  var selectedTab by remember(displayMedia.id) {
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

  // Cast members from TMDB extended details or fallback from media item
  val castMembers = remember(displayMedia, extendedDetails) {
    val director = extendedDetails?.directorMember
      ?: if (displayMedia.director.isNotBlank()) {
        CastMember(
          id = -1,
          name = displayMedia.director,
          character = if (displayMedia.type == MediaType.SERIE_TV) "Creatore" else "Regista",
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

    // Catena D-pad della barra azioni: Riproduci -> Cuore -> Occhio -> Trailer.
    // "Riproduci" e' il PRIMO (e unico) nodo focusabile della zona superiore:
    // header, logo, titolo, badge e backdrop sono puramente visivi e non
    // ricevono mai il focus D-pad.
    val actionFocus = remember(displayMedia.id) { MediaActionBarFocus() }
    // FocusRequester dedicato al pulsante principale "Riproduci".
    val playButtonFocusRequester = actionFocus.play

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
    var hasUserNavigatedAway by remember(displayMedia.id) { mutableStateOf(false) }
    val isTargetingSubItem = targetCastMemberId != null || targetCompanyId != null

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
          playButtonFocusRequester.requestFocus()
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

            val durationText = if (displayMedia.type == MediaType.SERIE_TV) {
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

          // Full Synopsis (Overview)
          val synopsisText = extendedDetails?.overview?.takeIf { it.isNotBlank() } ?: displayMedia.synopsis
          Text(
            text = synopsisText,
            color = NovaTextSecondary,
            fontSize = 14.sp,
            lineHeight = 22.sp
          )

          Spacer(modifier = Modifier.height(24.dp))

          // Action Buttons: "Riproduci" / "Guarda ora" + "+ La mia lista"
          Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            val resumeEpisode = if (displayMedia.type == MediaType.SERIE_TV) {
              val targetSeason = displayMedia.lastWatchedSeason ?: 1
              val targetEpNum = displayMedia.lastWatchedEpisode ?: 1
              val found = displayMedia.episodes.find {
                it.seasonNumber == targetSeason && it.episodeNumber == targetEpNum
              } ?: episodesForSeason.find {
                it.seasonNumber == targetSeason && it.episodeNumber == targetEpNum
              } ?: episodesForSeason.firstOrNull { it.seasonNumber == 1 && it.episodeNumber == 1 }
                ?: episodesForSeason.firstOrNull()
                ?: displayMedia.episodes.firstOrNull()
                ?: Episode(
                  id = "${displayMedia.id}_s${targetSeason}e${targetEpNum}",
                  seasonNumber = targetSeason,
                  episodeNumber = targetEpNum,
                  title = "Episodio $targetEpNum",
                  synopsis = "",
                  durationMinutes = displayMedia.durationMinutes.takeIf { it > 0 } ?: 55,
                  videoUrl = displayMedia.videoUrl
                )
              val savedProgress = MediaRepository.getEpisodeProgress(displayMedia.id, found.seasonNumber, found.episodeNumber)
              found.copy(currentProgressMs = savedProgress)
            } else null

            val playButtonLabel = if (displayMedia.type == MediaType.SERIE_TV) {
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
            // Trailer YouTube interno (mai Intent verso l'app esterna).
            // SU dalla barra resta bloccato: sopra non c'e' nessun nodo focusabile.
            MediaActionBar(
              playLabel = playButtonLabel,
              onPlay = { onPlayClick(displayMedia, resumeEpisode) },
              isFavorite = displayMedia.isFavorite,
              onToggleFavorite = { onToggleFavorite(displayMedia.id) },
              isWatched = displayMedia.isWatched,
              onToggleWatched = { onToggleWatched?.invoke(displayMedia) },
              onPlayTrailer = { onPlayTrailer?.invoke(displayMedia) },
              focus = actionFocus,
              upTarget = FocusRequester.Cancel,
              onFocusChanged = { focused ->
                isActionBarFocused = focused
                if (focused && !hasUserNavigatedAway && !isPlayFocused && !isTargetingSubItem) {
                  try { playButtonFocusRequester.requestFocus() } catch (_: Exception) {}
                }
              },
              onPlayFocusChanged = { isPlayFocused = it }
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
                  text = stringResource(tab.titleRes),
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
                  items(availableSeasons, key = { "season_$it" }) { seasonNum ->
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
            itemsIndexed(episodesForSeason, key = { _, episode -> episode.id }) { index, episode ->
              EpisodeRowItem(
                episode = episode,
                isSimklWatched = episode.episodeNumber in simklWatchedEpisodes,
                fallbackBackdropUrl = displayMedia.backdropUrl,
                fallbackBackdropRes = displayMedia.backdropRes,
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
        DetailTab.ALTRO_COME_QUESTO -> {
          item {
            val currentGenres = extendedDetails?.genres?.takeIf { it.isNotEmpty() } ?: displayMedia.genres
            val similarRecommendations = remember(extendedDetails, allMedia, displayMedia, currentGenres) {
              val matched = allMedia.filter { item ->
                item.id != displayMedia.id &&
                  (item.tmdbId == null || item.tmdbId != displayMedia.tmdbId) &&
                  item.genres.any { itemGenre ->
                    currentGenres.any { it.equals(itemGenre, ignoreCase = true) }
                  }
              }.sortedByDescending { item ->
                val sameTypeBonus = if (item.type == displayMedia.type) 10 else 0
                val sharedCount = item.genres.count { itemGenre ->
                  currentGenres.any { it.equals(itemGenre, ignoreCase = true) }
                }
                sameTypeBonus + sharedCount * 2 + (item.rating / 2f).toInt()
              }
              if (matched.isNotEmpty()) {
                matched
              } else {
                extendedDetails?.similarItems?.takeIf { it.isNotEmpty() }
                  ?: allMedia.filter { it.id != displayMedia.id && it.type == displayMedia.type }
              }
            }




            val similarState = rememberLazyListState()
            Text(
              text = stringResource(R.string.detail_tab_more_like_this),
              color = NovaTextMuted,
              fontSize = 13.sp,
              fontWeight = FontWeight.Bold,
              letterSpacing = 1.sp,
              modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp)
            )
            TvFocusRow {
              LazyRow(
                state = similarState,
                contentPadding = PaddingValues(horizontal = 32.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
              ) {
                items(similarRecommendations, key = { "rec_${it.id}" }) { item ->
                  PosterMediaCard(
                    media = item,
                    onClick = { onMediaClick?.invoke(item) }
                  )
                }
              }
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
      }
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
        items(cast, key = { "${it.id}_${it.name}" }) { member ->
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
  modifier: Modifier = Modifier
) {
  TvFocusableBox(
    modifier = modifier.fillMaxWidth(),
    shape = RoundedCornerShape(12.dp),
    focusedScale = 1.02f,
    onClick = onClick
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
        if (!thumbUrl.isNullOrBlank()) {
          AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
              .data(thumbUrl)
              .crossfade(true)
              .build(),
            contentDescription = episode.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
          )
        } else {
          val thumb = episode.thumbnailRes ?: fallbackBackdropRes
          if (thumb != null) {
          Image(
            painter = painterResource(id = thumb),
            contentDescription = episode.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
          )
          } else {
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
          items(companies, key = { "${it.id}_${it.name}" }) { company ->
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

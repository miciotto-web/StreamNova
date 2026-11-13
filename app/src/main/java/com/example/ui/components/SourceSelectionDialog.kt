package com.example.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.example.R
import com.example.data.model.Episode
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.prefs.PlaybackSettings
import com.example.data.streaming.CacheState
import com.example.data.streaming.StreamSource
import com.example.data.streaming.TorBoxSourceOrdering
import com.example.ui.theme.NovaBackground
import com.example.ui.theme.NovaCyan
import com.example.ui.theme.NovaCyanBright
import com.example.ui.theme.NovaDivider
import com.example.ui.theme.NovaGold
import com.example.ui.theme.NovaGreen
import com.example.ui.theme.NovaSurfaceVariant
import com.example.ui.theme.NovaTextMuted
import com.example.ui.theme.NovaTextPrimary
import com.example.ui.theme.NovaTextSecondary
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * Schermata dedicata (full-screen) per la selezione della sorgente TorBox.
 *
 * Sostituisce il vecchio popup modale "Seleziona sorgente (N)": il layout e'
 * organizzato come una schermata a due colonne con, a sinistra, la zona media
 * (clear logo originale + metadati/badge, centrati, senza poster e SENZA barra
 * azioni) e, a destra, la barra orizzontale dei provider e la lista
 * verticale degli stream.
 *
 * NOTA: i pulsanti rapidi (Riproduci, Cuore, Occhio, Trailer) appartengono
 * SOLO a [com.example.ui.screens.DetailScreen]: qui sarebbero ridondanti dato
 * che l'utente sta gia' scegliendo la sorgente di riproduzione.
 *
 * IMPORTANTE: questa schermata e' SOLO UI. Non modifica la discovery, il
 * ranking, l'unlock TorBox ne' la logica di selezione: riceve le [StreamSource]
 * gia' risolte e ne filtra esclusivamente la visualizzazione per provider
 * (addonName, con fallback su serverName).
 *
 * @param sources stream gia' risolti da mostrare.
 * @param media titolo per cui e' in corso la selezione (poster/backdrop/logo).
 * @param episode episodio attivo per le serie TV (badge SxEx), altrimenti null.
 * @param onSelect sorgente scelta: avvia il flusso TorBox esistente (invariato).
 * @param onDismiss chiusura senza avviare la riproduzione.
 */
@Composable
fun TorBoxSourceSelectionScreen(
  sources: List<StreamSource>,
  target: com.example.ui.viewmodel.SourceDialogTarget?,
  settings: PlaybackSettings = PlaybackSettings(),
  /** Provider HTTP scelto come principale: le sue sorgenti restano in cima. */
  preferredProviderId: String? = null,
  onSelect: (StreamSource) -> Unit,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
) {
  TorBoxSourceSelectionScreen(
    sources = sources,
    media = target?.media,
    episode = target?.episode,
    settings = settings,
    preferredProviderId = preferredProviderId,
    onSelect = onSelect,
    onDismiss = onDismiss,
    modifier = modifier
  )
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TorBoxSourceSelectionScreen(
  sources: List<StreamSource>,
  media: MediaItem?,
  episode: Episode? = null,
  settings: PlaybackSettings = PlaybackSettings(),
  preferredProviderId: String? = null,
  onSelect: (StreamSource) -> Unit,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
) {
  // Ordinamento deterministico applicato prima di mostrare la lista. Le preferenze
  // dell'utente ("Privilegia tracce italiane" e "Risoluzione preferita") guidano
  // l'ordine: prima venivano ignorate perché il sort usava solo i valori di default.
  // Il provider HTTP scelto come principale precede sempre gli altri.
  val ordered = remember(sources, settings, preferredProviderId) {
    TorBoxSourceOrdering.sort(sources, settings, preferredProviderId)
  }

  // Provider dedotti SOLO dai dati gia' presenti nello StreamSource (nessuna nuova
  // discovery): l'ordine rispetta la prima comparsa nella lista ordinata.
  val providers = remember(ordered) {
    val seen = LinkedHashMap<String, String>()
    ordered.forEach { source -> seen.getOrPut(providerKeyOf(source)) { providerLabelOf(source) } }
    seen.values.toList()
  }

  // Filtro visualizzazione: null = "Tutto" (nessun filtro). Non tocca i dati.
  var selectedProvider by remember(ordered) { mutableStateOf<String?>(null) }
  val filtered = remember(ordered, selectedProvider) {
    val selected = selectedProvider
    if (selected == null) ordered
    else ordered.filter { providerLabelOf(it).equals(selected, ignoreCase = true) }
  }

  val listState = rememberLazyListState()

  // Punto 1 – Sorgente "consigliata": la lista è già ordinata da TorBoxSourceOrdering
  // (cached in cima, poi risoluzione decrescente), quindi la prima traccia cached è
  // la migliore disponibile; se nessuna è cached si parte dalla prima in lista.
  val recommendedIndex = remember(filtered) {
    filtered.indexOfFirst { it.cacheState == CacheState.Cached }.takeIf { it >= 0 } ?: 0
  }
  var focusedStreamIndex by remember { mutableIntStateOf(recommendedIndex) }

  val allFilterRequester = remember { FocusRequester() }
  val providerRequesters = remember(providers) { providers.map { FocusRequester() } }
  val firstStreamRequester = remember { FocusRequester() }
  // Requester dedicato alla sorgente consigliata: fallback del focus iniziale.
  val recommendedStreamRequester = remember { FocusRequester() }

  // Requester del provider attivo: destinazione di UP dalla lista stream.
  val activeProviderRequester = remember(selectedProvider, providers, providerRequesters, allFilterRequester) {
    val selected = selectedProvider ?: return@remember allFilterRequester
    val index = providers.indexOfFirst { it.equals(selected, ignoreCase = true) }
    if (index in providerRequesters.indices) providerRequesters[index] else allFilterRequester
  }

  // Back: chiude la schermata senza avviare la riproduzione.
  BackHandler { onDismiss() }

  // Focus iniziale: la colonna sinistra non ha piu' elementi cliccabili (niente
  // barra azioni), quindi il focus D-pad va subito a destra: prima sulla pillola
  // "Tutto" dei filtri, con fallback sul primo stream consigliato della lista.
  LaunchedEffect(ordered) {
    repeat(8) {
      withFrameNanos { }
      try {
        allFilterRequester.requestFocus()
        return@LaunchedEffect
      } catch (_: IllegalStateException) {
        delay(40)
      }
      try {
        recommendedStreamRequester.requestFocus()
        return@LaunchedEffect
      } catch (_: IllegalStateException) {
        delay(40)
      }
    }
  }

  // Cambio filtro: riparte dalla sorgente consigliata della lista filtrata.
  LaunchedEffect(selectedProvider) {
    focusedStreamIndex = filtered.indexOfFirst { it.cacheState == CacheState.Cached }.takeIf { it >= 0 } ?: 0
  }

  // La card focalizzata resta sempre visibile.
  LaunchedEffect(focusedStreamIndex, filtered) {
    if (focusedStreamIndex in filtered.indices) {
      listState.animateScrollToItem(focusedStreamIndex)
    }
  }

  Box(
    modifier = modifier
      .fillMaxSize()
      .background(NovaBackground)
  ) {
    MediaBackdrop(media)

    // Overlay cinematico: il backdrop resta leggibile come sfondo ma non disturba la lista.
    Box(
      modifier = Modifier
        .fillMaxSize()
        .background(
          Brush.verticalGradient(
            colors = listOf(
              NovaBackground.copy(alpha = 0.40f),
              NovaBackground.copy(alpha = 0.88f),
              NovaBackground
            ),
            startY = 0f,
            endY = 900f
          )
        )
    )

    Row(
      modifier = Modifier
        .fillMaxSize()
        .padding(horizontal = 32.dp, vertical = 24.dp)
        // Focus group: la navigazione D-pad resta confinata a questa schermata,
        // impedendo che UP/LEFT sfuggano verso la sidebar sottostante.
        .focusGroup()
    ) {
      // Zona media: clear logo + badge metadati (senza barra azioni, centrati).
      MediaInfoPanel(
        media = media,
        episode = episode,
        modifier = Modifier
          .width(320.dp)
          .fillMaxHeight()
      )

      Spacer(modifier = Modifier.width(28.dp))

      // Zona risultati: provider filter (alto) + lista stream (sotto)
      Column(
        modifier = Modifier
          .weight(1f)
          .fillMaxHeight()
      ) {
        SourceSelectionHeader(count = ordered.size, onDismiss = onDismiss)

        Spacer(modifier = Modifier.height(18.dp))

        ProviderFilterBar(
          providers = providers,
          providerRequesters = providerRequesters,
          allRequester = allFilterRequester,
          selectedProvider = selectedProvider,
          hasStreams = filtered.isNotEmpty(),
          firstStreamRequester = firstStreamRequester,
          onSelect = { selectedProvider = it }
        )

        Spacer(modifier = Modifier.height(14.dp))

        LazyColumn(
          modifier = Modifier
            .fillMaxWidth()
            .weight(1f),
          state = listState,
          verticalArrangement = Arrangement.spacedBy(10.dp),
          // Padding laterale/top: evita che lo zoom (1.03x) e il bordo luminoso della
          // card focalizzata vengano tagliati (clip) ai bordi della lista.
          contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 24.dp)
        ) {
          itemsIndexed(
            items = filtered,
            key = { index, source -> sourceKey(source, index) }
          ) { index, source ->
            DebridSourceCard(
              source = source,
              onClick = { onSelect(source) },
              onFocus = { if (it) focusedStreamIndex = index },
              modifier = Modifier
                .then(if (index == 0) Modifier.focusRequester(firstStreamRequester) else Modifier)
                .then(
                  if (index == recommendedIndex) Modifier.focusRequester(recommendedStreamRequester)
                  else Modifier
                )
                .focusProperties {
                  // UP dalla lista torna alla barra provider (confine superiore della lista).
                  if (index == 0) up = activeProviderRequester
                  // DOWN sull'ultimo elemento non ha destinazione: il focus non deve uscire.
                  if (index == filtered.lastIndex) down = FocusRequester.Cancel
                }
            )
          }
        }
      }
    }
  }
}

/**
 * Backdrop del titolo come sfondo della schermata. Usa SOLO dati gia' presenti nel
 * [MediaItem]; se assente, resta il fondo NovaBackground (nessuna nuova richiesta).
 */
@Composable
private fun MediaBackdrop(media: MediaItem?) {
  if (media == null) return
  val backdropUrl = media.backdropUrl?.takeIf { it.isNotBlank() }
  if (backdropUrl != null) {
    AsyncImage(
      model = ImageRequest.Builder(LocalContext.current)
        .data(backdropUrl)
        .crossfade(true)
        .build(),
      contentDescription = null,
      contentScale = ContentScale.Crop,
      alpha = 0.45f,
      modifier = Modifier.fillMaxSize()
    )
  } else {
    val backdropRes = media.backdropRes ?: return
    Image(
      painter = painterResource(id = backdropRes),
      contentDescription = null,
      contentScale = ContentScale.Crop,
      alpha = 0.4f,
      modifier = Modifier.fillMaxSize()
    )
  }
}

/**
 * Zona media a sinistra: clear logo originale (o titolo testuale in stile display)
 * e badge metadati (generi, anno, durata, rating), centrati orizzontalmente e
 * posizionati al centro della meta' superiore della colonna.
 *
 * Non contiene pulsanti/controlli cliccabili: la barra azioni
 * (Riproduci/avorito/Visto/Trailer) vive SOLO in
 * [com.example.ui.screens.DetailScreen], quindi qui non ci sono nodi che possano
 * catturare il focus D-pad. Il poster rettangolare e' stato rimosso: resta il
 * backdrop cinematografico come sfondo della schermata.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MediaInfoPanel(
  media: MediaItem?,
  episode: Episode?,
  modifier: Modifier = Modifier,
) {
  val title = media?.title.orEmpty()
  val clearLogoUrl = media?.logoUrl?.takeIf { it.isNotBlank() }

  Column(modifier = modifier) {
    // Blocco logo + badge: centrato nella meta' superiore della colonna.
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .weight(1f),
      contentAlignment = Alignment.Center
    ) {
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
      ) {
        if (clearLogoUrl != null) {
          AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
              .data(clearLogoUrl)
              .crossfade(true)
              .build(),
            contentDescription = title,
            contentScale = ContentScale.Fit,
            alignment = Alignment.Center,
            modifier = Modifier
              .fillMaxWidth()
              .heightIn(min = 80.dp, max = 100.dp)
          )
        } else if (title.isNotBlank()) {
          Text(
            text = title,
            color = NovaTextPrimary,
            fontSize = 30.sp,
            fontWeight = FontWeight.Black,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            style = PanelTitleShadow
          )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Generi (max 3) subito sotto il logo, centrati rispetto alla colonna.
        val genres = media?.genres.orEmpty()
        if (genres.isNotEmpty()) {
          FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()
          ) {
            genres.take(3).forEach { genre ->
              Box(
                modifier = Modifier
                  .background(NovaSurfaceVariant.copy(alpha = 0.8f), RoundedCornerShape(12.dp))
                  .border(1.dp, NovaCyan.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                  .padding(horizontal = 9.dp, vertical = 3.dp)
              ) {
                Text(
                  text = genre,
                  color = NovaCyanBright,
                  fontSize = 11.sp,
                  fontWeight = FontWeight.Medium,
                  maxLines = 1
                )
              }
            }
          }
          Spacer(modifier = Modifier.height(10.dp))
        }

        // Anno di uscita, durata (o stagioni), classificazione eta' e rating TMDB.
        if (media != null) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            modifier = Modifier.fillMaxWidth()
          ) {
            if (media.year > 0) {
              QualityBadge(text = media.year.toString())
            }

            val durationLabel = if (media.type == MediaType.SERIE_TV && (media.seasonsCount ?: 0) > 0) {
              stringResource(R.string.media_card_seasons_abbr, media.seasonsCount ?: 1)
            } else {
              media.formattedDuration
            }
            QualityBadge(text = durationLabel)

            media.ageRating?.let { age -> QualityBadge(text = "$age+") }

            val rating = media.tmdbRating?.takeIf { it > 0f }
            if (rating != null) {
              QualityBadge(
                text = String.format(Locale.US, "%.1f", rating),
                leadingIcon = Icons.Default.Star,
                leadingIconTint = NovaGold
              )
            }
          }
        }

        if (episode != null) {
          Spacer(modifier = Modifier.height(8.dp))
          Text(
            text = "S${episode.seasonNumber}E${episode.episodeNumber} • ${episode.title}",
            color = NovaCyanBright.copy(alpha = 0.9f),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
          )
        }
      }
    }

    // Meta' inferiore lasciata vuota: nessun controllo, nessuna trappola di focus.
    Spacer(modifier = Modifier.weight(1f))
  }
}

/** Ombra proiettata per il titolo testuale di fallback (leggibilita' sul backdrop). */
private val PanelTitleShadow = TextStyle(
  shadow = Shadow(color = Color.Black.copy(alpha = 0.7f), blurRadius = 8f)
)

@Composable
private fun SourceSelectionHeader(count: Int, onDismiss: () -> Unit) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = stringResource(R.string.source_dialog_title, count),
        color = NovaTextPrimary,
        fontSize = 26.sp,
        fontWeight = FontWeight.Bold
      )
      Spacer(modifier = Modifier.height(4.dp))
      Text(
        text = stringResource(R.string.source_dialog_hints),
        color = NovaTextMuted,
        fontSize = 12.sp
      )
    }

    Spacer(modifier = Modifier.width(16.dp))

    TvFocusableBox(
      shape = RoundedCornerShape(50),
      focusedScale = 1.05f,
      onClick = onDismiss,
      modifier = Modifier.pointerInput(Unit) { detectTapGestures { onDismiss() } }
    ) { isFocused ->
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
          .background(
            if (isFocused) NovaCyan.copy(alpha = 0.2f) else NovaSurfaceVariant,
            RoundedCornerShape(50)
          )
          .border(
            1.dp,
            if (isFocused) NovaCyanBright else NovaDivider,
            RoundedCornerShape(50)
          )
          .padding(horizontal = 18.dp, vertical = 10.dp)
      ) {
        Icon(
          imageVector = Icons.Default.Close,
          contentDescription = null,
          tint = if (isFocused) NovaCyanBright else NovaTextSecondary,
          modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text = stringResource(R.string.action_cancel),
          color = if (isFocused) NovaCyanBright else NovaTextSecondary,
          fontSize = 15.sp,
          fontWeight = FontWeight.SemiBold
        )
      }
    }
  }
}

/**
 * Barra orizzontale dei provider con "Tutto" come primo filtro. Ogni chip e'
 * focusabile: LEFT/RIGHT tra i provider, DOWN entra nella lista stream. Il filtro
 * agisce SOLO sulla visualizzazione (nessuna modifica alla discovery/ranking).
 */
@Composable
private fun ProviderFilterBar(
  providers: List<String>,
  providerRequesters: List<FocusRequester>,
  allRequester: FocusRequester,
  selectedProvider: String?,
  hasStreams: Boolean,
  firstStreamRequester: FocusRequester,
  onSelect: (String?) -> Unit,
) {
  val scrollState = rememberScrollState()
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .horizontalScroll(scrollState),
    horizontalArrangement = Arrangement.spacedBy(10.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    ProviderFilterChip(
      label = stringResource(R.string.source_dialog_filter_all),
      isSelected = selectedProvider == null,
      hasStreams = hasStreams,
      firstStreamRequester = firstStreamRequester,
      onClick = { onSelect(null) },
      modifier = Modifier.focusRequester(allRequester)
    )

    providers.forEachIndexed { index, provider ->
      ProviderFilterChip(
        label = provider,
        isSelected = provider.equals(selectedProvider, ignoreCase = true),
        hasStreams = hasStreams,
        firstStreamRequester = firstStreamRequester,
        onClick = { onSelect(provider) },
        modifier = if (index < providerRequesters.size) {
          Modifier.focusRequester(providerRequesters[index])
        } else {
          Modifier
        }
      )
    }
  }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ProviderFilterChip(
  label: String,
  isSelected: Boolean,
  hasStreams: Boolean,
  firstStreamRequester: FocusRequester,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  TvFocusableBox(
    shape = RoundedCornerShape(50),
    focusedScale = 1.05f,
    onClick = onClick,
    modifier = modifier
      // Confini espliciti e stabili del filtro provider.
      .focusProperties {
        up = FocusRequester.Cancel
        down = if (hasStreams) firstStreamRequester else FocusRequester.Cancel
      }
      .pointerInput(Unit) { detectTapGestures { onClick() } }
  ) { isFocused ->
    val background = when {
      isFocused -> NovaCyan.copy(alpha = 0.28f)
      isSelected -> NovaCyan.copy(alpha = 0.18f)
      else -> NovaSurfaceVariant
    }
    val border = when {
      isFocused -> NovaCyanBright
      isSelected -> NovaCyan
      else -> NovaDivider
    }
    val foreground = when {
      isFocused -> NovaCyanBright
      isSelected -> NovaCyan
      else -> NovaTextSecondary
    }

    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      modifier = Modifier
        .background(background, RoundedCornerShape(50))
        .border(1.dp, border, RoundedCornerShape(50))
        .padding(horizontal = 16.dp, vertical = 9.dp)
    ) {
      if (isSelected || isFocused) {
        Box(
          modifier = Modifier
            .size(8.dp)
            .background(if (isFocused) NovaCyanBright else NovaCyan, RoundedCornerShape(50))
        )
      }
      Text(
        text = label,
        color = foreground,
        fontSize = 13.sp,
        fontWeight = if (isFocused || isSelected) FontWeight.Bold else FontWeight.SemiBold,
        maxLines = 1
      )
    }
  }
}

/**
 * Card di una sorgente Debrid, fedele al riferimento grafico approvato.
 *
 * Struttura:
 *  - colonna sinistra: tag istantanea, nome completo del file, dimensione, riga
 *    lingue/peer/stato cache, riga tecnica (risoluzione/audio/tipo) e fila di
 *    loghi tecnici con la dimensione in evidenza;
 *  - colonna destra: logo dell'ADDON di origine (manifest `logo` o asset ufficiale,
 *    con iniziali come fallback neutro), nome + indicatore torrent/lingua e, in
 *    subordine, il servizio Debrid usato per lo sblocco.
 *
 * È SOLO presentazione: i dati mancanti vengono omessi (o mostrati come "N/A" dove
 * il riferimento lo prevede), nessun metadato viene inventato. Focus, pressione OK
 * e click restano quelli di [TvFocusableBox].
 *
 * Componente CONDIVISO fra la lista pre-playback ([TorBoxSourceSelectionScreen]) e
 * il selettore interno al player: le due finestre mostrano quindi gli stessi
 * metadati e lo stesso layout, senza una seconda implementazione visuale.
 *
 * @param isCurrent `true` per la sorgente in riproduzione: aggiunge il badge
 *   "In riproduzione", il bordo ciano e un fondo leggermente distinto. Nel
 *   pre-playback resta `false` (nessuna riga è "in riproduzione").
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DebridSourceCard(
  source: StreamSource,
  onClick: () -> Unit,
  onFocus: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
  isCurrent: Boolean = false,
) {
  val shape = RoundedCornerShape(12.dp)
  val meta = DebridSourceCardPresentation
  val sizeLabel = meta.sizeLabel(source, decimals = 2)
  val shortSizeLabel = meta.sizeLabel(source, decimals = 1)
  val audioCodes = meta.audioLanguageCodes(source)
  val subtitleCodes = meta.subtitleLanguageCodes(source)
  val peers = meta.peersLabel(source)
  // Identità dell'ADDON (nome + logo) e provider Debrid sono dati distinti:
  // il logo del servizio Debrid non occupa mai il posto del logo dell'addon.
  val addon = meta.addonIdentityOf(source)
  val provider = meta.providerOf(source)

  TvFocusableBox(
    shape = shape,
    // Feedback focus 10-foot UI invariato: zoom 1.03x fluido (animateFloatAsState
    // interno al wrapper) + bordo ciano luminescente del sistema focus esistente.
    focusedScale = 1.03f,
    borderWidth = 2.5.dp,
    // La sorgente in riproduzione resta riconoscibile anche senza focus: il bordo
    // ciano è lo stesso indicatore di selezione usato dal resto del player.
    unfocusedBorderColor = if (isCurrent) NovaCyan.copy(alpha = 0.7f) else Color.Transparent,
    onClick = onClick,
    modifier = modifier
      .fillMaxWidth()
      .onFocusChanged { onFocus(it.isFocused) }
      // Tap touch: seleziona senza alterare il sistema di focus TV.
      .pointerInput(Unit) { detectTapGestures { onClick() } }
  ) { isFocused ->
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .background(
          when {
            isFocused -> DebridCardBackgroundFocused
            isCurrent -> DebridCardBackgroundCurrent
            else -> DebridCardBackground
          },
          shape
        )
        .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
      // 0) Sorgente in riproduzione: indicatore conservato dal selettore del player.
      if (isCurrent) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          Box(
            modifier = Modifier
              .size(8.dp)
              .background(NovaGreen, RoundedCornerShape(50))
          )
          Text(
            text = stringResource(R.string.player_source_selector_current),
            color = NovaGreen,
            fontSize = 11.sp,
            lineHeight = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
          )
          Spacer(modifier = Modifier.weight(1f))
          Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = stringResource(R.string.player_cd_selected),
            tint = NovaCyanBright,
            modifier = Modifier.size(16.dp)
          )
        }
        Spacer(modifier = Modifier.height(8.dp))
      }

      Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
          // 1) Tag istantanea ("2160p TB Instant"): dato reale dell'addon.
          Text(
            text = meta.headline(source),
            color = DebridTitleColor,
            fontSize = 15.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )

          Spacer(modifier = Modifier.height(4.dp))

          // 2) Nome completo del file, riga singola con ellissi solo se necessaria.
          SourceMetaRow(firstIcon = "📄") {
            Text(
              text = meta.fileName(source),
              color = DebridFileColor,
              fontSize = 13.sp,
              lineHeight = 16.sp,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
          }

          // 3) Dimensione del file (solo se il metadato esiste).
          if (sizeLabel != null) {
            Spacer(modifier = Modifier.height(4.dp))
            SourceMetaRow(firstIcon = "💾") {
              Text(
                text = sizeLabel,
                color = DebridMetaColor,
                fontSize = 12.sp,
                lineHeight = 15.sp,
                maxLines = 1
              )
            }
          }

          // 4) Lingue audio/sottotitoli, peer e stato cache.
          Spacer(modifier = Modifier.height(5.dp))
          SourceLanguageRow(
            audioCodes = audioCodes,
            subtitleCodes = subtitleCodes,
            peers = peers,
            cacheState = source.cacheState
          )

          // 5) Riga tecnica: risoluzione, formato audio, tipo di rilascio.
          Spacer(modifier = Modifier.height(5.dp))
          SourceTechnicalRow(
            resolution = meta.resolutionLabel(source),
            audioFormat = meta.audioFormatLabel(source),
            releaseType = meta.releaseTypeLabel(source)
          )
        }

        Spacer(modifier = Modifier.width(14.dp))

        SourceIdentityColumn(
          identity = addon,
          isTorrentSource = !source.infoHash.isNullOrBlank(),
          languageTag = audioCodes.firstOrNull(),
          provider = provider
        )
      }

      Spacer(modifier = Modifier.height(10.dp))

      // 6) Loghi tecnici + badge dimensione, come nel riferimento grafico.
      SourceTechnicalBadges(source = source, sizeLabel = shortSizeLabel ?: sizeLabel)
    }
  }
}

/**
 * Riga informativa della card: icona (emoji del riferimento) in una colonna fissa
 * da 18dp, così tutte le righe restano allineate anche quando un valore manca.
 */
@Composable
private fun SourceMetaRow(
  firstIcon: String,
  content: @Composable RowScope.() -> Unit
) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier.fillMaxWidth()
  ) {
    Box(modifier = Modifier.width(18.dp), contentAlignment = Alignment.CenterStart) {
      Text(text = firstIcon, fontSize = 12.sp, lineHeight = 15.sp)
    }
    Spacer(modifier = Modifier.width(6.dp))
    content()
  }
}

/** Riga lingue/peer/stato cache (🌍 IT 🌐 | 👤 382 | ⏰). */
@Composable
private fun SourceLanguageRow(
  audioCodes: List<String>,
  subtitleCodes: List<String>,
  peers: String?,
  cacheState: CacheState
) {
  val audioLabel = audioCodes.takeIf { it.isNotEmpty() }
    ?.joinToString(" \u00b7 ")
    ?: DebridSourceCardPresentation.UNKNOWN_VALUE

  SourceMetaRow(firstIcon = "🌍") {
    Row(
      modifier = Modifier.weight(1f),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
      Text(
        text = audioLabel,
        color = DebridMetaColor,
        fontSize = 12.sp,
        lineHeight = 15.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f, fill = false)
      )

      Text(text = "🌐", fontSize = 12.sp, lineHeight = 15.sp)
      if (subtitleCodes.isNotEmpty()) {
        Text(
          text = subtitleCodes.joinToString(" \u00b7 "),
          color = DebridMetaColor,
          fontSize = 12.sp,
          lineHeight = 15.sp,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f, fill = false)
        )
      }

      if (peers != null) {
        MetaSeparator()
        Text(text = "👤", fontSize = 12.sp, lineHeight = 15.sp)
        Text(
          text = peers,
          color = DebridMetaColor,
          fontSize = 12.sp,
          lineHeight = 15.sp,
          maxLines = 1
        )
      }

      val cacheIcon = cacheIconOf(cacheState)
      if (cacheIcon != null) {
        MetaSeparator()
        Text(text = cacheIcon, fontSize = 12.sp, lineHeight = 15.sp)
      }
    }
  }
}

/** Riga tecnica (🎬 2160p | 🔊 DDP5.1 | 🏷️ WEB-DL). */
@Composable
private fun SourceTechnicalRow(
  resolution: String,
  audioFormat: String?,
  releaseType: String?
) {
  SourceMetaRow(firstIcon = "🎬") {
    Row(
      modifier = Modifier.weight(1f),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
      Text(
        text = resolution,
        color = DebridMetaColor,
        fontSize = 12.sp,
        lineHeight = 15.sp,
        maxLines = 1
      )

      MetaSeparator()
      Text(text = "🔊", fontSize = 12.sp, lineHeight = 15.sp)
      if (audioFormat != null) {
        Text(
          text = audioFormat,
          color = DebridMetaColor,
          fontSize = 12.sp,
          lineHeight = 15.sp,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f, fill = false)
        )
      }

      MetaSeparator()
      Text(text = "🏷️", fontSize = 12.sp, lineHeight = 15.sp)
      Text(
        text = releaseType ?: DebridSourceCardPresentation.UNKNOWN_VALUE,
        color = DebridMetaColor,
        fontSize = 12.sp,
        lineHeight = 15.sp,
        maxLines = 1
      )
    }
  }
}

@Composable
private fun MetaSeparator() {
  Text(
    text = "|",
    color = DebridSeparatorColor,
    fontSize = 12.sp,
    lineHeight = 15.sp,
    maxLines = 1
  )
}

/** Icona stato cache: istantanea (cached), in coda, oppure nessuna se sconosciuto. */
private fun cacheIconOf(state: CacheState): String? = when (state) {
  CacheState.Cached -> "⏰"
  CacheState.NotCached -> "⏳"
  CacheState.Unknown -> null
}

/**
 * Colonna destra della card: identità dell'ADDON di origine (logo + nome +
 * indicatore torrent/lingua) e, separato e in subordine, il servizio Debrid.
 *
 * Il logo mostrato in alto è sempre quello dell'addon: il provider Debrid non
 * viene mai usato come sostituto quando il logo dell'addon non è disponibile.
 */
@Composable
private fun SourceIdentityColumn(
  identity: AddonIdentity,
  isTorrentSource: Boolean,
  languageTag: String?,
  provider: DebridProvider?
) {
  Column(
    modifier = Modifier.width(132.dp),
    horizontalAlignment = Alignment.CenterHorizontally
  ) {
    AddonLogoAvatar(identity)

    Spacer(modifier = Modifier.height(5.dp))

    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
      Text(
        text = identity.name,
        color = DebridMetaColor,
        fontSize = 11.sp,
        lineHeight = 13.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f, fill = false)
      )
      if (isTorrentSource) {
        Text(text = "🧲", fontSize = 10.sp, lineHeight = 12.sp)
      }
      if (languageTag != null) {
        Text(
          text = languageTag,
          color = DebridLanguageColor,
          fontSize = 9.sp,
          lineHeight = 11.sp,
          fontWeight = FontWeight.Bold,
          letterSpacing = 0.5.sp,
          maxLines = 1
        )
      }
    }

    if (provider != null) {
      Spacer(modifier = Modifier.height(4.dp))
      DebridServiceLabel(provider = provider)
    }
  }
}

/**
 * Logo dell'addon di origine nella posizione identitaria della card.
 *
 * Ordine: logo ufficiale del manifest → asset ufficiale incluso nell'app per gli
 * addon riconosciuti → iniziali (fallback neutro). Se il logo remoto non si carica,
 * si ricade sull'asset locale o sulle iniziali: mai su un logo di altro provider.
 */
@Composable
private fun AddonLogoAvatar(identity: AddonIdentity) {
  Box(
    modifier = Modifier
      .size(32.dp)
      .clip(RoundedCornerShape(50))
      .background(DebridAvatarBackground)
      .border(1.dp, DebridAvatarBorder, RoundedCornerShape(50)),
    contentAlignment = Alignment.Center
  ) {
    val logoUrl = identity.logoUrl
    if (logoUrl != null) {
      SubcomposeAsyncImage(
        model = ImageRequest.Builder(LocalContext.current)
          .data(logoUrl)
          .crossfade(true)
          .build(),
        contentDescription = identity.name,
        contentScale = ContentScale.Fit,
        modifier = Modifier
          .matchParentSize()
          .padding(1.dp),
        loading = { AddonInitials(name = identity.name) },
        error = { BundledAddonLogo(identity) }
      )
    } else {
      BundledAddonLogo(identity)
    }
  }
}

/** Asset locale dell'addon riconosciuto, oppure iniziali come fallback neutro. */
@Composable
private fun BoxScope.BundledAddonLogo(identity: AddonIdentity) {
  val logoResId = identity.logoResId
  if (logoResId != null) {
    Image(
      painter = painterResource(id = logoResId),
      contentDescription = identity.name,
      contentScale = ContentScale.Fit,
      modifier = Modifier
        .matchParentSize()
        .padding(1.dp)
    )
  } else {
    AddonInitials(name = identity.name)
  }
}

@Composable
private fun AddonInitials(name: String) {
  Text(
    text = DebridSourceCardPresentation.initials(name),
    color = DebridTitleColor,
    fontSize = 13.sp,
    lineHeight = 16.sp,
    fontWeight = FontWeight.Black,
    maxLines = 1
  )
}

/**
 * Servizio Debrid usato per sbloccare la sorgente. È un metadato SECONDARIO:
 * resta sotto il nome dell'addon, con icona ridotta e prefisso esplicito, così
 * non può essere confuso con il logo dell'addon.
 */
@Composable
private fun DebridServiceLabel(provider: DebridProvider) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(4.dp)
  ) {
    Image(
      painter = painterResource(id = provider.logoResId),
      contentDescription = null,
      contentScale = ContentScale.Fit,
      modifier = Modifier
        .height(12.dp)
        .widthIn(max = 14.dp)
    )
    Text(
      text = stringResource(R.string.source_card_debrid_via, provider.label),
      color = DebridMetaColor.copy(alpha = 0.75f),
      fontSize = 9.sp,
      lineHeight = 11.sp,
      fontWeight = FontWeight.Medium,
      letterSpacing = 0.2.sp,
      maxLines = 1
    )
  }
}

/**
 * Fila dei loghi tecnici, nello stesso ordine del riferimento: WEB-DL, 4K,
 * Dolby Vision, HDR10(+), HEVC, Dolby Atmos, Dolby Digital Plus, DIMENSIONE.
 *
 * Ogni logo compare SOLO se il metadato reale della sorgente lo dichiara; la fila
 * usa [FlowRow] per non andare in overflow sulle card più strette.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SourceTechnicalBadges(source: StreamSource, sizeLabel: String?) {
  val meta = DebridSourceCardPresentation
  val hdrBadges = meta.hdrBadges(source)

  FlowRow(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(16.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp)
  ) {
    if (meta.hasWebDl(source)) TechBadgeSlot { WebDlLogo() }
    if (meta.isUltraHd(source)) TechBadgeSlot { UltraHdLogo() }
    hdrBadges.forEach { badge ->
      TechBadgeSlot {
        when (badge) {
          HdrBadgeLabel.DOLBY_VISION -> DolbyLogo(label = "VISION")
          HdrBadgeLabel.HDR10_PLUS,
          HdrBadgeLabel.HDR10,
          HdrBadgeLabel.HDR -> HdrTextLogo(text = badge.text)
        }
      }
    }
    if (meta.isHevc(source)) TechBadgeSlot { HevcLogo() }
    if (meta.hasDolbyAtmos(source)) TechBadgeSlot { DolbyLogo(label = "ATMOS") }
    if (meta.hasDolbyDigitalPlus(source)) TechBadgeSlot { DolbyDigitalPlusLogo() }
    if (sizeLabel != null) TechBadgeSlot { SizeBadge(value = sizeLabel) }
  }
}

/**
 * Slot a altezza fissa della fila loghi: mantiene tutti i marchi allineati sul
 * centro anche quando hanno altezze diverse (nessuna deformazione, nessun salto).
 */
@Composable
private fun TechBadgeSlot(content: @Composable () -> Unit) {
  Box(
    modifier = Modifier.height(20.dp),
    contentAlignment = Alignment.CenterStart
  ) {
    content()
  }
}

/** Logo "WEB-DL": nuvola con freccia in blu + wordmark, come nel riferimento. */
@Composable
private fun WebDlLogo() {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(4.dp)
  ) {
    Canvas(modifier = Modifier.size(width = 16.dp, height = 13.dp)) {
      val w = size.width
      val h = size.height
      val stroke = Stroke(
        width = h * 0.14f,
        cap = StrokeCap.Round,
        join = StrokeJoin.Round
      )
      val cloud = Path().apply {
        moveTo(w * 0.16f, h * 0.82f)
        cubicTo(w * 0.02f, h * 0.82f, w * 0.00f, h * 0.58f, w * 0.13f, h * 0.52f)
        cubicTo(w * 0.09f, h * 0.25f, w * 0.31f, h * 0.10f, w * 0.44f, h * 0.25f)
        cubicTo(w * 0.51f, h * 0.01f, w * 0.81f, h * 0.04f, w * 0.83f, h * 0.27f)
        cubicTo(w * 1.01f, h * 0.25f, w * 1.04f, h * 0.63f, w * 0.86f, h * 0.72f)
        lineTo(w * 0.16f, h * 0.82f)
        close()
      }
      drawPath(path = cloud, color = WebDlBlue, style = stroke)
      // Freccia di download al centro della nuvola.
      drawLine(
        color = WebDlBlue,
        start = Offset(w * 0.50f, h * 0.34f),
        end = Offset(w * 0.50f, h * 0.64f),
        strokeWidth = h * 0.13f,
        cap = StrokeCap.Round
      )
      drawLine(
        color = WebDlBlue,
        start = Offset(w * 0.39f, h * 0.53f),
        end = Offset(w * 0.50f, h * 0.65f),
        strokeWidth = h * 0.13f,
        cap = StrokeCap.Round
      )
      drawLine(
        color = WebDlBlue,
        start = Offset(w * 0.61f, h * 0.53f),
        end = Offset(w * 0.50f, h * 0.65f),
        strokeWidth = h * 0.13f,
        cap = StrokeCap.Round
      )
    }
    Text(
      text = "WEB-DL",
      color = WebDlBlue,
      fontSize = 11.sp,
      lineHeight = 13.sp,
      fontWeight = FontWeight.Black,
      letterSpacing = 0.2.sp,
      maxLines = 1
    )
  }
}

/** Logo "4K ULTRA HD": riquadro nero, "4K" oro e banda inferiore oro. */
@Composable
private fun UltraHdLogo() {
  Column(
    modifier = Modifier
      // Larghezza fissa: il FlowRow non deve allargare il marchio a tutta la riga.
      .width(30.dp)
      .background(Color.Black, RoundedCornerShape(2.dp))
      .border(0.5.dp, UltraHdGold.copy(alpha = 0.65f), RoundedCornerShape(2.dp)),
    horizontalAlignment = Alignment.CenterHorizontally
  ) {
    Text(
      text = "4K",
      color = UltraHdGold,
      fontSize = 11.sp,
      lineHeight = 13.sp,
      fontWeight = FontWeight.Black,
      maxLines = 1,
      modifier = Modifier.padding(horizontal = 5.dp)
    )
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .background(UltraHdGold),
      contentAlignment = Alignment.Center
    ) {
      Text(
        text = "ULTRA HD",
        color = Color.Black,
        fontSize = 4.sp,
        lineHeight = 5.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.3.sp,
        maxLines = 1,
        modifier = Modifier.padding(vertical = 0.5.dp)
      )
    }
  }
}

/** Logo Dolby (Vision / Atmos): simbolo "DD" + wordmark su due righe. */
@Composable
private fun DolbyLogo(label: String) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(3.dp)
  ) {
    DolbyMark(modifier = Modifier.size(width = 13.dp, height = 9.dp))
    Column {
      Text(
        text = "Dolby",
        color = DolbyWhite,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 9.sp,
        maxLines = 1
      )
      Text(
        text = label,
        color = DolbyWhite.copy(alpha = 0.85f),
        fontSize = 4.5.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.9.sp,
        lineHeight = 5.sp,
        maxLines = 1
      )
    }
  }
}

/** Logo "Dolby Digital Plus": simbolo "DD" in riquadro + wordmark su due righe. */
@Composable
private fun DolbyDigitalPlusLogo() {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(3.dp)
  ) {
    DolbyMark(modifier = Modifier.size(width = 13.dp, height = 13.dp), boxed = true)
    Column {
      Text(
        text = "DOLBY",
        color = DolbyWhite,
        fontSize = 8.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 8.sp,
        maxLines = 1
      )
      Text(
        text = "DIGITAL PLUS",
        color = DolbyWhite.copy(alpha = 0.85f),
        fontSize = 4.5.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.4.sp,
        lineHeight = 5.sp,
        maxLines = 1
      )
    }
  }
}

/**
 * Simbolo Dolby: due semicerchi affacciati (eventualmente dentro il riquadro
 * arrotondato della variante Digital Plus). Disegnato in Compose, senza asset.
 */
@Composable
private fun DolbyMark(modifier: Modifier = Modifier, boxed: Boolean = false) {
  Canvas(modifier = modifier) {
    val w = size.width
    val h = size.height
    val pad = if (boxed) h * 0.16f else 0f
    if (boxed) {
      drawRoundRect(
        color = DolbyWhite,
        topLeft = Offset(0f, 0f),
        size = Size(w, h),
        cornerRadius = CornerRadius(h * 0.22f),
        style = Stroke(width = h * 0.10f)
      )
    }

    val innerW = w - pad * 2f
    val innerH = h - pad * 2f
    val gap = innerW * 0.08f
    val radius = (innerW - gap) / 4f
    val leftRect = Rect(Offset(pad, pad), Size(radius * 2f, innerH))
    val rightRect = Rect(Offset(pad + radius * 2f + gap, pad), Size(radius * 2f, innerH))

    val leftPath = Path().apply {
      moveTo(pad, pad)
      lineTo(pad + radius, pad)
      arcTo(leftRect, startAngleDegrees = -90f, sweepAngleDegrees = 180f, forceMoveTo = false)
      lineTo(pad, pad + innerH)
      close()
    }
    val rightPath = Path().apply {
      moveTo(pad + innerW, pad + innerH)
      lineTo(pad + radius * 3f + gap, pad + innerH)
      arcTo(rightRect, startAngleDegrees = 90f, sweepAngleDegrees = 180f, forceMoveTo = false)
      lineTo(pad + innerW, pad)
      close()
    }

    drawPath(path = leftPath, color = DolbyWhite)
    drawPath(path = rightPath, color = DolbyWhite)
  }
}

/** Wordmark HDR10 / HDR10+ / HDR. */
@Composable
private fun HdrTextLogo(text: String) {
  Text(
    text = text,
    color = DolbyWhite,
    fontSize = 12.sp,
    lineHeight = 15.sp,
    fontWeight = FontWeight.Black,
    letterSpacing = 0.4.sp,
    maxLines = 1
  )
}

/** Logo HEVC: wordmark verde corsivo. */
@Composable
private fun HevcLogo() {
  Text(
    text = "HEVC",
    color = HevcGreen,
    fontSize = 12.sp,
    lineHeight = 15.sp,
    fontWeight = FontWeight.Black,
    fontStyle = FontStyle.Italic,
    letterSpacing = 0.4.sp,
    maxLines = 1
  )
}

/** Badge finale "DIMENSIONE 23.8 GB", come nel riferimento. */
@Composable
private fun SizeBadge(value: String) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(5.dp)
  ) {
    Text(
      text = stringResource(R.string.source_card_size_label).uppercase(),
      color = DebridTitleColor,
      fontSize = 9.sp,
      lineHeight = 11.sp,
      fontWeight = FontWeight.Black,
      letterSpacing = 0.8.sp,
      maxLines = 1
    )
    Text(
      text = value,
      color = DebridTitleColor,
      fontSize = 11.sp,
      lineHeight = 13.sp,
      fontWeight = FontWeight.Black,
      maxLines = 1
    )
  }
}

/** Nome provider mostrato: addonName quando presente, con fallback su serverName. */
internal fun providerLabelOf(source: StreamSource): String {
  source.addonName?.takeIf { it.isNotBlank() }?.let { return it.trim() }
  val cleaned = source.serverName.substringBefore(" \ud83e\uddf2").trim()
  return cleaned.ifBlank { source.serverName }
}

/** Chiave normalizzata del provider, usata solo per deduplicare il filtro in UI. */
private fun providerKeyOf(source: StreamSource): String = providerLabelOf(source).lowercase()

/**
 * Chiave stabile per gli item della lista (evita ricreazioni del nodo focusabile).
 *
 * Include l'identità dell'installazione Stremio ([StreamSource.addonInstallId]): due
 * configurazioni dello stesso addon (Torrentio globale e IT) possono restituire
 * lo stesso infoHash/fileIdx, che altrimenti produrrebbe chiavi duplicate e
 * farebbe fallire la LazyColumn.
 */
private fun sourceKey(source: StreamSource, index: Int): String = buildString {
  source.addonInstallId?.takeIf { it.isNotBlank() }?.let { append(it).append('#') }
  val hash = source.infoHash
  if (hash != null) append(hash).append('#').append(source.fileIdx ?: -1)
  else append("src_$index")
}

// ── Palette della card Debrid ────────────────────────────────────────────────
// Toni caldi e neutri del riferimento grafico: la card Debrid resta volutamente
// distinta dalla palette ciano/navy usata dal resto dell'app, mentre il bordo e lo
// zoom del focus restano quelli del sistema focus TV esistente (TvFocusableBox).
private val DebridCardBackground = Color(0xFF211E1C)
private val DebridCardBackgroundFocused = Color(0xFF2B2623)
/** Fondo della sorgente in riproduzione: stesso tono caldo con una punta di ciano. */
private val DebridCardBackgroundCurrent = Color(0xFF1F2729)
private val DebridTitleColor = Color(0xFFF2F0EE)
private val DebridFileColor = Color(0xFFC3BFBB)
private val DebridMetaColor = Color(0xFFB0ACA7)
private val DebridSeparatorColor = Color(0xFF6F6A65)
private val DebridLanguageColor = Color(0xFF9E9994)
private val DebridAvatarBackground = Color(0xFF3A332F)
private val DebridAvatarBorder = Color(0x33FFFFFF)
private val WebDlBlue = Color(0xFF2D9CDB)
private val UltraHdGold = Color(0xFFF2B90C)
private val HevcGreen = Color(0xFF3DDC5A)
private val DolbyWhite = Color(0xFFEDEBE9)

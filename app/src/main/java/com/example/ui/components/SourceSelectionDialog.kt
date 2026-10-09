package com.example.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.example.R
import com.example.data.model.Episode
import com.example.data.model.MediaItem
import com.example.data.streaming.CacheState
import com.example.data.streaming.StreamSource
import com.example.data.streaming.TorBoxSourceOrdering
import com.example.ui.theme.NovaBackground
import com.example.ui.theme.NovaCardBgFocused
import com.example.ui.theme.NovaCyan
import com.example.ui.theme.NovaCyanBright
import com.example.ui.theme.NovaDivider
import com.example.ui.theme.NovaGold
import com.example.ui.theme.NovaGreen
import com.example.ui.theme.NovaOrange
import com.example.ui.theme.NovaSurface
import com.example.ui.theme.NovaSurfaceVariant
import com.example.ui.theme.NovaTextMuted
import com.example.ui.theme.NovaTextPrimary
import com.example.ui.theme.NovaTextSecondary
import kotlinx.coroutines.delay

/**
 * Schermata dedicata (full-screen) per la selezione della sorgente TorBox.
 *
 * Sostituisce il vecchio popup modale "Seleziona sorgente (N)": il layout e'
 * organizzato come una schermata di selezione con, a sinistra, la zona media
 * (poster + backdrop + logo originale del titolo, tutti dati gia' presenti nel
 * [MediaItem] risolto) e, a destra, la barra orizzontale dei provider e la lista
 * verticale degli stream.
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
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TorBoxSourceSelectionScreen(
  sources: List<StreamSource>,
  media: MediaItem?,
  episode: Episode? = null,
  onSelect: (StreamSource) -> Unit,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
) {
  // Ordinamento deterministico gia' esistente: applicato prima di mostrare la lista.
  val ordered = remember(sources) { TorBoxSourceOrdering.sort(sources) }

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
  // Requester dedicato alla sorgente consigliata: focus iniziale del dialog.
  val recommendedStreamRequester = remember { FocusRequester() }

  // Requester del provider attivo: destinazione di UP dalla lista stream.
  val activeProviderRequester = remember(selectedProvider, providers, providerRequesters, allFilterRequester) {
    val selected = selectedProvider ?: return@remember allFilterRequester
    val index = providers.indexOfFirst { it.equals(selected, ignoreCase = true) }
    if (index in providerRequesters.indices) providerRequesters[index] else allFilterRequester
  }

  // Back: chiude la schermata senza avviare la riproduzione.
  BackHandler { onDismiss() }

  // Punto 1 – Focus iniziale automatico sulla sorgente consigliata (prima cached,
  // potenzialmente 4K/1080p in cima): l'utente TV preme solo OK/INVIO per avviare
  // lo stream migliore senza scorrere l'elenco. Il retry copre il primo layout.
  LaunchedEffect(ordered) {
    repeat(8) {
      withFrameNanos { }
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
      // Zona media: poster + logo/titolo + metadati
      MediaInfoPanel(
        media = media,
        episode = episode,
        modifier = Modifier
          .width(280.dp)
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
            TorBoxSourceCard(
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
 * Zona media a sinistra: poster, logo originale (fallback sul titolo testuale) e
 * metadati principali. Ricava tutto dal [MediaItem]/[Episode] gia' disponibili.
 */
@Composable
private fun MediaInfoPanel(
  media: MediaItem?,
  episode: Episode?,
  modifier: Modifier = Modifier,
) {
  Column(modifier = modifier) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .height(300.dp)
        .clip(RoundedCornerShape(16.dp))
        .border(1.dp, NovaDivider, RoundedCornerShape(16.dp))
    ) {
      CardMediaImage(
        primaryUrl = media?.posterUrl,
        secondaryUrl = media?.backdropUrl,
        fallbackRes = media?.posterRes ?: media?.backdropRes ?: R.drawable.banner_dune,
        contentDescription = media?.title,
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop
      )
    }

    Spacer(modifier = Modifier.height(18.dp))

    val title = media?.title.orEmpty()
    if (!media?.logoUrl.isNullOrBlank()) {
      SubcomposeAsyncImage(
        model = ImageRequest.Builder(LocalContext.current)
          .data(media?.logoUrl)
          .crossfade(true)
          .build(),
        contentDescription = title,
        contentScale = ContentScale.Fit,
        alignment = Alignment.CenterStart,
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(min = 40.dp, max = 76.dp),
        error = {
          Text(
            text = title,
            color = NovaTextPrimary,
            fontSize = 24.sp,
            fontWeight = FontWeight.Black,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
          )
        }
      )
    } else {
      Text(
        text = title,
        color = NovaTextPrimary,
        fontSize = 24.sp,
        fontWeight = FontWeight.Black,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
      )
    }

    Spacer(modifier = Modifier.height(10.dp))

    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      val year = media?.year
      if (year != null && year > 0) {
        Text(
          text = year.toString(),
          color = NovaTextSecondary,
          fontSize = 14.sp,
          fontWeight = FontWeight.SemiBold
        )
        Text(text = "\u2022", color = NovaTextMuted, fontSize = 14.sp)
      }
      media?.let {
        Text(
          text = it.type.labelItalian,
          color = NovaTextSecondary,
          fontSize = 14.sp,
          fontWeight = FontWeight.SemiBold
        )
      }
    }

    if (episode != null) {
      Spacer(modifier = Modifier.height(6.dp))
      Text(
        text = "S${episode.seasonNumber}E${episode.episodeNumber} \u2022 ${episode.title}",
        color = NovaCyanBright.copy(alpha = 0.9f),
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
      )
    }
  }
}

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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TorBoxSourceCard(
  source: StreamSource,
  onClick: () -> Unit,
  onFocus: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
) {
  val shape = RoundedCornerShape(14.dp)
  TvFocusableBox(
    shape = shape,
    // Punto 2 – Feedback focus 10-foot UI: zoom 1.03x fluido (animateFloatAsState
    // interno a TvFocusableBox) + bordo luminescente e ombra già gestiti dal wrapper.
    focusedScale = 1.03f,
    borderWidth = 2.5.dp,
    onClick = onClick,
    modifier = modifier
      .fillMaxWidth()
      .onFocusChanged { onFocus(it.isFocused) }
      // Tap touch: seleziona senza alterare il sistema di focus TV.
      .pointerInput(Unit) { detectTapGestures { onClick() } }
  ) { isFocused ->
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .background(if (isFocused) NovaCardBgFocused else NovaSurfaceVariant, shape)
        // Bordo perimetrale interno più marcato quando focalizzato (inequivocabile da lontano).
        .border(if (isFocused) 2.dp else 1.dp, if (isFocused) NovaCyanBright else NovaDivider, shape)
        .padding(horizontal = 16.dp, vertical = 14.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Column(modifier = Modifier.weight(1f)) {
        // Riga badge (in alto): risoluzione, cache, HDR, codec, tipo rilascio, IT.
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
          ResolutionBadge(source)
          CacheBadge(source.cacheState)
          TorBoxSourceOrdering.hdrLabel(source)?.let { HdrBadge(it) }
          source.codec?.takeIf { it.isNotBlank() }?.let { CodecBadge(it) }
          source.releaseType?.takeIf { it.isNotBlank() }?.let { ReleaseTypeBadge(it) }
          if (source.isItalian) ItalianBadge()
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Nome file ben visibile, troncato su due righe senza deformare la card.
        Text(
          text = source.releaseTitle?.takeIf { it.isNotBlank() } ?: source.serverName,
          color = NovaTextPrimary,
          fontSize = 16.sp,
          fontWeight = FontWeight.SemiBold,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis
        )

        val sizeLabel = formatSize(TorBoxSourceOrdering.sizeBytesOf(source).takeIf { it > 0L })
        val extraDetails = remainingDetails(source.details)
        // Il nome dell'addon NON ripete qui: è già mostrato dal badge provider
        // sulla destra ([SourceProviderLogo] / [providerLabelOf]). La riga resta
        // dedicata a dimensione e dettagli residui (peer, ecc.).
        if (sizeLabel != null || extraDetails != null) {
          Spacer(modifier = Modifier.height(8.dp))
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
          ) {
            sizeLabel?.let { MetaPill(it) }
            extraDetails?.let { MetaText(it) }
          }
        }

        val audio = source.detectedAudioLanguages
        if (audio.isNotEmpty()) {
          Spacer(modifier = Modifier.height(6.dp))
          MetaRow(
            label = stringResource(R.string.torbox_source_audio),
            value = audio.joinToString(" \u00b7 ")
          )
        }

        val subtitleLanguages = source.subtitles
          .map { it.getDisplayLanguage() }
          .filter { it.isNotBlank() }
          .distinct()
        if (subtitleLanguages.isNotEmpty()) {
          Spacer(modifier = Modifier.height(4.dp))
          MetaRow(
            label = stringResource(R.string.torbox_source_subtitles),
            value = subtitleLanguages.joinToString(" \u00b7 ")
          )
        }
      }

      Spacer(modifier = Modifier.width(16.dp))

      // Logo del provider sulla destra, ricavato dai dati gia' presenti (addonName).
      SourceProviderLogo(name = providerLabelOf(source))
    }
  }
}

/**
 * Logo del provider dello stream. Non esistendo asset dedicati per gli addon,
 * viene rappresentato in modo deterministico dalle iniziali del provider con la
 * palette StreamNova (nessun asset duplicato, nessun nuovo sistema provider).
 */
@Composable
private fun SourceProviderLogo(name: String, modifier: Modifier = Modifier, size: Dp = 44.dp) {
  Column(
    modifier = modifier.width(78.dp),
    horizontalAlignment = Alignment.CenterHorizontally
  ) {
    Box(
      modifier = Modifier
        .size(size)
        .clip(RoundedCornerShape(12.dp))
        .background(NovaSurface)
        .border(1.dp, NovaCyan.copy(alpha = 0.4f), RoundedCornerShape(12.dp)),
      contentAlignment = Alignment.Center
    ) {
      Text(
        text = providerInitials(name),
        color = NovaCyanBright,
        fontSize = (size.value * 0.4f).sp,
        fontWeight = FontWeight.Black
      )
    }
    Spacer(modifier = Modifier.height(6.dp))
    Text(
      text = name,
      color = NovaTextSecondary,
      fontSize = 10.sp,
      fontWeight = FontWeight.SemiBold,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
      textAlign = TextAlign.Center
    )
  }
}

@Composable
private fun MetaPill(text: String) {
  Box(
    modifier = Modifier
      .background(NovaSurface, RoundedCornerShape(6.dp))
      .border(1.dp, NovaDivider, RoundedCornerShape(6.dp))
      .padding(horizontal = 8.dp, vertical = 3.dp)
  ) {
    Text(
      text = text,
      color = NovaTextPrimary,
      fontSize = 12.sp,
      fontWeight = FontWeight.SemiBold
    )
  }
}

@Composable
private fun MetaText(text: String) {
  Text(
    text = text,
    color = NovaTextSecondary,
    fontSize = 12.sp,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis
  )
}

@Composable
private fun MetaRow(label: String, value: String) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp)
  ) {
    Text(
      text = label,
      color = NovaCyan,
      fontSize = 11.sp,
      fontWeight = FontWeight.Bold
    )
    Text(
      text = value,
      color = NovaTextSecondary,
      fontSize = 13.sp,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis
    )
  }
}

@Composable
private fun ResolutionBadge(source: StreamSource) {
  val resolution = source.resolution
  val height = TorBoxSourceOrdering.resolutionHeight(source)
  val (background, foreground) = when {
    height >= 2160 -> NovaCyan.copy(alpha = 0.25f) to NovaCyanBright
    height >= 1080 -> NovaCyan.copy(alpha = 0.18f) to NovaCyan
    else -> NovaSurfaceVariant to NovaTextSecondary
  }
  Badge(text = resolution, background = background, foreground = foreground, fontSize = 13.sp)
}

@Composable
private fun CacheBadge(state: CacheState) {
  when (state) {
    CacheState.Cached -> Badge(
      text = stringResource(R.string.torbox_source_cached),
      background = NovaGreen.copy(alpha = 0.18f),
      foreground = NovaGreen
    )
    CacheState.NotCached -> Badge(
      text = stringResource(R.string.torbox_source_not_cached),
      background = NovaOrange.copy(alpha = 0.18f),
      foreground = NovaOrange
    )
    CacheState.Unknown -> Badge(
      text = stringResource(R.string.torbox_source_unknown),
      background = NovaSurface,
      foreground = NovaTextMuted
    )
  }
}

@Composable
private fun HdrBadge(label: String) {
  Badge(
    text = label,
    background = NovaGold.copy(alpha = 0.2f),
    foreground = NovaGold
  )
}

@Composable
private fun CodecBadge(codec: String) {
  val isHevc = codec.contains("HEVC", ignoreCase = true) ||
    codec.contains("x265", ignoreCase = true) ||
    codec.contains("AV1", ignoreCase = true) ||
    codec.contains("hvc1", ignoreCase = true)
  Badge(
    text = codec,
    background = if (isHevc) NovaOrange.copy(alpha = 0.2f) else NovaSurface,
    foreground = if (isHevc) NovaOrange else NovaTextSecondary
  )
}

@Composable
private fun ReleaseTypeBadge(type: String) {
  val (background, foreground) = when {
    type.contains("WEB", ignoreCase = true) -> NovaGreen.copy(alpha = 0.2f) to NovaGreen
    type.contains("Blu", ignoreCase = true) -> NovaOrange.copy(alpha = 0.2f) to NovaOrange
    type.contains("HDR", ignoreCase = true) -> NovaGold.copy(alpha = 0.2f) to NovaGold
    else -> NovaSurface to NovaTextSecondary
  }
  Badge(text = type, background = background, foreground = foreground)
}

@Composable
private fun ItalianBadge() {
  Badge(
    text = "\ud83c\uddee\ud83c\uddf9 IT",
    background = NovaGreen.copy(alpha = 0.2f),
    foreground = NovaGreen
  )
}

@Composable
private fun Badge(
  text: String,
  background: Color,
  foreground: Color,
  fontSize: androidx.compose.ui.unit.TextUnit = 11.sp
) {
  Box(
    modifier = Modifier
      .background(background, RoundedCornerShape(4.dp))
      .padding(horizontal = 7.dp, vertical = 3.dp)
  ) {
    Text(
      text = text,
      color = foreground,
      fontSize = fontSize,
      fontWeight = FontWeight.Bold,
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

/** Iniziali del provider (max 2) usate come "logo" testuale deterministico. */
private fun providerInitials(name: String): String {
  val words = name
    .replace(Regex("[^\\p{L}\\p{N} ]"), " ")
    .trim()
    .split(Regex("\\s+"))
    .filter { it.isNotBlank() }
  if (words.isEmpty()) return "?"
  if (words.size == 1) return words[0].take(2).uppercase()
  return (words[0].first().toString() + words[1].first().toString()).uppercase()
}

/** Chiave stabile per gli item della lista (evita ricreazioni del nodo focusabile). */
private fun sourceKey(source: StreamSource, index: Int): String =
  source.infoHash?.let { "$it#${source.fileIdx ?: -1}" } ?: "src_$index"

/** Formatta una dimensione in byte ("2.18 GB"); null se assente/zero. */
private fun formatSize(bytes: Long?): String? {
  if (bytes == null || bytes <= 0L) return null
  val units = listOf("B", "KB", "MB", "GB", "TB")
  var value = bytes.toDouble()
  var index = 0
  while (value >= 1024.0 && index < units.lastIndex) {
    value /= 1024.0
    index++
  }
  return if (index == 0) "$bytes B"
  else String.format(java.util.Locale.US, "%.2f %s", value, units[index])
}

/** Rimuove la dimensione da details, lasciando eventuali peer/extra utili. */
private fun remainingDetails(details: String?): String? {
  if (details.isNullOrBlank()) return null
  val cleaned = SIZE_TOKEN
    .replace(details, " ")
    .replace("|", " ")
    .replace(Regex("\\s+"), " ")
    .trim()
    .trim('\u00b7', '-', '\u2022')
    .trim()
  return cleaned.takeIf { it.isNotBlank() }
}

private val SIZE_TOKEN =
  Regex("(\\d+(?:[.,]\\d+)?)\\s*(TB|TiB|GB|GiB|MB|MiB|KB|KiB|B)\\b", RegexOption.IGNORE_CASE)

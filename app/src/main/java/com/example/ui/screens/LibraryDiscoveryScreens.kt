package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.example.data.discovery.WatchMeFilters
import com.example.data.discovery.WatchMeSuggestion
import com.example.data.library.NewEpisodesStatus
import com.example.data.library.SeriesEpisodeUpdate
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.ui.components.requestFocusWhenAttached
import com.example.ui.components.SidebarContentExitLink
import com.example.ui.theme.NovaCardBg
import com.example.ui.theme.NovaCardBgFocused
import com.example.ui.theme.NovaCyan
import com.example.ui.theme.NovaCyanBright
import com.example.ui.theme.NovaDivider
import com.example.ui.theme.NovaGreen
import com.example.ui.theme.NovaTextMuted
import com.example.ui.theme.NovaTextPrimary
import com.example.ui.theme.NovaTextSecondary
import com.example.ui.viewmodel.NewEpisodesUiState
import com.example.ui.viewmodel.StreamNovaViewModel
import kotlinx.coroutines.launch

/**
 * "Non so cosa guardare": suggerimento intelligente con filtri opzionali.
 *
 * Il pulsante principale non è un random puro: [WatchMeDecider] pesa gusti
 * (generi/registi preferiti), watchlist, voto e stato di visione. Le motivazioni
 * della scelta sono mostrate sotto il titolo (dati reali, nessuna spiegazione
 * inventata). "Scopri qualcosa di nuovo" propone un titolo mai visto.
 */
@Composable
fun WatchMeScreen(
  viewModel: StreamNovaViewModel,
  onMediaClick: (MediaItem) -> Unit,
  modifier: Modifier = Modifier
) {
  val filters by viewModel.watchMeFilters.collectAsState()
  val suggestion by viewModel.watchMeSuggestion.collectAsState()

  // Uscita dalla sidebar verso questa sezione (RIGHT sulla voce, o OK sulla voce che la
  // seleziona): il focus entra sul chip azione «Scegli per me», il gesto principale della
  // schermata, in modo DETERMINISTICO. La ricerca geometrica con la sidebar aperta non
  // trova candidati «strict-right» e il ripiego 2D atterra su un'altra voce del menu
  // (difetto: RIGHT da «Non so cosa guardare» chiudeva la sidebar riportando il focus
  // sull'icona stessa).
  val watchMeEntryRequester = remember { FocusRequester() }
  val watchMeUiScope = rememberCoroutineScope()
  val watchMeExitHandler = remember(watchMeEntryRequester, watchMeUiScope) {
    {
      try {
        watchMeEntryRequester.requestFocus()
        true
      } catch (_: Exception) {
        // Nodo non ancora agganciato (frame d'apertura): il ripristino prosegue fuori
        // dal dispatch; l'uscita resta presidiata (nessun ripiego geometrico).
        watchMeUiScope.launch {
          requestFocusWhenAttached(watchMeEntryRequester, maxAttempts = 6)
        }
        true
      }
    }
  }
  DisposableEffect(watchMeExitHandler) {
    SidebarContentExitLink.attach(watchMeExitHandler)
    onDispose { SidebarContentExitLink.detach(watchMeExitHandler) }
  }

  LazyColumn(
    modifier = modifier.fillMaxSize(),
    contentPadding = PaddingValues(horizontal = 28.dp, vertical = 22.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp)
  ) {
    item(key = "watchme_header") {
      Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(
            imageVector = Icons.Default.Casino,
            contentDescription = null,
            tint = NovaCyanBright,
            modifier = Modifier.size(28.dp)
          )
          Spacer(modifier = Modifier.width(10.dp))
          Text(
            text = "Non so cosa guardare",
            color = NovaTextPrimary,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
          )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
          text = "Scegliamo noi per te: una selezione intelligente basata sui tuoi gusti, " +
            "sulla watchlist e su ciò che non hai ancora visto.",
          color = NovaTextSecondary,
          fontSize = 13.sp
        )
      }
    }

    item(key = "watchme_filters") {
      WatchMeFiltersPanel(
        filters = filters,
        onFiltersChange = { viewModel.setWatchMeFilters(it) }
      )
    }

    item(key = "watchme_actions") {
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ActionChip(
          label = "Scegli per me",
          icon = Icons.Default.Casino,
          primary = true,
          onClick = { viewModel.requestWatchMeSuggestion() },
          // Target d'ingresso deterministico della sezione (uscita dalla sidebar).
          modifier = Modifier.focusRequester(watchMeEntryRequester)
        )
        ActionChip(
          label = "Scopri qualcosa di nuovo",
          icon = Icons.Default.AutoAwesome,
          primary = false,
          onClick = { viewModel.discoverSomethingNew() }
        )
      }
    }

    item(key = "watchme_result") {
      if (suggestion == null) {
        EmptyHint(
          text = "Nessun titolo compatibile con i filtri scelti. Prova ad allargare i criteri."
        )
      } else {
        WatchMeResultCard(
          suggestion = suggestion!!,
          onOpen = { onMediaClick(suggestion!!.item) },
          onAnother = { viewModel.requestWatchMeSuggestion() }
        )
      }
    }
  }
}

@Composable
private fun WatchMeFiltersPanel(
  filters: WatchMeFilters,
  onFiltersChange: (WatchMeFilters) -> Unit
) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .background(NovaCardBg, RoundedCornerShape(14.dp))
      .border(1.dp, NovaDivider, RoundedCornerShape(14.dp))
      .padding(14.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp)
  ) {
    Text(
      text = "Filtri",
      color = NovaTextPrimary,
      fontSize = 14.sp,
      fontWeight = FontWeight.SemiBold
    )
    FilterRow("Tipo") {
      FilterChip(
        label = "Tutti",
        selected = filters.type == null,
        onClick = { onFiltersChange(filters.copy(type = null)) }
      )
      FilterChip(
        label = "Film",
        selected = filters.type == MediaType.FILM,
        onClick = { onFiltersChange(filters.copy(type = MediaType.FILM)) }
      )
      FilterChip(
        label = "Serie",
        selected = filters.type == MediaType.SERIE_TV,
        onClick = { onFiltersChange(filters.copy(type = MediaType.SERIE_TV)) }
      )
    }
    FilterRow("Genere") {
      listOf(
        "Azione", "Commedia", "Drammatico", "Thriller",
        "Fantascienza", "Horror", "Animazione", "Documentario"
      ).forEach { genre ->
        FilterChip(
          label = genre,
          selected = filters.genre.equals(genre, ignoreCase = true),
          onClick = {
            onFiltersChange(
              filters.copy(genre = if (filters.genre.equals(genre, ignoreCase = true)) null else genre)
            )
          }
        )
      }
    }
    FilterRow("Voto minimo") {
      listOf(6f, 7f, 8f).forEach { rating ->
        FilterChip(
          label = "$rating+",
          selected = filters.minRating == rating,
          onClick = { onFiltersChange(filters.copy(minRating = rating)) }
        )
      }
      FilterChip(
        label = "Qualsiasi",
        selected = filters.minRating == null,
        onClick = { onFiltersChange(filters.copy(minRating = null)) }
      )
    }
    if (filters.type != MediaType.SERIE_TV) {
      FilterRow("Durata max (film)") {
        listOf(90, 120, 180).forEach { minutes ->
          FilterChip(
            label = "$minutes min",
            selected = filters.maxDurationMinutes == minutes,
            onClick = { onFiltersChange(filters.copy(maxDurationMinutes = minutes)) }
          )
        }
        FilterChip(
          label = "Nessun limite",
          selected = filters.maxDurationMinutes == null,
          onClick = { onFiltersChange(filters.copy(maxDurationMinutes = null)) }
        )
      }
    }
    FilterRow("Stato") {
      FilterChip(
        label = "Solo mai visti",
        selected = filters.onlyUnwatched,
        onClick = { onFiltersChange(filters.copy(onlyUnwatched = !filters.onlyUnwatched)) }
      )
      FilterChip(
        label = "Solo watchlist",
        selected = filters.onlyWatchlist,
        onClick = { onFiltersChange(filters.copy(onlyWatchlist = !filters.onlyWatchlist)) }
      )
    }
  }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun FilterRow(title: String, content: @Composable () -> Unit) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    Text(
      text = title,
      color = NovaTextMuted,
      fontSize = 12.sp,
      modifier = Modifier.width(140.dp)
    )
    // FlowRow: i chip vanno a capo sulle larghezze ridotte invece di uscire
    // dallo schermo (TV strette e mobile).
    androidx.compose.foundation.layout.FlowRow(
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
      modifier = Modifier.weight(1f)
    ) {
      content()
    }
  }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
  // Focus TV visibile: bordo ciano + zoom del componente standard TvFocusableBox
  // (un chip con il solo `clickable` non mostra NESSUNA indicazione di focus su
  // Android TV, rendendo i filtri non selezionabili col D-pad).
  com.example.ui.components.TvFocusableBox(
    shape = RoundedCornerShape(50),
    focusedScale = 1.07f,
    borderWidth = if (selected) 1.5.dp else 1.dp,
    unfocusedBorderColor = if (selected) NovaCyanBright else NovaDivider,
    onClick = onClick
  ) { isFocused ->
    Text(
      text = label,
      color = if (isFocused || selected) NovaCyanBright else NovaTextSecondary,
      fontSize = 12.sp,
      fontWeight = if (selected || isFocused) FontWeight.Bold else FontWeight.Medium,
      modifier = Modifier
        .background(
          when {
            isFocused -> NovaCyan.copy(alpha = 0.30f)
            selected -> NovaCyan.copy(alpha = 0.22f)
            else -> Color.White.copy(alpha = 0.06f)
          },
          RoundedCornerShape(50)
        )
        .padding(horizontal = 12.dp, vertical = 6.dp)
    )
  }
}

@Composable
private fun ActionChip(
  label: String,
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  // Il parametro resta per compatibilità con i chiamanti, ma NON produce più
  // alcuna evidenziazione: "Scegli per me" e "Apri scheda" sembravano già a
  // fuoco perché i chip "primari" erano dipinti in ciano anche SENZA focus.
  // Bug segnalato: l'evidenziazione deve apparire SOLO col focus.
  @Suppress("UNUSED_PARAMETER") primary: Boolean,
  onClick: () -> Unit,
  // Facoltativo: aggancia un FocusRequester al chip (target d'ingresso deterministico
  // della sezione, usato dall'uscita dalla sidebar). null = nessun aggancio.
  modifier: Modifier = Modifier
) {
  com.example.ui.components.TvFocusableBox(
    modifier = modifier,
    shape = RoundedCornerShape(50),
    focusedScale = 1.07f,
    borderWidth = 1.dp,
    unfocusedBorderColor = NovaDivider,
    onClick = onClick
  ) { isFocused ->
    Row(
      modifier = Modifier
        .background(
          // Aspetto NEUTRO da non a fuoco (identico per entrambi i chip);
          // l'evidenziazione ciano compare solo quando il chip è a fuoco.
          if (isFocused) NovaCyan.copy(alpha = 0.30f) else NovaCardBgFocused,
          RoundedCornerShape(50)
        )
        .padding(horizontal = 18.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        tint = if (isFocused) NovaCyanBright else NovaTextSecondary,
        modifier = Modifier.size(18.dp)
      )
      Text(
        text = label,
        color = if (isFocused) NovaCyanBright else NovaTextPrimary,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold
      )
    }
  }
}

@Composable
private fun WatchMeResultCard(
  suggestion: WatchMeSuggestion,
  onOpen: () -> Unit,
  onAnother: () -> Unit
) {
  val item = suggestion.item
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .background(NovaCardBg, RoundedCornerShape(16.dp))
      .border(1.5.dp, NovaCyan.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
      .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp)
  ) {
    Row {
      Box(
        modifier = Modifier
          .width(120.dp)
          .height(180.dp)
          .clip(RoundedCornerShape(10.dp))
          .background(Color.White.copy(alpha = 0.05f))
      ) {
        SubcomposeAsyncImage(
          model = ImageRequest.Builder(LocalContext.current)
            .data(item.posterUrl)
            .crossfade(true)
            .build(),
          contentDescription = item.title,
          contentScale = ContentScale.Crop,
          modifier = Modifier.fillMaxSize()
        )
      }
      Spacer(modifier = Modifier.width(16.dp))
      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
          text = item.title,
          color = NovaTextPrimary,
          fontSize = 20.sp,
          fontWeight = FontWeight.Bold,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis
        )
        Text(
          text = buildString {
            append(item.year)
            append(" · ")
            append(if (item.type == MediaType.FILM) "Film" else "Serie TV")
            append(" · ${"%.1f".format(item.rating)}/10")
            if (item.durationMinutes > 0) append(" · ${item.durationMinutes} min")
          },
          color = NovaTextSecondary,
          fontSize = 12.sp
        )
        if (item.genres.isNotEmpty()) {
          Text(
            text = item.genres.take(3).joinToString(" · "),
            color = NovaTextMuted,
            fontSize = 12.sp
          )
        }
        Text(
          text = "Perché: " + suggestion.reasons.take(3).joinToString(" · "),
          color = NovaGreen,
          fontSize = 12.sp,
          fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          ActionChip(
            label = "Apri scheda",
            icon = Icons.Default.NewReleases,
            primary = true,
            onClick = onOpen
          )
          ActionChip(
            label = "Un altro",
            icon = Icons.Default.Refresh,
            primary = false,
            onClick = onAnother
          )
        }
      }
    }
  }
}

@Composable
private fun EmptyHint(text: String) {
  Text(
    text = text,
    color = NovaTextMuted,
    fontSize = 13.sp,
    modifier = Modifier
      .fillMaxWidth()
      .background(NovaCardBg, RoundedCornerShape(12.dp))
      .padding(16.dp)
  )
}

/**
 * New Episodes Center: serie seguite con episodi già andati in onda dopo
 * l'ultimo visto. La scansione viene avviata all'apertura e può essere
 * ripetuta con "Aggiorna"; nessun dato viene modificato.
 */
@Composable
fun NewEpisodesScreen(
  viewModel: StreamNovaViewModel,
  onMediaClick: (MediaItem) -> Unit,
  modifier: Modifier = Modifier
) {
  val context = LocalContext.current
  val state by viewModel.newEpisodesState.collectAsState()
  LaunchedEffect(viewModel) {
    viewModel.attachContext(context)
    if (state is NewEpisodesUiState.Idle) {
      viewModel.refreshNewEpisodes(context)
    }
  }

  // Uscita dalla sidebar verso questa sezione (RIGHT sulla voce, o OK sulla voce che la
  // seleziona): il focus entra sul chip «Aggiorna» dell'intestazione, il primo elemento
  // interattivo sempre composto, in modo DETERMINISTICO. La ricerca geometrica con la
  // sidebar aperta non trova candidati «strict-right» e il ripiego 2D atterra su un'altra
  // voce del menu (difetto: RIGHT da «Nuovi episodi» chiudeva la sidebar riportando il
  // focus sull'icona stessa).
  val newEpisodesEntryRequester = remember { FocusRequester() }
  val newEpisodesUiScope = rememberCoroutineScope()
  val newEpisodesExitHandler = remember(newEpisodesEntryRequester, newEpisodesUiScope) {
    {
      try {
        newEpisodesEntryRequester.requestFocus()
        true
      } catch (_: Exception) {
        // Nodo non ancora agganciato (frame d'apertura): il ripristino prosegue fuori
        // dal dispatch; l'uscita resta presidiata (nessun ripiego geometrico).
        newEpisodesUiScope.launch {
          requestFocusWhenAttached(newEpisodesEntryRequester, maxAttempts = 6)
        }
        true
      }
    }
  }
  DisposableEffect(newEpisodesExitHandler) {
    SidebarContentExitLink.attach(newEpisodesExitHandler)
    onDispose { SidebarContentExitLink.detach(newEpisodesExitHandler) }
  }

  LazyColumn(
    modifier = modifier.fillMaxSize(),
    contentPadding = PaddingValues(horizontal = 28.dp, vertical = 22.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp)
  ) {
    item(key = "new_episodes_header") {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
          imageVector = Icons.Default.NewReleases,
          contentDescription = null,
          tint = NovaCyanBright,
          modifier = Modifier.size(28.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = "Nuovi episodi",
            color = NovaTextPrimary,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
          )
          Text(
            text = "Le serie che segui con puntate già andate in onda e non ancora viste.",
            color = NovaTextSecondary,
            fontSize = 13.sp
          )
        }
        ActionChip(
          label = "Aggiorna",
          icon = Icons.Default.Refresh,
          primary = false,
          onClick = { viewModel.refreshNewEpisodes(context) },
          // Target d'ingresso deterministico della sezione (uscita dalla sidebar).
          modifier = Modifier.focusRequester(newEpisodesEntryRequester)
        )
      }
    }

    when (val current = state) {
      is NewEpisodesUiState.Idle -> {
        item(key = "new_episodes_idle") {
          EmptyHint("Apri questa sezione per avviare la scansione delle serie seguite.")
        }
      }
      is NewEpisodesUiState.Loading -> {
        item(key = "new_episodes_loading") {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .background(NovaCardBg, RoundedCornerShape(12.dp))
              .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
          ) {
            CircularProgressIndicator(
              modifier = Modifier.size(18.dp),
              color = NovaCyanBright,
              strokeWidth = 2.dp
            )
            Text(
              text = "Controllo gli episodi andati in onda delle serie seguite…",
              color = NovaTextSecondary,
              fontSize = 13.sp
            )
          }
        }
      }
      is NewEpisodesUiState.Error -> {
        item(key = "new_episodes_error") {
          EmptyHint("Scansione non riuscita: ${current.message}. Riprova con Aggiorna.")
        }
      }
      is NewEpisodesUiState.Ready -> {
        val result = current.result
        if (!result.hasTmdbAccess) {
          item(key = "new_episodes_no_tmdb") {
            EmptyHint(
              "Per calcolare i nuovi episodi serve TMDB configurato: imposta la chiave API " +
                "nelle Impostazioni. Le serie seguite restano comunque nella tua libreria."
            )
          }
        }
        item(key = "new_episodes_summary") {
          Text(
            text = buildString {
              append("${result.scanned} serie controllate")
              append(" · ${result.newEpisodes.size} con nuovi episodi")
              if (result.totalNewEpisodes > 0) append(" · ${result.totalNewEpisodes} puntate da vedere")
              if (result.failed > 0) append(" · ${result.failed} non verificabili")
            },
            color = if (result.newEpisodes.isEmpty()) NovaTextMuted else NovaGreen,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
          )
        }
        if (result.updates.isEmpty()) {
          item(key = "new_episodes_empty") {
            EmptyHint(
              "Nessuna serie seguita. Aggiungi titoli ai preferiti o guarda qualche episodio: " +
                "compariranno qui."
            )
          }
        } else {
          items(result.updates, key = { it.series.mediaId }) { update ->
            NewEpisodeRow(
              update = update,
              onClick = { onMediaClick(update.series.toMediaItem()) }
            )
          }
        }
      }
    }
  }
}

@Composable
private fun NewEpisodeRow(update: SeriesEpisodeUpdate, onClick: () -> Unit) {
  val series = update.series
  // Focus TV visibile (bordo ciano + zoom): la riga usava il solo `clickable`,
  // invisibile col D-pad su Android TV.
  com.example.ui.components.TvFocusableBox(
    shape = RoundedCornerShape(12.dp),
    focusedScale = 1.02f,
    borderWidth = 1.dp,
    unfocusedBorderColor =
      if (update.hasNewEpisodes) NovaCyan.copy(alpha = 0.65f) else NovaDivider,
    onClick = onClick
  ) { isFocused ->
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .background(
          if (isFocused) NovaCardBgFocused else NovaCardBg,
          RoundedCornerShape(12.dp)
        )
        .padding(12.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
    Box(
      modifier = Modifier
        .width(56.dp)
        .height(84.dp)
        .clip(RoundedCornerShape(8.dp))
        .background(Color.White.copy(alpha = 0.05f))
    ) {
      SubcomposeAsyncImage(
        model = ImageRequest.Builder(LocalContext.current)
          .data(series.posterUrl)
          .crossfade(true)
          .build(),
        contentDescription = series.title,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize()
      )
    }
    Spacer(modifier = Modifier.width(14.dp))
    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
      Text(
        text = series.title,
        color = NovaTextPrimary,
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )
      Text(
        text = when (update.status) {
          NewEpisodesStatus.NEW_EPISODES ->
            "${update.newEpisodeCount} nuovi episodi · prossimo ${update.newestUnwatched?.label ?: "—"}"
          NewEpisodesStatus.UP_TO_DATE ->
            "Aggiornata · ultimo visto ${series.lastWatchedLabel ?: "—"}"
          NewEpisodesStatus.NOT_STARTED ->
            "Da iniziare · ultimo disponibile ${update.latestAired?.label ?: "—"}"
          NewEpisodesStatus.NO_DATA ->
            "Dati episodi non disponibili"
        },
        color = when (update.status) {
          NewEpisodesStatus.NEW_EPISODES -> NovaCyanBright
          NewEpisodesStatus.UP_TO_DATE -> NovaTextMuted
          NewEpisodesStatus.NOT_STARTED -> NovaTextSecondary
          NewEpisodesStatus.NO_DATA -> NovaTextMuted
        },
        fontSize = 12.sp
      )
      if (series.lastWatchedLabel != null && update.hasNewEpisodes) {
        Text(
          text = "Ultimo visto ${series.lastWatchedLabel}",
          color = NovaTextMuted,
          fontSize = 11.sp
        )
      }
    }
    }
  }
}

/** Serie seguita → MediaItem minimo per aprire la scheda Dettaglio. */
private fun com.example.data.library.TrackedSeries.toMediaItem(): MediaItem = MediaItem(
  id = mediaId,
  title = title,
  synopsis = "",
  videoUrl = "",
  type = MediaType.SERIE_TV,
  tmdbId = tmdbId,
  posterUrl = posterUrl,
  backdropUrl = backdropUrl,
  year = 0,
  seasonsCount = seasonsCount,
  lastWatchedSeason = lastWatchedSeason,
  lastWatchedEpisode = lastWatchedEpisode,
  lastWatchedAt = lastWatchedAt,
  completedAt = completedAt
)

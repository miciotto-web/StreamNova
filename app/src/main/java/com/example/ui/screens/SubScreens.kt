package com.example.ui.screens

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.HdrOn
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Wallpaper
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.snapshotFlow
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import com.example.R
import com.example.BuildConfig
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.repository.SimklRepository
import com.example.data.remote.simkl.SimklPinState
import com.example.data.model.SearchTypeFilter
import com.example.data.prefs.AppSettingsRepository
import com.example.data.prefs.BackBufferOption
import com.example.data.prefs.DecoderFallbackMode
import com.example.data.prefs.InitialBufferOption
import com.example.data.prefs.LastStreamCacheDuration
import com.example.data.prefs.PlaybackSettings
import com.example.data.prefs.PreferredResolution
import com.example.data.prefs.StreamingEngineMode
import com.example.data.prefs.SubtitleBackground
import com.example.data.prefs.SubtitlePosition
import com.example.data.prefs.SubtitleSize
import com.example.data.prefs.TargetBufferOption
import com.example.data.update.ApkInstaller
import com.example.data.update.DownloadErrorReason
import com.example.data.update.UpdateCheckErrorReason
import com.example.data.update.UpdateCheckState
import com.example.data.update.UpdateDownloadState
import com.example.ui.components.ContinueWatchingCard
import com.example.ui.components.FOCUS_ROW_ANY
import com.example.ui.components.LocalClearLastFocusedMedia
import com.example.ui.components.LocalFocusRestoreToken
import com.example.ui.components.LocalLastFocusedMediaId
import com.example.ui.components.PosterMediaCard
import com.example.ui.components.requestFocusWhenAttached
import com.example.ui.components.scrollToItemVerified
import com.example.ui.components.stabilizeFirstSeenOrder
import com.example.ui.components.StandardMediaCard
import com.example.ui.components.TvActionButton
import com.example.ui.components.TvFocusBringIntoView
import com.example.ui.components.TvFocusGridBringIntoView
import com.example.ui.components.TvFocusAttachment
import com.example.ui.components.TvFocusableBox
import com.example.ui.components.guard
import com.example.ui.components.tvFocusRequester
import com.example.ui.components.TextInputDialog
import com.example.ui.components.TvPinDialog
import com.example.ui.components.QrCodeImage
import com.example.ui.theme.NovaBackground
import com.example.ui.theme.NovaCardBg
import com.example.ui.theme.NovaCardBgFocused
import com.example.ui.theme.NovaCyan
import com.example.ui.theme.NovaCyanBright
import com.example.ui.theme.NovaDivider
import com.example.ui.theme.NovaGreen
import com.example.ui.theme.NovaOrange
import com.example.ui.theme.NovaRed
import com.example.ui.theme.NovaSurface
import com.example.ui.theme.NovaSurfaceVariant
import com.example.ui.theme.NovaTextMuted
import com.example.ui.theme.NovaTextPrimary
import com.example.ui.theme.NovaTextSecondary
import com.example.ui.viewmodel.SearchCategorySource
import com.example.ui.viewmodel.StreamNovaViewModel
import com.example.ui.viewmodel.TorBoxAccountState
import java.io.File

enum class CategoryCatalogFilter(
  val id: String,
  @StringRes val labelRes: Int? = null,
  val brandName: String? = null,
  val tmdbProviderId: Int? = null,
  val providerId: String? = null
) {
  ALL("all", labelRes = R.string.filter_all),
  TOP_10("top_10", labelRes = R.string.filter_top_10),
  NETFLIX("netflix", brandName = "Netflix", tmdbProviderId = 8, providerId = "netflix"),
  HBO_MAX("hbo_max", brandName = "HBO Max", tmdbProviderId = 384, providerId = "hbo"),
  DISNEY_PLUS("disney_plus", brandName = "Disney+", tmdbProviderId = 337, providerId = "disney"),
  PRIME_VIDEO("prime_video", brandName = "Prime Video", tmdbProviderId = 119, providerId = "prime"),
  APPLE_TV("apple_tv", brandName = "Apple TV+", tmdbProviderId = 350, providerId = "apple"),
  PARAMOUNT_PLUS("paramount_plus", brandName = "Paramount+", tmdbProviderId = 531, providerId = "paramount"),
  ACTION("action", labelRes = R.string.filter_action),
  SCI_FI("sci_fi", labelRes = R.string.filter_sci_fi),
  DRAMA("drama", labelRes = R.string.filter_drama),
  COMEDY("comedy", labelRes = R.string.filter_comedy)
}

@Composable
fun CategoryBrowsingScreen(
  title: String,
  subtitle: String = "",
  items: List<MediaItem>,
  isTv: Boolean = false,
  viewModel: StreamNovaViewModel? = null,
  onMediaClick: (MediaItem) -> Unit,
  onLoadMore: (() -> Unit)? = null,
  modifier: Modifier = Modifier
) {
  var selectedFilter by remember { mutableStateOf(CategoryCatalogFilter.ALL) }
  // Stato di scroll della griglia PERSISTENTE durante la navigazione: al Back dal Dettaglio la
  // griglia ritrova la stessa posizione (come la griglia Provider), quindi la card ripristinata
  // resta nella riga e nella posizione visiva da cui si era entrati.
  val gridState = rememberSaveable(saver = LazyGridState.Saver) { LazyGridState() }

  // Stato di ripristino del focus al ritorno dal Dettaglio: il target è la coppia
  // (FOCUS_ROW_ANY, mediaId) salvata al click (griglia = superficie unica di focus), il token
  // avanza ad ogni evento di ripristino così i ritorni consecutivi dalla STESSA card
  // riattivano il tentativo anche quando l'id non è cambiato.
  val restoreMediaId = LocalLastFocusedMediaId.current
  val focusRestoreToken = LocalFocusRestoreToken.current
  val clearLastFocusedMedia = LocalClearLastFocusedMedia.current

  // Reset scroll in cima SOLO al REALE cambio filtro: l'effetto viene rieseguito ad ogni
  // composizione (quindi anche al ritorno dal Dettaglio) e un reset incondizionato riportava
  // la griglia all'indice 0, alterando la posizione visiva da cui si era entrati.
  var appliedFilterName by remember { mutableStateOf<String?>(null) }
  LaunchedEffect(selectedFilter) {
    val filterChanged = appliedFilterName != null && appliedFilterName != selectedFilter.name
    appliedFilterName = selectedFilter.name
    if (filterChanged) {
      gridState.scrollToItem(0)
    }
  }

  val providerCategoryMap by (viewModel?.categoryProviderMedia ?: MutableStateFlow(emptyMap())).collectAsState()

  val currentProviderId = selectedFilter.providerId
  val currentTmdbProviderId = selectedFilter.tmdbProviderId

  // Caricamento catalogo provider (Stremio o Fallback TMDB Discover)
  LaunchedEffect(selectedFilter, isTv) {
    if (currentProviderId != null && currentTmdbProviderId != null && viewModel != null) {
      viewModel.loadCategoryProvider(currentProviderId, currentTmdbProviderId, isTv)
    }
  }

  val filters = CategoryCatalogFilter.values()

  val providerItems = if (currentProviderId != null) {
    providerCategoryMap[Pair(currentProviderId, isTv)]
  } else null

  // Ordine dei contenuti CONGELATO per id di prima comparsa: gli aggiornamenti del catalogo
  // (l'osservatore Room riordina `allMedia` per rating ad ogni persistenza dei dettagli)
  // non devono ricollocare le card già a schermo, altrimenti la posizione visiva cambia ad
  // ogni ritorno dal Dettaglio. Le nuove pagine si accodano nell'ordine di arrivo.
  // La memoria è Saveable: deve sopravvivere alla navigazione verso il Dettaglio, dove
  // questa composizione viene smontata e il catalogo può cambiare ordine.
  val frozenItemOrder = rememberSaveable(
    saver = listSaver<MutableList<String>, String>(
      save = { order -> order.toList() },
      restore = { saved -> saved.toMutableList() }
    )
  ) { mutableListOf<String>() }
  val stableItems = remember(items) { stabilizeFirstSeenOrder(items, frozenItemOrder) { it.id } }
  val stableProviderItems = providerItems?.let { list ->
    remember(list) { stabilizeFirstSeenOrder(list, frozenItemOrder) { it.id } }
  }

  val filteredItems = remember(stableItems, selectedFilter, stableProviderItems) {
    when {
      stableProviderItems != null -> stableProviderItems
      selectedFilter == CategoryCatalogFilter.ALL -> stableItems
      selectedFilter == CategoryCatalogFilter.TOP_10 -> stableItems.filter { item ->
        item.genres.any { it.contains("Top 10", ignoreCase = true) || it.contains("Top10", ignoreCase = true) || it.contains("Top 100", ignoreCase = true) } ||
        item.provider?.contains("Top 10", ignoreCase = true) == true
      }.ifEmpty {
        stableItems.sortedByDescending { it.rating }.take(10)
      }
      selectedFilter == CategoryCatalogFilter.ACTION -> stableItems.filter { item ->
        item.genres.any { g ->
          g.contains("Azione", ignoreCase = true) ||
          g.contains("Action", ignoreCase = true) ||
          g.contains("Avventura", ignoreCase = true) ||
          g.contains("Adventure", ignoreCase = true)
        }
      }
      selectedFilter == CategoryCatalogFilter.SCI_FI -> stableItems.filter { item ->
        item.genres.any { g ->
          g.contains("Fantascienza", ignoreCase = true) ||
          g.contains("Sci-Fi", ignoreCase = true) ||
          g.contains("Sci Fi", ignoreCase = true) ||
          g.contains("Fantasy", ignoreCase = true)
        }
      }
      selectedFilter == CategoryCatalogFilter.DRAMA -> stableItems.filter { item ->
        item.genres.any { g ->
          g.contains("Dramma", ignoreCase = true) ||
          g.contains("Drama", ignoreCase = true)
        }
      }
      selectedFilter == CategoryCatalogFilter.COMEDY -> stableItems.filter { item ->
        item.genres.any { g ->
          g.contains("Commedia", ignoreCase = true) ||
          g.contains("Comedy", ignoreCase = true)
        }
      }
      else -> {
        val pid = currentProviderId ?: ""
        stableItems.filter { item ->
          item.provider?.contains(pid, ignoreCase = true) == true ||
          item.genres.any { it.contains(pid, ignoreCase = true) }
        }
      }
    }
  }

  // Richieste di focus per IDENTITÀ di cella: il ripristino dal Dettaglio deve atterrare sulla
  // card salvata, non su una cella fissa della griglia (in precedenza il FocusRequester era
  // agganciato alla cella 0 della griglia e il focus finiva sempre sulla prima card della
  // prima riga).
  val cellFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }
  fun cellFocusRequester(mediaId: String): FocusRequester =
    cellFocusRequesters.getOrPut(mediaId) { FocusRequester() }

  // Ancora di recupero di un ripristino fallito: viene eseguita da un effetto dedicato perché
  // il rilascio del target (che apre il gate `canFocus` delle card) riavvia l'effetto di
  // ripristino e ne cancellerebbe il corpo. L'effetto parte dopo la ricomposizione, quindi il
  // gate è già aperto e la richiesta di focus non viene rifiutata.
  var recoveryAnchorId by remember { mutableStateOf<String?>(null) }
  LaunchedEffect(recoveryAnchorId) {
    val anchorId = recoveryAnchorId ?: return@LaunchedEffect
    recoveryAnchorId = null
    requestFocusWhenAttached(cellFocusRequester(anchorId))
  }

  // UNICO punto di ingresso del ripristino dal Dettaglio: risolve la cella per identità e
  // richiede il focus SU QUELLA cella. Lo scroll avviene solo quando la cella target non è
  // già in finestra CON la griglia già misurata: alla prima composizione dopo il ritorno
  // dal Dettaglio `layoutInfo` è ancora VUOTO e il vecchio controllo "target non visibile?"
  // era vacuamente vero — la griglia saltava con la card bersaglio allineata alla cella
  // in alto a sinistra (difetto "il focus va sempre alla prima scheda di qualsiasi fila").
  LaunchedEffect(restoreMediaId, focusRestoreToken, filteredItems) {
    val mediaId = restoreMediaId ?: return@LaunchedEffect
    val index = filteredItems.indexOfFirst { it.id == mediaId }
    if (index < 0) {
      // Target non più disponibile (catalogo cambiato mentre il Dettaglio era aperto): il
      // target viene rilasciato così le card tornano focalizzabili, e l'ancora di recupero
      // assegna la prima cella GIÀ visibile — nessuno scroll, nessun salto silenzioso alla
      // prima card del catalogo e nessun cambio di sezione. Il focus è richiesto dall'effetto
      // dedicato ([recoveryAnchorId]): il rilascio del target riavvia QUESTO effetto e ne
      // cancellerebbe il corpo.
      var firstVisibleId = gridState.layoutInfo.visibleItemsInfo.firstOrNull()?.key as? String
      var frames = 0
      while (firstVisibleId == null && frames < 5) {
        withFrameNanos { }
        frames++
        firstVisibleId = gridState.layoutInfo.visibleItemsInfo.firstOrNull()?.key as? String
      }
      recoveryAnchorId = firstVisibleId
      clearLastFocusedMedia?.invoke()
      return@LaunchedEffect
    }
    // La visibilità del target si valuta solo su una griglia GIÀ misurata (almeno una cella
    // in composizione): finché `visibleItemsInfo` è vuoto ogni verdetto sarebbe falso.
    var measuredFrames = 0
    while (gridState.layoutInfo.visibleItemsInfo.isEmpty() && measuredFrames < 8) {
      withFrameNanos { }
      measuredFrames++
    }
    val targetVisible = gridState.layoutInfo.visibleItemsInfo.any { it.index == index }
    if (!targetVisible && !gridState.isScrollInProgress) {
      try {
        gridState.scrollToItemVerified(index)
      } catch (_: Exception) {
        // La richiesta di focus sotto attende comunque il montaggio della cella.
      }
    }
    requestFocusWhenAttached(cellFocusRequester(mediaId))
  }

  Column(
    modifier = modifier
      .fillMaxSize()
      .background(NovaBackground)
      .padding(start = 32.dp, top = 24.dp, end = 32.dp)
  ) {
    Text(
      text = title,
      color = NovaTextPrimary,
      fontSize = 24.sp,
      fontWeight = FontWeight.Bold
    )

    // Filter Chips Row
    LazyRow(
      modifier = Modifier.padding(vertical = 14.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      items(filters, key = { it.name }, contentType = { "filter_chip" }) { filter ->
        val isSelected = selectedFilter == filter
        val label = filter.labelRes?.let { stringResource(it) } ?: filter.brandName ?: ""
        TvFocusableBox(
          shape = RoundedCornerShape(20.dp),
          onClick = { selectedFilter = filter }
        ) { isFocused ->
          Box(
            modifier = Modifier
              .background(
                when {
                  isFocused -> NovaCyanBright
                  isSelected -> NovaCyan.copy(alpha = 0.25f)
                  else -> NovaSurfaceVariant
                },
                RoundedCornerShape(20.dp)
              )
              .border(
                1.dp,
                if (isSelected || isFocused) NovaCyanBright else Color(0xFF1E293B),
                RoundedCornerShape(20.dp)
              )
              .padding(horizontal = 14.dp, vertical = 8.dp)
          ) {
            Text(
              text = label,
              color = if (isFocused) NovaBackground else if (isSelected) NovaCyanBright else NovaTextSecondary,
              fontSize = 12.sp,
              fontWeight = if (isSelected || isFocused) FontWeight.Bold else FontWeight.Medium
            )
          }
        }
      }
    }

    TvFocusBringIntoView {
      LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 150.dp),
        state = gridState,
        contentPadding = PaddingValues(bottom = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize()
      ) {
        itemsIndexed(filteredItems, key = { _, item -> item.id }, contentType = { _, _ -> "media_poster" }) { index, item ->
          if (index >= filteredItems.size - 6) {
            LaunchedEffect(Unit) {
              if (currentProviderId != null && currentTmdbProviderId != null && viewModel != null) {
                viewModel.loadNextCategoryProviderPage(currentProviderId, currentTmdbProviderId, isTv)
              } else {
                onLoadMore?.invoke()
              }
            }
          }
          Box(
            modifier = Modifier
              .fillMaxWidth(),
            contentAlignment = Alignment.TopCenter
          ) {
            PosterMediaCard(
              media = item,
              onClick = {
                viewModel?.saveLastFocusedMedia(FOCUS_ROW_ANY, item.id)
                onMediaClick(item)
              },
              // Il requester DELLA CARD SELZIONATA vive sul nodo FOCALIZZABILE della card
              // (come ProviderScreen): una richiesta via `focusRequester` attaccato a una Box
              // non focalizzabile lancia sempre "FocusRequester is not initialized" e il
              // ripristino non può mai essere consegnato (difetto: il focus atterra sulla
              // prima card della riga al ritorno dal Dettaglio).
              focusRequester = cellFocusRequester(item.id),
              // Protocollo Provider (conferma reale via onFocusChanged, tentativi a frame):
              // la richiesta non è più monocolpo, quindi su un dispositivo reale — dove le
              // finestre di composizione al rientro sono più lente che in test — il tentativo
              // non viene consumato su un nodo non ancora pronto.
              confirmFocusRestore = true
            )
          }
        }
      }
    }
  }
}

@Composable
fun FavoritesScreen(
  allMedia: List<MediaItem>,
  onMediaClick: (MediaItem) -> Unit,
  modifier: Modifier = Modifier,
  viewModel: StreamNovaViewModel? = null
) {
  val favorites = allMedia.filter { it.isFavorite }

  // Focus restoration: riceve il segnale dal ViewModel
  val focusRestoreSignal = remember { mutableStateOf<String?>(null) }
  LaunchedEffect(viewModel) {
    viewModel?.focusRestoreSignal?.collect { mediaId ->
      focusRestoreSignal.value = mediaId
    }
  }

  val gridState = rememberLazyGridState()
  val focusRequester = remember { FocusRequester() }
  LaunchedEffect(focusRestoreSignal.value, gridState) {
    val mediaId = focusRestoreSignal.value ?: return@LaunchedEffect
    focusRestoreSignal.value = null

    val index = favorites.indexOfFirst { it.id == mediaId }
    if (index >= 0) {
      gridState.scrollToItem(index)
      kotlinx.coroutines.delay(100)
      try {
        focusRequester.requestFocus()
      } catch (_: Exception) {}
    }
  }

  Column(
    modifier = modifier
      .fillMaxSize()
      .background(NovaBackground)
      .padding(start = 32.dp, top = 24.dp, end = 32.dp)
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Column {
        Text(
          text = stringResource(R.string.favorites_title),
          color = NovaTextPrimary,
          fontSize = 26.sp,
          fontWeight = FontWeight.Black,
          letterSpacing = 0.5.sp
        )
        Text(
          text = stringResource(R.string.favorites_subtitle),
          color = NovaTextSecondary,
          fontSize = 13.sp,
          modifier = Modifier.padding(top = 2.dp)
        )
      }

      Box(
        modifier = Modifier
          .background(Color(0x33000000), RoundedCornerShape(50))
          .border(1.dp, NovaCyanBright.copy(alpha = 0.5f), RoundedCornerShape(50))
          .padding(horizontal = 16.dp, vertical = 6.dp)
      ) {
        Text(
          text = if (favorites.isEmpty()) stringResource(R.string.favorites_count_zero) else stringResource(R.string.favorites_count_format, favorites.size),
          color = NovaCyanBright,
          fontSize = 12.sp,
          fontWeight = FontWeight.Bold
        )
      }
    }

    Spacer(modifier = Modifier.height(20.dp))

    if (favorites.isEmpty()) {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .padding(32.dp),
        contentAlignment = Alignment.Center
      ) {
        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          Text(
            text = stringResource(R.string.favorites_empty_title),
            color = NovaTextPrimary,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
          )
          Text(
            text = stringResource(R.string.favorites_empty_desc),
            color = NovaTextMuted,
            fontSize = 14.sp
          )
        }
      }
    } else {
      TvFocusBringIntoView {
        LazyVerticalGrid(
          state = gridState,
          columns = GridCells.Adaptive(minSize = 150.dp),
          contentPadding = PaddingValues(bottom = 48.dp),
          horizontalArrangement = Arrangement.spacedBy(16.dp),
          verticalArrangement = Arrangement.spacedBy(16.dp),
          modifier = Modifier.fillMaxSize()
        ) {
          itemsIndexed(favorites, key = { _, item -> "fav_grid_${item.id}" }, contentType = { _, _ -> "media_poster" }) { index, item ->
            Box(
              modifier = Modifier
                .fillMaxWidth()
                .then(if (index == 0) Modifier.focusRequester(focusRequester) else Modifier),
              contentAlignment = Alignment.TopCenter
            ) {
              PosterMediaCard(
                media = item,
                onClick = { onMediaClick(item) }
              )
            }
          }
        }
      }
    }
  }
}

@Composable
fun MyContentScreen(
  allMedia: List<MediaItem>,
  onMediaClick: (MediaItem) -> Unit,
  modifier: Modifier = Modifier,
  viewModel: StreamNovaViewModel? = null
) {
  FavoritesScreen(
    allMedia = allMedia,
    onMediaClick = onMediaClick,
    modifier = modifier,
    viewModel = viewModel
  )
}

data class GlobalSearchCategory(
  val id: String,
  @StringRes val labelRes: Int,
  val gradientStart: Color,
  val gradientEnd: Color,
  val accentColor: Color,
  val keywords: List<String>,
  val movieGenreId: Int? = null,
  val tvGenreId: Int? = null
)

val GLOBAL_SEARCH_CATEGORIES = listOf(
  GlobalSearchCategory("action_adventure", R.string.search_cat_action_adventure, Color(0xFFEF4444), Color(0xFF991B1B), Color(0xFFFCA5A5), listOf("Action & Adventure", "Azione & Avventura", "Action", "Azione", "Avventura"), movieGenreId = 28, tvGenreId = 10759),
  GlobalSearchCategory("animazione", R.string.filter_animation, Color(0xFFF59E0B), Color(0xFFB45309), Color(0xFFFDE68A), listOf("Animazione", "Animation"), movieGenreId = 16, tvGenreId = 16),
  GlobalSearchCategory("avventura", R.string.search_cat_adventure, Color(0xFF10B981), Color(0xFF047857), Color(0xFFA7F3D0), listOf("Avventura", "Adventure"), movieGenreId = 12, tvGenreId = 10759),
  GlobalSearchCategory("azione", R.string.filter_action, Color(0xFFDC2626), Color(0xFF7F1D1D), Color(0xFFFECACA), listOf("Azione", "Action"), movieGenreId = 28, tvGenreId = 10759),
  GlobalSearchCategory("commedia", R.string.filter_comedy, Color(0xFFEAB308), Color(0xFFA16207), Color(0xFFFEF08A), listOf("Commedia", "Comedy"), movieGenreId = 35, tvGenreId = 35),
  GlobalSearchCategory("crime", R.string.search_cat_crime, Color(0xFF64748B), Color(0xFF334155), Color(0xFFCBD5E1), listOf("Crime", "Crimine", "Poliziesco"), movieGenreId = 80, tvGenreId = 80),
  GlobalSearchCategory("documentario", R.string.search_cat_documentary, Color(0xFF06B6D4), Color(0xFF0E7490), Color(0xFFA5F3FC), listOf("Documentario", "Documnetario", "Documentary"), movieGenreId = 99, tvGenreId = 99),
  GlobalSearchCategory("dramma", R.string.filter_drama, Color(0xFF8B5CF6), Color(0xFF5B21B6), Color(0xFFDDD6FE), listOf("Dramma", "Drama"), movieGenreId = 18, tvGenreId = 18),
  GlobalSearchCategory("famiglia", R.string.search_cat_family, Color(0xFFEC4899), Color(0xFF9D174D), Color(0xFFFBCFE8), listOf("Famiglia", "Family", "Kids"), movieGenreId = 10751, tvGenreId = 10762),
  GlobalSearchCategory("fantascienza", R.string.filter_sci_fi, Color(0xFF00E5FF), Color(0xFF007799), Color(0xFFE0F7FA), listOf("Fantascienza", "Sci-Fi", "Science Fiction"), movieGenreId = 878, tvGenreId = 10765),
  GlobalSearchCategory("fantasy", R.string.search_cat_fantasy, Color(0xFFA855F7), Color(0xFF6B21A8), Color(0xFFF3E8FF), listOf("Fantasy", "Fantastico"), movieGenreId = 14, tvGenreId = 10765),
  GlobalSearchCategory("guerra", R.string.search_cat_war, Color(0xFF78716C), Color(0xFF44403C), Color(0xFFE7E5E4), listOf("Guerra", "War", "Guerra & Politica"), movieGenreId = 10752, tvGenreId = 10768),
  GlobalSearchCategory("horror", R.string.search_cat_horror, Color(0xFFE11D48), Color(0xFF881337), Color(0xFFFECDD3), listOf("Horror"), movieGenreId = 27, tvGenreId = 27),
  GlobalSearchCategory("kids", R.string.search_cat_kids, Color(0xFFF43F5E), Color(0xFF9F1239), Color(0xFFFFE4E6), listOf("Kids", "Bambini", "Famiglia"), movieGenreId = 10751, tvGenreId = 10762),
  GlobalSearchCategory("mistero", R.string.search_cat_mystery, Color(0xFF6366F1), Color(0xFF3730A3), Color(0xFFE0E7FF), listOf("Mistero", "Mystery"), movieGenreId = 9648, tvGenreId = 9648),
  GlobalSearchCategory("musica", R.string.search_cat_music, Color(0xFF14B8A6), Color(0xFF0F766E), Color(0xFFCCFBF1), listOf("Musica", "Music", "Musicale"), movieGenreId = 10402, tvGenreId = null),
  GlobalSearchCategory("news", R.string.search_cat_news, Color(0xFF3B82F6), Color(0xFF1E40AF), Color(0xFFBFDBFE), listOf("News", "Notizie", "Attualità"), movieGenreId = null, tvGenreId = 10763),
  GlobalSearchCategory("reality", R.string.search_cat_reality, Color(0xFFFB923C), Color(0xFFC2410C), Color(0xFFFFEDD5), listOf("Reality", "Reality-TV"), movieGenreId = null, tvGenreId = 10764),
  GlobalSearchCategory("romance", R.string.search_cat_romance, Color(0xFFF472B6), Color(0xFFBE185D), Color(0xFFFCE7F3), listOf("Romance", "Romantico", "Sentimentale"), movieGenreId = 10749, tvGenreId = 18),
  GlobalSearchCategory("sci_fi_fantasy", R.string.search_cat_sci_fi_fantasy, Color(0xFF0284C7), Color(0xFF4338CA), Color(0xFFBAE6FD), listOf("Sci-Fi & Fantasy", "Fantascienza", "Fantasy"), movieGenreId = 878, tvGenreId = 10765),
  GlobalSearchCategory("storia", R.string.search_cat_history, Color(0xFFD97706), Color(0xFF78350F), Color(0xFFFDE68A), listOf("Storia", "History", "Storico", "Biografia"), movieGenreId = 36, tvGenreId = 18),
  GlobalSearchCategory("thriller", R.string.search_cat_thriller, Color(0xFFEA580C), Color(0xFF9A3412), Color(0xFFFFEDD5), listOf("Thriller", "Suspense"), movieGenreId = 53, tvGenreId = 9648),
  GlobalSearchCategory("western", R.string.search_cat_western, Color(0xFFB45309), Color(0xFF713F12), Color(0xFFFEF3C7), listOf("Western"), movieGenreId = 37, tvGenreId = 37),
  GlobalSearchCategory("televisione_film", R.string.search_cat_tv_movie, Color(0xFF38BDF8), Color(0xFF0369A1), Color(0xFFE0F2FE), listOf("Televisione Film", "Film TV", "TV Movie", "Cinema", "Film"), movieGenreId = 10770, tvGenreId = null)
)

/**
 * Barra di ricerca TV.
 *
 * STRUTTURA STABILE: il contenitore focusable ([TvFocusableBox]) resta in composizione
 * sia a riposo sia in modalità editing e non viene mai sostituito dal [BasicTextField].
 * Sostituirlo farebbe perdere a Compose il nodo focusable attivo, che al detach cancella
 * il focus di tutta la gerarchia (e con esso la possibilità di usare il D-pad).
 *
 * Comportamento D-pad: la barra è focusabile e mostra il testo, ma la tastiera (IME)
 * NON si apre automaticamente quando riceve il focus. La tastiera viene attivata solo
 * premendo ENTER/OK sulla barra, entrando in modalità editing.
 */
@Composable
private fun SearchInputBar(
  query: String,
  onQueryChange: (String) -> Unit,
  onSubmit: () -> Unit,
  onClear: () -> Unit,
  modifier: Modifier = Modifier
) {
  var isEditing by remember { mutableStateOf(false) }
  val fieldFocusRequester = remember { FocusRequester() }
  val boxFocusRequester = remember { FocusRequester() }
  val focusManager = LocalFocusManager.current
  // true SOLO dopo che il campo di testo ha realmente acquisito il focus.
  // Serve a ignorare l'evento onFocusChanged(false) iniziale che Compose emette appena
  // il nuovo FocusEventNode viene agganciato: senza questa guardia isEditing verrebbe
  // annullato nello stesso ciclo che ha aperto la modalità editing e la tastiera
  // non potrebbe mai aprirsi.
  var hasReceivedFocus by remember { mutableStateOf(false) }

  // Se l'utente preme Back durante l'editing, esce dalla modalità editing tornando allo
  // stato di riposo. Il focus viene riportato sul contenitore stabile PRIMA di smontare
  // il campo, così Compose non cancella il focus della gerarchia e il D-pad resta vivo.
  BackHandler(enabled = isEditing) {
    isEditing = false
    boxFocusRequester.requestFocus()
  }

  // All'attivazione esplicita (solo con ENTER/OK) porta il focus sull'input e apre la tastiera.
  LaunchedEffect(isEditing) {
    if (isEditing) {
      hasReceivedFocus = false
      withFrameNanos { }
      if (isEditing) {
        try {
          fieldFocusRequester.requestFocus()
        } catch (_: Exception) {
        }
      }
    }
  }

  // Contenitore focusabile STABILE: non cambia struttura quando si entra in editing.
  TvFocusableBox(
    modifier = modifier
      .fillMaxWidth()
      .focusRequester(boxFocusRequester),
    shape = RoundedCornerShape(50),
    focusedScale = 1f,
    // Nessun requestFocus qui: il passaggio a editing è solo uno stato di composizione,
    // il focus sull'input viene richiesto dal LaunchedEffect sopra, a transizione avvenuta.
    onClick = {
      hasReceivedFocus = false
      isEditing = true
    }
  ) { isFocused ->
    val isActive = isFocused || isEditing
    SearchBarRow(
      borderWidth = if (isActive) 2.dp else 1.dp,
      borderColor = if (isActive) NovaCyanBright else NovaCyan.copy(alpha = 0.4f),
      trailing = {
        if (query.isNotEmpty()) {
          Icon(
            imageVector = Icons.Default.Close,
            contentDescription = stringResource(R.string.action_clear),
            tint = NovaTextSecondary,
            modifier = Modifier
              .size(20.dp)
              .clickable { onClear() }
          )
        }
      }
    ) {
      if (isEditing) {
        BasicTextField(
          value = query,
          onValueChange = onQueryChange,
          singleLine = true,
          maxLines = 1,
          keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
          keyboardActions = KeyboardActions(
            onSearch = {
              isEditing = false
              boxFocusRequester.requestFocus()
              onSubmit()
            }
          ),
          textStyle = TextStyle(
            color = NovaTextPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium
          ),
          cursorBrush = SolidColor(NovaCyanBright),
          modifier = Modifier
            .weight(1f)
            .focusRequester(fieldFocusRequester)
            .onFocusChanged { state ->
              if (state.isFocused) {
                hasReceivedFocus = true
              } else if (hasReceivedFocus) {
                // Il focus è uscito DAVVERO dal campo: solo allora si chiude l'editing.
                // L'evento Inactive emesso alla prima agganciata del nodo viene ignorato.
                isEditing = false
              }
            }
            // SU/GIÙ escono dalla barra verso i contenuti, chiudendo l'editing.
            .onPreviewKeyEvent { event ->
              if (event.type == KeyEventType.KeyDown &&
                (event.key == Key.DirectionDown || event.key == Key.DirectionUp)
              ) {
                val direction = if (event.key == Key.DirectionDown) FocusDirection.Down else FocusDirection.Up
                isEditing = false
                // Se non c'è nulla in quella direzione, si torna sul contenitore stabile
                // invece di lasciare il focus un nodo che sta per essere rimosso.
                if (!focusManager.moveFocus(direction)) {
                  boxFocusRequester.requestFocus()
                }
                true
              } else {
                false
              }
            }
            // ENTER/OK invia la ricerca e chiude l'editing riportando il focus sulla barra.
            .onKeyEvent { event ->
              if (isEditing && (event.key == Key.Enter || event.key == Key.NumPadEnter || event.key == Key.DirectionCenter)) {
                if (event.type == KeyEventType.KeyUp) {
                  isEditing = false
                  boxFocusRequester.requestFocus()
                  onSubmit()
                }
                true
              } else {
                false
              }
            },
          decorationBox = { innerTextField ->
            if (query.isEmpty()) {
              Text(
                text = stringResource(R.string.search_input_placeholder),
                color = NovaTextMuted,
                fontSize = 14.sp
              )
            }
            innerTextField()
          }
        )
      } else {
        Text(
          text = query.ifBlank { stringResource(R.string.search_input_placeholder) },
          color = if (query.isBlank()) NovaTextMuted else NovaTextPrimary,
          fontSize = if (query.isBlank()) 14.sp else 16.sp,
          fontWeight = FontWeight.Medium,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f)
        )
      }
    }
  }
}

@Composable
private fun SearchBarRow(
  borderWidth: androidx.compose.ui.unit.Dp = 1.dp,
  borderColor: Color = NovaCyan.copy(alpha = 0.4f),
  trailing: @Composable () -> Unit = {},
  content: @Composable RowScope.() -> Unit
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .background(NovaSurfaceVariant, RoundedCornerShape(50))
      .border(borderWidth, borderColor, RoundedCornerShape(50))
      .padding(horizontal = 20.dp, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Icon(
      imageVector = Icons.Default.Search,
      contentDescription = stringResource(R.string.nav_search),
      tint = NovaCyanBright,
      modifier = Modifier.size(24.dp)
    )
    Spacer(modifier = Modifier.width(12.dp))
    content()
    trailing()
  }
}

/**
 * Diagnostica del percorso di focus della schermata Ricerca.
 *
 * Attiva solo nelle build DEBUG (`BuildConfig.DEBUG`): i log distinguono il focus sulla
 * barra, sui filtri, sull'area contenuti ([hasFocus] del contenitore stabile) e sulla card
 * dei risultati che riceve DAVVERO il focus. Filtrare con `adb logcat -s SEARCH_FOCUS`.
 */
internal object SearchFocusDiagnostics {
  const val TAG = "SEARCH_FOCUS"
}

@OptIn(ExperimentalLayoutApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun SearchScreen(  viewModel: StreamNovaViewModel,
  onMediaClick: (MediaItem) -> Unit,
  modifier: Modifier = Modifier,
  /**
   * Risultati espliciti, usati SOLO dai test di regressione del focus: permettono di
   * riprodurre in modo deterministico l'arrivo a ondate dei risultati (con la griglia che
   * mantiene in vista la card corrente) senza passare dalla rete. In produzione resta `null`
   * e la schermata osserva [StreamNovaViewModel.filteredSearchResults].
   */
  resultsOverride: List<MediaItem>? = null
) {
  val query by viewModel.searchQuery.collectAsState()
  val results = if (resultsOverride != null) {
    resultsOverride
  } else {
    viewModel.filteredSearchResults.collectAsState().value
  }
  val searchFilter by viewModel.searchFilter.collectAsState()
  val categoryItems by viewModel.categoryItems.collectAsState()
  val isCategoryLoading by viewModel.isCategoryLoading.collectAsState()
  val categoryState by viewModel.categoryState.collectAsState()

  val savedCategoryId by viewModel.selectedSearchCategoryId.collectAsState()
  var selectedCategory by remember {
    mutableStateOf(
      savedCategoryId?.let { id ->
        GLOBAL_SEARCH_CATEGORIES.find { it.id == id }
      }
    )
  }

  var isSearchSubmitted by remember { mutableStateOf(false) }

  // Focus restoration: riceve il segnale dal ViewModel
  val focusRestoreSignal = remember { mutableStateOf<String?>(null) }
  LaunchedEffect(viewModel) {
    viewModel.focusRestoreSignal?.collect { mediaId ->
      focusRestoreSignal.value = mediaId
    }
  }

  val searchBarFocusRequester = remember { FocusRequester() }
  val tuttiFilterFocusRequester = remember { FocusRequester() }
  val filmFilterFocusRequester = remember { FocusRequester() }
  val serieTvFilterFocusRequester = remember { FocusRequester() }
  val contentFirstItemFocusRequester = remember { FocusRequester() }
  // Il requester della cella 0 è usato SOLO dalla griglia dei badge categoria (stato
  // default), dove è agganciato al nodo focalizzabile del badge tramite tvFocusRequester.
  // Non va MAI agganciato alle celle delle griglie lazy dei risultati: una Box-cella non è
  // un target di focus e una cella lazy smontata lascia il requester orfano (richieste
  // rifiute in silenzio o IllegalStateException).
  val backButtonFocusRequester = remember { FocusRequester() }

  val categoryFocusRequesters = remember {
    List(GLOBAL_SEARCH_CATEGORIES.size) { index ->
      if (index == 0) contentFirstItemFocusRequester else FocusRequester()
    }
  }
  val categoryAttachments = remember {
    mutableMapOf<Int, TvFocusAttachment>()
  }

  val activeFilterRequester = when (searchFilter) {
    SearchTypeFilter.ALL -> tuttiFilterFocusRequester
    SearchTypeFilter.FILM -> filmFilterFocusRequester
    SearchTypeFilter.SERIE_TV -> serieTvFilterFocusRequester
  }

  // Consumer il segnale di focus restoration per ripristinare il focus sulla card corretta
  val gridState = rememberLazyGridState()

  // Requester PER IDENTITÀ di card dei risultati: il D-pad Giù dai filtri deve chiedere il
  // focus a una cella che è DAVVERO composta in questo momento (non alla cella 0, che può
  // essere smontata dallo scroll o dal riordino della lista). Stessa struttura usata dalla
  // griglia Provider/Categoria, dove il requester vive sul nodo focalizzabile della card.
  val resultFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }
  fun resultFocusRequester(mediaId: String): FocusRequester =
    resultFocusRequesters.getOrPut(mediaId) { FocusRequester() }

  // Lista della categoria selezionata già filtrata per tipo: una sola fonte di verità,
  // condivisa dal ramo categorie (che la renderizza) e dal Giù dai filtri (che ne legge la
  // prima card realmente composta).
  val displayedCategoryMedia = remember(categoryItems, searchFilter) {
    when (searchFilter) {
      SearchTypeFilter.ALL -> categoryItems
      SearchTypeFilter.FILM -> categoryItems.filter { it.type == MediaType.FILM }
      SearchTypeFilter.SERIE_TV -> categoryItems.filter { it.type == MediaType.SERIE_TV }
    }
  }
  // Stato di scroll della griglia categoria, definito FUORI dal ramo `when`: il Giù dai
  // filtri in modalità categoria legge qui il viewport reale della griglia.
  val categoryGridState = rememberLazyGridState()

  val searchFocusScope = rememberCoroutineScope()

  // Stessa struttura per la griglia della CATEGORIA selezionata: la chiave replica la key
  // della griglia (una film e una serie possono condividere lo stesso id grezzo).
  val categoryResultFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }

  fun categoryResultFocusRequester(cat: GlobalSearchCategory, media: MediaItem): FocusRequester =
    categoryResultFocusRequesters.getOrPut("cat_${cat.id}_${media.type}_${media.id}") {
      FocusRequester()
    }

  /**
   * Prima card delle categorie attualmente composta e visibile nella griglia (stesso
   * criterio di [firstVisibleResultRequester], applicato alla griglia categoria).
   */
  fun firstVisibleCategoryResultRequester(): Pair<MediaItem, FocusRequester>? {
    val cat = selectedCategory ?: return null
    if (displayedCategoryMedia.isEmpty()) return null
    val firstVisibleIndex = categoryGridState.layoutInfo.visibleItemsInfo.firstOrNull()?.index
    val item = firstVisibleIndex?.let { displayedCategoryMedia.getOrNull(it) }
      ?: displayedCategoryMedia.firstOrNull()
      ?: return null
    return item to categoryResultFocusRequester(cat, item)
  }

  /**
   * Prima card dei risultati attualmente composta e visibile nella griglia.
   *
   * `layoutInfo.visibleItemsInfo` è la fotografia reale del viewport lazy: il primo indice
   * elencato è la cella che l'utente vede in alto a sinistra (quindi la più naturale da
   * raggiungere con un Giù dai filtri). Se la griglia non è ancora stata misurata si usa la
   * prima voce della lista, con richiesta protetta finché il nodo non è agganciato.
   */
  fun firstVisibleResultRequester(): Pair<MediaItem, FocusRequester>? {
    if (results.isEmpty()) return null
    val firstVisibleIndex = gridState.layoutInfo.visibleItemsInfo.firstOrNull()?.index
    val item = firstVisibleIndex?.let { results.getOrNull(it) }
      ?: results.firstOrNull()
      ?: return null
    return item to resultFocusRequester(item.id)
  }

  val lastFocusedMediaId by viewModel.lastFocusedMediaId.collectAsState()
  val focusManager = LocalFocusManager.current
  // Prova REALE che il focus è entrato nell'area contenuti: `hasFocus` del contenitore
  // (nella Box qui sotto) diventa true solo quando una sua discendente — cioè una card
  // dei risultati — è effettivamente a fuoco. Serve alla gestione del Giù dai filtri per
  // NON credere ciecamente all'esito di requestFocus(): un nodo agganciato ma non
  // focalizzabile (gate di ripristino chiuso) rifiuta la richiesta in silenzio.
  var contentAreaHasFocus by remember { mutableStateOf(false) }

  // Un target di ripristino che NON appartiene alla lista corrente è STANTIO e va
  // rilasciato. Le card della schermata possono prendere il focus solo se non c'è un
  // target pendente di altro id (gate `canFocus` di PosterMediaCard): un id orfano —
  // tipicamente l'ancora sintetica "home_content_anchor" scritta dalla MainActivity al
  // rientro dal Dettaglio, o il target di una sezione precedente — renderebbe PERMANENTE
  // l'annullamento del focus di TUTTE le card, lasciando morto il Giù dai filtri. Il
  // rilascio avviene solo con la lista popolata: durante il caricamento non si decide.
  LaunchedEffect(lastFocusedMediaId, results, categoryItems, selectedCategory, searchFilter) {
    val pending = lastFocusedMediaId ?: return@LaunchedEffect
    val visibleIds: Set<String> = if (selectedCategory != null) {
      when (searchFilter) {
        SearchTypeFilter.ALL -> categoryItems
        SearchTypeFilter.FILM -> categoryItems.filter { it.type == MediaType.FILM }
        SearchTypeFilter.SERIE_TV -> categoryItems.filter { it.type == MediaType.SERIE_TV }
      }.map { it.id }.toSet()
    } else {
      results.map { it.id }.toSet()
    }
    if (visibleIds.isNotEmpty() && pending !in visibleIds) {
      if (BuildConfig.DEBUG) {
        Log.d(
          SearchFocusDiagnostics.TAG,
          "stale restore target '$pending' absent from current list: cleared"
        )
      }
      viewModel.clearLastFocusedMedia()
    }
  }

  LaunchedEffect(focusRestoreSignal.value, gridState) {
    val mediaId = focusRestoreSignal.value ?: return@LaunchedEffect
    focusRestoreSignal.value = null

    // Cerca il mediaId tra i risultati visibili (query results o category results)
    val index = results.indexOfFirst { it.id == mediaId }
    if (index >= 0) {
      gridState.scrollToItem(index)
      kotlinx.coroutines.delay(100)
      try {
        contentFirstItemFocusRequester.requestFocus()
      } catch (_: Exception) {}
    }
  }

  LaunchedEffect(savedCategoryId) {
    if (savedCategoryId != null) {
      if (selectedCategory == null || selectedCategory?.id != savedCategoryId) {
        selectedCategory = GLOBAL_SEARCH_CATEGORIES.find { it.id == savedCategoryId }
      }
      try {
        contentFirstItemFocusRequester.requestFocus()
      } catch (_: Exception) {
        try {
          activeFilterRequester.requestFocus()
        } catch (_: Exception) {}
      }
    }
  }

  LaunchedEffect(selectedCategory) {
    if (selectedCategory != null) {
      try {
        contentFirstItemFocusRequester.requestFocus()
      } catch (_: Exception) {
        try {
          activeFilterRequester.requestFocus()
        } catch (_: Exception) {}
      }
    }
  }

  // All'ingresso nella schermata Cerca porta il focus sulla barra di ricerca (a riposo, senza tastiera)
  LaunchedEffect(Unit) {
    if (savedCategoryId != null || selectedCategory != null) return@LaunchedEffect
    withFrameNanos { }
    try {
      searchBarFocusRequester.requestFocus()
    } catch (_: Exception) {
      try {
        tuttiFilterFocusRequester.requestFocus()
      } catch (_: Exception) {}
    }
  }

   // Gestione pulsante Indietro del telecomando
  if (selectedCategory != null) {
    BackHandler {
      viewModel.setSelectedSearchCategoryId(null)
      selectedCategory = null
    }
  } else if (isSearchSubmitted || query.isNotBlank()) {
    BackHandler {
      isSearchSubmitted = false
      viewModel.updateSearchQuery("")
    }
  }

  Column(
    modifier = modifier
      .fillMaxSize()
      .background(NovaBackground)
      .padding(start = 32.dp, top = 24.dp, end = 32.dp)
  ) {
    // Header con Titolo e Pulsante Indietro
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Column {
        Text(
          text = stringResource(R.string.search_screen_title),
          color = NovaTextPrimary,
          fontSize = 26.sp,
          fontWeight = FontWeight.Black,
          letterSpacing = 0.5.sp
        )
        Text(
          text = stringResource(R.string.search_screen_subtitle),
          color = NovaTextSecondary,
          fontSize = 13.sp,
          modifier = Modifier.padding(top = 2.dp)
        )
      }

      if (selectedCategory != null || query.isNotBlank()) {
        TvActionButton(
          text = stringResource(R.string.search_back_to_categories),
          icon = Icons.AutoMirrored.Filled.ArrowBack,
          isPrimary = false,
          onClick = {
            viewModel.setSelectedSearchCategoryId(null)
            selectedCategory = null
            isSearchSubmitted = false
            viewModel.updateSearchQuery("")
          },
          modifier = Modifier
            .focusRequester(backButtonFocusRequester)
            .focusProperties {
              down = searchBarFocusRequester
              up = FocusRequester.Cancel
              left = FocusRequester.Cancel
            }
        )
      }
    }

    Spacer(modifier = Modifier.height(16.dp))

    // Search Input Bar (Pill Shape): la tastiera si apre solo con ENTER/OK sulla barra.
    SearchInputBar(
      query = query,
      onQueryChange = {
        viewModel.updateSearchQuery(it)
        if (it.isBlank()) {
          isSearchSubmitted = false
        }
      },
      onSubmit = {
        if (query.isNotBlank()) {
          viewModel.setSelectedSearchCategoryId(null)
          selectedCategory = null
          isSearchSubmitted = true
        }
      },
      onClear = {
        viewModel.updateSearchQuery("")
        isSearchSubmitted = false
      },
      modifier = Modifier
        .focusRequester(searchBarFocusRequester)
        .onFocusChanged { state ->
          if (BuildConfig.DEBUG && state.isFocused) {
            Log.d(SearchFocusDiagnostics.TAG, "focus on search bar")
          }
        }
        .focusProperties {
          up = if (selectedCategory != null || query.isNotBlank()) backButtonFocusRequester else FocusRequester.Cancel
          down = activeFilterRequester
          left = FocusRequester.Cancel
        }
    )

    Spacer(modifier = Modifier.height(12.dp))

    // Selettore Filtro Ricerca D-Pad: ["Tutti", "Film", "Serie TV"]
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(bottom = 12.dp)
        // Il Giù dai filtri viene gestito QUI, in modo esplicito: si chiede il focus alla
        // prima card realmente composta della griglia DI CONTESTO (categoria selezionata od
        // esiti della query). Affidarsi alla sola focus search (requester o geometrica) non
        // è sufficiente su dispositivo, dove la griglia lazy può aver smontato la cella 0 o
        // aver cambiato posizione dopo il riordino dei risultati. Se non c'è nessuna card
        // disponibile il tasto passa oltre (nessuna eccezione) e il rientro funziona appena
        // la griglia viene composta.
        .onPreviewKeyEvent { event ->
          if (event.type != KeyEventType.KeyDown || event.key != Key.DirectionDown) {
            return@onPreviewKeyEvent false
          }
          // Il target dipende dal CONTESTO della schermata: griglia categoria quando una
          // categoria è selezionata, griglia dei risultati della query altrimenti. Con
          // query vuota e nessuna categoria vale lo stato default (badge), che NON viene
          // intercettato: la ricerca geometrica nativa raggiunge la prima riga di badge.
          val target: Pair<MediaItem, FocusRequester> = when {
            selectedCategory != null ->
              if (displayedCategoryMedia.isEmpty()) {
                null
              } else {
                firstVisibleCategoryResultRequester()
              }
            query.isBlank() -> null
            results.isEmpty() -> null
            else -> firstVisibleResultRequester()
          } ?: return@onPreviewKeyEvent false
          if (BuildConfig.DEBUG) {
            Log.d(
              SearchFocusDiagnostics.TAG,
              "DOWN from filters: key consumed, category=${selectedCategory?.id ?: "-"} " +
                "targetId=${target.first.id} title='${target.first.title}'"
            )
          }
          searchFocusScope.launch {
            // Tentativi a FRAME (nessun delay): copre il primo frame in cui la griglia non
            // ha ancora agganciato il nodo della card. `requestFocusWhenAttached` dichiara
            // successo anche quando il nodo è agganciato ma NON focalizzabile (gate di
            // ripristino chiuso): la richiesta viene rifiutata in silenzio senza eccezioni.
            val sent = requestFocusWhenAttached(target.second, maxAttempts = 5)
            if (BuildConfig.DEBUG) {
              Log.d(
                SearchFocusDiagnostics.TAG,
                "DOWN request for ${target.first.id}: sent=$sent contentFocused=$contentAreaHasFocus"
              )
            }
            if (!contentAreaHasFocus) {
              // Il tasto è GIÀ stato consumato: non si può lasciare il focus sui filtri. Se
              // la richiesta non ha ATTRAVERSATO un trasferimento reale (nodo non agganciato
              // o rifiutato in silenzio da un gate di focus chiuso), si ripiega sulla
              // ricerca geometrica del focus system dal filtro corrente: la prima card
              // focalizzabile sotto la riga dei filtri riceve il focus. Se anche questa
              // non trova nulla il focus resta dov'è (stato senza risultati).
              val recovered = focusManager.moveFocus(FocusDirection.Down)
              Log.d(
                SearchFocusDiagnostics.TAG,
                "DOWN fallback moveFocus(Down) accepted=$recovered after failed direct request"
              )
            }
          }
          true
        },
      horizontalArrangement = Arrangement.spacedBy(10.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      SearchTypeFilter.values().forEach { filter ->
        val isSelected = searchFilter == filter
        val requester = when (filter) {
          SearchTypeFilter.ALL -> tuttiFilterFocusRequester
          SearchTypeFilter.FILM -> filmFilterFocusRequester
          SearchTypeFilter.SERIE_TV -> serieTvFilterFocusRequester
        }
        TvFocusableBox(
          modifier = Modifier
            .focusRequester(requester)
            .onFocusChanged { state ->
              if (BuildConfig.DEBUG && state.isFocused) {
                Log.d(SearchFocusDiagnostics.TAG, "focus on filter ${filter.name}")
              }
            }
            .focusProperties {
              up = searchBarFocusRequester
              // Il Giù NON ha un target esplicito: in presenza di risultati (query o
              // categoria) è intercettato dall'onPreviewKeyEvent della riga (sopra), che
              // consegna il focus alla prima card realmente composta; nello stato default
              // vale la ricerca geometrica nativa, che raggiunge la prima riga di badge.
              // Un requester fisso qui (`down = ...`) è la causa storica del Giù morto: la
              // cella lazy target esce dalla composizione allo scroll e la richiesta resta
              // a vuoto con il focus inchiodato sul filtro.
              when (filter) {
                SearchTypeFilter.ALL -> {
                  left = FocusRequester.Cancel
                  right = filmFilterFocusRequester
                }
                SearchTypeFilter.FILM -> {
                  left = tuttiFilterFocusRequester
                  right = serieTvFilterFocusRequester
                }
                SearchTypeFilter.SERIE_TV -> {
                  left = filmFilterFocusRequester
                  right = FocusRequester.Cancel
                }
              }
            },
          shape = RoundedCornerShape(50),
          focusedScale = 1.05f,
          onClick = { viewModel.setSearchFilter(filter) }
        ) { isFocused ->
          Row(
            modifier = Modifier
              .background(
                if (isSelected) NovaCyan else if (isFocused) NovaSurfaceVariant else Color(0x331E293B),
                RoundedCornerShape(50)
              )
              .border(
                width = if (isFocused) 1.5.dp else 1.dp,
                color = if (isFocused) NovaCyanBright else if (isSelected) NovaCyan else Color(0x33475569),
                shape = RoundedCornerShape(50)
              )
              .padding(horizontal = 18.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            Text(
              text = when (filter) {
                SearchTypeFilter.ALL -> stringResource(R.string.filter_all)
                SearchTypeFilter.FILM -> stringResource(R.string.filter_movies)
                SearchTypeFilter.SERIE_TV -> stringResource(R.string.filter_tv_series)
              },
              color = if (isSelected) Color.Black else if (isFocused) NovaCyanBright else NovaTextPrimary,
              fontSize = 13.sp,
              fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
            )
          }
        }
      }
    }

    // Vista Condizionale: Categoria Selezionata / Risultati di Ricerca Testo / Default con soli Badge Colorati
    // La superficie dei contenuti è SEMPRE composta insieme ai filtri e osserva il passaggio
    // reale del focus (`hasFocus`), che serve a verificare l'esito del Giù dai filtri. NON è
    // un target di focus: agganciarci un FocusRequester su una Box senza `focusable` genera
    // richieste rifiute (IllegalStateException) invisibili al chiamante.
    Box(
      modifier = Modifier
        .fillMaxSize()
        .onFocusChanged { state ->
          // Il contenitore non è focalizzabile: `hasFocus` diventa true quando il focus è su
          // un SUO discendente (una card dei risultati). È la prova che il focus è entrato
          // davvero nell'area contenuti, non solo che il requester è stato collegato. Lo
          // stato alimenta la verifica del Giù dai filtri (v. `contentAreaHasFocus`).
          contentAreaHasFocus = state.hasFocus
          if (BuildConfig.DEBUG) {
            Log.d(
              SearchFocusDiagnostics.TAG,
              "content surface hasFocus=${state.hasFocus} isFocused=${state.isFocused}"
            )
          }
        }
    ) {
      when {
        selectedCategory != null -> {
          val cat = selectedCategory!!
          // Solo i contenuti caricati per QUESTA categoria: nessun seed da allMedia,
          // così una categoria nuova non mostra mai i poster di quella precedente. La
          // lista (`displayedCategoryMedia`) e lo scroll (`categoryGridState`) vivono
          // FUORI dal `when`, condivisi con il Giù dai filtri.
          //
          // La superficie è un COLUMN: badge, messaggio di stato e griglia sono impilati
          // VERTICALMENTE nella stessa cella del Box. In precedenza erano emessi come
          // fratelli DIRETTI del Box (= Stack) e le card disegnavano sopra il badge della
          // categoria (il giallo "COMMEDIA").
          Column(modifier = Modifier.fillMaxSize()) {
            // Paginazione infinita allo scorrimento della griglia
            LaunchedEffect(categoryGridState) {
              snapshotFlow {
                val layout = categoryGridState.layoutInfo
                val total = layout.totalItemsCount
                val last = layout.visibleItemsInfo.lastOrNull()?.index ?: 0
                total to last
              }.collect { (total, last) ->
                if (total > 0 && last >= total - 6) {
                  viewModel.loadNextCategoryPage()
                }
              }
            }

            Row(
              // Nessun margine inferiore: lo spazio verticale guadagnato va all'area
              // scrollabile dei poster. Su un TV 1080p a densita' 2 il viewport e' appena
              // piu' alto di una riga di card: con i 14dp di margine la card focalizzata
              // (ingrandita del 6%) superava il bordo inferiore e l'anno veniva tagliato.
              modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 0.dp),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
              ) {
                Box(
                  modifier = Modifier
                    .background(
                      brush = Brush.horizontalGradient(listOf(cat.gradientStart, cat.gradientEnd)),
                      shape = RoundedCornerShape(50)
                    )
                    .border(1.5.dp, cat.accentColor, RoundedCornerShape(50))
                    .padding(horizontal = 18.dp, vertical = 7.dp)
                ) {
                  Text(
                    text = stringResource(cat.labelRes).uppercase(),
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.5.sp
                  )
                }
                Text(
                  // Sottotitolo secondo la sorgente REALE dei poster: l'etichetta
                  // TMDB Discover appare solo quando il fallback TMDB è attivo.
                  text = when (categoryState.source) {
                    SearchCategorySource.ADDON -> stringResource(
                      R.string.search_category_desc_addon,
                      categoryState.addonLabel.orEmpty()
                    )
                    SearchCategorySource.TMDB_FALLBACK -> stringResource(R.string.search_category_desc_fallback)
                    null -> ""
                  },
                  color = if (categoryState.source == SearchCategorySource.TMDB_FALLBACK) {
                    NovaOrange
                  } else {
                    NovaTextSecondary
                  },
                  fontSize = 13.sp
                )
              }

              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
              ) {
                if (isCategoryLoading) {
                  CircularProgressIndicator(
                    color = NovaCyanBright,
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp
                  )
                }
                Box(
                  modifier = Modifier
                    .background(Color(0x33000000), RoundedCornerShape(50))
                    .border(1.dp, cat.accentColor.copy(alpha = 0.5f), RoundedCornerShape(50))
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                  Text(
                    text = stringResource(R.string.search_titles_available, displayedCategoryMedia.size),
                    color = cat.accentColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                  )
                }
              }
            }

            // Avviso esplicito: errore del catalogo addon oppure motivo del fallback
            // TMDB Discover: non viene mai mascherato come caricamento riuscito.
            categoryState.statusMessage?.let { message ->
              Text(
                text = message,
                color = if (categoryState.source == SearchCategorySource.ADDON) NovaOrange else NovaTextMuted,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
              )
            }

            // Griglia (o messaggio "nessun contenuto") su weight(1f): occupa ESATTAMENTE
            // lo spazio che resta dopo badge e messaggio, qualunque sia il numero dei
            // badge e degli item.
            if (displayedCategoryMedia.isEmpty() && !isCategoryLoading) {
              Box(
                modifier = Modifier
                  .fillMaxWidth()
                  .weight(1f)
                  .padding(32.dp),
                contentAlignment = Alignment.Center
              ) {
                Text(
                  text = stringResource(R.string.search_no_content_for_category, stringResource(cat.labelRes)),
                  color = NovaTextMuted,
                  fontSize = 16.sp
                )
              }
            } else {
              Box(
                modifier = Modifier
                  .fillMaxWidth()
                  .weight(1f)
              ) {
                TvFocusGridBringIntoView {
                  LazyVerticalGrid(
                    state = categoryGridState,
                    columns = GridCells.Adaptive(minSize = 150.dp),
                    contentPadding = PaddingValues(bottom = 48.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize()
                  ) {
                    // La key include il tipo: film e serie possono condividere lo stesso
                    // id grezzo senza collidere (una key duplicata crasherebbe la griglia).
                    itemsIndexed(displayedCategoryMedia, key = { _, item -> "cat_${cat.id}_${item.type}_${item.id}" }, contentType = { _, _ -> "media_poster" }) { index, item ->
                      Box(
                        modifier = Modifier
                          .fillMaxWidth()
                          .focusProperties {
                            if (index < 5) {
                              up = activeFilterRequester
                            }
                          },
                        contentAlignment = Alignment.TopCenter
                      ) {
                        PosterMediaCard(
                          media = item,
                          onClick = { onMediaClick(item) },
                          // Requester per identità: è il nodo focalizzabile REALE della
                          // card, target del Giù dai filtri in modalità categoria. Le
                          // celle fuori viewport non sono composte: nessun requester
                          // fisso sulla cella 0.
                          focusRequester = categoryResultFocusRequester(cat, item),
                          modifier = Modifier.onFocusChanged { state ->
                            if (BuildConfig.DEBUG && state.isFocused) {
                              Log.d(
                                SearchFocusDiagnostics.TAG,
                                "CATEGORY CARD FOCUSED id=${item.id} title='${item.title}' index=$index"
                              )
                            }
                          }
                        )
                      }
                    }
                  }
                }
              }
            }
          }
        }
  
        query.isNotBlank() -> {
          // Anche qui la superficie è un COLUMN: l'header "N risultati per ..." e la
          // griglia (o il messaggio nessun risultato) sono impilati VERTICALMENTE. In
          // precedenza erano fratelli DIRETTI del Box (= Stack) e le card coprivano
          // l'header e l'area dei filtri.
          Column(modifier = Modifier.fillMaxSize()) {
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 14.dp),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Text(
                text = stringResource(R.string.search_results_found, query, results.size),
                color = NovaCyanBright,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
              )
            }

            if (results.isEmpty()) {
              Box(
                modifier = Modifier
                  .fillMaxWidth()
                  .weight(1f)
                  .padding(32.dp),
                contentAlignment = Alignment.Center
              ) {
                Text(
                  text = stringResource(R.string.search_no_results, query),
                  color = NovaTextMuted,
                  fontSize = 16.sp
                )
              }
            } else {
              Box(
                modifier = Modifier
                  .fillMaxWidth()
                  .weight(1f)
              ) {
                TvFocusGridBringIntoView {
                  LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(minSize = 150.dp),
                    contentPadding = PaddingValues(bottom = 48.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize()
                  ) {
                    itemsIndexed(results, key = { _, item -> "search_${item.id}" }, contentType = { _, _ -> "media_poster" }) { index, item ->
                      Box(
                        modifier = Modifier
                          .fillMaxWidth()
                          .focusProperties {
                            if (index < 5) {
                              up = activeFilterRequester
                            }
                          },
                        contentAlignment = Alignment.TopCenter
                      ) {
                        PosterMediaCard(
                          media = item,
                          onClick = { onMediaClick(item) },
                          // Requester per identità: è il nodo focalizzabile REALE della
                          // card (non un contenitore esterno), quindi il Giù dai filtri
                          // consegna il focus a una cella composta qualunque sia lo
                          // scroll. La Box-cella NON è un target di focus: agganciarci il
                          // requester produce richieste rifiute invisibili.
                          focusRequester = resultFocusRequester(item.id),
                          modifier = Modifier.onFocusChanged { state ->
                            if (BuildConfig.DEBUG && state.isFocused) {
                              Log.d(
                                SearchFocusDiagnostics.TAG,
                                "RESULT CARD FOCUSED id=${item.id} title='${item.title}' index=$index"
                              )
                            }
                          }
                        )
                      }
                    }
                  }
                }
              }
            }
          }
        }
  
        else -> {
          // STATO DEFAULT: Nessun poster! Mostra solo i badge colorati per la ricerca globale
          Column(modifier = Modifier.fillMaxSize()) {
            Text(
              text = stringResource(R.string.search_global_title),
              color = NovaCyanBright,
              fontSize = 13.sp,
              fontWeight = FontWeight.Black,
              letterSpacing = 1.sp
            )
            Text(
              text = stringResource(R.string.search_global_subtitle),
              color = NovaTextSecondary,
              fontSize = 13.sp,
              modifier = Modifier.padding(top = 2.dp, bottom = 14.dp)
            )
  
            TvFocusBringIntoView {
            LazyVerticalGrid(
              columns = GridCells.Adaptive(minSize = 170.dp),
              contentPadding = PaddingValues(bottom = 48.dp),
              horizontalArrangement = Arrangement.spacedBy(12.dp),
              verticalArrangement = Arrangement.spacedBy(12.dp),
              modifier = Modifier.fillMaxSize()
            ) {
              itemsIndexed(GLOBAL_SEARCH_CATEGORIES, key = { _, it -> it.id }, contentType = { _, _ -> "search_category" }) { index, cat ->
                val requester = if (index < categoryFocusRequesters.size) categoryFocusRequesters[index] else null
                // Le categorie sono in una LazyVerticalGrid: i requester delle celle fuori
                // viewport non sono montati e non vanno mai assegnati alle focusProperties.
                val attachment = categoryAttachments.getOrPut(index) { TvFocusAttachment() }
                TvFocusableBox(
                  modifier = Modifier
                    .then(
                      if (requester != null) {
                        Modifier.tvFocusRequester(requester, attachment)
                      } else {
                        Modifier
                      }
                    )
                    .focusProperties {
                      if (index < 4) {
                        up = activeFilterRequester
                      } else {
                        up = categoryAttachments[index - 4].guard(categoryFocusRequesters[index - 4])
                          ?: FocusRequester.Default
                      }
                      if (index + 4 < categoryFocusRequesters.size) {
                        down = categoryAttachments[index + 4].guard(categoryFocusRequesters[index + 4])
                          ?: FocusRequester.Default
                      }
                    },
                  shape = RoundedCornerShape(50),
                  focusedScale = 1.05f,
                  onClick = {
                    try {
                      activeFilterRequester.requestFocus()
                    } catch (_: Exception) {}
                    viewModel.setSelectedSearchCategoryId(cat.id)
                    // La categoria viene caricata SOLO dai cataloghi degli addon
                    // (Xperience/Stremio) dichiarati nei manifest; nessun seed da
                    // allMedia/TMDB Discover. Keywords = etichette della categoria.
                    viewModel.initCategory(
                      categoryId = cat.id,
                      keywords = cat.keywords,
                      movieGenreId = cat.movieGenreId,
                      tvGenreId = cat.tvGenreId
                    )
                    selectedCategory = cat
                    isSearchSubmitted = false
                  }
                ) { isFocused ->
                  Box(
                    modifier = Modifier
                      .fillMaxWidth()
                      .height(48.dp)
                      .background(
                        brush = Brush.horizontalGradient(
                          colors = listOf(
                            cat.gradientStart.copy(alpha = if (isFocused) 0.95f else 0.70f),
                            cat.gradientEnd.copy(alpha = if (isFocused) 0.95f else 0.70f)
                          )
                        ),
                        shape = RoundedCornerShape(50)
                      )
                      .border(
                        width = if (isFocused) 2.dp else 1.dp,
                        color = if (isFocused) Color.White else cat.accentColor.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(50)
                      )
                      .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center
                  ) {
                    Text(
                      text = stringResource(cat.labelRes).uppercase(),
                      color = Color.White,
                      fontSize = 12.sp,
                      fontWeight = FontWeight.Bold,
                      letterSpacing = 0.5.sp,
                      maxLines = 1
                    )
                  }
                }
              }
            }
            }
          }
        }
      }
    }
  }
}

@Composable
fun SettingsScreen(
  viewModel: StreamNovaViewModel? = null,
  modifier: Modifier = Modifier
) {
  val context = LocalContext.current
  val appVersion = APP_VERSION_DISPLAY
  val playbackSettings = viewModel?.playbackSettings?.collectAsState()?.value ?: PlaybackSettings()
  // ── Debrid / TorBox ─────────────────────────────────────────────────
  val torBoxApiKey = viewModel?.torBoxApiKey?.collectAsState()?.value
  val torBoxEnabled = viewModel?.torBoxInstantDebridEnabled?.collectAsState()?.value ?: true
  val torBoxState = viewModel?.torBoxAccountState?.collectAsState()?.value ?: TorBoxAccountState.Unknown
  val streamingEngineMode = AppSettingsRepository.streamingEngineMode.collectAsState().value ?: StreamingEngineMode.HTTP_WEB
  val autoplayEnabled = viewModel?.autoplayEnabled?.collectAsState()?.value ?: false
  val appLanguage = viewModel?.appLanguage?.collectAsState()?.value ?: "it"
  val parentalControlEnabled = viewModel?.parentalControlEnabled?.collectAsState()?.value ?: false
  val parentalControlLevel = viewModel?.parentalControlLevel?.collectAsState()?.value ?: "18"
  val updateCheckState = viewModel?.updateCheckState?.collectAsState()?.value ?: UpdateCheckState.Idle
  val updateDownloadState = viewModel?.updateDownloadState?.collectAsState()?.value ?: UpdateDownloadState.Idle
  var showTorBoxKeyDialog by remember { mutableStateOf(false) }
  // Ripristino del focus sulla riga che ha aperto il popup chiave API: al BACK (o
  // Salva/Annulla) il focus torna qui invece di perdersi sulla schermata.
  val torBoxKeyRowFocusRequester = remember { FocusRequester() }
  var restoreTorBoxKeyRowFocus by remember { mutableStateOf(false) }
  var showResolutionDialog by remember { mutableStateOf(false) }
  var showSubtitleLanguageDialog by remember { mutableStateOf(false) }
  var showSubtitleSizeDialog by remember { mutableStateOf(false) }
  var showSubtitleBackgroundDialog by remember { mutableStateOf(false) }
  var showSubtitlePositionDialog by remember { mutableStateOf(false) }
  var showAudioLanguageDialog by remember { mutableStateOf(false) }
  var showAppLanguageDialog by remember { mutableStateOf(false) }
  var showStreamingEngineModeDialog by remember { mutableStateOf(false) }
  var showParentalPinDialog by remember { mutableStateOf(false) }
  var parentalPinStep by remember { mutableStateOf<ParentalPinStep>(ParentalPinStep.None) }
  var parentalPinErrorMessage by remember { mutableStateOf<String?>(null) }
  var showParentalManageDialog by remember { mutableStateOf(false) }
  var showParentalLevelDialog by remember { mutableStateOf(false) }
  var showTargetBufferDialog by remember { mutableStateOf(false) }
  var showInitialBufferDialog by remember { mutableStateOf(false) }
  var showBackBufferDialog by remember { mutableStateOf(false) }
  var showReuseCacheDurationDialog by remember { mutableStateOf(false) }
  var showDecoderFallbackDialog by remember { mutableStateOf(false) }
  var isClearingCache by remember { mutableStateOf(false) }
  var showUpdateResultDialog by remember { mutableStateOf(false) }
  val simklAccount by SimklRepository.accountState.collectAsState()
  val simklScope = rememberCoroutineScope()
  var simklPin by remember { mutableStateOf<SimklPinState?>(null) }
  var simklStatus by remember { mutableStateOf<String?>(null) }
  var simklLoading by remember { mutableStateOf(false) }
  var showSimklClientIdDialog by remember { mutableStateOf(false) }
  var simklClientIdDraft by remember { mutableStateOf("") }

  // Verifica automatica (una sola volta) della chiave salvata: all'ingresso in
  // Impostazioni l'indicatore diventa verde "Collegato" se la chiave è valida.
  LaunchedEffect(torBoxApiKey) {
    if (!torBoxApiKey.isNullOrBlank() && torBoxState is TorBoxAccountState.Unknown) {
      viewModel?.verifyTorBoxAccount()
    }
  }

  // Mostra il feedback del controllo aggiornamenti non appena il check termina.
  LaunchedEffect(updateCheckState) {
    showUpdateResultDialog = when (updateCheckState) {
      is UpdateCheckState.UpToDate,
      is UpdateCheckState.UpdateAvailable,
      is UpdateCheckState.VersionMismatch,
      is UpdateCheckState.Error -> true
      else -> false
    }
  }

  // Quando il popup della chiave API si chiude (BACK, Salva o Annulla) il focus torna
  // sulla riga di Impostazioni che lo ha aperto, con una riga di frame di margine per
  // lasciare ricomporre lo sfondo.
  LaunchedEffect(restoreTorBoxKeyRowFocus) {
    if (!restoreTorBoxKeyRowFocus) return@LaunchedEffect
    restoreTorBoxKeyRowFocus = false
    withFrameNanos { }
    try {
      torBoxKeyRowFocusRequester.requestFocus()
    } catch (_: Exception) {
    }
  }

  TvFocusBringIntoView {
    LazyColumn(
      modifier = modifier
        .fillMaxSize()
        .background(NovaBackground)
        .padding(start = 32.dp, top = 24.dp, end = 32.dp),
      contentPadding = PaddingValues(bottom = 48.dp),
      verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
      item {
        Text(
          text = stringResource(R.string.settings_title),
          color = NovaTextPrimary,
          fontSize = 24.sp,
          fontWeight = FontWeight.Bold
        )
        Text(
          text = stringResource(R.string.settings_subtitle),
          color = NovaTextSecondary,
          fontSize = 13.sp,
          modifier = Modifier.padding(top = 4.dp)
        )
      }

      // ── ACCOUNT SIMKL ────────────────────────────────────────────────────
      item {
        val simklClientId by AppSettingsRepository.simklClientId.collectAsState()
        SettingsSection(title = "Account SIMKL") {
          SettingsRow(
            icon = Icons.Default.VpnKey,
            title = "Client ID SIMKL",
            value = simklClientId?.let { "••••${it.takeLast(4)}" } ?: "Non configurato",
            valueColor = if (simklClientId.isNullOrBlank()) NovaOrange else NovaGreen,
            onClick = {
              simklClientIdDraft = simklClientId.orEmpty()
              showSimklClientIdDialog = true
            }
          )
          if (simklClientId.isNullOrBlank()) {
            Text(
              "Crea un client PIN per TV su simkl.com/settings/developer e inserisci il Client ID qui.",
              color = NovaTextSecondary,
              fontSize = 12.sp,
              modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            )
          }
          SettingsRow(
            icon = Icons.Default.CloudDone,
            title = if (simklAccount.isLoggedIn) "Account collegato" else "Collega SIMKL",
            value = when {
              !SimklRepository.hasClientId() -> "Configura SIMKL_CLIENT_ID"
              simklLoading -> "In attesa di autorizzazione…"
              simklAccount.isLoggedIn -> simklAccount.username ?: "Collegato"
              simklPin != null -> "Codice ${simklPin!!.userCode}"
              else -> "Non collegato"
            },
            valueColor = if (simklAccount.isLoggedIn) NovaGreen else NovaCyanBright,
            onClick = {
              if (simklAccount.isLoggedIn) {
                simklScope.launch {
                  SimklRepository.signOut()
                  simklStatus = "Account SIMKL scollegato."
                }
              } else if (!simklLoading && SimklRepository.hasClientId()) {
                simklLoading = true
                simklStatus = null
                simklScope.launch {
                  runCatching { SimklRepository.requestDeviceCode() }
                    .onSuccess { pin ->
                      simklPin = pin
                      runCatching { SimklRepository.pollDeviceCode(pin) }
                        .onSuccess {
                          simklPin = null
                          simklStatus = "Account collegato${it.username?.let { name -> ": $name" } ?: ""}."
                        }
                        .onFailure { simklStatus = it.localizedMessage ?: "Codice scaduto o errore SIMKL" }
                    }
                    .onFailure { simklStatus = it.localizedMessage ?: "Errore SIMKL" }
                  simklLoading = false
                }
              }
            }
          )
          simklPin?.let { pin ->
            Row(
              modifier = Modifier.fillMaxWidth().padding(16.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(20.dp)
            ) {
              QrCodeImage(value = pin.verificationUri, modifier = Modifier.size(136.dp))
              Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("1. Visita ${pin.verificationUri}", color = NovaTextSecondary, fontSize = 14.sp)
                Text("2. Inserisci questo codice", color = NovaTextSecondary, fontSize = 14.sp)
                Text(
                  pin.userCode,
                  color = NovaCyanBright,
                  fontSize = 34.sp,
                  fontWeight = FontWeight.Black,
                  letterSpacing = 5.sp
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                  if (simklLoading) CircularProgressIndicator(color = NovaCyanBright, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                  Text(
                    if (simklLoading) "In attesa dell'autorizzazione…" else "Codice scaduto. Seleziona Collega SIMKL per riprovare.",
                    color = if (simklLoading) NovaCyanBright else NovaOrange,
                    fontSize = 13.sp
                  )
                }
              }
            }
          }
          simklStatus?.let {
            Text(it, color = if (simklAccount.isLoggedIn) NovaGreen else NovaOrange, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
          }
        }
      }

      // ── GENERALE ────────────────────────────────────────────────────────
      item {
        SettingsSection(title = stringResource(R.string.settings_sec_general)) {
          SettingsRow(
            icon = Icons.Default.Language,
            title = stringResource(R.string.settings_app_language),
            value = appLanguageLabel(appLanguage),
            onClick = { showAppLanguageDialog = true }
          )
          SettingsRow(
            icon = Icons.Default.Lock,
            title = stringResource(R.string.settings_parental_control),
            value = if (parentalControlEnabled) {
              stringResource(R.string.parental_control_on_level, "$parentalControlLevel+")
            } else {
              stringResource(R.string.parental_control_off)
            },
            onClick = {
              parentalPinErrorMessage = null
              if (!parentalControlEnabled) {
                parentalPinStep = ParentalPinStep.CreateNew
                showParentalPinDialog = true
              } else {
                parentalPinStep = ParentalPinStep.VerifyToManage
                showParentalPinDialog = true
              }
            }
          )
          SettingsRow(
            icon = Icons.Default.Refresh,
            title = stringResource(R.string.settings_check_updates),
            value = when (updateCheckState) {
              UpdateCheckState.Checking -> stringResource(R.string.settings_update_checking)
              is UpdateCheckState.UpdateAvailable -> updateCheckState.newVersion
              is UpdateCheckState.VersionMismatch -> stringResource(R.string.settings_update_error_short)
              is UpdateCheckState.Error -> stringResource(R.string.settings_update_error_short)
              else -> null
            },
            valueColor = when (updateCheckState) {
              is UpdateCheckState.UpdateAvailable -> NovaGreen
              is UpdateCheckState.VersionMismatch -> NovaRed
              is UpdateCheckState.Error -> NovaRed
              else -> NovaCyanBright
            },
            onClick = { viewModel?.checkForAppUpdates() }
          )
          SettingsRow(
            icon = Icons.Default.Info,
            title = stringResource(R.string.settings_app_version),
            value = appVersion
          )
        }
      }

      // ── MOTORE DI RIPRODUZIONE ──────────────────────────────────────────
      item {
        SettingsSection(title = stringResource(R.string.settings_sec_playback_engine)) {
          SettingsRow(
            icon = Icons.Default.Bolt,
            title = stringResource(R.string.settings_playback_mode),
            value = when (streamingEngineMode) {
              StreamingEngineMode.DEBRID_TORBOX -> stringResource(R.string.settings_mode_debrid)
              StreamingEngineMode.HTTP_WEB -> stringResource(R.string.settings_mode_web_scraper)
            },
            onClick = { showStreamingEngineModeDialog = true }
          )
          
          if (streamingEngineMode == StreamingEngineMode.DEBRID_TORBOX) {
            // Chiave API: dialog con campo di testo (incolla da torbox.app/account)
            SettingsRow(
              icon = Icons.Default.VpnKey,
              title = stringResource(R.string.settings_torbox_api_key),
              value = torBoxApiKey?.let { maskApiKey(it) } ?: stringResource(R.string.settings_not_configured),
              valueColor = if (!torBoxApiKey.isNullOrBlank()) NovaGreen else NovaTextMuted,
              onClick = { showTorBoxKeyDialog = true },
              modifier = Modifier.focusRequester(torBoxKeyRowFocusRequester)
            )

            // Stato account: verde "Collegato" se valida, altrimenti pulsante
            // "Verifica Account" (GET https://api.torbox.app/v1/api/user/me)
            SettingsRow(
              icon = Icons.Default.CloudDone,
              title = stringResource(R.string.settings_account_status),
              value = when (torBoxState) {
                is TorBoxAccountState.Checking -> stringResource(R.string.settings_status_checking)
                is TorBoxAccountState.Connected -> stringResource(R.string.settings_status_connected)
                is TorBoxAccountState.Error -> stringResource(R.string.settings_status_verify_account)
                else -> if (torBoxApiKey.isNullOrBlank()) stringResource(R.string.settings_key_not_configured) else stringResource(R.string.settings_status_verify_account)
              },
              valueColor = when (torBoxState) {
                is TorBoxAccountState.Connected -> NovaGreen
                is TorBoxAccountState.Error -> NovaRed
                else -> NovaCyanBright
              },
              onClick = { viewModel?.verifyTorBoxAccount() }
            )

            // Toggle "Usa TorBox Instant Debrid"
            SettingsRow(
              icon = Icons.Default.Bolt,
              title = stringResource(R.string.settings_use_torbox_instant),
              value = if (torBoxEnabled) stringResource(R.string.settings_value_on) else stringResource(R.string.settings_value_off),
              valueColor = if (torBoxEnabled) NovaGreen else NovaTextMuted,
              onClick = { viewModel?.setTorBoxInstantDebridEnabled(!torBoxEnabled) }
            )
          } else {
            // Spiegazione per modalità HTTP
            Text(
              text = stringResource(R.string.settings_http_desc),
              color = NovaTextSecondary,
              fontSize = 12.sp,
              modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
            )
          }
        }
      }

      // ── RIPRODUZIONE ────────────────────────────────────────────────────
      item {
        SettingsSection(title = stringResource(R.string.settings_sec_playback)) {
          SettingsRow(
            icon = Icons.Default.PlayArrow,
            title = stringResource(R.string.settings_autoplay),
            value = if (autoplayEnabled) stringResource(R.string.settings_value_on) else stringResource(R.string.settings_value_off),
            valueColor = if (autoplayEnabled) NovaGreen else NovaTextMuted,
            onClick = { viewModel?.setAutoplayEnabled(!autoplayEnabled) }
          )
          SettingsRow(
            icon = Icons.Default.HighQuality,
            title = stringResource(R.string.settings_preferred_resolution),
            value = preferredResolutionLabel(playbackSettings.preferredResolution),
            onClick = { showResolutionDialog = true }
          )
          SettingsRow(
            icon = Icons.Default.Language,
            title = stringResource(R.string.settings_preferred_audio_language),
            value = audioLanguageLabel(playbackSettings.preferredAudioLanguage),
            onClick = { showAudioLanguageDialog = true }
          )
          SettingsRow(
            icon = Icons.Default.Language,
            title = stringResource(R.string.settings_prioritize_italian_audio),
            value = if (playbackSettings.prioritizeItalianAudio) stringResource(R.string.settings_value_on) else stringResource(R.string.settings_value_off),
            valueColor = if (playbackSettings.prioritizeItalianAudio) NovaGreen else NovaTextMuted,
            onClick = { viewModel?.setPrioritizeItalianAudio(!playbackSettings.prioritizeItalianAudio) }
          )
          SettingsRow(
            icon = Icons.Default.PlayArrow,
            title = stringResource(R.string.settings_autoplay_next_episode),
            value = if (playbackSettings.autoPlayNextEpisodeEnabled) stringResource(R.string.settings_value_on) else stringResource(R.string.settings_value_off),
            valueColor = if (playbackSettings.autoPlayNextEpisodeEnabled) NovaGreen else NovaTextMuted,
            onClick = { viewModel?.setAutoPlayNextEpisode(!playbackSettings.autoPlayNextEpisodeEnabled) }
          )
          SettingsRow(
            icon = Icons.Default.Replay,
            title = stringResource(R.string.settings_auto_resume),
            value = if (playbackSettings.autoResume) stringResource(R.string.settings_value_on) else stringResource(R.string.settings_value_off),
            valueColor = if (playbackSettings.autoResume) NovaGreen else NovaTextMuted,
            onClick = { viewModel?.setAutoResume(!playbackSettings.autoResume) }
          )
        }
      }

      // ── SELEZIONE FLUSSO ────────────────────────────────────────────────
      item {
        SettingsSection(title = stringResource(R.string.settings_sec_stream_selection)) {
          SettingsRow(
            icon = Icons.Default.Restore,
            title = stringResource(R.string.settings_reuse_last_stream),
            value = if (playbackSettings.reuseLastWorkingStreamEnabled) stringResource(R.string.settings_value_on) else stringResource(R.string.settings_value_off),
            valueColor = if (playbackSettings.reuseLastWorkingStreamEnabled) NovaGreen else NovaTextMuted,
            onClick = { viewModel?.setReuseLastWorkingStreamEnabled(!playbackSettings.reuseLastWorkingStreamEnabled) }
          )
          Text(
            text = stringResource(R.string.settings_reuse_last_stream_desc),
            color = NovaTextSecondary,
            fontSize = 12.sp,
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 16.dp, vertical = 4.dp)
          )
          SettingsRow(
            icon = Icons.Default.Speed,
            title = stringResource(R.string.settings_reuse_last_stream_cache_duration),
            value = lastStreamCacheDurationLabel(playbackSettings.lastWorkingStreamCacheDurationHours),
            onClick = { showReuseCacheDurationDialog = true }
          )
        }
      }

      // ── SOTTOTITOLI ─────────────────────────────────────────────────────
      item {
        SettingsSection(title = stringResource(R.string.settings_sec_subtitles)) {
          SettingsRow(
            icon = Icons.Default.Subtitles,
            title = stringResource(R.string.settings_auto_subtitles),
            value = if (playbackSettings.subtitlesEnabled) stringResource(R.string.settings_value_on) else stringResource(R.string.settings_value_off),
            valueColor = if (playbackSettings.subtitlesEnabled) NovaGreen else NovaTextMuted,
            onClick = { viewModel?.setSubtitlesEnabled(!playbackSettings.subtitlesEnabled) }
          )
          SettingsRow(
            icon = Icons.Default.Translate,
            title = stringResource(R.string.settings_preferred_subtitle_lang),
            value = subtitleLanguageLabel(playbackSettings.preferredSubtitleLanguage),
            onClick = { showSubtitleLanguageDialog = true }
          )
          SettingsRow(
            icon = Icons.Default.FormatSize,
            title = stringResource(R.string.settings_subtitle_size),
            value = subtitleSizeLabel(playbackSettings.subtitleSize),
            onClick = { showSubtitleSizeDialog = true }
          )
          SettingsRow(
            icon = Icons.Default.Wallpaper,
            title = stringResource(R.string.settings_subtitle_background),
            value = subtitleBackgroundLabel(playbackSettings.subtitleBackground),
            onClick = { showSubtitleBackgroundDialog = true }
          )
          SettingsRow(
            icon = Icons.Default.VerticalAlignBottom,
            title = stringResource(R.string.settings_subtitle_position),
            value = subtitlePositionLabel(playbackSettings.subtitlePosition),
            onClick = { showSubtitlePositionDialog = true }
          )
          SettingsRow(
            icon = Icons.Default.ClosedCaption,
            title = stringResource(R.string.settings_forced_subtitles),
            value = if (playbackSettings.forcedSubtitlesEnabled) stringResource(R.string.settings_value_on) else stringResource(R.string.settings_value_off),
            valueColor = if (playbackSettings.forcedSubtitlesEnabled) NovaGreen else NovaTextMuted,
            onClick = { viewModel?.setForcedSubtitlesEnabled(!playbackSettings.forcedSubtitlesEnabled) }
          )
        }
      }

      // ── AVANZATO ────────────────────────────────────────────────────────
      item {
        SettingsSection(title = stringResource(R.string.settings_sec_advanced)) {
          // 1. Target Buffer
          SettingsRow(
            icon = Icons.Default.Memory,
            title = stringResource(R.string.settings_adv_target_buffer),
            value = targetBufferLabel(playbackSettings.targetBuffer),
            onClick = { showTargetBufferDialog = true }
          )

          // 2. Initial Buffer
          SettingsRow(
            icon = Icons.Default.Speed,
            title = stringResource(R.string.settings_adv_initial_buffer),
            value = initialBufferLabel(playbackSettings.initialBuffer),
            onClick = { showInitialBufferDialog = true }
          )

          // 3. Back Buffer
          SettingsRow(
            icon = Icons.Default.Replay,
            title = stringResource(R.string.settings_adv_back_buffer),
            value = backBufferLabel(playbackSettings.backBuffer),
            onClick = { showBackBufferDialog = true }
          )

          // 4. Audio Passthrough
          SettingsRow(
            icon = Icons.AutoMirrored.Filled.VolumeUp,
            title = stringResource(R.string.settings_adv_audio_passthrough),
            value = if (playbackSettings.audioPassthroughEnabled) stringResource(R.string.settings_value_on) else stringResource(R.string.settings_value_off),
            valueColor = if (playbackSettings.audioPassthroughEnabled) NovaGreen else NovaTextMuted,
            onClick = { viewModel?.setAudioPassthroughEnabled(!playbackSettings.audioPassthroughEnabled) }
          )

          // 5. Audio Tunneling
          SettingsRow(
            icon = Icons.Default.SyncAlt,
            title = stringResource(R.string.settings_adv_audio_tunneling),
            value = if (playbackSettings.audioTunnelingEnabled) stringResource(R.string.settings_value_on) else stringResource(R.string.settings_value_off),
            valueColor = if (playbackSettings.audioTunnelingEnabled) NovaGreen else NovaTextMuted,
            onClick = { viewModel?.setAudioTunnelingEnabled(!playbackSettings.audioTunnelingEnabled) }
          )

          // 6. Decoder Fallback (FFmpeg)
          SettingsRow(
            icon = Icons.Default.Extension,
            title = stringResource(R.string.settings_adv_decoder_fallback),
            value = decoderFallbackLabel(playbackSettings.decoderFallbackMode),
            onClick = { showDecoderFallbackDialog = true }
          )

          // 7. Auto Frame Rate Matching (AFR)
          SettingsRow(
            icon = Icons.Default.Tv,
            title = stringResource(R.string.settings_adv_afr),
            value = if (playbackSettings.autoFrameRateMatching) stringResource(R.string.settings_value_on) else stringResource(R.string.settings_value_off),
            valueColor = if (playbackSettings.autoFrameRateMatching) NovaGreen else NovaTextMuted,
            onClick = { viewModel?.setAutoFrameRateMatching(!playbackSettings.autoFrameRateMatching) }
          )

          // 8. Dolby Vision Fallback
          SettingsRow(
            icon = Icons.Default.HdrOn,
            title = stringResource(R.string.settings_adv_dolby_vision_fallback),
            value = if (playbackSettings.dolbyVisionFallbackEnabled) stringResource(R.string.settings_value_on) else stringResource(R.string.settings_value_off),
            valueColor = if (playbackSettings.dolbyVisionFallbackEnabled) NovaGreen else NovaTextMuted,
            onClick = { viewModel?.setDolbyVisionFallbackEnabled(!playbackSettings.dolbyVisionFallbackEnabled) }
          )

          // 9. Stats for Nerds / Debug Info Overlay
          SettingsRow(
            icon = Icons.Default.Assessment,
            title = stringResource(R.string.settings_adv_stats_overlay),
            value = if (playbackSettings.debugOverlayEnabled) stringResource(R.string.settings_value_on) else stringResource(R.string.settings_value_off),
            valueColor = if (playbackSettings.debugOverlayEnabled) NovaGreen else NovaTextMuted,
            onClick = { viewModel?.setDebugOverlayEnabled(!playbackSettings.debugOverlayEnabled) }
          )

          // 10. Clear Cache
          SettingsRow(
            icon = Icons.Default.DeleteSweep,
            title = stringResource(R.string.settings_adv_clear_cache_title),
            value = if (isClearingCache) stringResource(R.string.settings_in_progress) else null,
            onClick = {
              if (!isClearingCache && viewModel != null) {
                isClearingCache = true
                viewModel.clearAppCache(context) { success ->
                  isClearingCache = false
                  val msg = if (success) {
                    context.getString(R.string.settings_adv_cache_cleared)
                  } else {
                    context.getString(R.string.settings_adv_cache_clear_error)
                  }
                  Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                }
              }
            }
          )
        }
      }
    }
  }

  if (showUpdateResultDialog) {
    UpdateResultDialog(
      state = updateCheckState,
      downloadState = updateDownloadState,
      onDownload = { viewModel?.downloadUpdate(context) },
      onDismiss = {
        showUpdateResultDialog = false
        viewModel?.dismissUpdateCheckResult()
      }
    )
  }

  if (showSimklClientIdDialog) {
    TextInputDialog(
      title = "Client ID SIMKL",
      hint = "Incolla il Client ID creato nelle impostazioni sviluppatore SIMKL",
      initialText = simklClientIdDraft,
      confirmLabel = stringResource(R.string.action_save),
      onConfirm = { value ->
        AppSettingsRepository.setSimklClientId(value)
        simklClientIdDraft = value.trim()
        showSimklClientIdDialog = false
        simklStatus = "Client ID SIMKL salvato. Avvia il collegamento account."
      },
      onDismiss = { showSimklClientIdDialog = false }
    )
  }

  if (showTorBoxKeyDialog) {
    TextInputDialog(
      title = stringResource(R.string.settings_torbox_api_key),
      hint = stringResource(R.string.settings_torbox_key_hint),
      initialText = torBoxApiKey ?: "",
      confirmLabel = stringResource(R.string.action_save),
      onConfirm = { key ->
        showTorBoxKeyDialog = false
        restoreTorBoxKeyRowFocus = true
        viewModel?.setTorBoxApiKey(key)
        viewModel?.verifyTorBoxAccount()
      },
      onDismiss = {
        showTorBoxKeyDialog = false
        restoreTorBoxKeyRowFocus = true
      }
    )
  }

  if (showStreamingEngineModeDialog) {
    val modeOptions = listOf(
      stringResource(R.string.settings_mode_debrid),
      stringResource(R.string.settings_mode_web_scraper)
    )
    TrackSelectionDialog(
      title = stringResource(R.string.settings_playback_mode),
      options = modeOptions,
      selectedIndex = if (streamingEngineMode == StreamingEngineMode.DEBRID_TORBOX) 0 else 1,
      onSelect = { idx ->
        val newMode = if (idx == 0) StreamingEngineMode.DEBRID_TORBOX else StreamingEngineMode.HTTP_WEB
        viewModel?.setStreamingEngineMode(newMode)
        showStreamingEngineModeDialog = false
      },
      onDismiss = { showStreamingEngineModeDialog = false }
    )
  }

  if (showResolutionDialog) {
    val resOptions = PreferredResolution.values()
    val resLabels = resOptions.map { preferredResolutionLabel(it) }
    TrackSelectionDialog(
      title = stringResource(R.string.settings_preferred_resolution),
      options = resLabels,
      selectedIndex = resOptions.indexOf(playbackSettings.preferredResolution).coerceAtLeast(0),
      onSelect = { idx ->
        viewModel?.setPreferredResolution(resOptions[idx])
        showResolutionDialog = false
      },
      onDismiss = { showResolutionDialog = false }
    )
  }

  if (showReuseCacheDurationDialog) {
    val durationOptions = LastStreamCacheDuration.entries
    val durationLabels = durationOptions.map { lastStreamCacheDurationLabel(it.hours) }
    TrackSelectionDialog(
      title = stringResource(R.string.settings_reuse_last_stream_cache_duration),
      options = durationLabels,
      selectedIndex = durationOptions
        .indexOfFirst { it.hours == playbackSettings.lastWorkingStreamCacheDurationHours }
        .coerceAtLeast(0),
      onSelect = { idx ->
        viewModel?.setLastWorkingStreamCacheDurationHours(durationOptions[idx].hours)
        showReuseCacheDurationDialog = false
      },
      onDismiss = { showReuseCacheDurationDialog = false }
    )
  }

  if (showSubtitleSizeDialog) {
    val sizeOptions = SubtitleSize.entries
    val sizeLabels = sizeOptions.map { subtitleSizeLabel(it) }
    TrackSelectionDialog(
      title = stringResource(R.string.settings_subtitle_size),
      options = sizeLabels,
      selectedIndex = sizeOptions.indexOf(playbackSettings.subtitleSize).coerceAtLeast(0),
      onSelect = { idx ->
        viewModel?.setSubtitleSize(sizeOptions[idx])
        showSubtitleSizeDialog = false
      },
      onDismiss = { showSubtitleSizeDialog = false }
    )
  }

  if (showSubtitleBackgroundDialog) {
    val backgroundOptions = SubtitleBackground.entries
    val backgroundLabels = backgroundOptions.map { subtitleBackgroundLabel(it) }
    TrackSelectionDialog(
      title = stringResource(R.string.settings_subtitle_background),
      options = backgroundLabels,
      selectedIndex = backgroundOptions.indexOf(playbackSettings.subtitleBackground).coerceAtLeast(0),
      onSelect = { idx ->
        viewModel?.setSubtitleBackground(backgroundOptions[idx])
        showSubtitleBackgroundDialog = false
      },
      onDismiss = { showSubtitleBackgroundDialog = false }
    )
  }

  if (showSubtitlePositionDialog) {
    val positionOptions = SubtitlePosition.entries
    val positionLabels = positionOptions.map { subtitlePositionLabel(it) }
    TrackSelectionDialog(
      title = stringResource(R.string.settings_subtitle_position),
      options = positionLabels,
      selectedIndex = positionOptions.indexOf(playbackSettings.subtitlePosition).coerceAtLeast(0),
      onSelect = { idx ->
        viewModel?.setSubtitlePosition(positionOptions[idx])
        showSubtitlePositionDialog = false
      },
      onDismiss = { showSubtitlePositionDialog = false }
    )
  }

  if (showSubtitleLanguageDialog) {
    val subOptions = SUBTITLE_LANGUAGE_CODES.map { subtitleLanguageLabel(it) }
    TrackSelectionDialog(
      title = stringResource(R.string.settings_preferred_subtitle_lang),
      options = subOptions,
      selectedIndex = SUBTITLE_LANGUAGE_CODES
        .indexOf(playbackSettings.preferredSubtitleLanguage)
        .coerceAtLeast(0),
      onSelect = { idx ->
        viewModel?.setPreferredSubtitleLanguage(SUBTITLE_LANGUAGE_CODES[idx])
        showSubtitleLanguageDialog = false
      },
      onDismiss = { showSubtitleLanguageDialog = false }
    )
  }

  if (showAudioLanguageDialog) {
    val audioOptions = AUDIO_LANGUAGE_CODES.map { audioLanguageLabel(it) }
    TrackSelectionDialog(
      title = stringResource(R.string.settings_preferred_audio_language),
      options = audioOptions,
      selectedIndex = AUDIO_LANGUAGE_CODES
        .indexOf(playbackSettings.preferredAudioLanguage)
        .coerceAtLeast(0),
      onSelect = { idx ->
        viewModel?.setPreferredAudioLanguage(AUDIO_LANGUAGE_CODES[idx])
        showAudioLanguageDialog = false
      },
      onDismiss = { showAudioLanguageDialog = false }
    )
  }

  if (showAppLanguageDialog) {
    val appOptions = APP_LANGUAGE_CODES.map { appLanguageLabel(it) }
    TrackSelectionDialog(
      title = stringResource(R.string.settings_app_language),
      options = appOptions,
      selectedIndex = APP_LANGUAGE_CODES.indexOf(appLanguage).coerceAtLeast(0),
      onSelect = { idx ->
        viewModel?.setAppLanguage(APP_LANGUAGE_CODES[idx])
        showAppLanguageDialog = false
      },
      onDismiss = { showAppLanguageDialog = false }
    )
  }

  if (showParentalPinDialog && parentalPinStep !is ParentalPinStep.None) {
    val title = stringResource(R.string.parental_dialog_enter_pin_title)
    val subtitle = when (parentalPinStep) {
      is ParentalPinStep.CreateNew, is ParentalPinStep.ChangeNewPin ->
        stringResource(R.string.parental_dialog_create_pin)
      is ParentalPinStep.ConfirmNew, is ParentalPinStep.ChangeConfirmPin ->
        stringResource(R.string.parental_dialog_confirm_pin)
      else ->
        stringResource(R.string.parental_dialog_enter_current_pin)
    }
    val mismatchMsg = stringResource(R.string.parental_dialog_pin_mismatch)
    val wrongPinMsg = stringResource(R.string.parental_dialog_wrong_pin)

    TvPinDialog(
      title = title,
      subtitle = subtitle,
      errorMessage = parentalPinErrorMessage,
      onPinSubmit = { enteredPin ->
        when (val step = parentalPinStep) {
          is ParentalPinStep.CreateNew -> {
            parentalPinErrorMessage = null
            parentalPinStep = ParentalPinStep.ConfirmNew(enteredPin)
          }
          is ParentalPinStep.ConfirmNew -> {
            if (enteredPin == step.firstPin) {
              viewModel?.setParentalControlPin(enteredPin)
              viewModel?.setParentalControlEnabled(true)
              showParentalPinDialog = false
              parentalPinStep = ParentalPinStep.None
              parentalPinErrorMessage = null
            } else {
              parentalPinErrorMessage = mismatchMsg
            }
          }
          is ParentalPinStep.VerifyToManage -> {
            val valid = viewModel?.verifyParentalControlPin(enteredPin) ?: false
            if (valid) {
              showParentalPinDialog = false
              parentalPinStep = ParentalPinStep.None
              parentalPinErrorMessage = null
              showParentalManageDialog = true
            } else {
              parentalPinErrorMessage = wrongPinMsg
            }
          }
          is ParentalPinStep.ChangeNewPin -> {
            parentalPinErrorMessage = null
            parentalPinStep = ParentalPinStep.ChangeConfirmPin(enteredPin)
          }
          is ParentalPinStep.ChangeConfirmPin -> {
            if (enteredPin == step.firstPin) {
              viewModel?.setParentalControlPin(enteredPin)
              showParentalPinDialog = false
              parentalPinStep = ParentalPinStep.None
              parentalPinErrorMessage = null
            } else {
              parentalPinErrorMessage = mismatchMsg
            }
          }
          else -> {}
        }
      },
      onDismiss = {
        showParentalPinDialog = false
        parentalPinStep = ParentalPinStep.None
        parentalPinErrorMessage = null
      }
    )
  }

  if (showParentalManageDialog) {
    val manageOptions = listOf(
      stringResource(R.string.parental_option_level, "$parentalControlLevel+"),
      stringResource(R.string.parental_option_change_pin),
      stringResource(R.string.parental_option_disable)
    )
    TrackSelectionDialog(
      title = stringResource(R.string.parental_dialog_manage_title),
      options = manageOptions,
      selectedIndex = 0,
      onSelect = { idx ->
        showParentalManageDialog = false
        when (idx) {
          0 -> showParentalLevelDialog = true
          1 -> {
            parentalPinErrorMessage = null
            parentalPinStep = ParentalPinStep.ChangeNewPin
            showParentalPinDialog = true
          }
          2 -> viewModel?.setParentalControlEnabled(false)
        }
      },
      onDismiss = { showParentalManageDialog = false }
    )
  }

  if (showParentalLevelDialog) {
    val levels = listOf("14", "16", "18")
    val levelOptions = levels.map { "$it+" }
    TrackSelectionDialog(
      title = stringResource(R.string.parental_select_level_title),
      options = levelOptions,
      selectedIndex = levels.indexOf(parentalControlLevel).coerceAtLeast(0),
      onSelect = { idx ->
        viewModel?.setParentalControlLevel(levels[idx])
        showParentalLevelDialog = false
      },
      onDismiss = { showParentalLevelDialog = false }
    )
  }

  if (showTargetBufferDialog) {
    val targetOptions = TargetBufferOption.entries
    val targetLabels = targetOptions.map { targetBufferLabel(it) }
    TrackSelectionDialog(
      title = stringResource(R.string.settings_adv_target_buffer),
      options = targetLabels,
      selectedIndex = targetOptions.indexOf(playbackSettings.targetBuffer).coerceAtLeast(0),
      onSelect = { idx ->
        viewModel?.setTargetBuffer(targetOptions[idx])
        showTargetBufferDialog = false
      },
      onDismiss = { showTargetBufferDialog = false }
    )
  }

  if (showInitialBufferDialog) {
    val initialOptions = InitialBufferOption.entries
    val initialLabels = initialOptions.map { initialBufferLabel(it) }
    TrackSelectionDialog(
      title = stringResource(R.string.settings_adv_initial_buffer),
      options = initialLabels,
      selectedIndex = initialOptions.indexOf(playbackSettings.initialBuffer).coerceAtLeast(0),
      onSelect = { idx ->
        viewModel?.setInitialBuffer(initialOptions[idx])
        showInitialBufferDialog = false
      },
      onDismiss = { showInitialBufferDialog = false }
    )
  }

  if (showBackBufferDialog) {
    val backOptions = BackBufferOption.entries
    val backLabels = backOptions.map { backBufferLabel(it) }
    TrackSelectionDialog(
      title = stringResource(R.string.settings_adv_back_buffer),
      options = backLabels,
      selectedIndex = backOptions.indexOf(playbackSettings.backBuffer).coerceAtLeast(0),
      onSelect = { idx ->
        viewModel?.setBackBuffer(backOptions[idx])
        showBackBufferDialog = false
      },
      onDismiss = { showBackBufferDialog = false }
    )
  }

  if (showDecoderFallbackDialog) {
    val decoderOptions = DecoderFallbackMode.entries
    val decoderLabels = decoderOptions.map { decoderFallbackLabel(it) }
    TrackSelectionDialog(
      title = stringResource(R.string.settings_adv_decoder_fallback),
      options = decoderLabels,
      selectedIndex = decoderOptions.indexOf(playbackSettings.decoderFallbackMode).coerceAtLeast(0),
      onSelect = { idx ->
        viewModel?.setDecoderFallbackMode(decoderOptions[idx])
        showDecoderFallbackDialog = false
      },
      onDismiss = { showDecoderFallbackDialog = false }
    )
  }
}

/** Passi del flusso di inserimento/verifica PIN per il controllo genitori. */
private sealed interface ParentalPinStep {
  object None : ParentalPinStep
  object CreateNew : ParentalPinStep
  data class ConfirmNew(val firstPin: String) : ParentalPinStep
  object VerifyToManage : ParentalPinStep
  object ChangeNewPin : ParentalPinStep
  data class ChangeConfirmPin(val firstPin: String) : ParentalPinStep
}

/** Codici lingua selezionabili per l'app e metadati TMDB. */
private val APP_LANGUAGE_CODES = listOf("it", "en", "es", "fr", "de")

private val APP_VERSION_DISPLAY = "${BuildConfig.VERSION_NAME} Beta"

@Composable
private fun appLanguageLabel(code: String): String = when (code) {
  "en" -> stringResource(R.string.lang_english)
  "es" -> stringResource(R.string.lang_spanish)
  "fr" -> stringResource(R.string.lang_french)
  "de" -> stringResource(R.string.lang_german)
  else -> stringResource(R.string.lang_italian)
}

/** Codici lingua selezionabili per l'audio; "" = "Originale / Qualsiasi" (nessun vincolo). */
private val AUDIO_LANGUAGE_CODES = listOf("it", "en", "es", "fr", "de", "")

@Composable
private fun audioLanguageLabel(code: String): String = when (code) {
  "it" -> stringResource(R.string.lang_italian)
  "en" -> stringResource(R.string.lang_english)
  "es" -> stringResource(R.string.lang_spanish)
  "fr" -> stringResource(R.string.lang_french)
  "de" -> stringResource(R.string.lang_german)
  else -> stringResource(R.string.lang_original_any)
}

/** Codici lingua selezionabili per i sottotitoli; "" = "Originale / Qualsiasi" (nessun vincolo). */
private val SUBTITLE_LANGUAGE_CODES = listOf("it", "en", "es", "fr", "de", "pt", "")

/** Maschera la chiave API per la UI: `abcd1234...wxyz` → `abcd••••••••wxyz`. */
private fun maskApiKey(key: String): String {
  val clean = key.trim()
  return if (clean.length <= 8) "••••••••" else clean.take(4) + "••••••••" + clean.takeLast(4)
}

@Composable
private fun subtitleLanguageLabel(code: String): String = when (code) {
  "it" -> stringResource(R.string.lang_sub_italian)
  "en" -> stringResource(R.string.lang_sub_english)
  "es" -> stringResource(R.string.lang_sub_spanish)
  "fr" -> stringResource(R.string.lang_sub_french)
  "de" -> stringResource(R.string.lang_sub_german)
  "pt" -> stringResource(R.string.lang_sub_portuguese)
  else -> stringResource(R.string.lang_original_any)
}

@Composable
private fun preferredResolutionLabel(res: PreferredResolution): String = when (res) {
  PreferredResolution.AUTO -> stringResource(R.string.res_auto)
  PreferredResolution.UHD_4K -> "4K Ultra HD"
  PreferredResolution.FULL_HD_1080P -> "1080p Full HD"
  PreferredResolution.HD_720P -> "720p HD"
}

@Composable
private fun subtitleSizeLabel(size: SubtitleSize): String = when (size) {
  SubtitleSize.SMALL -> stringResource(R.string.sub_size_small)
  SubtitleSize.MEDIUM -> stringResource(R.string.sub_size_medium)
  SubtitleSize.LARGE -> stringResource(R.string.sub_size_large)
  SubtitleSize.VERY_LARGE -> stringResource(R.string.sub_size_very_large)
}

/** Etichetta della durata cache dell'ultimo flusso ("Riusa l'ultimo link"). */
@Composable
private fun lastStreamCacheDurationLabel(hours: Int): String {
  val option = LastStreamCacheDuration.entries.firstOrNull { it.hours == hours }
    ?: LastStreamCacheDuration.ONE_DAY
  return when (option) {
    LastStreamCacheDuration.SIX_HOURS -> stringResource(R.string.reuse_cache_6h)
    LastStreamCacheDuration.TWELVE_HOURS -> stringResource(R.string.reuse_cache_12h)
    LastStreamCacheDuration.ONE_DAY -> stringResource(R.string.reuse_cache_1d)
    LastStreamCacheDuration.THREE_DAYS -> stringResource(R.string.reuse_cache_3d)
    LastStreamCacheDuration.SEVEN_DAYS -> stringResource(R.string.reuse_cache_7d)
  }
}

@Composable
private fun subtitleBackgroundLabel(bg: SubtitleBackground): String = when (bg) {
  SubtitleBackground.NONE -> stringResource(R.string.sub_bg_none)
  SubtitleBackground.BLACK -> stringResource(R.string.sub_bg_black)
  SubtitleBackground.SEMI_TRANSPARENT -> stringResource(R.string.sub_bg_semi_transparent)
}

@Composable
private fun subtitlePositionLabel(pos: SubtitlePosition): String = when (pos) {
  SubtitlePosition.BOTTOM -> stringResource(R.string.sub_pos_bottom)
  SubtitlePosition.CENTER -> stringResource(R.string.sub_pos_center)
  SubtitlePosition.TOP -> stringResource(R.string.sub_pos_top)
}

@Composable
private fun targetBufferLabel(option: TargetBufferOption): String = when (option) {
  TargetBufferOption.DEFAULT -> stringResource(R.string.buffer_target_default)
  TargetBufferOption.MINIMUM -> stringResource(R.string.buffer_target_min)
  TargetBufferOption.BALANCED -> stringResource(R.string.buffer_target_balanced)
  TargetBufferOption.EXTENDED -> stringResource(R.string.buffer_target_extended)
}

@Composable
private fun initialBufferLabel(option: InitialBufferOption): String = when (option) {
  InitialBufferOption.FAST -> stringResource(R.string.buffer_initial_fast)
  InitialBufferOption.STANDARD -> stringResource(R.string.buffer_initial_standard)
  InitialBufferOption.STABLE -> stringResource(R.string.buffer_initial_stable)
}

@Composable
private fun backBufferLabel(option: BackBufferOption): String = when (option) {
  BackBufferOption.OFF -> stringResource(R.string.buffer_back_off)
  BackBufferOption.SHORT -> stringResource(R.string.buffer_back_10s)
  BackBufferOption.STANDARD -> stringResource(R.string.buffer_back_15s)
  BackBufferOption.EXTENDED -> stringResource(R.string.buffer_back_30s)
  BackBufferOption.MAXIMUM -> stringResource(R.string.buffer_back_60s)
}

@Composable
private fun decoderFallbackLabel(mode: DecoderFallbackMode): String = when (mode) {
  DecoderFallbackMode.OFF -> stringResource(R.string.decoder_mode_off)
  DecoderFallbackMode.ON -> stringResource(R.string.decoder_mode_on)
  DecoderFallbackMode.PREFER -> stringResource(R.string.decoder_mode_prefer)
}

/**
 * Dialog con l'esito del controllo aggiornamenti e, quando disponibile, il
 * flusso download APK verificato + avvio ESPLICITO dell'installer.
 *
 * L'installer di sistema non viene mai aperto in automatico: parte solo alla
 * pressione del pulsante "Installa", dopo che il file è stato scaricato e
 * verificato.
 */
@Composable
private fun UpdateResultDialog(
  state: UpdateCheckState,
  downloadState: UpdateDownloadState,
  onDownload: () -> Unit,
  onDismiss: () -> Unit
) {
  val context = LocalContext.current
  val canInstall = ApkInstaller.canInstall(context)
  // Esito dell'ultimo tentativo ESPLICITO di avvio installer: resta visibile
  // così l'utente non finisce in uno stato apparentemente completato.
  var installErrorRes by remember { mutableStateOf<Int?>(null) }

  Dialog(onDismissRequest = onDismiss) {
    Column(
      modifier = Modifier
        .width(380.dp)
        .background(NovaSurface, RoundedCornerShape(20.dp))
        .border(1.5.dp, NovaCyan, RoundedCornerShape(20.dp))
        .padding(24.dp)
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          text = stringResource(R.string.settings_check_updates),
          color = NovaTextPrimary,
          fontSize = 18.sp,
          fontWeight = FontWeight.Bold
        )
        Icon(
          imageVector = Icons.Default.Close,
          contentDescription = stringResource(R.string.action_close),
          tint = NovaTextSecondary,
          modifier = Modifier
            .size(22.dp)
            .clickable { onDismiss() }
        )
      }

      Spacer(modifier = Modifier.height(16.dp))

      when (state) {
        is UpdateCheckState.UpToDate -> {
          Text(
            text = stringResource(
              R.string.settings_update_installed_version,
              state.currentVersion
            ),
            color = NovaTextSecondary,
            fontSize = 14.sp
          )
          Spacer(modifier = Modifier.height(8.dp))
          Text(
            text = stringResource(R.string.settings_update_upto_date),
            color = NovaGreen,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold
          )
        }
        is UpdateCheckState.UpdateAvailable -> {
          Text(
            text = stringResource(
              R.string.settings_update_installed_version,
              state.currentVersion
            ),
            color = NovaTextSecondary,
            fontSize = 14.sp
          )
          Spacer(modifier = Modifier.height(8.dp))
          Text(
            text = stringResource(R.string.settings_update_available, state.newVersion),
            color = NovaCyanBright,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold
          )
          state.assetName?.let { assetName ->
            Spacer(modifier = Modifier.height(6.dp))
            Text(
              text = stringResource(R.string.settings_update_asset_name, assetName),
              color = NovaTextMuted,
              fontSize = 12.sp
            )
          }

          // Changelog della release: area scorrevole di altezza contenuta,
          // leggibile con il D-pad su Android TV.
          UpdateChangelogBlock(state.changelog)

          Spacer(modifier = Modifier.height(14.dp))

          when (downloadState) {
            UpdateDownloadState.Idle -> UpdateDialogButton(
              text = stringResource(R.string.settings_update_download),
              onClick = onDownload
            )
            is UpdateDownloadState.Downloading -> {
              val percent = downloadState.percent
              if (percent != null) {
                LinearProgressIndicator(
                  progress = { percent / 100f },
                  modifier = Modifier.fillMaxWidth()
                )
              } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
              }
              Spacer(modifier = Modifier.height(8.dp))
              Text(
                text = stringResource(R.string.settings_update_downloading) +
                  if (percent != null) " $percent%" else "",
                color = NovaTextSecondary,
                fontSize = 13.sp
              )
            }
            is UpdateDownloadState.Completed -> {
              Text(
                text = stringResource(R.string.settings_update_download_complete),
                color = NovaGreen,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
              )
              Spacer(modifier = Modifier.height(10.dp))
              if (canInstall) {
                UpdateDialogButton(
                  text = stringResource(R.string.settings_update_install),
                  onClick = {
                    val apk = File(downloadState.apkPath)
                    val launched = ApkInstaller.launch(context, apk)
                    installErrorRes = when {
                      launched -> null
                      !apk.exists() || apk.length() <= 0L ->
                        R.string.settings_update_install_failed_file
                      !ApkInstaller.canInstall(context) ->
                        R.string.settings_update_install_failed_permission
                      else -> R.string.settings_update_install_failed_generic
                    }
                  }
                )
              } else {
                Text(
                  text = stringResource(R.string.settings_update_install_permission_needed),
                  color = NovaTextSecondary,
                  fontSize = 13.sp
                )
                Spacer(modifier = Modifier.height(10.dp))
                UpdateDialogButton(
                  text = stringResource(R.string.settings_update_install_permission_button),
                  onClick = { ApkInstaller.openInstallPermissionSettings(context) }
                )
              }
              installErrorRes?.let { errorRes ->
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                  text = stringResource(errorRes),
                  color = NovaRed,
                  fontSize = 13.sp
                )
                if (errorRes == R.string.settings_update_install_failed_permission) {
                  Spacer(modifier = Modifier.height(10.dp))
                  UpdateDialogButton(
                    text = stringResource(R.string.settings_update_install_permission_button),
                    onClick = { ApkInstaller.openInstallPermissionSettings(context) }
                  )
                }
              }
            }
            is UpdateDownloadState.Error -> {
              Text(
                text = downloadErrorText(downloadState.reason),
                color = NovaRed,
                fontSize = 14.sp
              )
              Spacer(modifier = Modifier.height(10.dp))
              UpdateDialogButton(
                text = stringResource(R.string.settings_update_download),
                onClick = onDownload
              )
            }
          }
        }
        is UpdateCheckState.VersionMismatch -> {
          Text(
            text = stringResource(
              R.string.settings_update_installed_version,
              state.currentVersion
            ),
            color = NovaTextSecondary,
            fontSize = 14.sp
          )
          Spacer(modifier = Modifier.height(8.dp))
          Text(
            text = stringResource(
              R.string.settings_update_mismatch,
              state.tagVersion,
              state.assetVersion ?: "?"
            ),
            color = NovaRed,
            fontSize = 14.sp
          )
          state.assetName?.let { assetName ->
            Spacer(modifier = Modifier.height(6.dp))
            Text(
              text = stringResource(R.string.settings_update_asset_name, assetName),
              color = NovaTextMuted,
              fontSize = 12.sp
            )
          }
        }
        is UpdateCheckState.Error -> {
          Text(
            text = updateCheckErrorText(state.reason),
            color = NovaRed,
            fontSize = 14.sp
          )
        }
        else -> Unit
      }

      Spacer(modifier = Modifier.height(20.dp))

      UpdateDialogButton(text = stringResource(R.string.action_close), onClick = onDismiss)
    }
  }
}

/** Pulsante standard dei dialog: evidenziato ciano quando è in focus (D-pad). */
@Composable
private fun UpdateDialogButton(text: String, onClick: () -> Unit) {
  TvFocusableBox(
    shape = RoundedCornerShape(12.dp),
    focusedScale = 1.02f,
    onClick = onClick,
    modifier = Modifier.fillMaxWidth()
  ) { isFocused ->
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .background(
          if (isFocused) NovaCyanBright else NovaSurfaceVariant,
          RoundedCornerShape(12.dp)
        )
        .padding(vertical = 10.dp),
      contentAlignment = Alignment.Center
    ) {
      Text(
        text = text,
        color = if (isFocused) NovaBackground else NovaTextPrimary,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold
      )
    }
  }
}

/** Messaggio localizzato del motivo di errore del download. */
@Composable
private fun downloadErrorText(reason: DownloadErrorReason): String = when (reason) {
  DownloadErrorReason.NO_APK_IN_RELEASE ->
    stringResource(R.string.settings_update_dl_error_no_apk)
  DownloadErrorReason.NO_NETWORK ->
    stringResource(R.string.settings_update_dl_error_network)
  DownloadErrorReason.TIMEOUT ->
    stringResource(R.string.settings_update_dl_error_timeout)
  DownloadErrorReason.HTTP_ERROR ->
    stringResource(R.string.settings_update_dl_error_http)
  DownloadErrorReason.HOST_NOT_ALLOWED ->
    stringResource(R.string.settings_update_dl_error_host)
  DownloadErrorReason.INTERRUPTED ->
    stringResource(R.string.settings_update_dl_error_interrupted)
  DownloadErrorReason.FILE_MISSING ->
    stringResource(R.string.settings_update_dl_error_file)
  DownloadErrorReason.INSUFFICIENT_SPACE ->
    stringResource(R.string.settings_update_dl_error_space)
  DownloadErrorReason.SIZE_MISMATCH ->
    stringResource(R.string.settings_update_dl_error_size)
  DownloadErrorReason.CHECKSUM_MISMATCH ->
    stringResource(R.string.settings_update_dl_error_checksum)
  DownloadErrorReason.UNKNOWN ->
    stringResource(R.string.settings_update_dl_error_generic)
}

/** Messaggio localizzato del motivo di errore del controllo aggiornamenti. */
@Composable
private fun updateCheckErrorText(reason: UpdateCheckErrorReason): String = when (reason) {
  UpdateCheckErrorReason.RATE_LIMITED ->
    stringResource(R.string.settings_update_error_rate_limited)
  UpdateCheckErrorReason.NOT_FOUND ->
    stringResource(R.string.settings_update_error_not_found)
  UpdateCheckErrorReason.NETWORK ->
    stringResource(R.string.settings_update_error_network)
  UpdateCheckErrorReason.INVALID_RELEASE ->
    stringResource(R.string.settings_update_error_invalid_release)
  UpdateCheckErrorReason.NO_COMPATIBLE_APK ->
    stringResource(R.string.settings_update_error_no_compatible_apk)
  UpdateCheckErrorReason.UNKNOWN ->
    stringResource(R.string.settings_update_error)
}

/**
 * Changelog della release in area scorrevole ad altezza contenuta: leggibile
 * su Android TV con il D-pad senza far crescere il dialog a dismisura.
 */
@Composable
private fun UpdateChangelogBlock(changelog: String?) {
  if (changelog.isNullOrBlank()) return
  Spacer(modifier = Modifier.height(12.dp))
  Text(
    text = stringResource(R.string.settings_update_changelog),
    color = NovaTextPrimary,
    fontSize = 14.sp,
    fontWeight = FontWeight.Bold
  )
  Spacer(modifier = Modifier.height(6.dp))
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .heightIn(max = 180.dp)
      .background(NovaSurfaceVariant, RoundedCornerShape(10.dp))
      .padding(10.dp)
  ) {
    Text(
      text = changelog,
      color = NovaTextSecondary,
      fontSize = 13.sp,
      modifier = Modifier.verticalScroll(rememberScrollState())
    )
  }
}

/**
 * Sezione delle impostazioni: intestazione con accento ciano + card contenitore.
 * Le sezioni sono separate visivamente per una lettura immediata su TV.
 */
@Composable
fun SettingsSection(
  title: String,
  content: @Composable () -> Unit
) {
  Column(modifier = Modifier.fillMaxWidth()) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Box(
        modifier = Modifier
          .width(4.dp)
          .height(18.dp)
          .background(
            Brush.verticalGradient(listOf(NovaCyan, NovaCyanBright)),
            RoundedCornerShape(2.dp)
          )
      )
      Spacer(modifier = Modifier.width(10.dp))
      Text(
        text = title.uppercase(),
        color = NovaTextPrimary,
        fontSize = 15.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp
      )
    }
    Spacer(modifier = Modifier.height(12.dp))
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .background(NovaCardBg, RoundedCornerShape(14.dp))
        .border(1.dp, NovaDivider, RoundedCornerShape(14.dp))
        .padding(vertical = 6.dp),
      verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
      content()
    }
  }
}

/**
 * Riga di impostazione navigabile con D-pad.
 *
 * @param value valore reale mostrato a destra (es. versione app). Resta null per
 *        le voci ancora prive di implementazione: nessun valore inventato.
 * @param onClick azione reale. Se null la riga è solo una voce UI (FASE 1):
 *        resta focalizzabile per la navigazione ma non esegue comportamenti fittizi.
 */
@Composable
fun SettingsRow(
  icon: ImageVector,
  title: String,
  value: String? = null,
  valueColor: Color = NovaCyanBright,
  onClick: (() -> Unit)? = null,
  modifier: Modifier = Modifier
) {
  TvFocusableBox(
    shape = RoundedCornerShape(10.dp),
    focusedScale = 1.02f,
    onClick = { onClick?.invoke() },
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = 8.dp)
  ) { isFocused ->
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .background(
          if (isFocused) NovaCardBgFocused else Color.Transparent,
          RoundedCornerShape(10.dp)
        )
        .padding(horizontal = 16.dp, vertical = 14.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        tint = if (isFocused) NovaCyanBright else NovaTextMuted,
        modifier = Modifier.size(20.dp)
      )
      Spacer(modifier = Modifier.width(14.dp))
      Text(
        text = title,
        color = if (isFocused) NovaTextPrimary else NovaTextSecondary,
        fontSize = 15.sp,
        fontWeight = if (isFocused) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f)
      )
      if (value != null) {
        Spacer(modifier = Modifier.width(12.dp))
        Text(
          text = value,
          color = valueColor,
          fontSize = 13.sp,
          fontWeight = FontWeight.Medium,
          maxLines = 1
        )
      }
    }
  }
}

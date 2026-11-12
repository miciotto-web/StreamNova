package com.example.ui.screens

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Opacity
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Wallpaper
import android.widget.Toast
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.snapshotFlow
import androidx.annotation.StringRes
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.example.R
import com.example.BuildConfig
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.opensubtitles.OpenSubtitlesVerifyError
import com.example.data.repository.MediaIdentity
import com.example.data.repository.SimklRepository
import com.example.data.repository.SimklSyncManager
import com.example.data.remote.simkl.SimklSyncStatus
import com.example.data.model.SearchCategoryFilter
import com.example.data.model.SearchTypeFilter
import com.example.data.prefs.AppSettingsRepository
import com.example.data.prefs.BackBufferOption
import com.example.data.prefs.ContinueWatchingLayout
import com.example.data.prefs.ContinueWatchingSort
import com.example.data.prefs.DecoderFallbackMode
import com.example.data.prefs.InitialBufferOption
import com.example.data.prefs.LastStreamCacheDuration
import com.example.data.prefs.PlaybackSettings
import com.example.data.prefs.PreferredResolution
import com.example.data.prefs.StreamingEngineMode
import com.example.data.prefs.SubtitleBackground
import com.example.data.prefs.SubtitleOpacity
import com.example.data.prefs.SubtitlePosition
import com.example.data.prefs.SubtitleSize
import com.example.data.prefs.TargetBufferOption
import com.example.data.update.UpdateCheckState
import com.example.data.update.UpdateDownloadState
import androidx.media3.common.MediaLibraryInfo
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
import com.example.ui.components.SimklLoginController
import com.example.ui.components.SimklLoginDialog
import com.example.ui.screens.player.subtitleBackgroundLabel
import com.example.ui.screens.player.subtitleOpacityLabel
import com.example.ui.screens.player.subtitleSizeLabel
import com.example.ui.theme.NovaBackground
import com.example.ui.theme.NovaCardBg
import com.example.ui.theme.NovaCardBgFocused
import com.example.ui.theme.NovaCyan
import com.example.ui.theme.NovaCyanBright
import com.example.ui.theme.NovaDivider
import com.example.ui.theme.NovaGreen
import com.example.ui.theme.NovaOrange
import com.example.ui.theme.NovaRed
import com.example.ui.theme.NovaSurfaceVariant
import com.example.ui.theme.NovaTextMuted
import com.example.ui.theme.NovaTextPrimary
import com.example.ui.theme.NovaTextSecondary
import com.example.ui.viewmodel.OpenSubtitlesConnectionState
import com.example.ui.viewmodel.SearchCategorySource
import com.example.ui.viewmodel.StreamNovaViewModel
import com.example.ui.viewmodel.TorBoxAccountState

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
  // NOW TV (Sky): provider TMDB 39 verificato su watch_region=IT (Film e Serie TV).
  // providerId = "nowtv" coincide con ProviderConstants.NOW_TV.id: il ViewModel lo usa
  // per l'associazione Stremio (alias "now"/"nowtv") e per il tag dei titoli.
  NOW_TV("now_tv", brandName = "NOW TV", tmdbProviderId = 39, providerId = "nowtv"),
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

  // BACK su una card della griglia: UNA sola pressione torna in cima alla scheda — griglia
  // riportata al primo elemento e focus sul PRIMO badge «Tutti». Con il focus sui badge (o
  // fuori dalla griglia) il gestore è disattivato e il BACK prosegue con la navigazione
  // esistente (apertura sidebar / ritorno alla Home).
  var gridHasFocus by remember { mutableStateOf(false) }
  val filterRowState = rememberLazyListState()
  val filterChipRequesters = remember { mutableMapOf<String, FocusRequester>() }
  fun filterChipRequester(filter: CategoryCatalogFilter): FocusRequester =
    filterChipRequesters.getOrPut(filter.name) { FocusRequester() }
  val backToTopScope = rememberCoroutineScope()
  BackHandler(enabled = gridHasFocus) {
    backToTopScope.launch {
      // La fila di badge torna all'inizio: il chip «Tutti» deve essere montato prima che
      // la richiesta di focus possa essere consegnata (chip riciclata fuori finestra).
      try {
        filterRowState.scrollToItem(0)
      } catch (_: Exception) {
      }
      // «Inizio scheda»: anche la griglia riparte dal primo elemento, così discese successive
      // dal badge riprendono dal catalogo dall'inizio.
      try {
        gridState.scrollToItem(0)
      } catch (_: Exception) {
      }
      requestFocusWhenAttached(filterChipRequester(CategoryCatalogFilter.ALL))
    }
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
      state = filterRowState,
      modifier = Modifier.padding(vertical = 14.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      items(filters, key = { it.name }, contentType = { "filter_chip" }) { filter ->
        val isSelected = selectedFilter == filter
        val label = filter.labelRes?.let { stringResource(it) } ?: filter.brandName ?: ""
        TvFocusableBox(
          // Requester del chip: il BACK dalla griglia deve consegnare il focus al primo
          // badge «Tutti» (vedi il BackHandler sopra). Il chip è montato perché la LazyRow
          // è già stata riportata all'inizio prima della richiesta.
          modifier = Modifier.focusRequester(filterChipRequester(filter)),
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
        modifier = Modifier
          .fillMaxSize()
          // Tracking del focus nella griglia (hasFocus include le card): è la condizione
          // del BackHandler «torna in cima al badge Tutti».
          .onFocusChanged { gridHasFocus = it.hasFocus }
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

/**
 * Chiave stabile di una scheda dei Preferiti nella [LazyVerticalGrid]: identifica il contenuto
 * (non la posizione) e consente all'ancora di recupero di risalire all'id dall'elemento
 * visibile letto dal `layoutInfo`.
 */
private const val FAVORITE_CARD_KEY_PREFIX = "fav_grid_"

private fun favoriteCardKey(mediaId: String): String = "$FAVORITE_CARD_KEY_PREFIX$mediaId"

/**
 * Posizione della scheda di Preferiti da RIPRISTINARE, risolta per identità del contenuto.
 *
 * La scheda aperta è quella che il catalogo mostrava al momento del click; mentre il Dettaglio
 * è aperto l'elenco può cambiare (l'osservatore Room riordina `allMedia` quando i dettagli
 * arricchiti vengono persistiti, e i Preferiti deduplicano una scheda per titolo). Quindi:
 *
 * - prima la corrispondenza DIRETTA sull'id della scheda aperta;
 * - in mancanza, la scheda che rappresenta lo STESSO contenuto secondo [MediaIdentity] (una
 *   scheda TMDB e la sua gemella IMDb/Stremio hanno id diversi ma sono lo stesso titolo);
 * - `-1` quando il titolo non è più fra i Preferiti: nessuna scheda viene indovinata per
 *   posizione.
 */
internal fun favoriteRestoreIndex(
  favorites: List<MediaItem>,
  allMedia: List<MediaItem>,
  mediaId: String
): Int {
  val exact = favorites.indexOfFirst { it.id == mediaId }
  if (exact >= 0) return exact
  val seed = allMedia.firstOrNull { it.id == mediaId } ?: return -1
  return favorites.indexOfFirst { MediaIdentity.isSameTitle(it, seed) }
}

@Composable
fun FavoritesScreen(
  allMedia: List<MediaItem>,
  onMediaClick: (MediaItem) -> Unit,
  modifier: Modifier = Modifier,
  viewModel: StreamNovaViewModel? = null
) {
  // Una griglia per TITOLO: con due schede dello stesso contenuto in catalogo (una TMDB, una
  // IMDb/Stremio) la stessa card comparirebbe due volte (vedi `MediaIdentity`).
  // L'istanza è stabile per ricomposizione: la chiave dell'effetto di ripristino è l'elenco.
  val favorites = remember(allMedia) { MediaIdentity.dedupeFavorites(allMedia) }

  // Badge di filtro in alto («Film» / «Serie TV»): il tipo selezionato mostra SOLO le schede
  // di quel tipo; nessun tipo selezionato (o selezione annullata ricliccando il badge attivo)
  // mostra tutti i Preferiti insieme, come nel comportamento storico.
  var selectedType by rememberSaveable { mutableStateOf<MediaType?>(null) }
  val visibleFavorites = remember(favorites, selectedType) {
    if (selectedType == null) favorites else favorites.filter { it.type == selectedType }
  }

  // Posizione di scroll PERSISTENTE attraverso la navigazione (come le griglie di Film, Serie
  // TV e Provider): al BACK dal Dettaglio la griglia ritrova la stessa posizione, quindi la
  // scheda ripristinata resta nella riga e nella posizione visiva da cui si era entrati.
  val gridState = rememberSaveable(saver = LazyGridState.Saver) { LazyGridState() }

  // Reset dello scroll al REALE cambio di badge: al ritorno dal Dettaglio (nessun cambio)
  // la posizione salvata della griglia resta intatta.
  var appliedFavoriteTypeName by remember { mutableStateOf<String?>(null) }
  LaunchedEffect(selectedType) {
    val typeName = selectedType?.name
    val filterChanged = appliedFavoriteTypeName != null && appliedFavoriteTypeName != typeName
    appliedFavoriteTypeName = typeName
    if (filterChanged) {
      try {
        gridState.scrollToItem(0)
      } catch (_: Exception) {
      }
    }
  }

  // Canali di ripristino della Home (MainActivity -> HomeScreen): TARGET salvato al click,
  // TOKEN dell'evento (riattiva il tentativo anche su ritorni consecutivi dalla stessa
  // scheda) e rilascio del target quando il focus è DAVVERO sulla scheda.
  val restoreMediaId = LocalLastFocusedMediaId.current
  val focusRestoreToken = LocalFocusRestoreToken.current
  val clearLastFocusedMedia = LocalClearLastFocusedMedia.current

  // Un FocusRequester per IDENTITÀ di scheda: il ripristino deve atterrare sulla scheda di
  // origine — non su una cella fissa della griglia, che con `FocusRequester` agganciato a un
  // nodo non focalizzabile non riceve mai il focus.
  val cellFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }
  fun cellFocusRequester(mediaId: String): FocusRequester =
    cellFocusRequesters.getOrPut(mediaId) { FocusRequester() }

  /**
   * Prima scheda GIÀ visibile della griglia (id del contenuto), attesa al massimo pochi frame
   * quando il layout non è ancora misurato: nessun `delay()` arbitrario.
   */
  suspend fun firstVisibleFavoriteId(): String? {
    var frames = 0
    while (gridState.layoutInfo.visibleItemsInfo.isEmpty() && frames < 8) {
      withFrameNanos { }
      frames++
    }
    val key = gridState.layoutInfo.visibleItemsInfo.firstOrNull()?.key as? String ?: return null
    return key.removePrefix(FAVORITE_CARD_KEY_PREFIX)
  }

  // Ancora di recupero quando il target NON è più la scheda a schermo (titolo uscito dai
  // Preferiti, oppure visualizzato dalla scheda "gemella" con id diverso). Il rilascio del
  // target apre il gate `canFocus` delle card e riavvia l'effetto di ripristino, quindi
  // l'ancora vive in un effetto DEDICATO: parte dopo la ricomposizione, quando il gate è già
  // aperto, e la richiesta di focus non viene respinta.
  var recoveryAnchorId by remember { mutableStateOf<String?>(null) }
  LaunchedEffect(recoveryAnchorId) {
    val anchorId = recoveryAnchorId ?: return@LaunchedEffect
    recoveryAnchorId = null
    requestFocusWhenAttached(cellFocusRequester(anchorId))
  }

  // UNICO punto di ingresso del ripristino dal Dettaglio: risolve la scheda per IDENTITÀ e
  // richiede il focus SU QUELLA. È il protocollo delle griglie di Film/Serie TV e Provider,
  // che è l'unico a garantire che al ritorno il focus non resti fuori dal contenuto (sulla
  // sidebar) quando la griglia viene ricomposta. La risoluzione usa l'elenco FILTRATO: un
  // titolo fuori dal badge attivo cade nel percorso di recupero (prima scheda visibile).
  LaunchedEffect(restoreMediaId, focusRestoreToken, visibleFavorites) {
    val mediaId = restoreMediaId ?: return@LaunchedEffect
    val index = favoriteRestoreIndex(visibleFavorites, allMedia, mediaId)
    val target = visibleFavorites.getOrNull(index)
    if (target == null) {
      // Titolo non più fra i Preferiti: il target viene rilasciato (le card tornano
      // focalizzabili) e il focus va alla prima scheda GIÀ visibile. Nessuno scroll in cima,
      // nessuna scheda indovinata per posizione, nessuna schermata senza focus.
      recoveryAnchorId = firstVisibleFavoriteId()
      clearLastFocusedMedia?.invoke()
      return@LaunchedEffect
    }
    if (target.id != mediaId) {
      // Lo stesso titolo è a schermo con un identificatore DIVERSO (scheda TMDB aperta, ora
      // visualizzata la gemella IMDb/Stremio): il gate `canFocus` delle card confronta l'id,
      // quindi il target viene rilasciato e l'ancora porta il focus sulla scheda realmente
      // presente.
      recoveryAnchorId = target.id
      clearLastFocusedMedia?.invoke()
      return@LaunchedEffect
    }
    // La visibilità del target si valuta solo su una griglia GIÀ misurata (almeno una cella in
    // composizione): finché `visibleItemsInfo` è vuoto ogni verdetto sarebbe falso.
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
        // La richiesta di focus sotto attende comunque il montaggio della scheda.
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
      // Badge di filtro «Film» / «Serie TV»: stile identico ai chip delle sezioni Film e
      // Serie TV. Il badge attivo si riclicca per annullare il filtro e tornare a TUTTI i
      // Preferiti.
      Row(
        modifier = Modifier.padding(bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        listOf(
          MediaType.FILM to R.string.filter_movies,
          MediaType.SERIE_TV to R.string.filter_tv_series
        ).forEach { (type, labelRes) ->
          val isSelected = selectedType == type
          TvFocusableBox(
            shape = RoundedCornerShape(20.dp),
            onClick = { selectedType = if (isSelected) null else type }
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
                text = stringResource(labelRes),
                color = if (isFocused) NovaBackground else if (isSelected) NovaCyanBright else NovaTextSecondary,
                fontSize = 12.sp,
                fontWeight = if (isSelected || isFocused) FontWeight.Bold else FontWeight.Medium
              )
            }
          }
        }
      }

      if (visibleFavorites.isEmpty()) {
        // Il badge attivo non ha schede: messaggio coerente col filtro (nessuna scheda
        // indovinata per posizione, griglia comunque navigabile sui badge).
        Box(
          modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
          contentAlignment = Alignment.Center
        ) {
          Text(
            text = stringResource(
              R.string.provider_empty_filter,
              stringResource(if (selectedType == MediaType.FILM) R.string.filter_movies else R.string.filter_tv_series)
            ),
            color = NovaTextMuted,
            fontSize = 16.sp
          )
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
            itemsIndexed(visibleFavorites, key = { _, item -> favoriteCardKey(item.id) }, contentType = { _, _ -> "media_poster" }) { _, item ->
              Box(
                modifier = Modifier
                  .fillMaxWidth(),
                contentAlignment = Alignment.TopCenter
              ) {
                PosterMediaCard(
                  media = item,
                  // L'identità della scheda aperta viene salvata PRIMA di navigare: senza target
                  // il ripristino al BACK non ha nulla da ricondurre alla scheda di origine.
                  onClick = {
                    viewModel?.saveLastFocusedMedia(FOCUS_ROW_ANY, item.id)
                    onMediaClick(item)
                  },
                  // Requester DELLA scheda (nodo focalizzabile della card): una richiesta via
                  // `focusRequester` su una Box non focalizzabile non può mai essere consegnata.
                  focusRequester = cellFocusRequester(item.id),
                  // Protocollo con CONFERMA del focus reale: il target viene rilasciato solo
                  // quando la scheda lo ha davvero acquisito, quindi al ritorno nessuna
                  // richiesta prematura resta senza esito.
                  confirmFocusRestore = true
                )
              }
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
  val tvGenreId: Int? = null,
  /**
   * Fonte della categoria. Default [SearchCategoryFilter.GENRE] per le categorie
   * storiche; "Talk" usa [SearchCategoryFilter.TMDB_TV_GENRE] ed è servita
   * nativamente da TMDB tramite [tvGenreId].
   */
  val contentFilter: SearchCategoryFilter = SearchCategoryFilter.GENRE
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
  /**
   * "Talk" occupa il posto che era di "Televisione film" (genere TMDB 10770).
   *
   * È il genere TV ufficiale TMDB "Talk" (id 10767) e viene servita NATIVAMENTE da
   * `discover/tv?with_genres=10767`, ordinata per popolarità e paginata. Non passa
   * dai cataloghi degli addon e non richiede la modalità Debrid: funziona anche
   * senza addon installati. Nessun filtro di lingua o paese: la categoria mostra
   * talk show italiani e stranieri.
   */
  GlobalSearchCategory(
    id = "talk",
    labelRes = R.string.search_cat_talk,
    gradientStart = Color(0xFF38BDF8),
    gradientEnd = Color(0xFF0369A1),
    accentColor = Color(0xFFE0F2FE),
    keywords = listOf("Talk", "Talk Show"),
    tvGenreId = 10767,
    contentFilter = SearchCategoryFilter.TMDB_TV_GENRE
  )
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

/** Direzione del movimento verticale D-pad in una griglia a righe. */
internal enum class SearchGridVerticalDirection { UP, DOWN }

/** Key stabile della card nella griglia dei risultati (identifica il contenuto). */
internal fun searchResultGridItemKey(media: MediaItem): String = "search_${media.id}"

/** Key stabile della card nella griglia di una categoria (tipo incluso: id grezzi condivisi). */
internal fun searchCategoryGridItemKey(
  cat: GlobalSearchCategory,
  media: MediaItem
): String = "cat_${cat.id}_${media.type}_${media.id}"

/**
 * Indice della cella da raggiungere con un movimento verticale del D-pad in una griglia a
 * righe piene (LazyVerticalGrid senza span): la COLONNA corrente viene preservata.
 *
 * Se la riga di destinazione ha meno colonne (ultima riga parziale) si sceglie la cella
 * valida più vicina alla colonna corrente; `null` quando il movimento non ha destinazione
 * (prima riga in Su, ultima riga in Giù) e deve decidere il focus system.
 */
internal fun searchGridVerticalTargetIndex(
  currentRow: Int,
  currentColumn: Int,
  itemCount: Int,
  columns: Int,
  direction: SearchGridVerticalDirection
): Int? {
  if (columns <= 0 || itemCount <= 0) return null
  if (currentRow < 0 || currentColumn < 0 || currentColumn >= columns) return null
  val targetRow = when (direction) {
    SearchGridVerticalDirection.DOWN -> currentRow + 1
    SearchGridVerticalDirection.UP -> currentRow - 1
  }
  if (targetRow < 0) return null
  val rowStart = targetRow * columns
  if (rowStart >= itemCount) return null
  val rowLength = minOf(columns, itemCount - rowStart)
  return rowStart + minOf(currentColumn, rowLength - 1)
}

/**
 * Numero ESATTO di colonne letto dal layout reale della griglia.
 *
 * Un item delle righe successive alla prima dichiara riga e colonna: `index - column` è
 * l'inizio della sua riga, quindi `(index - column) / row` è il numero di colonne. Quando è
 * composta solo la prima riga, la si conta direttamente (occupa l'intera larghezza).
 * `null` se il layout non espone ancora riga/colonna per nessun item.
 */
internal fun LazyGridState.exactColumnCount(): Int? {
  val visible = layoutInfo.visibleItemsInfo.filter { it.row >= 0 && it.column >= 0 }
  if (visible.isEmpty()) return null
  visible.firstOrNull { it.row > 0 && it.index - it.column > 0 }?.let { info ->
    return (info.index - info.column) / info.row
  }
  return visible.filter { it.row == 0 }.maxOfOrNull { it.column + 1 }
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
  resultsOverride: List<MediaItem>? = null,
  /**
   * Contenuti espliciti della categoria selezionata, con lo stesso scopo di [resultsOverride]:
   * i test della griglia categoria (navigazione verticale e ripristino dal Dettaglio) non
   * possono passare dalla rete. In produzione resta `null` e la schermata osserva
   * [StreamNovaViewModel.categoryItems].
   */
  categoryItemsOverride: List<MediaItem>? = null
) {
  val query by viewModel.searchQuery.collectAsState()
  // Contesto applicativo al ViewModel: i messaggi della categoria sono risorse
  // localizzate risolte nel ViewModel (idempotente, nessun leak).
  val appContext = LocalContext.current
  LaunchedEffect(viewModel) { viewModel.attachContext(appContext) }
  val results = if (resultsOverride != null) {
    resultsOverride
  } else {
    viewModel.filteredSearchResults.collectAsState().value
  }
  val searchFilter by viewModel.searchFilter.collectAsState()
  val categoryItems = categoryItemsOverride
    ?: viewModel.categoryItems.collectAsState().value
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

  // Stato di scroll della griglia dei risultati, PERSISTENTE attraverso la navigazione verso
  // il Dettaglio: al BACK la griglia ritrova la posizione da cui si era entrati, quindi la
  // card ripristinata resta visibile nella stessa riga.
  val gridState = rememberSaveable(saver = LazyGridState.Saver) { LazyGridState() }

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
  // filtri in modalità categoria legge qui il viewport reale della griglia. Saveable come la
  // griglia dei risultati: al BACK dal Dettaglio la sezione ritrova la posizione di scroll.
  val categoryGridState = rememberSaveable(saver = LazyGridState.Saver) { LazyGridState() }

  val searchFocusScope = rememberCoroutineScope()

  // Passo verticale tra le righe della griglia (16dp): serve alla navigazione verticale per
  // far ENTRARE la riga di destinazione dal bordo del viewport quando non è ancora composta.
  val gridRowSpacingPx = with(LocalDensity.current) { 16.dp.toPx() }

  // Stessa struttura per la griglia della CATEGORIA selezionata: la chiave replica la key
  // della griglia (una film e una serie possono condividere lo stesso id grezzo).
  val categoryResultFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }

  fun categoryResultFocusRequester(cat: GlobalSearchCategory, media: MediaItem): FocusRequester =
    categoryResultFocusRequesters.getOrPut(searchCategoryGridItemKey(cat, media)) {
      FocusRequester()
    }

  // ── Ripristino del focus al ritorno dal Dettaglio ─────────────────────────────────────
  // Canali condivisi con le altre schermate (MainActivity/HomeScreen): target salvato al
  // click, token dell'evento (riattiva il tentativo su ritorni consecutivi dalla STESSA
  // card) e rilascio del target quando il focus è DAVVERO sulla card.
  //
  // Il vecchio percorso ascoltava un SharedFlow e chiedeva il focus a un requester FISSO
  // (quello della cella 0 dei badge, non agganciato alle card): dopo un'attesa arbitraria
  // il focus atterrava sui chip Film/Serie TV. Qui il ripristino è per IDENTITÀ della card,
  // sulla griglia del contesto corrente (categoria o risultati della query).
  val restoreMediaId = LocalLastFocusedMediaId.current
  val focusRestoreToken = LocalFocusRestoreToken.current
  val clearLastFocusedMedia = LocalClearLastFocusedMedia.current

  // Card che QUESTA schermata ha aperto (salvata al click). Distingue una VERA richiesta di
  // ripristino (si torna dal Dettaglio di una card della Ricerca) da un target lasciato da
  // un'altra schermata o andato a vuoto: il secondo va solo rilasciato, senza spostare il
  // focus, perché la schermata lo sta gestendo con il proprio ingresso naturale (barra,
  // filtri). Saveable: sopravvive allo smontaggio della destinazione durante il Dettaglio.
  var claimedRestoreId by rememberSaveable { mutableStateOf<String?>(null) }
  val pendingRestoreForThisScreen = restoreMediaId != null && restoreMediaId == claimedRestoreId

  // Card REALMENTE focalizzata in ciascuna griglia, espressa con la STESSA key usata dal
  // lazy grid. La navigazione verticale risolve l'indice sulla lista corrente, quindi un
  // filtro o un riordino non fanno atterrare il movimento sulla colonna sbagliata.
  var focusedResultKey by remember { mutableStateOf<String?>(null) }
  var focusedCategoryKey by remember { mutableStateOf<String?>(null) }

  // Requester dell'ancora di recupero di un ripristino fallito. Vive in un effetto dedicato
  // perché il rilascio del target (che apre il gate `canFocus` delle card) riavvia l'effetto
  // di ripristino e ne cancellerebbe il corpo.
  var recoveryFocusRequester by remember { mutableStateOf<FocusRequester?>(null) }
  LaunchedEffect(recoveryFocusRequester) {
    val requester = recoveryFocusRequester ?: return@LaunchedEffect
    recoveryFocusRequester = null
    requestFocusWhenAttached(requester, maxAttempts = 5)
  }

  /**
   * UNICO punto di ingresso del ripristino dal Dettaglio: risolve la card per identità nella
   * griglia del contesto corrente, la porta nel viewport se non è composta e le chiede il
   * focus. Se la card non esiste più, il target viene rilasciato e il focus va alla prima
   * card GIÀ visibile della stessa sezione; i chip dei filtri sono l'ultima risorsa, usata
   * solo quando non esiste alcuna card su cui atterrare.
   */
  LaunchedEffect(
    restoreMediaId,
    focusRestoreToken,
    selectedCategory,
    searchFilter,
    displayedCategoryMedia,
    results
  ) {
    val mediaId = restoreMediaId ?: return@LaunchedEffect
    if (mediaId != claimedRestoreId) {
      // Target di un'altra schermata (o già andato a vuoto): va rilasciato subito, così i
      // cancelletti `canFocus` delle card si riaprono, ma senza sottrarre il focus
      // all'ingresso naturale della schermata (barra di ricerca / filtri).
      if (BuildConfig.DEBUG) {
        Log.d(
          SearchFocusDiagnostics.TAG,
          "stale restore target '$mediaId' not claimed by search screen: released"
        )
      }
      clearLastFocusedMedia?.invoke()
      return@LaunchedEffect
    }
    val cat = selectedCategory
    val items = if (cat != null) displayedCategoryMedia else results
    val grid = if (cat != null) categoryGridState else gridState
    val index = items.indexOfFirst { it.id == mediaId }
    if (index < 0) {
      // Target non più disponibile (catalogo cambiato mentre il Dettaglio era aperto):
      // nessuno scroll, nessuna card indovinata per posizione e nessun salto ai badge se
      // esiste una card composta. Il rilascio del target riapre i cancelletti `canFocus`.
      if (BuildConfig.DEBUG) {
        Log.d(
          SearchFocusDiagnostics.TAG,
          "stale restore target '$mediaId' absent from current list: cleared"
        )
      }
      var frames = 0
      var firstVisibleIndex = grid.layoutInfo.visibleItemsInfo.firstOrNull()?.index
      while (firstVisibleIndex == null && frames < 8) {
        withFrameNanos { }
        frames++
        firstVisibleIndex = grid.layoutInfo.visibleItemsInfo.firstOrNull()?.index
      }
      val anchor = firstVisibleIndex?.let { items.getOrNull(it) }
      recoveryFocusRequester = when {
        anchor != null && cat != null -> categoryResultFocusRequester(cat, anchor)
        anchor != null -> resultFocusRequester(anchor.id)
        else -> activeFilterRequester
      }
      clearLastFocusedMedia?.invoke()
      return@LaunchedEffect
    }
    // La visibilità del target si valuta solo su una griglia GIÀ misurata (almeno una cella
    // in composizione): finché `visibleItemsInfo` è vuoto ogni verdetto sarebbe falso.
    var measuredFrames = 0
    while (grid.layoutInfo.visibleItemsInfo.isEmpty() && measuredFrames < 8) {
      withFrameNanos { }
      measuredFrames++
    }
    val targetVisible = grid.layoutInfo.visibleItemsInfo.any { it.index == index }
    if (!targetVisible && !grid.isScrollInProgress) {
      try {
        grid.scrollToItemVerified(index)
      } catch (_: Exception) {
        // La richiesta di focus sotto attende comunque il montaggio della card.
      }
    }
    requestFocusWhenAttached(
      if (cat != null) {
        categoryResultFocusRequester(cat, items[index])
      } else {
        resultFocusRequester(items[index].id)
      },
      maxAttempts = 5
    )
  }

  /**
   * Navigazione verticale ESPLICITA delle griglie della Ricerca.
   *
   * Il focus system di Compose, quando la riga successiva non è ancora composta (viewport TV
   * alto quanto una riga), scorre la griglia e consegna il focus alla PRIMA cella composta
   * della nuova riga: con la terza card selezionata il Giù "tornava" alla prima colonna. Qui
   * la cella di destinazione è calcolata dalla riga/colonna REALI del layout (colonna
   * preservata, in una riga più corta si sceglie la colonna valida più vicina), la riga viene
   * portata in composizione e solo allora si chiede il focus a QUELLA card.
   *
   * @return `true` se il tasto è stato gestito (e consumato); `false` quando non esiste una
   *   destinazione verticale valida: in quel caso decide il focus system (es. Su dalla prima
   *   riga verso la riga dei filtri).
   */
  fun moveGridFocusVertically(
    event: androidx.compose.ui.input.key.KeyEvent,
    grid: LazyGridState,
    items: List<MediaItem>,
    keyOf: (MediaItem) -> String,
    focusedKey: String?,
    requesterOf: (MediaItem) -> FocusRequester
  ): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val direction = when (event.key) {
      Key.DirectionDown -> SearchGridVerticalDirection.DOWN
      Key.DirectionUp -> SearchGridVerticalDirection.UP
      else -> return false
    }
    val currentIndex = items.indexOfFirst { keyOf(it) == focusedKey }
    if (currentIndex < 0) return false
    val current = grid.layoutInfo.visibleItemsInfo.firstOrNull { it.index == currentIndex }
      ?: return false
    val columns = grid.exactColumnCount() ?: return false
    val targetIndex = searchGridVerticalTargetIndex(
      currentRow = current.row,
      currentColumn = current.column,
      itemCount = items.size,
      columns = columns,
      direction = direction
    ) ?: return false
    val targetRequester = requesterOf(items[targetIndex])
    searchFocusScope.launch {
      if (grid.layoutInfo.visibleItemsInfo.none { it.index == targetIndex }) {
        // La riga di destinazione non è composta: la si fa ENTRARE dal bordo del viewport
        // (mezza riga) invece di incollarla in alto con `scrollToItem`. Così resta composta
        // insieme a quella corrente (nessun frame senza focus) e il bring-into-view
        // condiviso può centrarla esattamente come per il movimento nativo.
        val halfRowPx = (current.size.height + gridRowSpacingPx) / 2f
        val delta = if (direction == SearchGridVerticalDirection.DOWN) halfRowPx else -halfRowPx
        try {
          grid.scrollBy(delta)
        } catch (_: Exception) {
          // La richiesta di focus sotto attende comunque il montaggio della card.
        }
      }
      requestFocusWhenAttached(targetRequester, maxAttempts = 8)
    }
    return true
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

  val focusManager = LocalFocusManager.current
  // Prova REALE che il focus è entrato nell'area contenuti: `hasFocus` del contenitore
  // (nella Box qui sotto) diventa true solo quando una sua discendente — cioè una card
  // dei risultati — è effettivamente a fuoco. Serve alla gestione del Giù dai filtri per
  // NON credere ciecamente all'esito di requestFocus(): un nodo agganciato ma non
  // focalizzabile (gate di ripristino chiuso) rifiuta la richiesta in silenzio.
  var contentAreaHasFocus by remember { mutableStateOf(false) }

  // NOTA: il rilascio di un target STANTIO (id non più nella lista corrente — tipicamente
  // l'ancora sintetica "home_content_anchor" scritta dalla MainActivity al rientro dal
  // Dettaglio) è gestito dall'effetto di ripristino qui sopra, nello STESSO corpo che assegna
  // l'ancora di recupero. Un effetto separato che chiamasse clearLastFocusedMedia() poteva
  // cancellare l'effetto di ripristino a metà, lasciando la schermata senza focus.

  LaunchedEffect(savedCategoryId) {
    // Al rientro dal Dettaglio il focus appartiene al ripristino della card (effetto sopra):
    // portarlo qui sui badge sarebbe proprio il difetto "il focus finisce su Film/Serie TV".
    if (pendingRestoreForThisScreen) return@LaunchedEffect
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
    // Stessa guardia del ripristino: al ritorno dal Dettaglio il focus deve andare alla card
    // salvata, non ai chip dei filtri.
    if (pendingRestoreForThisScreen) return@LaunchedEffect
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
    // Al rientro dal Dettaglio il focus appartiene al ripristino della card: la barra di
    // ricerca non deve sottrarlo (difetto: il focus non tornava sulla card dei risultati).
    if (pendingRestoreForThisScreen) return@LaunchedEffect
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
                  // Finché lo stato non è determinato (caricamento) non si annuncia
                  // alcun catalogo addon: evita "Catalogo addon: X" con 0 titoli.
                  text = when (categoryState.source) {
                    SearchCategorySource.ADDON -> stringResource(
                      R.string.search_category_desc_addon,
                      categoryState.addonLabel.orEmpty()
                    )
                    SearchCategorySource.TMDB_FALLBACK -> stringResource(
                      R.string.search_category_desc_fallback,
                      stringResource(cat.labelRes)
                    )
                    // Categoria nativa TMDB ("Talk"): fonte TMDB dichiarata, non un
                    // fallback dovuto alla mancanza di un catalogo addon.
                    SearchCategorySource.TMDB -> stringResource(
                      R.string.search_category_desc_tmdb,
                      stringResource(cat.labelRes)
                    )
                    null -> if (isCategoryLoading) {
                      if (cat.contentFilter == SearchCategoryFilter.TMDB_TV_GENRE) {
                        stringResource(R.string.search_category_loading_tmdb)
                      } else {
                        stringResource(R.string.search_category_loading)
                      }
                    } else {
                      ""
                    }
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
                    // Su/Giù espliciti: mantengono la COLONNA corrente anche quando la riga
                    // successiva non è ancora composta (viewport TV alto quanto una riga).
                    modifier = Modifier
                      .fillMaxSize()
                      .onPreviewKeyEvent { event ->
                        moveGridFocusVertically(
                          event = event,
                          grid = categoryGridState,
                          items = displayedCategoryMedia,
                          keyOf = { searchCategoryGridItemKey(cat, it) },
                          focusedKey = focusedCategoryKey,
                          requesterOf = { categoryResultFocusRequester(cat, it) }
                        )
                      }
                  ) {
                    // La key include il tipo: film e serie possono condividere lo stesso
                    // id grezzo senza collidere (una key duplicata crasherebbe la griglia).
                    itemsIndexed(displayedCategoryMedia, key = { _, item -> searchCategoryGridItemKey(cat, item) }, contentType = { _, _ -> "media_poster" }) { index, item ->
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
                          onClick = {
                            // Target di ripristino PRIMA del Dettaglio: al BACK il focus
                            // torna su QUESTA card (identità, non posizione). La "claim"
                            // distingue questo ripristino dai target di altre schermate.
                            claimedRestoreId = item.id
                            viewModel.saveLastFocusedMedia(FOCUS_ROW_ANY, item.id)
                            onMediaClick(item)
                          },
                          // Requester per identità: è il nodo focalizzabile REALE della
                          // card, target del Giù dai filtri in modalità categoria. Le
                          // celle fuori viewport non sono composte: nessun requester
                          // fisso sulla cella 0.
                          focusRequester = categoryResultFocusRequester(cat, item),
                          // Protocollo con CONFERMA reale del focus (tentativi a frame):
                          // al rientro dal Dettaglio la card può non essere ancora pronta
                          // quando arriva la richiesta.
                          confirmFocusRestore = true,
                          modifier = Modifier.onFocusChanged { state ->
                            if (state.isFocused) {
                              focusedCategoryKey = searchCategoryGridItemKey(cat, item)
                            }
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
                    // Su/Giù espliciti: mantengono la COLONNA corrente anche quando la riga
                    // successiva non è ancora composta (viewport TV alto quanto una riga).
                    modifier = Modifier
                      .fillMaxSize()
                      .onPreviewKeyEvent { event ->
                        moveGridFocusVertically(
                          event = event,
                          grid = gridState,
                          items = results,
                          keyOf = { searchResultGridItemKey(it) },
                          focusedKey = focusedResultKey,
                          requesterOf = { resultFocusRequester(it.id) }
                        )
                      }
                  ) {
                    itemsIndexed(results, key = { _, item -> searchResultGridItemKey(item) }, contentType = { _, _ -> "media_poster" }) { index, item ->
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
                          onClick = {
                            // Target di ripristino PRIMA del Dettaglio: al BACK il focus
                            // torna su QUESTA card (identità, non posizione). La "claim"
                            // distingue questo ripristino dai target di altre schermate.
                            claimedRestoreId = item.id
                            viewModel.saveLastFocusedMedia(FOCUS_ROW_ANY, item.id)
                            onMediaClick(item)
                          },
                          // Requester per identità: è il nodo focalizzabile REALE della
                          // card (non un contenitore esterno), quindi il Giù dai filtri
                          // consegna il focus a una cella composta qualunque sia lo
                          // scroll. La Box-cella NON è un target di focus: agganciarci il
                          // requester produce richieste rifiute invisibili.
                          focusRequester = resultFocusRequester(item.id),
                          // Protocollo con CONFERMA reale del focus (tentativi a frame):
                          // al rientro dal Dettaglio la card può non essere ancora pronta
                          // quando arriva la richiesta.
                          confirmFocusRestore = true,
                          modifier = Modifier.onFocusChanged { state ->
                            if (state.isFocused) {
                              focusedResultKey = searchResultGridItemKey(item)
                            }
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
                      tvGenreId = cat.tvGenreId,
                      contentFilter = cat.contentFilter
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
  // Chiave/OpenSubtitles: la chiave è un dato condiviso con il client (AppSettingsRepository),
  // lo stato di verifica vive nel ViewModel (con fallback "sconosciuto" fuori dall'app reale).
  val openSubtitlesApiKey = viewModel?.openSubtitlesApiKey?.collectAsState()?.value
    ?: AppSettingsRepository.openSubtitlesApiKey.collectAsState().value
  val openSubtitlesConnectionState = viewModel?.openSubtitlesConnectionState?.collectAsState()?.value
    ?: OpenSubtitlesConnectionState.Unknown
  val streamingEngineMode = AppSettingsRepository.streamingEngineMode.collectAsState().value ?: StreamingEngineMode.HTTP_WEB
  val autoplayEnabled = viewModel?.autoplayEnabled?.collectAsState()?.value ?: false
  val tmdbGenreFallbackEnabled = viewModel?.tmdbGenreFallbackEnabled?.collectAsState()?.value ?: true
  val hideUnreleasedContent = viewModel?.hideUnreleasedContent?.collectAsState()?.value
    ?: AppSettingsRepository.hideUnreleasedContent.collectAsState().value
  // Sezione «Continua a guardare»: stessa sorgente di verità della riga Home.
  val continueWatchingPreferences = viewModel?.continueWatchingPreferences?.collectAsState()?.value
    ?: AppSettingsRepository.continueWatchingPreferences.collectAsState().value
  // Contesto al ViewModel: i messaggi della sezione Ricerca sono risorse localizzate
  // risolte lì (fuori dalla composizione). Operazione idempotente, nessun leak.
  LaunchedEffect(viewModel) { viewModel?.attachContext(context) }
  val appLanguage = viewModel?.appLanguage?.collectAsState()?.value ?: "it"
  val parentalControlEnabled = viewModel?.parentalControlEnabled?.collectAsState()?.value ?: false
  val parentalControlLevel = viewModel?.parentalControlLevel?.collectAsState()?.value ?: "18"
  val updateCheckState = viewModel?.updateCheckState?.collectAsState()?.value ?: UpdateCheckState.Idle
  val updateDownloadState = viewModel?.updateDownloadState?.collectAsState()?.value ?: UpdateDownloadState.Idle
  var showTorBoxKeyDialog by remember { mutableStateOf(false) }
  var showOpenSubtitlesKeyDialog by remember { mutableStateOf(false) }
  // Ripristino del focus sulle righe che aprono i popup chiave API: al BACK (o
  // Salva/Annulla) il focus torna qui invece di perdersi sulla schermata.
  val torBoxKeyRowFocusRequester = remember { FocusRequester() }
  val openSubtitlesKeyRowFocusRequester = remember { FocusRequester() }
  var restoreTorBoxKeyRowFocus by remember { mutableStateOf(false) }
  var restoreOpenSubtitlesKeyRowFocus by remember { mutableStateOf(false) }
  var showResolutionDialog by remember { mutableStateOf(false) }
  var showSubtitleLanguageDialog by remember { mutableStateOf(false) }
  var showSubtitleSizeDialog by remember { mutableStateOf(false) }
  var showSubtitleBackgroundDialog by remember { mutableStateOf(false) }
  var showSubtitleOpacityDialog by remember { mutableStateOf(false) }
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
  // Sezione «Continua a guardare»: aperta di default (come nelle immagini di
  // riferimento); lo stato vive solo nella composizione, le preferenze interne sono
  // persistite singolarmente.
  var continueWatchingExpanded by remember { mutableStateOf(true) }
  var showContinueWatchingSortDialog by remember { mutableStateOf(false) }
  val simklAccount by SimklRepository.accountState.collectAsState()
  val simklSyncResult by SimklRepository.lastSyncResult.collectAsState()
  val simklSyncing by SimklRepository.isSyncing.collectAsState()
  val simklScope = rememberCoroutineScope()
  var simklNotice by remember { mutableStateOf<String?>(null) }
  var simklLastSyncAt by remember { mutableStateOf(SimklRepository.lastSyncAtEpochMs()) }

  // Ciclo di vita del login SIMKL: richiesta del codice, QR nel modale, polling fino
  // all'approvazione, annullamento. Il Client ID è una configurazione di build (vedi
  // `.env.example`): non c'è nulla da chiedere all'utente in questa schermata.
  val simklLoginController = remember(simklScope) {
    SimklLoginController(
      requestPrompt = { SimklRepository.startLogin() },
      awaitAuthorization = { prompt -> SimklRepository.awaitLogin(prompt) },
      // La sincronizzazione è il motivo per cui l'account viene collegato: parte subito
      // dopo il login, e un suo errore non trasforma il login in un fallimento.
      onAuthorized = { SimklSyncManager.sync(force = true) },
      scope = simklScope
    )
  }
  val simklLoginState by simklLoginController.state.collectAsState()

  // Esito del login: avviso in schermata (un errore di sincronizzazione non lo cambia).
  LaunchedEffect(simklLoginState.account) {
    val state = simklLoginState.account ?: return@LaunchedEffect
    simklNotice = buildString {
      append("Account collegato")
      state.username?.let { append(": $it") }
      append(".")
      if (state.isReadOnly) {
        append(" Accesso in SOLA LETTURA: ricollega per abilitare lo scrobbling.")
      }
    }
    simklLoginController.clearResult()
  }

  // "Ultima sincronizzazione" segue ogni pull riuscito, compreso quello dopo il login.
  LaunchedEffect(simklSyncResult) {
    simklLastSyncAt = SimklRepository.lastSyncAtEpochMs()
  }

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

  LaunchedEffect(restoreOpenSubtitlesKeyRowFocus) {
    if (!restoreOpenSubtitlesKeyRowFocus) return@LaunchedEffect
    restoreOpenSubtitlesKeyRowFocus = false
    withFrameNanos { }
    try {
      openSubtitlesKeyRowFocusRequester.requestFocus()
    } catch (_: Exception) {
    }
  }

  // Quando il popup della chiave API TorBox si chiude (BACK, Salva o Annulla) il focus torna
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

      // ── PROFILI ──────────────────────────────────────────────────────────
      // Ogni profilo ha la propria libreria (preferiti, progressi, cronologia) e il
      // proprio account SIMKL; le impostazioni del televisore restano condivise.
      // La sezione ospita anche i dialoghi di gestione (finestra separata, non vincolata
      // allo scroll di questa lista).
      item { ProfilesSection() }

      // ── ACCOUNT SIMKL ────────────────────────────────────────────────────
      // Nessun campo per il Client ID: è una configurazione dello sviluppatore che arriva
      // con la build. Qui l'utente trova solo "Collega account SIMKL" e la sincronizzazione.
      item {
        SettingsSection(title = "Account SIMKL") {
          SettingsRow(
            icon = Icons.Default.CloudDone,
            title = if (simklAccount.isLoggedIn) "Account collegato" else "Collega account SIMKL",
            value = when {
              simklAccount.isLoggedIn -> simklAccount.username ?: "Collegato"
              simklLoginState.waiting -> "In attesa di autorizzazione…"
              !SimklRepository.hasClientId() -> "Non disponibile"
              else -> "Non collegato"
            },
            valueColor = when {
              simklAccount.isLoggedIn && simklAccount.sessionExpired -> NovaOrange
              simklAccount.isLoggedIn -> NovaGreen
              !SimklRepository.hasClientId() -> NovaTextSecondary
              else -> NovaCyanBright
            },
            onClick = {
              if (simklAccount.isLoggedIn) {
                simklScope.launch {
                  SimklRepository.signOut()
                  simklNotice = "Account SIMKL scollegato."
                  simklLastSyncAt = null
                }
              } else {
                simklLoginController.start()
              }
            }
          )
          if (simklAccount.isLoggedIn) {
            // Il login con QR lascia sul server un refresh token: la sincronizzazione è la
            // ragione per cui l'account è collegato, quindi ha una riga dedicata.
            SettingsRow(
              icon = Icons.Default.Sync,
              title = "Sincronizza ora",
              value = when {
                simklSyncing -> "Sincronizzazione in corso…"
                simklLastSyncAt != null -> "Ultima: ${formatSyncTimestamp(simklLastSyncAt!!)}"
                else -> "Mai eseguita"
              },
              valueColor = if (simklSyncing) NovaCyanBright else NovaTextSecondary,
              onClick = {
                if (!simklSyncing) {
                  simklScope.launch {
                    val result = SimklSyncManager.sync(force = true)
                    simklLastSyncAt = result.finishedAtEpochMs
                    simklNotice = result.message
                  }
                }
              }
            )
          }
          val simklWarning = when {
            !simklAccount.isLoggedIn -> null
            simklAccount.sessionExpired ->
              "Sessione SIMKL scaduta: seleziona \"Account collegato\" per scollegare e ricollegare l'account."
            simklAccount.isReadOnly ->
              "Autorizzazione di sola lettura: lo scrobbling e le scritture su SIMKL non funzioneranno. " +
                "Ricollega l'account per concedere l'accesso in scrittura."
            else -> null
          }
          simklWarning?.let { note ->
            Text(
              note,
              color = NovaOrange,
              fontSize = 12.sp,
              modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            )
          }
          simklSyncResult?.takeIf { simklNotice == null }?.let { result ->
            // Esito dell'ultima sincronizzazione automatica (all'avvio/alla connessione).
            // PARTIAL è un esito NON riuscito (una sezione non è stata scaricata): stesso
            // colore di avviso di un errore, nessuna ambiguità con "tutto a posto".
            if (result.status != SimklSyncStatus.UP_TO_DATE || result.adoptedTotal > 0 || result.watchlistImported > 0) {
              Text(
                "Ultima sincronizzazione: ${result.message}",
                color = if (result.status == SimklSyncStatus.FAILED ||
                  result.status == SimklSyncStatus.SESSION_EXPIRED ||
                  result.status == SimklSyncStatus.PARTIAL
                ) NovaOrange else NovaTextSecondary,
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
              )
            }
          }
          simklNotice?.let {
            Text(
              it,
              color = if (simklAccount.isLoggedIn) NovaGreen else NovaOrange,
              fontSize = 13.sp,
              modifier = Modifier.padding(16.dp)
            )
          }
        }
      }

      // ── SINCRONIZZAZIONE (modalità Sync) ─────────────────────────────────
      // Replica la configurazione completa su altri dispositivi StreamNova della
      // stessa rete. Tutta la logica e i dialog vivono in SyncSettings.kt: qui
      // resta solo l'aggancio alla lista delle sezioni.
      item { SyncSettingsSection() }

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
            SettingsSwitchRow(
              title = stringResource(R.string.settings_use_torbox_instant),
              checked = torBoxEnabled,
              onCheckedChange = { viewModel?.setTorBoxInstantDebridEnabled(it) }
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

      // ── HOME ────────────────────────────────────────────────────────────
      // Subito dopo Motore di Riproduzione e prima di Ricerca e cataloghi:
      // l'opzione riguarda ciò che la Home mostra, non la riproduzione né la ricerca.
      item {
        SettingsSection(title = stringResource(R.string.settings_home_section)) {
          SettingsSwitchRow(
            title = stringResource(R.string.settings_hide_unreleased),
            subtitle = stringResource(R.string.settings_hide_unreleased_desc),
            checked = hideUnreleasedContent,
            onCheckedChange = { viewModel?.setHideUnreleasedContent(it) }
          )
        }
      }

      // ── CONTINUA A GUARDARE ─────────────────────────────────────────────
      // Sezione espandibile dedicata alla riga omonima della Home. Tutte le opzioni
      // modificano la presentazione della riga (visibilità, layout, immagine,
      // sfocatura, ripresa, episodi futuri, ordinamento) tramite un unico policy
      // condiviso; qui vive solo la UI delle preferenze.
      item {
        SettingsExpandableSection(
          title = stringResource(R.string.settings_cw_section_title),
          subtitle = stringResource(R.string.settings_cw_section_desc),
          expanded = continueWatchingExpanded,
          expandedLabel = stringResource(R.string.settings_cw_expanded),
          collapsedLabel = stringResource(R.string.settings_cw_collapsed),
          onToggle = { continueWatchingExpanded = !continueWatchingExpanded }
        ) {
          SettingsSwitchRow(
            title = stringResource(R.string.settings_cw_show),
            subtitle = stringResource(R.string.settings_cw_show_desc),
            checked = continueWatchingPreferences.showRow,
            onCheckedChange = { viewModel?.setContinueWatchingShowRow(it) }
          )
          SettingsContinueWatchingLayoutSelector(
            title = stringResource(R.string.settings_cw_layout),
            selected = continueWatchingPreferences.layout,
            onSelect = { layout -> viewModel?.setContinueWatchingLayout(layout) }
          )
          SettingsSwitchRow(
            title = stringResource(R.string.settings_cw_episode_thumbnails),
            subtitle = stringResource(R.string.settings_cw_episode_thumbnails_desc),
            checked = continueWatchingPreferences.useEpisodeThumbnails,
            onCheckedChange = { viewModel?.setContinueWatchingEpisodeThumbnails(it) }
          )
          SettingsSwitchRow(
            title = stringResource(R.string.settings_cw_blur_unwatched),
            subtitle = stringResource(R.string.settings_cw_blur_unwatched_desc),
            checked = continueWatchingPreferences.blurUnwatched,
            onCheckedChange = { viewModel?.setContinueWatchingBlurUnwatched(it) }
          )
          SettingsSwitchRow(
            title = stringResource(R.string.settings_cw_next_from_furthest),
            subtitle = stringResource(R.string.settings_cw_next_from_furthest_desc),
            checked = continueWatchingPreferences.nextEpisodeFromFurthest,
            onCheckedChange = { viewModel?.setContinueWatchingNextEpisodeFromFurthest(it) }
          )
          SettingsSwitchRow(
            title = stringResource(R.string.settings_cw_show_unaired),
            subtitle = stringResource(R.string.settings_cw_show_unaired_desc),
            checked = continueWatchingPreferences.showUnairedEpisodes,
            onCheckedChange = { viewModel?.setContinueWatchingShowUnairedEpisodes(it) }
          )
          SettingsRow(
            icon = Icons.AutoMirrored.Filled.Sort,
            title = stringResource(R.string.settings_cw_sort),
            value = continueWatchingSortLabel(continueWatchingPreferences.sort),
            onClick = { showContinueWatchingSortDialog = true },
            showChevron = true
          )
        }
      }

      // ── RICERCA E CATALOGHI ─────────────────────────────────────────────
      // Lo switch più vicino alla sua area di effetto: la sezione Ricerca e i
      // cataloghi. La descrizione vive dentro la riga, sotto il titolo.
      item {
        SettingsSection(title = stringResource(R.string.settings_search_section)) {
          SettingsSwitchRow(
            title = stringResource(R.string.settings_tmdb_genre_fallback),
            subtitle = stringResource(R.string.settings_tmdb_genre_fallback_desc),
            checked = tmdbGenreFallbackEnabled,
            onCheckedChange = { viewModel?.setTmdbGenreFallbackEnabled(it) }
          )
        }
      }

      // ── RIPRODUZIONE ────────────────────────────────────────────────────
      item {
        SettingsSection(title = stringResource(R.string.settings_sec_playback)) {
          SettingsSwitchRow(
            title = stringResource(R.string.settings_autoplay),
            checked = autoplayEnabled,
            onCheckedChange = { viewModel?.setAutoplayEnabled(it) }
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
          SettingsSwitchRow(
            title = stringResource(R.string.settings_prioritize_italian_audio),
            subtitle = stringResource(R.string.settings_prioritize_italian_audio_desc),
            checked = playbackSettings.prioritizeItalianAudio,
            onCheckedChange = { viewModel?.setPrioritizeItalianAudio(it) }
          )
          SettingsSwitchRow(
            title = stringResource(R.string.settings_autoplay_next_episode),
            checked = playbackSettings.autoPlayNextEpisodeEnabled,
            onCheckedChange = { viewModel?.setAutoPlayNextEpisode(it) }
          )
          SettingsSwitchRow(
            title = stringResource(R.string.settings_auto_resume),
            checked = playbackSettings.autoResume,
            onCheckedChange = { viewModel?.setAutoResume(it) }
          )
          // Overlay e avvisi di riproduzione: quattro opzioni indipendenti tra loro.
          SettingsSwitchRow(
            title = stringResource(R.string.settings_loading_overlay),
            subtitle = stringResource(R.string.settings_loading_overlay_desc),
            checked = playbackSettings.loadingOverlayEnabled,
            onCheckedChange = { viewModel?.setLoadingOverlayEnabled(it) }
          )
          SettingsSwitchRow(
            title = stringResource(R.string.settings_loading_status),
            subtitle = stringResource(R.string.settings_loading_status_desc),
            checked = playbackSettings.loadingStatusEnabled,
            onCheckedChange = { viewModel?.setLoadingStatusEnabled(it) }
          )
          SettingsSwitchRow(
            title = stringResource(R.string.settings_pause_overlay),
            subtitle = stringResource(R.string.settings_pause_overlay_desc),
            checked = playbackSettings.pauseOverlayEnabled,
            onCheckedChange = { viewModel?.setPauseOverlayEnabled(it) }
          )
          SettingsSwitchRow(
            title = stringResource(R.string.settings_parental_guide),
            subtitle = stringResource(R.string.settings_parental_guide_desc),
            checked = playbackSettings.parentalGuideEnabled,
            onCheckedChange = { viewModel?.setParentalGuideEnabled(it) }
          )
        }
      }

      // ── SELEZIONE FLUSSO ────────────────────────────────────────────────
      item {
        SettingsSection(title = stringResource(R.string.settings_sec_stream_selection)) {
          SettingsSwitchRow(
            title = stringResource(R.string.settings_reuse_last_stream),
            subtitle = stringResource(R.string.settings_reuse_last_stream_desc),
            checked = playbackSettings.reuseLastWorkingStreamEnabled,
            onCheckedChange = { viewModel?.setReuseLastWorkingStreamEnabled(it) }
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
          SettingsSwitchRow(
            title = stringResource(R.string.settings_auto_subtitles),
            checked = playbackSettings.subtitlesEnabled,
            onCheckedChange = { viewModel?.setSubtitlesEnabled(it) }
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
            icon = Icons.Default.Opacity,
            title = stringResource(R.string.settings_subtitle_opacity),
            value = subtitleOpacityLabel(playbackSettings.subtitleOpacity),
            onClick = { showSubtitleOpacityDialog = true }
          )
          SettingsRow(
            icon = Icons.Default.VerticalAlignBottom,
            title = stringResource(R.string.settings_subtitle_position),
            value = subtitlePositionLabel(playbackSettings.subtitlePosition),
            onClick = { showSubtitlePositionDialog = true }
          )
          SettingsSwitchRow(
            title = stringResource(R.string.settings_forced_subtitles),
            checked = playbackSettings.forcedSubtitlesEnabled,
            onCheckedChange = { viewModel?.setForcedSubtitlesEnabled(it) }
          )
        }
      }

      // ── OPENSUBTITLES ───────────────────────────────────────────────────
      // Configurazione della chiave API per la ricerca ufficiale OpenSubtitles v3,
      // già usata dal player ("Sottotitoli Online"). La chiave salvata vale per
      // ricerca e download immediatamente (l'interceptor la rilegge a ogni richiesta,
      // nessuna ricompilazione o riavvio). Senza chiave resta attiva l'altra sorgente
      // (addon Stremio), come spiegato nella nota sotto.
      item {
        SettingsSection(title = "OpenSubtitles") {
          SettingsRow(
            icon = Icons.Default.VpnKey,
            title = stringResource(R.string.settings_opensubtitles_api_key),
            value = openSubtitlesApiKey?.let { maskApiKey(it) }
              ?: stringResource(R.string.settings_not_configured),
            valueColor = if (!openSubtitlesApiKey.isNullOrBlank()) NovaGreen else NovaTextMuted,
            onClick = { showOpenSubtitlesKeyDialog = true },
            modifier = Modifier.focusRequester(openSubtitlesKeyRowFocusRequester)
          )
          SettingsRow(
            icon = Icons.Default.CloudDone,
            title = stringResource(R.string.settings_opensubtitles_verify_connection),
            value = when (openSubtitlesConnectionState) {
              OpenSubtitlesConnectionState.Checking -> stringResource(R.string.settings_status_checking)
              OpenSubtitlesConnectionState.Connected -> stringResource(R.string.settings_opensubtitles_connected)
              OpenSubtitlesConnectionState.NotConfigured -> stringResource(R.string.settings_not_configured)
              is OpenSubtitlesConnectionState.Error -> stringResource(R.string.settings_update_error_short)
              OpenSubtitlesConnectionState.Unknown ->
                if (openSubtitlesApiKey.isNullOrBlank()) stringResource(R.string.settings_not_configured) else null
            },
            valueColor = when {
              openSubtitlesConnectionState is OpenSubtitlesConnectionState.Connected -> NovaGreen
              openSubtitlesConnectionState is OpenSubtitlesConnectionState.Error -> NovaRed
              else -> NovaCyanBright
            },
            onClick = { viewModel?.verifyOpenSubtitlesConnection() }
          )
          // Messaggio esplicativo: chiave mancante oppure esito negativo della verifica
          // (motivo tipizzato, mai la chiave). Con lo stato Unknown nessun rumore.
          val osNote = when {
            openSubtitlesApiKey.isNullOrBlank() ->
              stringResource(R.string.settings_opensubtitles_missing_desc)
            openSubtitlesConnectionState is OpenSubtitlesConnectionState.Error &&
              openSubtitlesConnectionState.error == OpenSubtitlesVerifyError.INVALID_KEY ->
              stringResource(R.string.settings_opensubtitles_key_not_valid_desc)
            openSubtitlesConnectionState is OpenSubtitlesConnectionState.Error &&
              openSubtitlesConnectionState.error == OpenSubtitlesVerifyError.NETWORK ->
              stringResource(R.string.settings_opensubtitles_network_error)
            openSubtitlesConnectionState is OpenSubtitlesConnectionState.Error &&
              openSubtitlesConnectionState.error == OpenSubtitlesVerifyError.HTTP ->
              stringResource(R.string.settings_opensubtitles_http_error)
            else -> null
          }
          osNote?.let { note ->
            Text(
              text = note,
              color = NovaTextSecondary,
              fontSize = 12.sp,
              modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
            )
          }
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
          SettingsSwitchRow(
            title = stringResource(R.string.settings_adv_audio_passthrough),
            checked = playbackSettings.audioPassthroughEnabled,
            onCheckedChange = { viewModel?.setAudioPassthroughEnabled(it) }
          )

          // 5. Audio Tunneling
          SettingsSwitchRow(
            title = stringResource(R.string.settings_adv_audio_tunneling),
            checked = playbackSettings.audioTunnelingEnabled,
            onCheckedChange = { viewModel?.setAudioTunnelingEnabled(it) }
          )

          // 6. Decoder Fallback (FFmpeg)
          SettingsRow(
            icon = Icons.Default.Extension,
            title = stringResource(R.string.settings_adv_decoder_fallback),
            value = decoderFallbackLabel(playbackSettings.decoderFallbackMode),
            onClick = { showDecoderFallbackDialog = true }
          )

          // 7. Auto Frame Rate Matching (AFR)
          SettingsSwitchRow(
            title = stringResource(R.string.settings_adv_afr),
            checked = playbackSettings.autoFrameRateMatching,
            onCheckedChange = { viewModel?.setAutoFrameRateMatching(it) }
          )

          // 8. Dolby Vision Fallback
          SettingsSwitchRow(
            title = stringResource(R.string.settings_adv_dolby_vision_fallback),
            checked = playbackSettings.dolbyVisionFallbackEnabled,
            onCheckedChange = { viewModel?.setDolbyVisionFallbackEnabled(it) }
          )

          // 9. Stats for Nerds / Debug Info Overlay
          SettingsSwitchRow(
            title = stringResource(R.string.settings_adv_stats_overlay),
            checked = playbackSettings.debugOverlayEnabled,
            onCheckedChange = { viewModel?.setDebugOverlayEnabled(it) }
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

      // ── INFORMAZIONI ─────────────────────────────────────────────────────
      // Identità dell'app: logo ufficiale (invariato), nome e versione effettiva
      // della build (BuildConfig.VERSION_NAME tramite APP_VERSION_DISPLAY). Il
      // contenuto è di sola lettura: nessun nuovo target del D-pad, il focus
      // scorre oltre come nelle altre sezioni delle Impostazioni.
      item {
        SettingsStaticSection(title = stringResource(R.string.settings_sec_info)) {
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            Image(
              painter = painterResource(R.drawable.streamnova_logo),
              contentDescription = stringResource(R.string.app_name),
              contentScale = ContentScale.FillWidth,
              // Rapporto d'aspetto nativo dell'asset (1006x588): larghezza fissa,
              // altezza derivata, qualità originale del logo preservata.
              modifier = Modifier
                .width(200.dp)
                .aspectRatio(1006f / 588f)
            )
            Text(
              text = stringResource(R.string.app_name),
              color = NovaTextPrimary,
              fontSize = 17.sp,
              fontWeight = FontWeight.SemiBold
            )
            Text(
              text = stringResource(R.string.settings_info_version, appVersion),
              color = NovaTextSecondary,
              fontSize = 14.sp
            )
          }
        }
      }

      // ── RICONOSCIMENTI ───────────────────────────────────────────────────
      // TMDB: logo ufficiale e testi di attribuzione esatti, in inglese in tutte
      // le localizzazioni come richiede l'attribuzione stessa. ExoPlayer: la
      // versione mostrata è quella realmente compilata negli AAR Media3 usati
      // dal progetto (costante MediaLibraryInfo.VERSION, inlined a compile-time).
      item {
        SettingsStaticSection(title = stringResource(R.string.settings_sec_acknowledgements)) {
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            Image(
              painter = painterResource(R.drawable.ic_logo_tmdb),
              contentDescription = "TMDB",
              contentScale = ContentScale.FillWidth,
              // Rapporto d'aspetto nativo dell'asset (144x104).
              modifier = Modifier
                .width(104.dp)
                .aspectRatio(144f / 104f)
            )
            Text(
              text = stringResource(R.string.settings_info_tmdb_attribution),
              color = NovaTextSecondary,
              fontSize = 14.sp,
              lineHeight = 20.sp
            )
            Text(
              text = stringResource(R.string.settings_info_tmdb_url),
              color = NovaCyanBright,
              fontSize = 13.sp
            )
            Text(
              text = stringResource(R.string.settings_info_legal_disclaimer),
              color = NovaTextSecondary,
              fontSize = 14.sp,
              lineHeight = 20.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
              text = "ExoPlayer",
              color = NovaTextPrimary,
              fontSize = 15.sp,
              fontWeight = FontWeight.SemiBold
            )
            Text(
              text = stringResource(R.string.settings_info_version, MediaLibraryInfo.VERSION),
              color = NovaTextSecondary,
              fontSize = 14.sp
            )
          }
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

  // Pannello di login SIMKL: QR + codice temporaneo. Chiuderlo (Annulla o BACK) ferma il
  // polling; "Nuovo codice" lo riavvia senza chiudere il pannello. Viene mostrato anche
  // quando la richiesta del codice fallisce, così l'errore non resta invisibile.
  if (simklLoginState.visible) {
    SimklLoginDialog(
      state = simklLoginState,
      onRetry = { simklLoginController.retry() },
      onDismiss = { simklLoginController.cancel() }
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

  if (showOpenSubtitlesKeyDialog) {
    TextInputDialog(
      title = stringResource(R.string.settings_opensubtitles_api_key),
      hint = stringResource(R.string.settings_opensubtitles_key_hint),
      initialText = openSubtitlesApiKey ?: "",
      confirmLabel = stringResource(R.string.action_save),
      // Modalità segreto: mascherata di default + toggle mostra/nascondi durante la
      // digitazione. La chiave non appare mai in chiaro nella schermata Impostazioni.
      secure = true,
      onConfirm = { key ->
        showOpenSubtitlesKeyDialog = false
        restoreOpenSubtitlesKeyRowFocus = true
        viewModel?.setOpenSubtitlesApiKey(key)
      },
      onDismiss = {
        showOpenSubtitlesKeyDialog = false
        restoreOpenSubtitlesKeyRowFocus = true
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

  if (showSubtitleOpacityDialog) {
    val opacityOptions = SubtitleOpacity.entries
    val opacityLabels = opacityOptions.map { subtitleOpacityLabel(it) }
    TrackSelectionDialog(
      title = stringResource(R.string.settings_subtitle_opacity),
      options = opacityLabels,
      selectedIndex = opacityOptions.indexOf(playbackSettings.subtitleOpacity).coerceAtLeast(0),
      onSelect = { idx ->
        viewModel?.setSubtitleOpacity(opacityOptions[idx])
        showSubtitleOpacityDialog = false
      },
      onDismiss = { showSubtitleOpacityDialog = false }
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

  if (showContinueWatchingSortDialog) {
    // Selettore dell'ordinamento: stesse righe focusabili degli altri dialoghi
    // (TrackSelectionDialog), valore corrente già evidenziato. Ogni voce presente
    // nell'enum è implementata dal policy della riga.
    val sortOptions = ContinueWatchingSort.entries
    val sortLabels = sortOptions.map { continueWatchingSortLabel(it) }
    TrackSelectionDialog(
      title = stringResource(R.string.settings_cw_sort),
      options = sortLabels,
      selectedIndex = sortOptions.indexOf(continueWatchingPreferences.sort).coerceAtLeast(0),
      onSelect = { idx ->
        viewModel?.setContinueWatchingSort(sortOptions[idx])
        showContinueWatchingSortDialog = false
      },
      onDismiss = { showContinueWatchingSortDialog = false }
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

/** Data/ora dell'ultima sincronizzazione SIMKL, nel formato breve del telecomando. */
internal fun formatSyncTimestamp(epochMs: Long): String {
  val format = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.ITALY)
  return format.format(java.util.Date(epochMs))
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
 * Variante statica di [SettingsSection]: stessa intestazione e stessa card, ma la card
 * è un UNICO target del D-pad.
 *
 * Serve per i contenuti di sola lettura a fine lista (Informazioni / Riconoscimenti):
 * senza un nodo focalizzabile la ricerca del focus non trova nulla sotto l'ultima riga
 * azionabile, la LazyColumn non scorre e i contenuti resterebbero per sempre fuori
 * schermo con il solo telecomando. OK non aziona nulla: il target esiste solo per
 * portare la card in vista (bringIntoView) e mostrarne i confini con il bordo ciano,
 * coerente con l'evidenziazione delle altre righe.
 */
@Composable
private fun SettingsStaticSection(
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
    TvFocusableBox(
      shape = RoundedCornerShape(14.dp),
      focusedScale = 1f,
      onClick = {},
      modifier = Modifier.fillMaxWidth()
    ) { isFocused ->
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .background(if (isFocused) NovaCardBgFocused else NovaCardBg)
          .border(1.dp, NovaDivider, RoundedCornerShape(14.dp))
          .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
      ) {
        content()
      }
    }
  }
}

/**
 * Sezione espandibile delle Impostazioni.
 *
 * L'intestazione è un unico target del D-pad (OK apre/chiude); le opzioni interne sono
 * righe focusabili normali. Usa gli stessi colori/card/bordi di [SettingsSection]: la
 * sezione resta nativa StreamNova, senza un tema separato.
 */
@Composable
fun SettingsExpandableSection(
  title: String,
  subtitle: String,
  expanded: Boolean,
  expandedLabel: String,
  collapsedLabel: String,
  onToggle: () -> Unit,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit
) {
  val stateLabel = if (expanded) expandedLabel else collapsedLabel
  Column(modifier = modifier.fillMaxWidth()) {
    TvFocusableBox(
      shape = RoundedCornerShape(12.dp),
      focusedScale = 1.01f,
      onClick = onToggle,
      modifier = Modifier
        .fillMaxWidth()
        .testTag(SETTINGS_CW_SECTION_HEADER_TAG)
        .semantics {
          role = Role.Button
          stateDescription = stateLabel
        }
    ) { isFocused ->
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .background(
            if (isFocused) NovaCardBgFocused else NovaCardBg,
            RoundedCornerShape(12.dp)
          )
          .border(
            1.dp,
            if (isFocused) Color.Transparent else NovaDivider,
            RoundedCornerShape(12.dp)
          )
          .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = title,
            color = NovaTextPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
          Spacer(modifier = Modifier.height(3.dp))
          Text(
            text = subtitle,
            color = NovaTextSecondary,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
          )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
          text = stateLabel,
          color = if (isFocused) NovaCyanBright else NovaTextSecondary,
          fontSize = 12.sp,
          fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.width(6.dp))
        Icon(
          imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
          contentDescription = null,
          tint = if (isFocused) NovaCyanBright else NovaTextMuted,
          modifier = Modifier.size(20.dp)
        )
      }
    }
    if (expanded) {
      Spacer(modifier = Modifier.height(10.dp))
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .background(NovaCardBg.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
          .border(1.dp, NovaDivider, RoundedCornerShape(14.dp))
          .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
      ) {
        content()
      }
    }
  }
}

/** Tag semantico dell'intestazione della sezione «Continua a guardare» (test UI). */
internal const val SETTINGS_CW_SECTION_HEADER_TAG = "settings_cw_section_header"

/** Tag semantico di una card del selettore di layout (Scheda/Larga/Locandina). */
internal fun settingsCwLayoutOptionTestTag(layout: ContinueWatchingLayout): String =
  "settings_cw_layout_option_${layout.name.lowercase()}"

/**
 * Selettore visuale del layout della riga «Continua a guardare»: tre card focusabili con
 * anteprima schematica, bordo di selezione e segno di spunta negli stessi colori ufficiali
 * delle altre Impostazioni. L'intera card è un unico target del D-pad.
 */
@Composable
private fun SettingsContinueWatchingLayoutSelector(
  title: String,
  selected: ContinueWatchingLayout,
  onSelect: (ContinueWatchingLayout) -> Unit,
  modifier: Modifier = Modifier
) {
  val selectedLabel = stringResource(R.string.settings_cw_layout_selected)
  Column(
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = 8.dp, vertical = 4.dp)
  ) {
    Text(
      text = title,
      color = NovaTextPrimary,
      fontSize = 15.sp,
      fontWeight = FontWeight.SemiBold,
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
    )
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
      ContinueWatchingLayout.entries.forEach { layout ->
        val isSelected = layout == selected
        val label = continueWatchingLayoutLabel(layout)
        TvFocusableBox(
          shape = RoundedCornerShape(12.dp),
          focusedScale = 1.02f,
          focusedBorderColor = NovaCyanBright,
          unfocusedBorderColor = if (isSelected) NovaCyan else NovaDivider,
          borderWidth = if (isSelected) 2.dp else 1.dp,
          onClick = { onSelect(layout) },
          modifier = Modifier
            .weight(1f)
            .testTag(settingsCwLayoutOptionTestTag(layout))
            .semantics {
              role = Role.RadioButton
              this.selected = isSelected
              if (isSelected) stateDescription = selectedLabel
            }
        ) { isFocused ->
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .background(
                if (isFocused) NovaCardBgFocused else NovaCardBg.copy(alpha = 0.75f),
                RoundedCornerShape(12.dp)
              )
              .padding(horizontal = 10.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
          ) {
            ContinueWatchingLayoutPreview(layout = layout, highlighted = isSelected)
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
              if (isSelected) {
                Icon(
                  imageVector = Icons.Default.Check,
                  contentDescription = null,
                  tint = NovaCyanBright,
                  modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
              }
              Text(
                text = label,
                color = if (isSelected) NovaCyanBright else NovaTextSecondary,
                fontSize = 12.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1
              )
            }
          }
        }
      }
    }
  }
}

/**
 * Anteprima schematica del layout, disegnata con la palette ufficiale (nessuna immagine
 * o colore preso dai riferimenti): mostra solo la disposizione di immagine e testi.
 */
@Composable
private fun ContinueWatchingLayoutPreview(
  layout: ContinueWatchingLayout,
  highlighted: Boolean
) {
  val accent = if (highlighted) NovaCyanBright else NovaTextMuted
  val block = NovaTextMuted.copy(alpha = 0.55f)
  val shape = RoundedCornerShape(5.dp)
  val base = Modifier
    .background(NovaCardBgFocused, shape)
    .border(1.dp, accent.copy(alpha = 0.55f), shape)

  when (layout) {
    ContinueWatchingLayout.CARD -> Column(
      modifier = Modifier
        .width(78.dp)
        .height(46.dp)
        .then(base)
    ) {
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .weight(1f)
          .background(block)
      )
      Column(modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
        Box(modifier = Modifier.width(42.dp).height(3.dp).background(accent, RoundedCornerShape(2.dp)))
        Spacer(modifier = Modifier.height(3.dp))
        Box(modifier = Modifier.width(30.dp).height(2.dp).background(NovaTextMuted, RoundedCornerShape(2.dp)))
      }
    }

    ContinueWatchingLayout.WIDE -> Row(
      modifier = Modifier
        .width(96.dp)
        .height(46.dp)
        .then(base)
    ) {
      Box(
        modifier = Modifier
          .width(30.dp)
          .fillMaxHeight()
          .background(block)
      )
      Column(
        modifier = Modifier
          .weight(1f)
          .fillMaxHeight()
          .padding(horizontal = 6.dp, vertical = 7.dp)
      ) {
        Box(modifier = Modifier.width(44.dp).height(3.dp).background(accent, RoundedCornerShape(2.dp)))
        Spacer(modifier = Modifier.height(4.dp))
        Box(modifier = Modifier.fillMaxWidth(0.8f).height(2.dp).background(NovaTextMuted, RoundedCornerShape(2.dp)))
        Spacer(modifier = Modifier.height(3.dp))
        Box(modifier = Modifier.fillMaxWidth(0.6f).height(2.dp).background(NovaTextMuted, RoundedCornerShape(2.dp)))
      }
    }

    ContinueWatchingLayout.POSTER -> Box(
      modifier = Modifier
        .width(32.dp)
        .height(48.dp)
        .then(base)
    ) {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .background(block)
      )
      Column(
        modifier = Modifier
          .align(Alignment.BottomStart)
          .padding(horizontal = 4.dp, vertical = 4.dp)
      ) {
        Box(modifier = Modifier.width(20.dp).height(2.dp).background(accent, RoundedCornerShape(1.dp)))
        Spacer(modifier = Modifier.height(3.dp))
        Box(modifier = Modifier.width(16.dp).height(2.dp).background(NovaTextMuted, RoundedCornerShape(1.dp)))
      }
    }
  }
}

/** Etichetta localizzata di un layout della riga «Continua a guardare». */
@Composable
private fun continueWatchingLayoutLabel(layout: ContinueWatchingLayout): String = when (layout) {
  ContinueWatchingLayout.CARD -> stringResource(R.string.settings_cw_layout_card)
  ContinueWatchingLayout.WIDE -> stringResource(R.string.settings_cw_layout_wide)
  ContinueWatchingLayout.POSTER -> stringResource(R.string.settings_cw_layout_poster)
}

/** Etichetta localizzata dell'ordinamento della riga «Continua a guardare». */
@Composable
private fun continueWatchingSortLabel(sort: ContinueWatchingSort): String = when (sort) {
  ContinueWatchingSort.DEFAULT -> stringResource(R.string.settings_cw_sort_default)
  ContinueWatchingSort.TITLE -> stringResource(R.string.settings_cw_sort_title)
  ContinueWatchingSort.RATING -> stringResource(R.string.settings_cw_sort_rating)
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
  /**
   * Mostra la freccia di accesso a destra quando la riga apre un selettore/dialog.
   * Default `false`: le righe esistenti mantengono esattamente l'aspetto di prima.
   */
  showChevron: Boolean = false,
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
      if (showChevron && onClick != null) {
        Spacer(modifier = Modifier.width(4.dp))
        Icon(
          imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
          contentDescription = null,
          tint = if (isFocused) NovaCyanBright else NovaTextMuted,
          modifier = Modifier.size(20.dp)
        )
      }
    }
  }
}

/** Dimensioni dello switch comune: un solo punto di verità per tutte le Impostazioni. */
private val SettingsSwitchTrackWidth = 46.dp
private val SettingsSwitchTrackHeight = 26.dp
private val SettingsSwitchKnobSize = 20.dp
private val SettingsSwitchKnobInset = 3.dp

/**
 * Riga delle Impostazioni per un'opzione ON/OFF.
 *
 * Tutte le impostazioni booleane usano questo componente: titolo (e, quando prevista,
 * descrizione) a sinistra, switch a destra. L'intera riga è un unico target del D-pad:
 * il tasto OK alterna il valore una sola volta, senza un secondo click sullo switch.
 * Lo switch è solo la rappresentazione grafica dello stato e non è un secondo nodo
 * focalizzabile: la navigazione resta identica a quella delle altre righe.
 */
@Composable
fun SettingsSwitchRow(
  title: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
  subtitle: String? = null,
  modifier: Modifier = Modifier
) {
  val stateOn = stringResource(R.string.settings_value_on)
  val stateOff = stringResource(R.string.settings_value_off)
  TvFocusableBox(
    shape = RoundedCornerShape(12.dp),
    focusedScale = 1.015f,
    onClick = { onCheckedChange(!checked) },
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = 8.dp)
      .semantics {
        role = Role.Switch
        stateDescription = if (checked) stateOn else stateOff
        onClick(label = title) {
          onCheckedChange(!checked)
          true
        }
      }
  ) { isFocused ->
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .background(
          if (isFocused) NovaCardBgFocused else NovaCardBgFocused.copy(alpha = 0.55f),
          RoundedCornerShape(12.dp)
        )
        .border(
          1.dp,
          if (isFocused) Color.Transparent else NovaDivider,
          RoundedCornerShape(12.dp)
        )
        .padding(horizontal = 16.dp, vertical = 13.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = title,
          color = NovaTextPrimary,
          fontSize = 15.sp,
          fontWeight = FontWeight.SemiBold,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis
        )
        if (!subtitle.isNullOrBlank()) {
          Spacer(modifier = Modifier.height(3.dp))
          Text(
            text = subtitle,
            color = NovaTextSecondary,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
          )
        }
      }
      Spacer(modifier = Modifier.width(16.dp))
      SettingsSwitchIndicator(
        checked = checked,
        isFocused = isFocused
      )
    }
  }
}

/**
 * Switch grafico condiviso: pillola scura con knob a sinistra da spenta, pillola ciano
 * con knob a destra da accesa. Il focus del D-pad aggiunge un bordo ciano alla pillola,
 * coerente con il bordo della riga.
 */
@Composable
private fun SettingsSwitchIndicator(
  checked: Boolean,
  isFocused: Boolean,
  modifier: Modifier = Modifier
) {
  val knobOffset by animateDpAsState(
    targetValue = if (checked) {
      SettingsSwitchTrackWidth - SettingsSwitchKnobSize - SettingsSwitchKnobInset * 2
    } else {
      0.dp
    },
    animationSpec = tween(durationMillis = 160),
    label = "SettingsSwitchKnobOffset"
  )
  val trackColor by animateColorAsState(
    targetValue = when {
      checked && isFocused -> NovaCyanBright
      checked -> NovaCyan
      isFocused -> NovaTextMuted.copy(alpha = 0.55f)
      else -> NovaTextMuted.copy(alpha = 0.38f)
    },
    animationSpec = tween(durationMillis = 160),
    label = "SettingsSwitchTrackColor"
  )
  val knobColor by animateColorAsState(
    targetValue = if (checked) Color.White else NovaTextSecondary,
    animationSpec = tween(durationMillis = 160),
    label = "SettingsSwitchKnobColor"
  )

  Box(
    modifier = modifier
      .width(SettingsSwitchTrackWidth)
      .height(SettingsSwitchTrackHeight)
      .background(trackColor, RoundedCornerShape(50))
      .then(
        if (isFocused) {
          Modifier.border(1.5.dp, NovaCyanBright, RoundedCornerShape(50))
        } else {
          Modifier
        }
      )
      .padding(SettingsSwitchKnobInset),
    contentAlignment = Alignment.CenterStart
  ) {
    Box(
      modifier = Modifier
        .offset(x = knobOffset)
        .size(SettingsSwitchKnobSize)
        .background(knobColor, CircleShape)
    )
  }
}

package com.example.ui.screens

import android.util.Log
import android.view.KeyEvent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.example.R
import com.example.HOME_CONTENT_ANCHOR_ROW
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.repository.HomeCatalogs
import com.example.data.repository.MediaIdentity
import com.example.data.repository.StremioCatalogRepository
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import com.example.ui.components.ContinueWatchingCard
import com.example.ui.components.MediaContextMenu
import com.example.ui.components.HeroBanner
import com.example.ui.components.PosterMediaCard
import com.example.ui.components.ProviderConstants
import com.example.ui.components.ProviderCard
import com.example.ui.components.StandardMediaCard
import com.example.ui.components.StreamingProvider
import com.example.ui.components.Top10RankedMediaCard
import com.example.ui.components.Top10RowSpacing
import com.example.ui.theme.NovaBackground
import com.example.ui.theme.NovaCyanBright
import com.example.ui.theme.NovaTextPrimary
import com.example.ui.theme.NovaTextSecondary
import com.example.ui.viewmodel.SidebarSection
import com.example.ui.viewmodel.StreamNovaViewModel

import com.example.ui.components.TvFocusBringIntoView
import com.example.ui.components.TvFocusBringIntoViewSpec
import com.example.ui.components.TvFocusRow
import com.example.ui.components.LocalLastFocusedMediaId
import com.example.ui.components.LocalLastFocusedTarget
import com.example.ui.components.LocalFocusRestoreToken
import com.example.ui.components.LocalClearLastFocusedMedia
import com.example.ui.components.LocalLastFocusedProviderId
import com.example.ui.components.LocalClearLastFocusedProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import com.example.ui.components.DpadVisibleRowBounds
import com.example.ui.components.dpadVerticalFastScroll
import com.example.ui.components.resolveFastScrollLandingRowKey
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun HomeScreen(
  viewModel: StreamNovaViewModel,
  onProviderClick: (StreamingProvider) -> Unit = {},
  onMediaClick: ((MediaItem) -> Unit)? = null,
  modifier: Modifier = Modifier
) {
  val currentSection by viewModel.currentSection.collectAsState()
  val streamingEngineMode by viewModel.streamingEngineMode.collectAsState()
  val allMedia by viewModel.allMedia.collectAsState()
  val homeCatalogs by viewModel.homeCatalogs.collectAsState()
  val handleMediaClick: (MediaItem) -> Unit = onMediaClick ?: { viewModel.openDetail(it) }

  val lastFocusedMediaId by viewModel.lastFocusedMediaId.collectAsState()
  val lastFocusedTarget by viewModel.lastFocusedTarget.collectAsState()
  val lastFocusedProviderId by viewModel.lastFocusedProviderId.collectAsState()
  val focusRestoreToken by viewModel.focusRestoreToken.collectAsState()

  // La sidebar offre la sezione "Addon" solo in modalità Debrid TorBox: se il cambio di
  // modalità l'ha resa non più raggiungibile mentre era aperta, il contenuto ricade sulla
  // Home invece di mostrare una schermata che l'utente non può più richiamare dal menu.
  val activeSection = if (currentSection == SidebarSection.ADDON &&
    !streamingEngineMode.supportsAddonsSection
  ) {
    SidebarSection.HOME
  } else {
    currentSection
  }

  CompositionLocalProvider(
    LocalLastFocusedMediaId provides lastFocusedMediaId,
    LocalLastFocusedTarget provides lastFocusedTarget,
    LocalFocusRestoreToken provides focusRestoreToken,
    LocalClearLastFocusedMedia provides { viewModel.clearLastFocusedMedia() },
    LocalLastFocusedProviderId provides lastFocusedProviderId,
    LocalClearLastFocusedProvider provides { viewModel.clearLastFocusedProvider() }
  ) {
    when (activeSection) {
    SidebarSection.HOME -> {
      HomeMainBrowsingContent(
        allMedia = allMedia,
        homeCatalogs = homeCatalogs,
        onMediaClick = handleMediaClick,
        onPlayClick = { viewModel.openStreamForMedia(it) },
        onRemoveFromContinueWatching = { viewModel.removeFromContinueWatching(it) },
        onProviderClick = onProviderClick,
        viewModel = viewModel,
        modifier = modifier
      )
    }
    SidebarSection.FILM -> {
      val moviesItems = remember(homeCatalogs, allMedia) {
        if (homeCatalogs.allMovies.isNotEmpty()) {
          homeCatalogs.allMovies
        } else {
          allMedia.filter { it.type == MediaType.FILM }
        }
      }
      CategoryBrowsingScreen(
        title = stringResource(R.string.category_all_movies),
        items = moviesItems,
        isTv = false,
        viewModel = viewModel,
        onMediaClick = handleMediaClick,
        onLoadMore = { viewModel.loadNextMoviesPage() },
        modifier = modifier
      )
    }
    SidebarSection.SERIE_TV -> {
      val seriesItems = remember(homeCatalogs, allMedia) {
        if (homeCatalogs.allSeries.isNotEmpty()) {
          homeCatalogs.allSeries
        } else {
          allMedia.filter { it.type == MediaType.SERIE_TV }
        }
      }
      CategoryBrowsingScreen(
        title = stringResource(R.string.category_all_tv_series),
        items = seriesItems,
        isTv = true,
        viewModel = viewModel,
        onMediaClick = handleMediaClick,
        onLoadMore = { viewModel.loadNextTvPage() },
        modifier = modifier
      )
    }
    SidebarSection.I_MIEI_CONTENUTI -> {
      FavoritesScreen(
        allMedia = allMedia,
        onMediaClick = handleMediaClick,
        modifier = modifier,
        viewModel = viewModel
      )
    }
    SidebarSection.CERCA -> {
      SearchScreen(
        viewModel = viewModel,
        onMediaClick = handleMediaClick,
        modifier = modifier
      )
    }
    SidebarSection.ADDON -> {
      AddonsScreen(
        viewModel = viewModel,
        modifier = modifier
      )
    }
    SidebarSection.IMPOSTAZIONI -> {
      SettingsScreen(
        viewModel = viewModel,
        modifier = modifier
      )
    }
  }
}
}

/** Direzione verticale del D-pad governata dal coordinatore delle righe Home. */
enum class HomeVerticalDirection { UP, DOWN }

/** Cella di una riga della Home: unità del movimento verticale (riga + colonna). */
data class HomeCellTarget(val rowKey: String, val colIndex: Int)

@OptIn(ExperimentalFoundationApi::class)
/**
 * Coordinatore del focus verticale e orizzontale tra righe della Home TV.
 *
 * Restrizioni rigorose sui movimenti:
 * - D-Pad DOWN: scende alla riga IMMEDIATAMENTE sottostante (mai una riga saltata).
 * - D-Pad UP: sale alla riga IMMEDIATAMENTE soprastante.
 * - Colonna di partenza preservata; se la riga di destinazione è più corta si sceglie
 *   deterministicamente l'indice valido più vicino (mai un ripiego arbitrario su 0).
 * - Isolamento bidirezionale: Hero <-> Providers <-> Prima riga poster.
 *
 * Ruolo dopo il porting del modello NuvioTV: lo step SINGOLO Su/Giù tra righe è governato
 * dalla RICERCA NATIVA del focus di Compose (righe come `focusGroup` con `focusRestorer`
 * che ripristina la colonna RICORDATA della riga, vedi [homeRowFocusGroup]) mentre i tasti
 * TENUTI sono presidiati da `dpadVerticalFastScroll` sul LazyColumn (drag del viewport +
 * atterraggio sulla colonna ricordata). Il coordinatore NON intercetta più i tasti delle
 * card: resta proprietà del movimento programmatico dell'Hero ([requestVerticalMove]), del
 * RIPRISTINO dal Dettaglio e dell'atterraggio del fast-scroll (tutti percorsi che emettono
 * una richiesta pendente completata da [HomeVerticalFocusSync]), oltre al tracciamento del
 * focus reale ([onCellFocused], [lastFocusedColByRow]) che alimenta il focusRestorer di riga.
 *
 * Le richieste pendenti sono consapevoli dell'IDENTITÀ dei contenuti: se un catalogo si
 * riordina o si accorcia mentre una richiesta è in attesa, l'indice viene riallineato al
 * contenuto originale (o clampato posizionalmente se il contenuto non esiste più), così la
 * richiesta non può atterrare sul titolo sbagliato. Solo le celle MONTATE possono essere
 * usate come target di `focusProperties`: un [FocusRequester] non agganciato a un nodo fa
 * esplodere il focus search del D-pad.
 */
class HomeRowFocusCoordinator(
  initialRowKeys: List<String>,
  initialRowItemCounts: Map<String, Int>,
  initialRowIdentities: Map<String, List<String>> = emptyMap()
) {
  // Dati delle righe OSSERVABILI ma aggiornabili sul posto: la stessa istanza del
  // coordinatore sopravvive a tutte le ondate di caricamento dei cataloghi. I
  // [FocusRequester] già assegnati restano validi, quindi le celle non devono essere
  // riagganciate ad ogni cambiamento e i target verticali non si azzerano a metà caricamento.
  private val _activeRowKeys = mutableStateListOf<String>().apply { addAll(initialRowKeys) }
  val activeRowKeys: List<String> get() = _activeRowKeys

  private val _rowItemCounts = mutableStateMapOf<String, Int>().apply { putAll(initialRowItemCounts) }
  val rowItemCounts: Map<String, Int> get() = _rowItemCounts

  // Identità ordinate dei contenuti per riga, aggiornate da updateRows.
  // Consentono di riallineare l'INDICE di una richiesta pendente quando un catalogo si
  // riordina/accorcia: la richiesta continua a puntare allo stesso contenuto, non alla
  // stessa posizione. Le righe senza identità note (Hero) restano posizionali: nessun id
  // inventato. L'hub provider fornisce gli id dei provider (lista statica).
  private val _rowIdentities = mutableMapOf<String, List<String>>().apply { putAll(initialRowIdentities) }

  val lastFocusedColByRow = mutableStateMapOf<String, Int>()
  private val cellRequesters = mutableMapOf<Pair<String, Int>, FocusRequester>()

  // Mappa OSSERVABILE delle celle montate: chiunque legga lo stato (le focusProperties che
  // assegnano up/down) si ricompone quando una cella entra/esce dalla composizione, cosi'
  // un target non resta mai puntato a un nodo smontato. Una mappa "semplice" lasciava
  // target orfani e il focus search del D-pad scatenava
  // IllegalStateException "FocusRequester is not initialized".
  private val attachedCellState = mutableStateMapOf<Pair<String, Int>, Boolean>()

  // Contatore osservabile incrementato ad ogni registrazione di cella: consente a un unico
  // effetto di completare la richiesta di focus pendente non appena il nodo bersaglio esiste.
  private val _registrationTick = mutableStateOf(0)
  val registrationTick: Int get() = _registrationTick.value

  // Riga attualmente focalizzata. Inizialmente NULLA: nessun focus è ancora atterrato,
  // quindi il coordinatore non deve forzare la LazyColumn su una riga arbitraria (né
  // sull'indice 0/Hero) al ritorno dal Dettaglio, dove la posizione di scroll salvata
  // deve restare intatta finché il focus non raggiunge la card target.
  private val _focusedRowKey = mutableStateOf<String?>(null)
  val focusedRowKey: String? get() = _focusedRowKey.value

  // Cella che ha REALMENTE il focus (colonna inclusa). È la conferma usata per non
  // consumare una richiesta di focus prima che il focus sia stato davvero acquisito.
  private val _focusedCell = mutableStateOf<Pair<String, Int>?>(null)
  val focusedCell: Pair<String, Int>? get() = _focusedCell.value

  /**
   * Predicato del viewport installato dalla UI: `true` SOLO quando la riga è INTERAMENTE
   * visibile nella LazyColumn (nessun taglio sul bordo superiore o inferiore).
   *
   * Serve perché "riga montata" non significa "riga visibile": la LazyColumn compone le righe
   * anche appena oltre il viewport, quindi una cella può essere montata con la sua riga tagliata
   * dal bordo. In quel caso il focus NON va spostato direttamente, altrimenti il cursore atterra
   * su una card fuori campo e la Home non scorre: il movimento deve passare dalla richiesta
   * pendente, che fa eseguire lo scroll verticale a [HomeVerticalFocusSync].
   *
   * Finché la UI non installa il predicato ogni riga è considerata visibile, così i percorsi
   * fuori dalla Home (test di logica pura) restano invariati.
   */
  var isRowFullyVisible: (String) -> Boolean = { true }

  /** Richiesta di focus differita (una sola): il target verticale è noto (riga in
   * activeRowKeys) ma il suo nodo non è ancora montato. Viene completata quando la cella
   * compare e annullata se diventa obsoleta (riga rimossa o focus andato altrove).
   */
  private val _pendingFocusRequest = mutableStateOf<Pair<String, Int>?>(null)
  val pendingFocusRequest: Pair<String, Int>? get() = _pendingFocusRequest.value

  // Identità del contenuto bersaglio della richiesta pendente (null se la riga non fornisce
  // identità): vive e muore con la richiesta, così updateRows può riallinearne l'indice al
  // contenuto originale quando la riga si riordina o si accorcia.
  private val _pendingFocusIdentity = mutableStateOf<String?>(null)
  val pendingFocusIdentity: String? get() = _pendingFocusIdentity.value

  /**
   * Aggiorna righe, conteggi e (facoltativamente) identità dei contenuti SENZA ricreare il
   * coordinatore. Le registrazioni non più valide (riga eliminata o colonna oltre il nuovo
   * conteggio) vengono rimosse insieme ai loro [FocusRequester], così un target non punta mai
   * a un nodo che non esiste più.
   *
   * Con [newIdentities] presente e cambiato — anche a parità di chiavi e conteggi, cioè a
   * seguito di un riordino dentro la riga — la richiesta pendente viene RIALLINEATA
   * all'identità del suo bersaglio (vedi [realignPendingFocusAfterRowDataChange]). Con
   * `null` le identità già note restano invariate (chiamanti e test legacy).
   */
  fun updateRows(
    newKeys: List<String>,
    newCounts: Map<String, Int>,
    newIdentities: Map<String, List<String>>? = null
  ) {
    val identitiesChanged = newIdentities != null && newIdentities != _rowIdentities
    if (_activeRowKeys.toList() == newKeys && _rowItemCounts.toMap() == newCounts && !identitiesChanged) return

    _rowItemCounts.clear()
    _rowItemCounts.putAll(newCounts)
    if (newIdentities != null) {
      _rowIdentities.clear()
      _rowIdentities.putAll(newIdentities)
    }
    _activeRowKeys.clear()
    _activeRowKeys.addAll(newKeys)

    val validKeys = newKeys.toSet()
    val isStale: (Pair<String, Int>) -> Boolean = { (row, col) ->
      row !in validKeys || (_rowItemCounts[row] ?: 0) <= col
    }
    cellRequesters.keys.filter { isStale(it) }.forEach { cellRequesters.remove(it) }
    attachedCellState.keys.filter { isStale(it) }.forEach { attachedCellState.remove(it) }
    lastFocusedColByRow.keys
      .filter { it !in validKeys }
      .toList()
      .forEach { lastFocusedColByRow.remove(it) }

    // Una cella focalizzata non più esistente non può restare la "conferma" del focus.
    _focusedCell.value?.let { (row, col) -> if (isStale(row to col)) _focusedCell.value = null }

    // Se la riga precedentemente focalizzata è stata rimossa, aggiorna la riga corrente.
    // Se nessuna riga è ancora focalizzata (null) non imporre la prima: evita il salto
    // all'indice 0 al resume quando un ripristino è in corso.
    if (_focusedRowKey.value != null && _focusedRowKey.value !in validKeys) {
      _focusedRowKey.value = newKeys.firstOrNull()
    }

    // Una richiesta pendente verso una riga eliminata non ha più senso.
    _pendingFocusRequest.value?.let { (row, _) ->
      if (row !in validKeys) {
        _pendingFocusRequest.value = null
        _pendingFocusIdentity.value = null
      }
    }

    // Riallinea la richiesta pendente sopravvissuta al nuovo ordine/contenuto della riga.
    realignPendingFocusAfterRowDataChange()
  }

  /** Identità del contenuto nella posizione [colIndex] della riga [rowKey], se nota. */
  private fun identityOf(rowKey: String, colIndex: Int): String? =
    _rowIdentities[rowKey]?.getOrNull(colIndex)

  /**
   * Riallinea la richiesta pendente ai dati di riga appena aggiornati da [updateRows]:
   *
   * - riga rimossa o svuotata: la richiesta è annullata (nessuna cella potrà mai soddisfarla);
   * - indice oltre il nuovo conteggio: clamp posizionale, equivalente al fallback che il
   *   completamento applicava comunque via [resolveTargetColumn]: nessun cambiamento visibile;
   * - identità del bersaglio ancora presente nella riga: l'indice segue il CONTENUTO alla
   *   nuova posizione (la richiesta continua a puntare alla stessa card dopo un riordino);
   * - identità non più presente: resta il fallback posizionale con clamp (comportamento
   *   attuale) e l'identità viene dimenticata; le ondate successive non la cercano più.
   */
  private fun realignPendingFocusAfterRowDataChange() {
    val pending = _pendingFocusRequest.value ?: return
    val (row, col) = pending
    val count = _rowItemCounts[row] ?: 0
    if (count <= 0) {
      _pendingFocusRequest.value = null
      _pendingFocusIdentity.value = null
      return
    }

    var newCol = col.coerceIn(0, count - 1)
    val identity = _pendingFocusIdentity.value
    if (identity != null) {
      val rowIds = _rowIdentities[row]
      if (!rowIds.isNullOrEmpty()) {
        val relocated = rowIds.indexOf(identity)
        if (relocated >= 0) {
          newCol = relocated.coerceIn(0, count - 1)
        } else {
          _pendingFocusIdentity.value = null
        }
      }
    }
    if (newCol != col) _pendingFocusRequest.value = row to newCol
  }

  fun getRequester(rowKey: String, colIndex: Int): FocusRequester {
    return cellRequesters.getOrPut(Pair(rowKey, colIndex)) { FocusRequester() }
  }

  fun onCellFocused(rowKey: String, colIndex: Int) {
    _focusedRowKey.value = rowKey
    lastFocusedColByRow[rowKey] = colIndex
    _focusedCell.value = Pair(rowKey, colIndex)
    // Se il focus raggiunge una cella diversa da quella attesa, la richiesta pendente è obsoleta.
    _pendingFocusRequest.value?.let { pending ->
      if (pending != Pair(rowKey, colIndex)) {
        _pendingFocusRequest.value = null
        _pendingFocusIdentity.value = null
      }
    }
  }

  /** true se la cella ha DAVVERO il focus (conferma arrivata da `onFocusChanged`). */
  fun isCellFocused(rowKey: String, colIndex: Int): Boolean =
    _focusedCell.value == Pair(rowKey, colIndex)

  /**
   * Invalida la CONFERMA del focus di cella: nessuna cella ha più il focus.
   *
   * Da invocare quando il contenuto della Home perde il focus (es. l'overlay del player prende
   * il focus). Le card NON emettono una perdita utile quando la superficie esce dal focus:
   * [focusedCell] resterebbe quella di PRIMA, quindi la verifica di [HomeVerticalFocusSync]
   * ("il focus è stato acquisito?") risulterebbe vera per una cella che NON ha più il focus, la
   * richiesta di ripristino verrebbe consumata senza che il focus si sia mosso e il cursore
   * resterebbe fuori dal contenuto. La riga e la colonna di partenza restano memorizzate
   * ([focusedRowKey], [lastFocusedColByRow]): si perde solo la conferma, non la memoria.
   */
  fun onCellFocusInvalidated() {
    _focusedCell.value = null
  }

  /** Colonna in cui il focus ha lasciato la riga, o null se la riga non è mai stata focalizzata. */
  fun lastFocusedColumn(rowKey: String): Int? = lastFocusedColByRow[rowKey]

  /** true se la riga ha ALMENO una cella montata in composizione. */
  fun isRowAttached(rowKey: String): Boolean =
    attachedCellState.keys.any { it.first == rowKey }

  fun registerCell(rowKey: String, colIndex: Int) {
    val cell = Pair(rowKey, colIndex)
    if (attachedCellState[cell] != true) {
      attachedCellState[cell] = true
      _registrationTick.value++
    }
  }

  fun unregisterCell(rowKey: String, colIndex: Int) {
    attachedCellState.remove(Pair(rowKey, colIndex))
  }

  fun isCellAttached(rowKey: String, colIndex: Int): Boolean =
    attachedCellState.containsKey(Pair(rowKey, colIndex))

  /** `true` se la riga fa parte delle righe attive e ha almeno un elemento. */
  fun hasItems(rowKey: String): Boolean = (_rowItemCounts[rowKey] ?: 0) > 0

  fun getNextRowKey(currentRowKey: String): String? {
    val idx = activeRowKeys.indexOf(currentRowKey)
    return if (idx in 0 until activeRowKeys.lastIndex) activeRowKeys[idx + 1] else null
  }

  fun getPrevRowKey(currentRowKey: String): String? {
    val idx = activeRowKeys.indexOf(currentRowKey)
    return if (idx > 0) activeRowKeys[idx - 1] else null
  }

  /** Registra una richiesta di focus verso una riga nota il cui nodo non è ancora montato. */
  fun requestPendingFocus(rowKey: String, colIndex: Int) {
    _pendingFocusRequest.value = Pair(rowKey, colIndex)
    _pendingFocusIdentity.value = identityOf(rowKey, colIndex)
  }

  /** Consuma (una sola volta) la richiesta pendente, restituendola. */
  fun consumePendingFocusRequest(): Pair<String, Int>? {
    val current = _pendingFocusRequest.value ?: return null
    _pendingFocusRequest.value = null
    _pendingFocusIdentity.value = null
    return current
  }

  /** Riga adiacente nella direzione richiesta: `null` su prima/ultima riga (nessun salto). */
  fun adjacentRowKey(currentRowKey: String, direction: HomeVerticalDirection): String? =
    when (direction) {
      HomeVerticalDirection.DOWN -> getNextRowKey(currentRowKey)
      HomeVerticalDirection.UP -> getPrevRowKey(currentRowKey)
    }

  /**
   * Colonna di destinazione nella riga [targetRowKey] partendo dalla colonna [fromCol].
   *
   * La colonna di partenza viene PRESERVATA quando la riga ha una card in quella posizione;
   * se la riga è più corta si sceglie l'indice valido più vicino (l'ultimo disponibile),
   * mai un indice arbitrario o un ripiego sulla colonna 0.
   */
  fun resolveTargetColumn(targetRowKey: String, fromCol: Int): Int {
    val count = _rowItemCounts[targetRowKey] ?: 0
    if (count <= 0) return 0
    return fromCol.coerceIn(0, count - 1)
  }

  /**
   * Cella di destinazione del movimento verticale, o `null` se la direzione non ha una riga
   * adiacente con contenuti (prima/ultima riga).
   *
   * [fromCol] è la colonna della cella di partenza; `null` quando la superficie che chiede il
   * movimento non ha colonne proprie (Hero): in quel caso si usa la colonna in cui il focus
   * aveva lasciato la riga di destinazione, o la prima.
   */
  fun resolveVerticalTarget(
    fromRowKey: String,
    fromCol: Int?,
    direction: HomeVerticalDirection
  ): HomeCellTarget? {
    val targetRowKey = adjacentRowKey(fromRowKey, direction) ?: return null
    if (!hasItems(targetRowKey)) return null
    val departureCol = fromCol ?: (lastFocusedColByRow[targetRowKey] ?: 0)
    return HomeCellTarget(targetRowKey, resolveTargetColumn(targetRowKey, departureCol))
  }

  /**
   * Esegue il movimento verticale del D-pad.
   *
   * Il focus viene spostato subito SOLO se la cella di destinazione è montata E la sua riga è
   * interamente dentro il viewport. Negli altri casi (cella non montata, oppure riga montata ma
   * tagliata dal bordo: la LazyColumn compone anche appena oltre il viewport) la richiesta resta
   * pendente e verrà completata appena la cella entra in composizione dopo lo scroll verticale
   * di [HomeVerticalFocusSync]. Mai un focus su una card fuori campo con la Home ferma.
   *
   * @return `true` se la direzione ha una destinazione: in quel caso il tasto va CONSUMATO,
   *   così la ricerca geometrica automatica non può scegliere una riga non prevista.
   */
  fun requestVerticalMove(
    fromRowKey: String,
    fromCol: Int?,
    direction: HomeVerticalDirection
  ): Boolean {
    val target = resolveVerticalTarget(fromRowKey, fromCol, direction) ?: return false
    val isReachableNow =
      isCellAttached(target.rowKey, target.colIndex) && isRowFullyVisible(target.rowKey)
    if (isReachableNow) {
      _pendingFocusRequest.value = null
      _pendingFocusIdentity.value = null
      try {
        getRequester(target.rowKey, target.colIndex).requestFocus()
      } catch (_: Exception) {
        // Nodo smontato tra la verifica e la richiesta: resta valida la richiesta differita.
        _pendingFocusRequest.value = target.rowKey to target.colIndex
        _pendingFocusIdentity.value = identityOf(target.rowKey, target.colIndex)
      }
    } else {
      _pendingFocusRequest.value = target.rowKey to target.colIndex
      _pendingFocusIdentity.value = identityOf(target.rowKey, target.colIndex)
    }
    return true
  }

  /**
   * Requester della cella MONTATA più vicina alla colonna desiderata nella riga bersaglio.
   *
   * Ritorna `null` se la riga bersaglio non ha alcuna cella montata: assegnare un
   * FocusRequester non agganciato a `focusProperties.down/up` fa esplodere il focus search
   * del D-pad (`IllegalStateException: FocusRequester is not initialized`).
   *
   * Serve per i percorsi che NON passano dal D-pad (moveFocus programmatico, Hero): con il
   * D-pad il movimento è deciso da [requestVerticalMove].
   */
  fun getTargetRequester(targetRowKey: String, preferredCol: Int): FocusRequester? {
    if (!hasItems(targetRowKey)) return null
    val desiredCol = resolveTargetColumn(targetRowKey, preferredCol)

    if (isCellAttached(targetRowKey, desiredCol)) {
      return getRequester(targetRowKey, desiredCol)
    }

    // Cella desiderata non montata: si ripiega sulla cella MONTATA più vicina (mai su una
    // cella non montata e mai su un requester orfano).
    val closestAttachedCol = attachedCellState.keys
      .asSequence()
      .filter { it.first == targetRowKey }
      .map { it.second }
      .minByOrNull { kotlin.math.abs(it - desiredCol) }
      ?: return null

    return getRequester(targetRowKey, closestAttachedCol)
  }

  /** Prima colonna MONTATA della riga (ordine di composizione) o `null` se nessuna. */
  fun firstAttachedColumn(rowKey: String): Int? =
    attachedCellState.keys.asSequence().filter { it.first == rowKey }.map { it.second }.minOrNull()

  /**
   * Riporta il focus sulla cella di PARTENZA quando un trasferimento verticale non è riuscito.
   *
   * Senza questo, un fallimento lascia il cursore su un nodo smontato (o su nessun nodo) e il
   * D-pad sembra congelato: il focus non ha più una posizione da cui ripartire. I candidati
   * sono provati in ordine di vicinanza alla posizione di partenza e SOLO se MONTATI, così
   * non si usa mai un [FocusRequester] orfano (che fa esplodere la focus search del D-pad):
   *
   * 1. la cella che ha DAVVERO avuto il focus ([focusedCell]: ultima conferma di
   *    `onFocusChanged`, quindi la cella di partenza del movimento);
   * 2. la colonna in cui il focus aveva lasciato quella riga;
   * 3. la cella montata più vicina nella stessa riga.
   *
   * @return `true` se la richiesta di focus è stata emessa verso una cella montata.
   */
  fun reassertCurrentCellFocus(): Boolean {
    val departureRowKey = _focusedRowKey.value ?: return false
    val candidates = buildList {
      _focusedCell.value?.let { add(it) }
      lastFocusedColByRow[departureRowKey]?.let { add(departureRowKey to it) }
      attachedCellState.keys
        .asSequence()
        .filter { it.first == departureRowKey }
        .sortedBy { it.second }
        .forEach { add(it) }
    }

    candidates.forEach { (rowKey, colIndex) ->
      if (!isCellAttached(rowKey, colIndex)) return@forEach
      try {
        getRequester(rowKey, colIndex).requestFocus()
        return true
      } catch (_: Exception) {
        // Nodo smontato tra la verifica e la richiesta: si prova il candidato successivo.
      }
    }
    return false
  }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Modifier.rowFocusProperties(
  coordinator: HomeRowFocusCoordinator,
  rowKey: String,
  colIndex: Int
): Modifier {
  DisposableEffect(coordinator, rowKey, colIndex) {
    coordinator.registerCell(rowKey, colIndex)
    onDispose {
      coordinator.unregisterCell(rowKey, colIndex)
    }
  }

  // Solo tracciamento: il movimento verticale del D-pad NON è più intercettato dalla singola
  // card. Lo step Su/Giù è della ricerca nativa del focus di Compose (riga come focusGroup con
  // focusRestorer: vedi [homeRowFocusGroup], modello Nuvio ModernHomeRows.kt) e tiene il resto
  // ai tasti ripetuti (`dpadVerticalFastScroll` sul LazyColumn). Gli espliciti
  // `focusProperties { up/down }` per-card sono stati RIMOSSI: forzavano QUALSIASI attraversamento
  // verticale su una colonna singola, costringendo la sincronizzazione a riallineare in
  // orizzontale ogni riga raggiunta (difetto Home "si spostano tutte le card orizzontali")
  // e a consumare il tasto lasciando lo scorrimento partire prima della richiesta pendente
  // (difetto Home "focus bloccato sulla riga mentre la Home scorre").
  return this
    .onFocusChanged { state ->
      if (state.isFocused || state.hasFocus) {
        coordinator.onCellFocused(rowKey, colIndex)
      }
    }
}

/**
 * Catena di focus della riga Home (port di NuvioTV `ModernRowSection`, ModernHomeRows.kt):
 *
 * - [focusRestorer]: quando il focus ENTRA nella riga, Compose ripristina la card che ne era
 *   uscita (memoria di riga: colonna salvata in [HomeRowFocusCoordinator.lastFocusedColByRow]
 *   da [rowFocusProperties]); se quella card non è più agganciata (riciclata fuori finestra)
 *   si ripiega sulla PRIMA CARD VISIBILE della finestra corrente (variante robusta del
 *   `focusRestorer` di Nuvio `CatalogRowSection.kt`), mai su un requester orfano — un
 *   [FocusRequester] non agganciato a un nodo composto fa esplodere la ricerca nativa.
 *   NOTA: il `focusRestorer` crea da sé il FocusGroupNode della riga; un `.focusGroup()`
 *   esplicito aggiunto qui sopra lo nidificherebbe e romperebbe la consegna del focus al
 *   rientro (D-pad morto — difetto verificato in harness).
 * - Il vecchio "requester di ingresso di riga" (`.focusRequester` sulla LazyRow, bersaglio
 *   del restorer del LazyColumn) è stato RIMOSSO: la LazyRow è un nodo NON focalizzabile e
 *   un requestFocus tramite quel requester termina con `IllegalStateException
 *   "FocusRequester is not initialized"` (crash). Il reingresso nel contenuto da fuori è ora
 *   gestito dal restorer del LazyColumn che richiede direttamente la CELLA della riga
 *   corrente (vedi HomeMainBrowsingContent).
 */
@OptIn(ExperimentalComposeUiApi::class)
internal fun Modifier.homeRowFocusGroup(
  coordinator: HomeRowFocusCoordinator,
  rowKey: String,
  rowState: LazyListState
): Modifier {
  return this
    .focusRestorer {
      // Convergenza con il percorso autorevole e memoria di riga (port Nuvio ModernRowSection):
      // in Compose 1.7.2 il restorer E' la via d'ingresso del gruppo (il fallback viene
      // consultato a ogni rientro), quindi deve indicare la colonna da ripristinare: richieste
      // PENDENTI verso questa riga (convergenza con HomeVerticalFocusSync, che altrimenti
      // divergerebbe: lezione verificata sui test), altrimenti la colonna in cui il focus ha
      // lasciato la riga, altrimenti 0. Colonna ricordata non agganciata -> prima colonna
      // MONTATA della riga; riga vuota -> Default. MAI un FocusRequester orfano e, QUANDO LA
      // RIGA HA CELLE MONTATE, MAI `Default`: restituire Default al rientro fa puntare la
      // ricerca sul nodo del gruppo (non focalizzabile) e cancella il focus (D-pad morto).
      // Nessuna lettura di layoutInfo qui dentro: il dispatch del focus non deve toccare il
      // layout della riga.
      val count = coordinator.rowItemCounts[rowKey] ?: 0
      if (count <= 0) {
        FocusRequester.Default
      } else {
        val pending = coordinator.pendingFocusRequest
        val savedCol = if (pending != null && pending.first == rowKey) pending.second
        else coordinator.lastFocusedColumn(rowKey)
        val preferredCol = (savedCol ?: 0).coerceIn(0, count - 1)
        val resolved = when {
          coordinator.isCellAttached(rowKey, preferredCol) -> coordinator.getRequester(rowKey, preferredCol)
          else -> coordinator.firstAttachedColumn(rowKey)?.let { coordinator.getRequester(rowKey, it) }
            ?: FocusRequester.Default
        }
        resolved
      }
    }
    // ATTENZIONE: qui NON va aggiunto un `.focusGroup()` esplicito. In Compose 1.7.2
    // `focusRestorer` crea GIA' il nodo di gruppo (FocusGroupNode) e un secondo gruppo
    // NIDIFICATO sullo stesso nodo fa fallire la consegna del focus al rientro nella riga
    // (verificato in harness): la ricerca 1D entra nel gruppo esterno, il rientro nel
    // gruppo interno non conclude il trasferimento e il D-pad muore. Il gruppo implicito
    // del restorer basta e decide il rientro con la colonna ricordata qui sopra.
    .let { this }
}

/**
 * Step SINGOLO Su/Giù delle righe della Home: lo decide il [coordinator], non la ricerca
 * geometrica del focus di Compose.
 *
 * Perché serve: la Home non è l'unica superficie focalizzabile a schermo — accanto al contenuto
 * vive la sidebar. Lo step verticale lasciato alla ricerca a due dimensioni (`TwoDimensionalFocusSearch`)
 * è un FASCIO geometrico: quando la riga sopra quella focalizzata è tagliata dal bordo (o le sue
 * card sono momentaneamente non focalizzabili) il candidato più vicino nel fascio può essere un
 * elemento FUORI dal contenuto (sidebar, in alto a sinistra) e il SU abbandona la colonna delle
 * righe; se quel trasferimento non si conclude il cursore resta senza focus (D-pad morto). Il
 * modello del coordinatore è invece l'ordine ESPLICITO delle righe ([HomeRowFocusCoordinator.activeRowKeys]) con la
 * colonna ricordata: la stessa destinazione che l'utente vede sopra/sotto di sé, sempre dentro il
 * contenuto.
 *
 * Ambito:
 * - solo PRIMA pressione (`repeatCount == 0`): i ripetuti restano al takeover di
 *   [dpadVerticalFastScroll] (scroll a vista + atterraggio sulla colonna ricordata);
 * - solo SU/GIÙ: SX/DX restano alla navigazione nativa (e alla throttle orizzontale);
 * - solo dentro le righe: l'Hero è escluso perché gestisce i propri tasti (ciclo dei titoli,
 *   [HeroBanner.onHeroMoveDown]) e il proprio ingresso nel contenuto;
 * - se il coordinatore non ha una destinazione (`false`: prima riga senza Hero sopra, ultima riga)
 *   il tasto NON viene consumato e vale la ricerca nativa.
 *
 * @return il modificatore che consuma il tasto SOLO quando il movimento è stato risolto.
 */
@OptIn(ExperimentalComposeUiApi::class)
internal fun Modifier.homeVerticalStepKeys(
  coordinator: HomeRowFocusCoordinator
): Modifier = onPreviewKeyEvent { event ->
  val native = event.nativeKeyEvent
  if (native.action != KeyEvent.ACTION_DOWN || native.repeatCount != 0) {
    return@onPreviewKeyEvent false
  }
  val direction = when (native.keyCode) {
    KeyEvent.KEYCODE_DPAD_UP -> HomeVerticalDirection.UP
    KeyEvent.KEYCODE_DPAD_DOWN -> HomeVerticalDirection.DOWN
    else -> return@onPreviewKeyEvent false
  }
  // L'Hero ha i propri tasti (e il proprio ingresso nel contenuto).
  val rowKey = coordinator.focusedRowKey
  if (rowKey == null || rowKey == "hero_banner") return@onPreviewKeyEvent false
  val col = coordinator.lastFocusedColumn(rowKey) ?: 0

  // Step SINGOLO verso una riga adiacente GIA' MONTATA ma con la colonna ricordata FUORI dalla
  // sua finestra orizzontale: il focus atterra sulla card MONTATA più vicina (la
  // "geometricamente vicina" sopra/sotto, modello Nuvio/nativo) SENZA spostare in orizzontale la
  // riga attraversata. La riposizionatura orizzontale resta ai soli percorsi di ripristino dal
  // Dettaglio e di atterraggio del fast-scroll: la navigazione verticale non muove mai le righe
  // in orizzontale (difetto F). Se invece la riga bersaglio non ha alcuna cella montata (riga
  // non composta) si prosegue con la richiesta differita, che fa eseguire lo scroll verticale.
  val target = coordinator.resolveVerticalTarget(rowKey, col, direction)
  if (target != null && !coordinator.isCellAttached(target.rowKey, target.colIndex)) {
    coordinator.getTargetRequester(target.rowKey, target.colIndex)?.let { adjacentCell ->
      coordinator.consumePendingFocusRequest()
      try {
        adjacentCell.requestFocus()
        return@onPreviewKeyEvent true
      } catch (_: Exception) {
        // Nodo smontato tra la verifica e la richiesta: si prosegue con la richiesta differita.
      }
    }
  }

  coordinator.requestVerticalMove(rowKey, col, direction)
}

/**
 * Unico punto che muove la Home (LazyColumn verticale + LazyRow orizzontali) in risposta alla
 * richiesta di focus pendente del coordinatore.
 *
 * Perché serve: una cella fuori dalla finestra della sua LazyRow non viene MAI composta (la
 * richiesta non potrebbe quindi completarsi) e una riga fuori dal viewport della LazyColumn non
 * esiste affatto. Una riga TAGLIATA dal bordo (montata ma solo parzialmente in vista) è un caso
 * altrettanto valido: il focus non deve atterrare su una card fuori campo, quindi la riga viene
 * prima portata INTERAMENTE nel viewport. Qui la cella bersaglio viene resa raggiungibile, il
 * focus viene richiesto e VERIFICATO (la richiesta pendente si consuma solo quando `onFocusChanged`
 * conferma davvero l'arrivo del focus) e, se il bersaglio non è raggiungibile, il cursore viene
 * riportato sulla cella di partenza: mai un D-pad congelato su un nodo che non esiste più.
 *
 * NON esistono altri effetti che muovono la Home: lo scroll verticale segue sempre un
 * trasferimento reale di focus e mai un cambio di stato del coordinatore. È anche l'UNICO
 * percorso di acquisizione del focus durante il ripristino dal Dettaglio: l'effetto di
 * ripristino risolve la destinazione e registra la richiesta, e qui avviene l'eventuale
 * escalation e l'abbandono (con [onRestoreFocusFailed]) se la richiesta non può più essere
 * soddisfatta, così le altre card tornano focalizzabili invece di restare bloccate.
 */
@Composable
fun HomeVerticalFocusSync(
  coordinator: HomeRowFocusCoordinator,
  listState: LazyListState,
  rowStateFor: (String) -> LazyListState?,
  onRestoreFocusFailed: (rowKey: String, colIndex: Int) -> Unit = { _, _ -> }
) {
  // Il coordinatore decide i movimenti verticali ma non conosce il viewport: la visibilità
  // REALE della riga (montata non basta: può essere tagliata dal bordo) gliela comunica qui,
  // l'unico punto che possiede il layout della LazyColumn. Così `requestVerticalMove` sa quando
  // deve lasciar fare lo scroll verticale invece di spostare il focus su una card fuori campo.
  SideEffect {
    coordinator.isRowFullyVisible = { rowKey -> listState.isRowFullyVisible(rowKey) }
  }

  // Token di ripristino dal Dettaglio: mentre è presente (rientro in corso) ogni fallback è
  // disattivato, perché il rientro deve atterrare ESATTAMENTE sulla card salvata senza deviare
  // sulla colonna 0 o sulla riga sottostante. La richiesta resta pendente finché la card
  // salvata non conferma il focus (tentativo diretto del LaunchedEffect di ripristino compreso).
  val currentRestoreId = LocalLastFocusedMediaId.current
  LaunchedEffect(coordinator.pendingFocusRequest, coordinator.registrationTick) {
    val (rowKey, colIndex) = coordinator.pendingFocusRequest ?: return@LaunchedEffect

    // 1. Verticale: la riga bersaglio deve essere INTERAMENTE nel viewport, altrimenti le sue
    //    card sono tagliate dal bordo (o non esistono nemmeno in composizione) e il focus
    //    arriverebbe su una card fuori campo con la Home ferma. La visibilità è decisa dalla
    //    CHIAVE dell'item (la stessa passata a `item(key = ...)`): nessun indice ricostruito.
    val rowInfo = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == rowKey }
    val isRowFullyVisible = rowInfo != null &&
      rowInfo.offset >= 0 &&
      (rowInfo.offset + rowInfo.size) <= listState.layoutInfo.viewportEndOffset

    if (!isRowFullyVisible) {
      val rowIndex = listState.rowItemIndex(rowKey, coordinator.activeRowKeys)
      if (rowIndex >= 0) {
        try {
          listState.animateScrollToItem(rowIndex)
        } catch (_: Exception) {
          // Lista non ancora composta: la richiesta resta pendente e riparte alla prossima
          // registrazione di cella.
        }
      }
    }

    // 2. Orizzontale: la colonna bersaglio si porta in finestra SOLO SE non è già visibile e
    //    la riga non sta già scorrendo. Il vecchio scrollToItem incondizionato allineava ogni
    //    riga raggiunta in verticale al BORDO SINISTRO della colonna scelta: muovere Su/Giù
    //    spostava in orizzontale tutte le righe attraversate (difetto Home). Con il porting
    //    Nuvio la posizione orizzontale delle righe durante la navigazione è garantita dal
    //    BringIntoView nativo (minimo necessario); qui resta solo la pre-posizionatura per i
    //    percorsi pendenti (ripristino dal Dettaglio, atterraggio fast-scroll).
    rowStateFor(rowKey)?.let { rowState ->
      val isColVisible = rowState.layoutInfo.visibleItemsInfo.any { it.index == colIndex }
      if (!isColVisible && !rowState.isScrollInProgress) {
        try {
          rowState.scrollToItem(colIndex)
        } catch (_: Exception) {
        }
      }
    }

    // 3. Attesa REATTIVA del mount: lo scroll chiesto sopra si materializza nei frame
    //    successivi. Nessun ciclo a tentativi fissi: si attende l'evento, con un tetto.
    val isTargetAttached = awaitCondition(VERTICAL_SYNC_TIMEOUT_MS) {
      coordinator.isCellAttached(rowKey, colIndex)
    }

    val targetRequester = coordinator.getRequester(rowKey, colIndex)
    var focusAcquired = false

    // 4. Il focus è acquisito SOLO quando `onFocusChanged` lo conferma: `requestFocus()` non
    //    solleva eccezioni se il nodo è già smontato, quindi consumare la richiesta sulla sola
    //    assenza di eccezione perderebbe la destinazione.
    if (isTargetAttached) {
      try {
        targetRequester.requestFocus()
        focusAcquired = awaitCondition(VERTICAL_SYNC_TIMEOUT_MS) {
          coordinator.isCellFocused(rowKey, colIndex)
        }
      } catch (cancellation: CancellationException) {
        // L'effetto è stato riavviato (nuova destinazione o nuova cella montata): questa
        // esecuzione non deve proseguire. Senza il rilancio, il resto del protocollo
        // girerebbe su una coroutine morta: fallback/reassert verso altre celle,
        // consumo o ABBANDONO della richiesta pendente (`onRestoreFocusFailed`) mentre il
        // ripristino era ancora in volo, con il focus lasciato fuori dal contenuto.
        throw cancellation
      } catch (_: Exception) {
        focusAcquired = false
      }
    }

    // 5. Fallback deterministico sulla riga bersaglio: la cella MONTATA più vicina alla colonna
    //    richiesta (mai un requester orfano: `getTargetRequester` ritorna null quando la riga
    //    non ha celle montate). Qui la conferma è il consumo della richiesta, che
    //    `onCellFocused` provoca appena il focus atterra su una cella diversa dal bersaglio.
    //    Durante il rientro dal Dettaglio (`currentRestoreId != null`) NON esegue ALCUN
    //    fallback: il ripristino deve rimanere rigorosamente ancorato alla card salvata.
    if (!focusAcquired && currentRestoreId == null) {
      coordinator.getTargetRequester(rowKey, colIndex)
        ?.takeIf { it !== targetRequester }
        ?.let { fallbackRequester ->
          try {
            fallbackRequester.requestFocus()
            focusAcquired = awaitCondition(VERTICAL_SYNC_TIMEOUT_MS) {
              coordinator.pendingFocusRequest == null
            }
          } catch (cancellation: CancellationException) {
            throw cancellation
          } catch (_: Exception) {
          }
        }
    }

    // 5-bis. Ripristino dal Dettaglio: fallback e reassert sono disattivati (il rientro deve
    //    restare rigorosamente ancorato alla card salvata), quindi è QUI che un tentativo
    //    fallito arriva a verdetto. L'ESCALATION ripete la richiesta sulla STESSA
    //    richiesta pendente (erede del vecchio protocollo diretto a 1000 ms) per dare tempo ai
    //    dispositivi lenti. Se anche questa fallisce MENTRE il bersaglio è ancora agganciato
    //    (o la sua colonna è già nella finestra della riga), la richiesta non può più essere
    //    soddisfatta: viene ABANDONATA (consumata e segnalata a [onRestoreFocusFailed], che
    //    rilascia lo stato di ripristino) e le altre card tornano focalizzabili. Il requisito
    //    "riga interamente visibile" è stato rilassato: una card AGGANCIATA ha un nodo vivo
    //    che può accettare il focus anche se la sua riga è momentaneamente tagliata dal bordo;
    //    resta pendente SOLO quando il bersaglio non è ancora montato (self-healing), perché
    //    mentre il gate `canFocus` è chiuso l'unica superficie focalizzabile resta la sidebar
    //    (difetto "il focus finisce nella sidebar al rientro dal Dettaglio").
    if (!focusAcquired && currentRestoreId != null) {
      if (isTargetAttached) {
        try {
          targetRequester.requestFocus()
          focusAcquired = awaitCondition(VERTICAL_SYNC_TIMEOUT_MS) {
            coordinator.isCellFocused(rowKey, colIndex)
          }
        } catch (cancellation: CancellationException) {
          throw cancellation
        } catch (_: Exception) {
        }
      }
      if (!focusAcquired) {
        val colAlreadyInWindow = rowStateFor(rowKey)?.layoutInfo?.visibleItemsInfo
          ?.any { it.index == colIndex } == true
        if (isTargetAttached || colAlreadyInWindow) {
          // Consumo GUARDATO: se nel frattempo un tasto utente ha sovrascritto la richiesta
          // pendente con una nuova destinazione, quella nuova non va distrutta.
          if (coordinator.pendingFocusRequest == (rowKey to colIndex)) {
            coordinator.consumePendingFocusRequest()
            onRestoreFocusFailed(rowKey, colIndex)
          }
        }
      }
    }

    // 6. La richiesta pendente si consuma solo se il focus è DAVVERO atterrato da qualche parte.
    //    Altrimenti il focus torna sulla cella di partenza: senza questo ripristino il cursore
    //    resterebbe nel vuoto (D-pad apparentemente congelato). Se il ripristino stesso non ha
    //    una cella montata su cui atterrare la richiesta resta pendente, così la prossima cella
    //    che entra in composizione può ancora completarla. Con `currentRestoreId != null` la
    //    richiesta NON viene né deviata né consumata: resta appesa alla card salvata.
    if (focusAcquired) {
      coordinator.consumePendingFocusRequest()
    } else if (currentRestoreId == null && coordinator.reassertCurrentCellFocus()) {
      coordinator.consumePendingFocusRequest()
    }
  }
}

/**
 * Indice dell'item [rowKey] nella LazyColumn, ricavato dalla COMPOSIZIONE corrente.
 *
 * L'ancora è il primo item visibile: il suo indice è quello reale della lista e la sua chiave è
 * una riga nota, quindi l'indice della riga bersaglio è l'ancora più il delta in chiavi. Nessuna
 * lista di chiavi↔indici mantenuta a mano: se l'ancora non è utilizzabile si ripiega
 * sull'ordine delle righe note al coordinatore, che è l'ordine con cui la LazyColumn le emette.
 */
private fun LazyListState.rowItemIndex(rowKey: String, rowKeys: List<String>): Int {
  val targetOrdinal = rowKeys.indexOf(rowKey)
  if (targetOrdinal < 0) return -1

  val anchor = layoutInfo.visibleItemsInfo.firstOrNull()
  val anchorOrdinal = (anchor?.key as? String)?.let(rowKeys::indexOf) ?: -1
  val index = if (anchor != null && anchorOrdinal >= 0) {
    anchor.index + (targetOrdinal - anchorOrdinal)
  } else {
    targetOrdinal
  }
  return index.coerceIn(0, (layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
}

/**
 * `true` SOLO quando la riga [rowKey] è INTERAMENTE dentro il viewport: una riga tagliata dal
 * bordo superiore o inferiore non conta come visibile, perché il focus vi atterrerebbe su una
 * card fuori campo (o su un'intestazione di sezione tagliata) lasciando la Home ferma.
 *
 * Riferimenti: [LazyListItemInfo.offset] è relativo all'inizio dell'area di contenuto (il
 * content padding superiore è già escluso) e [LazyListLayoutInfo.viewportEndOffset] è il bordo
 * inferiore DAVVERO visibile: il content padding inferiore non è spazio in cui una card possa
 * restare in vista.
 */
private fun LazyListState.isRowFullyVisible(rowKey: String): Boolean {
  val rowInfo = layoutInfo.visibleItemsInfo.firstOrNull { it.key == rowKey } ?: return false
  return rowInfo.offset >= 0 &&
    (rowInfo.offset + rowInfo.size) <= layoutInfo.viewportEndOffset
}

/**
 * Attende REATTIVAMENTE (nessun ciclo a tentativi fissi) che [condition] diventi vera.
 *
 * `snapshotFlow` emette il valore corrente appena collezionato e poi ad ogni cambiamento degli
 * stati Compose che legge: l'attesa finisce appena la condizione si avvera, o con `false` se il
 * tetto [timeoutMs] scade (lista ancora in movimento, nodo che non si monta).
 */
private suspend fun awaitCondition(timeoutMs: Long, condition: () -> Boolean): Boolean =
  withTimeoutOrNull(timeoutMs) { snapshotFlow(condition).first { it } } ?: false

/**
 * Tetto di attesa della sincronizzazione verticale: mount della cella bersaglio dopo lo scroll
 * e conferma del focus da parte di `onFocusChanged`.
 */
private const val VERTICAL_SYNC_TIMEOUT_MS = 500L

/** Riga «Continua a guardare»: l'unica riga della Home da cui si rimuovono contenuti. */
private const val CONTINUE_WATCHING_ROW_KEY = "section_continue_watching"

/**
 * Attesa della conferma di focus dentro la riga «Continua a guardare» prima di applicare una
 * rimozione (vedi [HomeMainBrowsingContent]).
 */
private const val CW_REMOVAL_FOCUS_TIMEOUT_MS = 500L

/** Ripetizioni della richiesta di rientro nella riga prima di lasciare la rimozione in attesa. */
private const val CW_REMOVAL_FOCUS_RETRIES = 3

@Composable
private fun HomeMediaLazyRow(
  rowState: LazyListState,
  modifier: Modifier = Modifier,
  restoreMediaId: String? = null,
  items: List<MediaItem> = emptyList(),
  contentPadding: PaddingValues = PaddingValues(horizontal = 32.dp, vertical = 8.dp),
  horizontalArrangement: Arrangement.Horizontal = Arrangement.spacedBy(16.dp),
  content: LazyListScope.() -> Unit
) {
  LazyRow(
    state = rowState,
    modifier = modifier,
    contentPadding = contentPadding,
    horizontalArrangement = horizontalArrangement,
    content = content
  )
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun HomeMainBrowsingContent(
  allMedia: List<MediaItem>,
  homeCatalogs: HomeCatalogs? = null,
  onMediaClick: (MediaItem) -> Unit,
  onPlayClick: (MediaItem) -> Unit,
  onRemoveFromContinueWatching: (String) -> Unit,
  onProviderClick: (StreamingProvider) -> Unit = {},
  viewModel: StreamNovaViewModel? = null,
  modifier: Modifier = Modifier
) {
  // Liste dinamiche dai dati reali TMDB sincronizzati nel repository.
  // Una card per TITOLO: quando il catalogo contiene due schede dello stesso contenuto (una
  // con id TMDB, una con id IMDb/Stremio) vince quella con il progresso più recente, così la
  // riga non mostra due volte lo stesso film (vedi `MediaIdentity`).
  val continueWatching = remember(allMedia) {
    MediaIdentity.dedupeByProgress(allMedia)
      .sortedByDescending { it.lastWatchedAt ?: 0L }
  }
  var contextMenuMedia by remember { mutableStateOf<MediaItem?>(null) }

  // Rimozioni da «Continua a guardare» in attesa della conferma di focus dentro la riga:
  // l'applicazione è in DUE TEMPI (vedi il commento nell'handler del menu contestuale), così
  // la card non viene mai smontata mentre un `focusRestorer` trattiene un PIN sul suo item.
  val pendingCwRemovals = remember { mutableStateListOf<String>() }

  val hasCustomCatalogMovies = homeCatalogs?.hasCustomCatalogMovies == true
  val hasCustomCatalogSeries = homeCatalogs?.hasCustomCatalogSeries == true

  val top10Movies = remember(homeCatalogs) {
    if (hasCustomCatalogMovies) emptyList()
    else homeCatalogs?.top10Movies?.take(StremioCatalogRepository.HOME_TOP_10_SIZE) ?: emptyList()
  }
  val top10Series = remember(homeCatalogs) {
    if (hasCustomCatalogSeries) emptyList()
    else homeCatalogs?.top10Series?.take(StremioCatalogRepository.HOME_TOP_10_SIZE) ?: emptyList()
  }
  val trendingMovies = remember(homeCatalogs) { if (hasCustomCatalogMovies) emptyList() else homeCatalogs?.trendingMovies ?: emptyList() }
  val trendingSeries = remember(homeCatalogs) { if (hasCustomCatalogSeries) emptyList() else homeCatalogs?.trendingSeries ?: emptyList() }
  val forYouMovies = remember(homeCatalogs) { if (hasCustomCatalogMovies) emptyList() else homeCatalogs?.forYouMovies ?: emptyList() }
  val forYouSeries = remember(homeCatalogs) { if (hasCustomCatalogSeries) emptyList() else homeCatalogs?.forYouSeries ?: emptyList() }

  val popularMovies = remember(homeCatalogs) { if (hasCustomCatalogMovies) emptyList() else homeCatalogs?.popularMovies ?: emptyList() }
  val popularSeries = remember(homeCatalogs) { if (hasCustomCatalogSeries) emptyList() else homeCatalogs?.popularSeries ?: emptyList() }

  // Hero Banner Dinamico: selezione di massimo 9 titoli alternati tra Film e Serie TV
  val dynamicHeroItems = remember(homeCatalogs, allMedia) {
    if (homeCatalogs != null && homeCatalogs.heroItems.isNotEmpty()) {
      homeCatalogs.heroItems
    } else {
      val movies = allMedia.filter { it.type == MediaType.FILM && (!it.backdropUrl.isNullOrBlank() || it.backdropRes != null) }
      val series = allMedia.filter { it.type == MediaType.SERIE_TV && (!it.backdropUrl.isNullOrBlank() || it.backdropRes != null) }
      val combined = mutableListOf<MediaItem>()
      val maxLen = maxOf(movies.size, series.size)
      for (i in 0 until maxLen) {
        if (i < movies.size && combined.size < 9 && !combined.any { it.id == movies[i].id }) {
          combined.add(movies[i])
        }
        if (i < series.size && combined.size < 9 && !combined.any { it.id == series[i].id }) {
          combined.add(series[i])
        }
      }
      if (combined.isEmpty()) allMedia.take(9) else combined.take(9)
    }
  }

  // Definizione ordinata di tutte le righe attive della Home
  val activeRowKeys = remember(
    dynamicHeroItems.isNotEmpty(),
    continueWatching,
    hasCustomCatalogMovies,
    hasCustomCatalogSeries,
    top10Movies,
    top10Series,
    trendingMovies,
    trendingSeries,
    forYouMovies,
    forYouSeries,
    popularMovies,
    popularSeries,
    homeCatalogs?.extraSections
  ) {
    buildList {
      if (dynamicHeroItems.isNotEmpty()) add("hero_banner")
      if (continueWatching.isNotEmpty()) add("section_continue_watching")
      add("section_providers_hub")
      if (!hasCustomCatalogMovies && top10Movies.isNotEmpty()) add("section_top_10_film")
      if (!hasCustomCatalogSeries && top10Series.isNotEmpty()) add("section_top_10_serie")
      if (!hasCustomCatalogMovies && trendingMovies.isNotEmpty()) add("section_trending_film")
      if (!hasCustomCatalogSeries && trendingSeries.isNotEmpty()) add("section_trending_serie")
      if (!hasCustomCatalogMovies && forYouMovies.isNotEmpty()) add("section_for_you_film")
      if (!hasCustomCatalogSeries && forYouSeries.isNotEmpty()) add("section_for_you_serie")
      if (!hasCustomCatalogMovies && popularMovies.isNotEmpty()) add("section_popolari_film")
      if (!hasCustomCatalogSeries && popularSeries.isNotEmpty()) add("section_popolari_serie")
      homeCatalogs?.extraSections?.forEach { extra ->
        if (extra.items.isNotEmpty()) {
          add("section_${extra.id}")
        }
      }
    }
  }

  val rowItemCounts = remember(
    dynamicHeroItems.size,
    continueWatching.size,
    top10Movies.size,
    top10Series.size,
    trendingMovies.size,
    trendingSeries.size,
    forYouMovies.size,
    forYouSeries.size,
    popularMovies.size,
    popularSeries.size,
    homeCatalogs?.extraSections
  ) {
    buildMap {
      // L'Hero e' una superficie unica di focus (nessun pulsante interno).
      put("hero_banner", 1)
      put("section_providers_hub", ProviderConstants.ALL.size)
      put("section_continue_watching", continueWatching.size)
      put("section_top_10_film", top10Movies.size)
      put("section_top_10_serie", top10Series.size)
      put("section_trending_film", trendingMovies.size)
      put("section_trending_serie", trendingSeries.size)
      put("section_for_you_film", forYouMovies.size)
      put("section_for_you_serie", forYouSeries.size)
      put("section_popolari_film", popularMovies.size)
      put("section_popolari_serie", popularSeries.size)
      homeCatalogs?.extraSections?.forEach { extra ->
        put("section_${extra.id}", extra.items.size)
      }
    }
  }

  // Identità ordinate dei contenuti di ciascuna riga, allineate ai conteggi: consentono al
  // coordinatore di riallineare l'indice di una richiesta pending quando un catalogo si riordina
  // o si accorcia, così la richiesta continua a puntare allo stesso contenuto. L'Hero resta una
  // superficie speciale priva di card identificate (movimento posizionale); l'hub provider adotta
  // gli id dei provider (lista statica) per il ripristino per identità al ritorno dalla scheda.
  val rowIdentities = remember(
    dynamicHeroItems.size,
    continueWatching.size,
    top10Movies.size,
    top10Series.size,
    trendingMovies.size,
    trendingSeries.size,
    forYouMovies.size,
    forYouSeries.size,
    popularMovies.size,
    popularSeries.size,
    homeCatalogs?.extraSections
  ) {
    buildMap {
      put("hero_banner", emptyList<String>())
      // Hub provider: identità = id del provider (lista statica). Rende la richiesta di
      // ripristino riallineabile per contenuto, non solo per posizione.
      put("section_providers_hub", ProviderConstants.ALL.map { it.id })
      put("section_continue_watching", continueWatching.map { it.id })
      put("section_top_10_film", top10Movies.map { it.id })
      put("section_top_10_serie", top10Series.map { it.id })
      put("section_trending_film", trendingMovies.map { it.id })
      put("section_trending_serie", trendingSeries.map { it.id })
      put("section_for_you_film", forYouMovies.map { it.id })
      put("section_for_you_serie", forYouSeries.map { it.id })
      put("section_popolari_film", popularMovies.map { it.id })
      put("section_popolari_serie", popularSeries.map { it.id })
      homeCatalogs?.extraSections?.forEach { extra ->
        put("section_${extra.id}", extra.items.map { it.id })
      }
    }
  }

  // UNICA istanza per tutta la vita della Home: sopravvive ai caricamenti asincroni dei
  // cataloghi. I dati delle righe vengono aggiornati sul posto, così i FocusRequester già
  // assegnati restano validi e la prima navigazione non parte con target azzerati.
  val coordinator = remember { HomeRowFocusCoordinator(activeRowKeys, rowItemCounts, rowIdentities) }

  // Le righe del coordinatore devono rispecchiare SEMPRE la composizione corrente che le
  // disegna. SideEffect gira dopo la composizione ma PRIMA del layout/draw del frame, quindi
  // nessun tasto può essere risolto su una lista di righe obsoleta: con l'aggiornamento in un
  // LaunchedEffect (un frame dopo) un Giù immediato poteva risolversi sulla riga sbagliata
  // (una riga saltata o la riga successiva a quella attesa).
  SideEffect {
    coordinator.updateRows(activeRowKeys, rowItemCounts, rowIdentities)
  }

  val handleRowMediaClick: (String, Int, MediaItem) -> Unit = { rowKey, colIndex, item ->
    coordinator.onCellFocused(rowKey, colIndex)
    viewModel?.saveLastFocusedMedia(rowKey, item.id)
    onMediaClick(item)
  }

  /**
   * Avvio della riproduzione da una card di riga (es. «Continua a guardare»).
   *
   * La provenienza della riproduzione (riga + ID del contenuto) viene registrata QUI, al
   * click: questa riproduzione non passa dal Dettaglio, quindi la destinazione del rientro va
   * decisa a posteriori, quando il player si chiude. Al BACK dal player si rientra nella
   * scheda DETTAGLIO del contenuto appena riprodotto — non sulla card di origine — e il focus
   * è quello della scheda (la sua card di riproduzione/ripresa del contenuto): la Home non
   * riceve alcun target di ripristino, perché il target vero e proprio verrebbe consumato dal
   * proprio ciclo di vita mentre la Home è ancora composta sotto l'overlay del player (vedi
   * [StreamNovaViewModel.savePlaybackFocusOrigin]).
   */
  val handleRowPlayClick: (String, MediaItem) -> Unit = { rowKey, item ->
    viewModel?.savePlaybackFocusOrigin(rowKey, item.id)
    onPlayClick(item)
  }

  val listState = rememberLazyListState()

  // Stato di scroll ORIZZONTALE persistente per OGNI riga della Home, chiavato sul rowKey.
  // Salvato con rememberSaveable: tornando dal Dettaglio (o anche dopo una morte del processo)
  // la LazyRow NON si rimonta resettata a offset 0, ma ritrova l'esatta posizione orizzontale.
  // Le istanze di LazyListState restano stabili tra le ricomposizioni (stessa istanza per
  // rowKey), quindi la riga non "salta" all'indice 0 al resume.
  val rowStates = rememberSaveable(
    saver = mapSaver<MutableMap<String, LazyListState>>(
      save = { states ->
        states.mapValues { (_, state) ->
          listOf(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset)
        }
      },
      restore = { saved ->
        saved.mapValues { (_, coords) ->
          @Suppress("UNCHECKED_CAST")
          val coordsList = coords as List<Int>
          LazyListState(coordsList[0], coordsList[1])
        }.toMutableMap()
      }
    )
  ) { mutableMapOf<String, LazyListState>() }

  fun getRowState(rowKey: String): LazyListState = rowStates.getOrPut(rowKey) { LazyListState() }

  // Un tempo qui viveva la mappa dei "requester di ingresso di riga" (Nuvio
  // rowFocusRequesters): rimossa perché attaccata a LazyRow non focalizzabili causava il
  // crash `FocusRequester is not initialized` al reingresso nel contenuto. Il reingresso
  // passa dal restorer del LazyColumn sulla CELLA corrente (più sotto).

  // Catena di focus completa di una riga (focusRestorer con memoria di colonna):
  // vedi [homeRowFocusGroup].
  fun rowGroupModifier(rowKey: String): Modifier = Modifier.homeRowFocusGroup(
    coordinator = coordinator,
    rowKey = rowKey,
    rowState = getRowState(rowKey)
  )

  // Stato del ripristino focus in arrivo dal Dettaglio. Finché `restoreMediaId` è valorizzato
  // il focus NON è ancora atterrato sulla card target: nessun autofocus (Hero o item 0 di una
  // LazyRow) deve rubare il focus. La card consuma il target quando il focus arriva davvero
  // (LocalClearLastFocusedMedia), quindi il ripristino non si perde se la richiesta fallisce.
  val restoreMediaId = LocalLastFocusedMediaId.current
  val restoreTargetPair = LocalLastFocusedTarget.current
  val savedRestoreRowKey = restoreTargetPair?.first?.takeIf { it.isNotEmpty() }
  val focusRestoreToken = LocalFocusRestoreToken.current
  val clearLastFocusedMedia = LocalClearLastFocusedMedia.current
  // Target di ripristino per le CARD PROVIDER (canale dedicato): al BACK dalla scheda Provider
  // il focus deve tornare sulla card provider di origine, non sull'Hero.
  val restoreProviderId = LocalLastFocusedProviderId.current
  val clearLastFocusedProvider = LocalClearLastFocusedProvider.current

  // Richiesta di focus sull'Hero ancora in attesa: al primo avvio della Home (e al click su
  // "Home" dalla Sidebar) i cataloghi TMDB possono non essere ancora arrivati, quindi la hero
  // non esiste ancora. La richiesta viene completata appena compare, cosi' il focus non resta
  // su una card provider qualunque.
  var pendingHeroFocus by remember { mutableStateOf(false) }

  // Letto al momento dell'evento senza riavviare il collettore (nessuna perdita di eventi).
  val heroItemsAvailable by rememberUpdatedState(dynamicHeroItems.isNotEmpty())

  // Scroll to top automatico cliccando "Home" dalla sidebar (Punto 4) e focus iniziale
  // della Home: entrambi devono appoggiarsi sull'Hero, prima cella del contenuto.
  // Il collettore resta agganciato UNA SOLA volta: se fosse riavviato ad ogni cambiamento
  // delle righe, un evento emesso nel frattempo andrebbe perso (SharedFlow senza replay).
  LaunchedEffect(viewModel) {
    viewModel?.homeScrollToTopEvent?.collect {
      listState.animateScrollToItem(0)
      try {
        if (heroItemsAvailable) {
          coordinator.getRequester("hero_banner", 0).requestFocus()
        } else {
          pendingHeroFocus = true
          coordinator.getRequester("section_providers_hub", 0).requestFocus()
        }
      } catch (_: Exception) {}
    }
  }

  LaunchedEffect(pendingHeroFocus, dynamicHeroItems.isNotEmpty()) {
    if (pendingHeroFocus && dynamicHeroItems.isNotEmpty()) {
      pendingHeroFocus = false
      try {
        coordinator.getRequester("hero_banner", 0).requestFocus()
      } catch (_: Exception) {}
    }
  }

  fun itemsOf(sectionKey: String): List<MediaItem> = when (sectionKey) {
    "hero_banner" -> dynamicHeroItems
    "section_continue_watching" -> continueWatching
    "section_top_10_film" -> top10Movies
    "section_top_10_serie" -> top10Series
    "section_trending_film" -> trendingMovies
    "section_trending_serie" -> trendingSeries
    "section_for_you_film" -> forYouMovies
    "section_for_you_serie" -> forYouSeries
    "section_popolari_film" -> popularMovies
    "section_popolari_serie" -> popularSeries
    else -> homeCatalogs?.extraSections?.find { "section_${it.id}" == sectionKey }?.items ?: emptyList()
  }

  /**
   * Riga di ripristino del focus al ritorno dal Dettaglio.
   *
   * Politica NuvioTV (ModernHomeContent.restoreFromSavedState): la riga salvata all'apertura
   * della scheda è la destinazione ASSOLUTA. Se esiste ancora tra le righe effettivamente
   * disegnate, il ripristino avviene LÌ — mai su un'altra riga, anche se lo stesso titolo vi
   * compare (le ondate TMDB possono duplicare un titolo in più sezioni: causa dei difetti
   * "ripristino su un'altra riga"). La ricerca per identità tra le righe resta solo per:
   * - i target SENZA riga esplicita ([FOCUS_ROW_ANY], superfici a griglia), oppure
   * - i casi in cui la riga salvata NON esiste più (sezione scomparsa dalla Home).
   *
   * Ritorna `null` se il titolo non è ripristinabile in nessuna riga (l'effetto di ripristino
   * rilascia il target e l'ancora di recupero assegna un punto di partenza deterministico).
   */
  fun restoreRowFor(mediaId: String, preferredRowKey: String?): String? {
    val savedRowUsable = preferredRowKey != null && preferredRowKey in activeRowKeys
    val resolved = if (savedRowUsable) {
      preferredRowKey
    } else when {
      continueWatching.any { it.id == mediaId } -> "section_continue_watching"
      top10Movies.any { it.id == mediaId } -> "section_top_10_film"
      top10Series.any { it.id == mediaId } -> "section_top_10_serie"
      trendingMovies.any { it.id == mediaId } -> "section_trending_film"
      trendingSeries.any { it.id == mediaId } -> "section_trending_serie"
      forYouMovies.any { it.id == mediaId } -> "section_for_you_film"
      forYouSeries.any { it.id == mediaId } -> "section_for_you_serie"
      popularMovies.any { it.id == mediaId } -> "section_popolari_film"
      popularSeries.any { it.id == mediaId } -> "section_popolari_serie"
      dynamicHeroItems.any { it.id == mediaId } -> "hero_banner"
      homeCatalogs?.extraSections?.any { section -> section.items.any { it.id == mediaId } } == true ->
        homeCatalogs.extraSections.find { section -> section.items.any { it.id == mediaId } }?.let { "section_${it.id}" }
      else -> null
    }
    // Solo le righe EFFETTIVAMENTE disegnate nella Home possono ripristinare il focus: una riga
    // non presente (cataloghi custom, sezione scomparsa) non ha celle e lascerebbe il target
    // appeso, con tutte le card non focalizzabili.
    return resolved?.takeIf { it in activeRowKeys }
  }

  /**
   * Ripristina la navigabilità dopo un ripristino fallito: ancora il focus sulla cella di
   * partenza (colonna ricordata nella riga da cui il focus era uscito, altrimenti la prima cella
   * montata di quella riga) e, se la Home è appena rientrata (nessuna riga di partenza), su una
   * riga interamente visibile — stessa politica d'ancoraggio del collector `homeScrollToTopEvent`
   * (Hero, poi le righe che seguono). Solo celle MONTATE come candidati: mai un requester orfano.
   */
  fun recoverNavigabilityAfterFailedRestore(): Boolean {
    // L'ancora è pubblicata come richiesta PENDENTE, non come `requestFocus` diretto:
    // l'acquisizione del focus appartiene a [HomeVerticalFocusSync], che la VERIFICA con
    // `onFocusChanged` e la ritenta finché la cella non è realmente focalizzata. Un
    // `requestFocus` secco emesso mentre la composizione si sta assestando (subito dopo la
    // chiusura del player) può essere scartato dal sistema di focus: il cursore resterebbe
    // senza focus (D-pad morto) anche se la cella è montata e focalizzabile.
    //
    // Candidati (vedi [HomeRowFocusCoordinator]): la cella di partenza nella riga da cui il
    // focus è uscito (colonna ricordata / prima cella montata della riga), altrimenti la prima
    // riga interamente visibile (Hero, poi le righe che seguono). Solo celle MONTATE: mai un
    // requester orfano.
    val departureRowKey = coordinator.focusedRowKey
    if (departureRowKey != null) {
      val columns = coordinator.lastFocusedColumn(departureRowKey)
        ?: coordinator.firstAttachedColumn(departureRowKey)
      if (columns != null && coordinator.isCellAttached(departureRowKey, columns)) {
        coordinator.requestPendingFocus(departureRowKey, columns)
        return true
      }
    }
    val viewportEnd = listState.layoutInfo.viewportEndOffset
    val visibleRowKeys = listState.layoutInfo.visibleItemsInfo
      .asSequence()
      .filter { it.offset >= 0 && (it.offset + it.size) <= viewportEnd }
      .mapNotNull { it.key as? String }
    for (rowKey in visibleRowKeys) {
      val column = coordinator.firstAttachedColumn(rowKey) ?: continue
      coordinator.requestPendingFocus(rowKey, column)
      return true
    }
    return false
  }

  // Riga che possiede DAVVERO il titolo da ripristinare: quella salvata se lo contiene ancora,
  // altrimenti la riga in cui il titolo vive adesso (i cataloghi possono cambiare mentre la
  // scheda Dettaglio è aperta). Solo QUESTA riga può ripristinare il focus: è l'unica le cui
  // card sono ancora focalizzabili durante il ripristino e l'unica che consuma il target.
  val restoreRowKey = restoreMediaId?.let { restoreRowFor(it, savedRestoreRowKey) }

  // Durante un rientro dal Dettaglio (restore attivo) l'unica superficie di focus autorizzata
  // nel contenuto è la card bersaglio: le card contempgono già il gate `canFocus`, ma l'Hero e
  // l'hub provider ne sono PRIVI. Le si chiude esplicitamente perché, al rimontaggio del
  // contenuto, il focus repair di Compose (il nodo focalizzato della scheda Dettaglio viene
  // smontato) può atterrare su una superficie non bersaglio: `onCellFocused` cancellerebbe la
  // richiesta pendente del coordinatore e il focus resterebbe fuori dal contenuto (difetto
  // "focus nella sidebar al rientro dopo una risalita"). Il gate è inerte fuori dal rientro
  // (restoreMediaId == null) e si apre quando il bersaglio È quella superficie.
  val restoreContentLocked = restoreMediaId != null
  val heroFocusLocked = restoreContentLocked && restoreRowKey != "hero_banner"
  val providersFocusLocked = restoreContentLocked && restoreRowKey != "section_providers_hub"

  // Traccia che un ripristino è stato avviato in questa vita della Home: quando it termina
  // senza conferma del focus (verdetto della sincronizzazione o destinazione sparita) il
  // recupero assegna un'ancora di focus deterministica invece di lasciare la Home senza cella
  // focalizzata. Al primo avvio (nessun ripristino) resta false: nessun effetto collaterale.
  var restoreWasActive by remember { mutableStateOf(false) }

  // Il target va rilasciato quando il focus è DAVVERO arrivato: senza questo, un ripristino
  // senza destinazione (o verso l'Hero, che non è una card) lascerebbe ogni card con
  // `canFocus = false`, cioè una Home non più navigabile.
  LaunchedEffect(coordinator.focusedCell, restoreMediaId, restoreRowKey) {
    if (restoreMediaId == null) return@LaunchedEffect
    if (restoreRowKey == null || coordinator.isCellFocused("hero_banner", 0)) {
      clearLastFocusedMedia?.invoke()
    }
  }

  // UNICO punto di ingresso del ripristino dal Dettaglio: risolve la destinazione (riga +
  // indice per identità) e registra LA richiesta pendente; l'acquisizione del focus appartiene
  // SOLO a HomeVerticalFocusSync (unico percorso autorevole: nessuna corsa parallela verso
  // requestFocus). Il token rende il ripristino ripetibile anche sullo STESSO titolo (ritorni
  // consecutivi dalla stessa locandina) e la richiesta resta pendente finché la card non
  // conferma il focus o non giunge a verdetto di fallimento (Protocollo 5-bis della sync).
  LaunchedEffect(restoreMediaId, restoreRowKey, focusRestoreToken) {
    val mediaId = restoreMediaId ?: return@LaunchedEffect
    restoreWasActive = true
    val sectionKey = restoreRowKey ?: return@LaunchedEffect
    if (sectionKey == "hero_banner") {
      coordinator.requestPendingFocus("hero_banner", 0)
      return@LaunchedEffect
    }
    val index = itemsOf(sectionKey).indexOfFirst { it.id == mediaId }
    if (index >= 0) {
      // 1. La LazyRow della sezione va INIZIALIZZATA prima della richiesta: se la riga è fuori
      //    dal viewport il suo LazyListState non esiste ancora, e senza di esso lo scroll
      //    orizzontale che monta la card bersaglio salterebbe del tutto.
      val rowState = getRowState(sectionKey)
      // 2. Richiesta pendente UNICA: da qui la completa HomeVerticalFocusSync (riga + colonna).
      coordinator.requestPendingFocus(sectionKey, index)
      // 3. Scroll orizzontale ANTICIPATO SOLO SE la colonna salvata non è già nella finestra
      //    della riga. La LazyRow si rimonta alla posizione salvata (rememberSaveable): il
      //    vecchio scrollToItem incondizionato rimontava la finestra con la card al bordo
      //    SINISTRO, spingendo fuori dal viewport le card precedenti (difetto Top 10: aprire la
      //    quarta card e tornare indietro toglieva di vista le prime tre).Quando la colonna è
      //    già in finestra la riga non si tocca: il focus arriva senza riallineamenti.
      val isColVisible = rowState.layoutInfo.visibleItemsInfo.any { it.index == index }
      if (!isColVisible && !rowState.isScrollInProgress) {
        try {
          rowState.scrollToItem(index)
        } catch (_: Exception) {
          // LazyRow non ancora agganciata: la posizione resta a carico della sincronizzazione.
        }
      }
    } else {
      // Il titolo non vive più nella riga di origine (onda TMDB mentre la scheda era aperta,
      // oppure contenuto tolto da «Continua a guardare» durante la riproduzione). Politica
      // Nuvio: NON si caccia nelle altre righe — il vecchio fallback ripristinava la card di
      // un'ALTRA riga (difetto "Spider-Man aperto da Popolari Film, rientro su un'altra riga").
      // Il target viene rilasciato: la Home resta interamente navigabile e l'ancora di
      // recupero assegna un punto di partenza deterministico — la cella di partenza nella riga
      // di origine se il coordinatore la ricorda (rientro dal player: la Home non è stata
      // smontata), altrimenti la prima riga interamente visibile.
      clearLastFocusedMedia?.invoke()
    }
  }

  // Ripristino del focus sulla CARD PROVIDER al ritorno dalla scheda Provider (canale dedicato,
  // vedi [StreamNovaViewModel.saveLastFocusedProvider]). La colonna è risolta per IDENTITÀ del
  // provider: l'hub è una lista statica ([ProviderConstants.ALL]), quindi l'indice è stabile e
  // non serve un id "media" inventato. La richiesta passa dall'UNICO percorso autorevole
  // (richiesta pendente + [HomeVerticalFocusSync]) come ogni ripristino della Home.
  LaunchedEffect(restoreProviderId, focusRestoreToken) {
    val providerId = restoreProviderId ?: return@LaunchedEffect
    val index = ProviderConstants.ALL.indexOfFirst { it.id == providerId }
    if (index < 0) {
      // Provider non più presente nell'hub: nessuna card potrebbe confermare il focus.
      clearLastFocusedProvider?.invoke()
      return@LaunchedEffect
    }
    val rowState = getRowState("section_providers_hub")
    val isColVisible = rowState.layoutInfo.visibleItemsInfo.any { it.index == index }
    if (!isColVisible && !rowState.isScrollInProgress) {
      try {
        rowState.scrollToItem(index)
      } catch (_: Exception) {
        // LazyRow non ancora agganciata: la posizione resta a carico della sincronizzazione.
      }
    }
    coordinator.requestPendingFocus("section_providers_hub", index)
  }

  // Il target provider si rilascia SOLO quando la card provider riceve DAVVERO il focus: come
  // per le card media, il rilascio avviene nell'`onFocusChanged` della card (evento reale, non
  // stato), così un click che lascia la card già focalizzata NON azzera il target prima della
  // navigazione (vedi [ProviderCard.onRestoreFocusConfirmed]).

  // Recupero della navigabilità quando un ripristino dal Dettaglio termina SENZA che il focus
  // sia arrivato sulla card bersaglio. Il verdetto è legato ai segnali REALI del ciclo di vita
  // della richiesta (richiesta consumata come fallita dalla sincronizzazione, oppure
  // destinazione sparita e target rilasciato): nessun timeout arbitrario. Senza questa ancora
  // tutte le card tornerebbero focalizzabili ma NESSUNA avrebbe il focus: il D-pad sembrerebbe
  // morto.
  LaunchedEffect(
    restoreWasActive,
    restoreMediaId,
    coordinator.pendingFocusRequest,
    coordinator.focusedCell
  ) {
    if (!restoreWasActive || restoreMediaId != null) return@LaunchedEffect
    restoreWasActive = false
    // Il focus è già atterrato su qualche cella (card bersaglio, Hero o navigazione utente):
    // nessun recupero necessario.
    if (coordinator.focusedCell != null) return@LaunchedEffect
    // Una richiesta vivente detiene ancora il ciclo di vita (self-healing in corso).
    if (coordinator.pendingFocusRequest != null) return@LaunchedEffect
    recoverNavigabilityAfterFailedRestore()
  }

  // SECONDO TEMPO della rimozione da «Continua a guardare» (il primo è nell'handler del menu
  // contestuale). La card viene smontata SOLO con il focus confermato DENTRO la riga: è
  // l'ingresso nel gruppo della riga che fa eseguire l'`enter` dei `focusRestorer` (riga e
  // LazyColumn), e quella è l'unica release dei PIN che avviene con l'item ancora VIVO.
  // Smontare la card con il pin trattenuto azzera il contatore del pin senza avvisare i
  // restorer, che restano con un handle MORTO: la loro `release()` successiva — rientro nella
  // riga, uscita, o `onDetach` quando la Home viene smontata (uscita dalla schermata, cambio
  // sezione dalla sidebar) — esplode con `IllegalStateException: "Release should only be
  // called once"`. Mai una rimozione con il focus fuori dalla riga.
  LaunchedEffect(pendingCwRemovals.isEmpty()) {
    if (pendingCwRemovals.isEmpty()) return@LaunchedEffect

    // Fase 1 — rientro richiesto finché NESSUNA cella ha il focus (il menu contestuale è appena
    // stato smontato e il suo item era quello focalizzato), con un tetto di tentativi.
    var retriesLeft = CW_REMOVAL_FOCUS_RETRIES
    while (pendingCwRemovals.isNotEmpty() &&
      coordinator.focusedCell == null &&
      retriesLeft > 0
    ) {
      retriesLeft--
      val count = coordinator.rowItemCounts[CONTINUE_WATCHING_ROW_KEY] ?: 0
      if (count > 0) {
        val column = continueWatching.indexOfFirst { it.id == pendingCwRemovals.first() }
          .takeIf { it >= 0 }
          ?: coordinator.lastFocusedColumn(CONTINUE_WATCHING_ROW_KEY)
          ?: coordinator.firstAttachedColumn(CONTINUE_WATCHING_ROW_KEY)
          ?: 0
        coordinator.requestPendingFocus(
          CONTINUE_WATCHING_ROW_KEY,
          column.coerceIn(0, count - 1)
        )
      }
      awaitCondition(CW_REMOVAL_FOCUS_TIMEOUT_MS) {
        coordinator.focusedCell != null || pendingCwRemovals.isEmpty()
      }
    }

    // Fase 2 — attesa REATTIVA del rientro nella riga: nessun ciclo periodico, nessun furto del
    // focus. Se l'utente naviga altrove la rimozione resta in attesa (finché la card non viene
    // smontata lo stato è coerente) e si applica appena il focus rientra nella riga.
    if (pendingCwRemovals.isNotEmpty() &&
      coordinator.focusedCell?.first != CONTINUE_WATCHING_ROW_KEY
    ) {
      snapshotFlow { coordinator.focusedCell }
        .first { it?.first == CONTINUE_WATCHING_ROW_KEY || pendingCwRemovals.isEmpty() }
    }
    if (pendingCwRemovals.isEmpty()) return@LaunchedEffect

    val removedIds = pendingCwRemovals.toList()
    pendingCwRemovals.clear()
    removedIds.forEach { onRemoveFromContinueWatching(it) }

    // Ancoraggio canonico della Home: la card rimossa era quella con il focus, quindi il
    // cursore va riportato su una cella valida PRIMA che il primo tasto venga risolto dalla
    // ricerca geometrica (che a sinistra trova la sidebar). L'Hero è la cella canonica; se non
    // è disegnato (catalogo senza hero) o se è la riga stessa a sparire con l'ultima card, si
    // ripiega sulla prima riga che sopravvive alla rimozione. Il conteggio viene dal
    // coordinatore (dati vivi della composizione corrente), non da una lista catturata.
    val cwRowWillBeEmpty =
      (coordinator.rowItemCounts[CONTINUE_WATCHING_ROW_KEY] ?: 0) - removedIds.size <= 0
    val anchorRow = HOME_CONTENT_ANCHOR_ROW.takeIf { it in coordinator.activeRowKeys }
      ?: coordinator.activeRowKeys.firstOrNull {
        !(cwRowWillBeEmpty && it == CONTINUE_WATCHING_ROW_KEY)
      }
      ?: coordinator.activeRowKeys.firstOrNull()
    if (anchorRow != null) {
      coordinator.requestPendingFocus(anchorRow, 0)
    }
  }

  TvFocusBringIntoView(topOffsetDp = 0.dp) {
  HomeVerticalFocusSync(
    coordinator = coordinator,
    listState = listState,
    // Solo la riga del target può muovere la LazyColumn ed è l'unico scroll verticale della
    // Home: non esistono altri effetti che spostano la lista su un cambio di stato.
    rowStateFor = { rowKey -> rowStates[rowKey] },
    // Verdetto di fallimento di un ripristino (emesso dalla sincronizzazione: stesso ciclo di
    // vita della richiesta, nessun timeout esterno): si rilascia lo stato del ripristino così
    // il gate `canFocus` si apre e le card tornano focalizzabili; l'ancora di recupero è
    // assegnata dall'effetto dedicato (sotto) solo se nessuna cella detiene il focus.
    onRestoreFocusFailed = { _, _ ->
      clearLastFocusedMedia?.invoke()
    }
  )
  LazyColumn(
    state = listState,
    modifier = modifier
      .fillMaxSize()
      .background(NovaBackground)
      // Il contenuto non ha (più) il focus: nessuna cella può essere considerata focalizzata.
      // Uscendo dal contenuto (overlay del player sopra la Home, focus alla sidebar) le card
      // non emettono una perdita utile e la conferma del coordinatore resterebbe quella di
      // prima: la verifica della sincronizzazione risulterebbe vera per una cella che non ha
      // più il focus e il ripristino verrebbe consumato senza spostare nulla.
      .onFocusChanged { focusState ->
        if (!focusState.hasFocus) coordinator.onCellFocusInvalidated()
      }
      // Nuvio ModernHomeRowsList: quando il focus (ri)entra nel contenuto delle righe da fuori
      // (ripristino senza target, ritorno dalla sidebar) il restorer lo rimanda alla CELLA
      // della riga attualmente focalizzata, che a sua volta ripristina la propria colonna
      // ricordata. `Default` = ricerca nativa (prima riga visibile).
      .focusRestorer {
        // Reingresso nel contenuto delle righe da fuori (ripristino senza target, ritorno
        // dalla sidebar): il focus va sulla CELLA della riga attualmente focalizzata, che a
        // sua volta la riga rimanda alla propria colonna ricordata. MAI il vecchio
        // "requester di ingresso di riga": era agganciato alla LazyRow — nodo NON
        // focalizzabile — e ogni richiesta tramite lui finiva in
        // `IllegalStateException "FocusRequester is not initialized"` (crash).
        // `getTargetRequester` restituisce SOLO celle montate o null, quindi mai un
        // requester non inizializzato.
        //
        // MENTRE una richiesta AUTOREVOLE è in volo (ripristino dal Dettaglio, atterraggio
        // fast-scroll o movimento verticale pendente) questo restorer NON INTERVIENE: la
        // discesa del protocollo attraversa il gruppo del LazyColumn e senza questa guardia
        // il restorer ridirigeva la richiesta verso la riga ANCORA ufficialmente focalizzata
        // (le cui card sono non focalizzabili per il gate `canFocus`) annientando il
        // trasferimento (focus = nessuno; difetto "il rientro non converge").
        if (coordinator.pendingFocusRequest != null) {
          FocusRequester.Default
        } else {
          val rowKey = coordinator.focusedRowKey
          val isRowComposed = rowKey != null &&
            listState.layoutInfo.visibleItemsInfo.any { (it.key as? String) == rowKey }
          if (rowKey != null && isRowComposed) {
            coordinator.getTargetRequester(rowKey, coordinator.lastFocusedColumn(rowKey) ?: 0)
              ?: FocusRequester.Default
          } else {
            FocusRequester.Default
          }
        }
      }
      // Tasti ripetuti (tasto TENUTO): port del takeover di NuvioTV — il focus resta sulla card
      // di partenza mentre la lista scorre e al rilascio atterra sulla colonna RICORDATA della
      // riga di destinazione (mod. resolveVerticalLanding). Le prime pressioni NON sono
      // consumate da qui: lo step singolo è risolto da [homeVerticalStepKeys] (ordine esplicito
      // delle righe), così la ricerca geometrica non può far uscire il focus dal contenuto.
      // L'Hero è esclusa da entrambi: gestisce i propri tasti via coordinatore.
      .homeVerticalStepKeys(coordinator)
      .dpadVerticalFastScroll(
        scrollableState = listState,
        shouldEngage = {
          val rowKey = coordinator.focusedRowKey
          rowKey != null && rowKey != "hero_banner"
        },
        shouldHaltForward = {
          val info = listState.layoutInfo
          val lastRowKey = coordinator.activeRowKeys.lastOrNull()
          val lastVisible = info.visibleItemsInfo.lastOrNull { (it.key as? String) == lastRowKey }
          lastVisible != null && (lastVisible.offset + lastVisible.size) <= info.viewportEndOffset
        },
        resolveVerticalLanding = { sign ->
          val info = listState.layoutInfo
          val visibleRows = info.visibleItemsInfo.mapNotNull { item ->
            (item.key as? String)?.let { DpadVisibleRowBounds(it, item.offset, item.size) }
          }
          val targetRowKey = resolveFastScrollLandingRowKey(
            visibleRows = visibleRows,
            viewportEndOffset = info.viewportEndOffset,
            sign = sign,
            rowKeysInOrder = coordinator.activeRowKeys
          ) ?: return@dpadVerticalFastScroll null
          val count = (coordinator.rowItemCounts[targetRowKey] ?: 0)
          if (count <= 0) return@dpadVerticalFastScroll null
          val col = (coordinator.lastFocusedColumn(targetRowKey) ?: 0)
            .coerceIn(0, (count - 1).coerceAtLeast(0))
          // Come Nuvio (activeRowKey/activeItemIndex + self-claim): la riga e la colonna
          // RICORDATA diventano il nuovo bersaglio. Il completamento resta affidato
          // all'unico percorso autorevole (richiesta pendente -> HomeVerticalFocusSync),
          // che porta la riga INTERAMENTE nel viewport prima di spostare il focus.
          coordinator.requestPendingFocus(targetRowKey, col)
          targetRowKey
        }
      ),
    contentPadding = PaddingValues(top = 16.dp, bottom = 60.dp)
  ) {
    // 1. Hero / Banner Principale Cinematografico Dinamico (max 9 titoli)
    if (dynamicHeroItems.isNotEmpty()) {
      item(key = "hero_banner") {
        DisposableEffect(coordinator) {
          coordinator.registerCell("hero_banner", 0)
          onDispose {
            coordinator.unregisterCell("hero_banner", 0)
          }
        }
        Column(
          modifier = Modifier.onFocusChanged {
            if (it.hasFocus) {
              coordinator.onCellFocused("hero_banner", 0)
            }
          }
        ) {
          HeroBanner(
            items = dynamicHeroItems,
            // OK/Enter e click sull'Hero aprono la scheda del titolo corrente: i pulsanti
            // azione non esistono piu', l'intero banner e' il target di focus.
            onDetailsClick = { handleRowMediaClick("hero_banner", 0, it) },
            heroFocusRequester = coordinator.getRequester("hero_banner", 0),
            // Hero va alla riga successiva determinata dal coordinatore (movimento dello
            // stesso coordinatore usato dalle righe poster: nessuna scorciatoia dedicata).
            onHeroMoveDown = {
              coordinator.requestVerticalMove("hero_banner", null, HomeVerticalDirection.DOWN)
            },
            onHeroFocusChanged = { if (it) coordinator.onCellFocused("hero_banner", 0) },
            modifier = Modifier.focusProperties { canFocus = !heroFocusLocked }
          )
          Spacer(modifier = Modifier.height(16.dp))
        }
      }
    }

    // 2. "Continua a guardare" (se ci sono titoli iniziati)
    if (continueWatching.isNotEmpty()) {
      item(key = "section_continue_watching") {
        CarouselHeader(
          title = stringResource(R.string.home_section_continue_watching),
          badge = stringResource(R.string.home_badge_in_progress, continueWatching.size)
        )
        TvFocusRow {
          HomeMediaLazyRow(
            rowState = getRowState("section_continue_watching"),
            restoreMediaId = restoreMediaId,
            items = continueWatching,
            modifier = rowGroupModifier("section_continue_watching"),
            contentPadding = PaddingValues(horizontal = 32.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
          ) {
            itemsIndexed(continueWatching, key = { _, it -> "cw_${it.id}" }, contentType = { _, _ -> "continue_watching" }) { index, item ->
              ContinueWatchingCard(
                media = item,
                onClick = {
                  Log.d("HomeScreen", "ContinueWatchingCard onClick (resume playback) called for ${item.title}")
                  handleRowPlayClick("section_continue_watching", item)
                },
                onLongPress = {
                  Log.d("HomeScreen", "ContinueWatchingCard onLongPress called for ${item.title}")
                  contextMenuMedia = item
                },
                modifier = Modifier.rowFocusProperties(coordinator, "section_continue_watching", index),
                focusRequester = coordinator.getRequester("section_continue_watching", index),
                rowKey = "section_continue_watching",
                isRestoreTarget = (restoreMediaId == item.id && (restoreRowKey == null || restoreRowKey == "section_continue_watching"))
              )
            }
          }
        }
        Spacer(modifier = Modifier.height(20.dp))
      }
    }

    // 3. Hub Streaming Providers (Netflix, HBO, Disney+, Prime Video)
    item(key = "section_providers_hub") {
      CarouselHeader(
        title = stringResource(R.string.home_section_streaming_platforms),
        badge = stringResource(R.string.home_badge_select_provider)
      )
      TvFocusRow {
        LazyRow(
          state = getRowState("section_providers_hub"),
          modifier = Modifier
            .fillMaxWidth()
            .focusProperties { canFocus = !providersFocusLocked }
            .homeRowFocusGroup(
              coordinator = coordinator,
              rowKey = "section_providers_hub",
              rowState = getRowState("section_providers_hub")
            )
            .height(125.dp),
          contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp),
          horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
          itemsIndexed(ProviderConstants.ALL, key = { _, it -> it.id }, contentType = { _, _ -> "provider" }) { index, provider ->
            ProviderCard(
              provider = provider,
              onClick = {
                // Provenienza del focus: la card PROVIDER di origine. Al BACK dalla scheda il
                // focus deve tornare QUI, non sull'Hero (canale dedicato ai provider).
                viewModel?.saveLastFocusedProvider(provider.id)
                onProviderClick(provider)
              },
              modifier = Modifier.rowFocusProperties(coordinator, "section_providers_hub", index),
              focusRequester = coordinator.getRequester("section_providers_hub", index),
              isRestoreTarget = restoreProviderId == provider.id,
              onRestoreFocusConfirmed = { clearLastFocusedProvider?.invoke() }
            )
          }
        }
      }
      Spacer(modifier = Modifier.height(16.dp))
    }

    // 4. TOP 10 FILM (Card con numero gigante stilizzato)
    if (!hasCustomCatalogMovies && top10Movies.isNotEmpty()) {
      item(key = "section_top_10_film") {
        CarouselHeader(
          title = stringResource(R.string.home_section_top_10_movies),
          badge = stringResource(R.string.home_badge_tmdb_ranking_italy)
        )
        TvFocusRow {
          HomeMediaLazyRow(
            rowState = getRowState("section_top_10_film"),
            restoreMediaId = restoreMediaId,
            items = top10Movies,
            modifier = rowGroupModifier("section_top_10_film"),
            contentPadding = PaddingValues(start = 24.dp, end = 32.dp, top = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(Top10RowSpacing)
          ) {
            itemsIndexed(top10Movies, key = { _, item -> "top10_film_${item.id}" }, contentType = { _, _ -> "top10" }) { index, item ->
              Top10RankedMediaCard(
                rank = index + 1,
                media = item,
                onClick = { handleRowMediaClick("section_top_10_film", index, item) },
                modifier = Modifier.rowFocusProperties(coordinator, "section_top_10_film", index),
                focusRequester = coordinator.getRequester("section_top_10_film", index),
                rowKey = "section_top_10_film",
                isRestoreTarget = (restoreMediaId == item.id && (restoreRowKey == null || restoreRowKey == "section_top_10_film"))
              )
            }
          }
        }
        Spacer(modifier = Modifier.height(20.dp))
      }
    }

    // 5. TOP 10 SERIE (Card con numero gigante stilizzato)
    if (!hasCustomCatalogSeries && top10Series.isNotEmpty()) {
      item(key = "section_top_10_serie") {
        CarouselHeader(
          title = stringResource(R.string.home_section_top_10_series),
          badge = stringResource(R.string.home_badge_most_watched_tmdb)
        )
        TvFocusRow {
          HomeMediaLazyRow(
            rowState = getRowState("section_top_10_serie"),
            restoreMediaId = restoreMediaId,
            items = top10Series,
            modifier = rowGroupModifier("section_top_10_serie"),
            contentPadding = PaddingValues(start = 24.dp, end = 32.dp, top = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(Top10RowSpacing)
          ) {
            itemsIndexed(top10Series, key = { _, item -> "top10_serie_${item.id}" }, contentType = { _, _ -> "top10" }) { index, item ->
              Top10RankedMediaCard(
                rank = index + 1,
                media = item,
                onClick = { handleRowMediaClick("section_top_10_serie", index, item) },
                modifier = Modifier.rowFocusProperties(coordinator, "section_top_10_serie", index),
                focusRequester = coordinator.getRequester("section_top_10_serie", index),
                rowKey = "section_top_10_serie",
                isRestoreTarget = (restoreMediaId == item.id && (restoreRowKey == null || restoreRowKey == "section_top_10_serie"))
              )
            }
          }
        }
        Spacer(modifier = Modifier.height(20.dp))
      }
    }

    // 6. TRENDING FILM
    if (!hasCustomCatalogMovies && trendingMovies.isNotEmpty()) {
      item(key = "section_trending_film") {
        CarouselHeader(
          title = stringResource(R.string.home_section_trending_movies),
          badge = stringResource(R.string.home_badge_trending_week)
        )
        TvFocusRow {
          HomeMediaLazyRow(
            rowState = getRowState("section_trending_film"),
            restoreMediaId = restoreMediaId,
            items = trendingMovies,
            modifier = rowGroupModifier("section_trending_film"),
            contentPadding = PaddingValues(horizontal = 32.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
          ) {
            itemsIndexed(trendingMovies, key = { _, item -> "trend_m_${item.id}" }, contentType = { _, _ -> "poster" }) { index, item ->
              PosterMediaCard(
                media = item,
                onClick = { handleRowMediaClick("section_trending_film", index, item) },
                modifier = Modifier.rowFocusProperties(coordinator, "section_trending_film", index),
                focusRequester = coordinator.getRequester("section_trending_film", index),
                rowKey = "section_trending_film",
                isRestoreTarget = (restoreMediaId == item.id && (restoreRowKey == null || restoreRowKey == "section_trending_film"))
              )
            }
          }
        }
        Spacer(modifier = Modifier.height(20.dp))
      }
    }

    // 7. TRENDING SERIE
    if (!hasCustomCatalogSeries && trendingSeries.isNotEmpty()) {
      item(key = "section_trending_serie") {
        CarouselHeader(
          title = stringResource(R.string.home_section_trending_series),
          badge = stringResource(R.string.home_badge_trending_phenomena)
        )
        TvFocusRow {
          HomeMediaLazyRow(
            rowState = getRowState("section_trending_serie"),
            restoreMediaId = restoreMediaId,
            items = trendingSeries,
            modifier = rowGroupModifier("section_trending_serie"),
            contentPadding = PaddingValues(horizontal = 32.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
          ) {
            itemsIndexed(trendingSeries, key = { _, item -> "trend_s_${item.id}" }, contentType = { _, _ -> "poster" }) { index, item ->
              PosterMediaCard(
                media = item,
                onClick = { handleRowMediaClick("section_trending_serie", index, item) },
                modifier = Modifier.rowFocusProperties(coordinator, "section_trending_serie", index),
                focusRequester = coordinator.getRequester("section_trending_serie", index),
                rowKey = "section_trending_serie",
                isRestoreTarget = (restoreMediaId == item.id && (restoreRowKey == null || restoreRowKey == "section_trending_serie"))
              )
            }
          }
        }
        Spacer(modifier = Modifier.height(20.dp))
      }
    }

    // 8. FOR YOU FILM (Consigliati per te - Film)
    if (!hasCustomCatalogMovies && forYouMovies.isNotEmpty()) {
      item(key = "section_for_you_film") {
        CarouselHeader(
          title = stringResource(R.string.home_section_for_you_movies),
          badge = stringResource(R.string.home_badge_based_on_preferences)
        )
        TvFocusRow {
          HomeMediaLazyRow(
            rowState = getRowState("section_for_you_film"),
            restoreMediaId = restoreMediaId,
            items = forYouMovies,
            modifier = rowGroupModifier("section_for_you_film"),
            contentPadding = PaddingValues(horizontal = 32.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
          ) {
            itemsIndexed(forYouMovies, key = { _, item -> "foryou_m_${item.id}" }, contentType = { _, _ -> "poster" }) { index, item ->
              StandardMediaCard(
                media = item,
                onClick = { handleRowMediaClick("section_for_you_film", index, item) },
                modifier = Modifier.rowFocusProperties(coordinator, "section_for_you_film", index),
                focusRequester = coordinator.getRequester("section_for_you_film", index),
                rowKey = "section_for_you_film",
                isRestoreTarget = (restoreMediaId == item.id && (restoreRowKey == null || restoreRowKey == "section_for_you_film"))
              )
            }
          }
        }
        Spacer(modifier = Modifier.height(20.dp))
      }
    }

    // 9. FOR YOU SERIE (Consigliate per te - Serie TV)
    if (!hasCustomCatalogSeries && forYouSeries.isNotEmpty()) {
      item(key = "section_for_you_serie") {
        CarouselHeader(
          title = stringResource(R.string.home_section_for_you_series),
          badge = stringResource(R.string.home_badge_suggested_for_you)
        )
        TvFocusRow {
          HomeMediaLazyRow(
            rowState = getRowState("section_for_you_serie"),
            restoreMediaId = restoreMediaId,
            items = forYouSeries,
            modifier = rowGroupModifier("section_for_you_serie"),
            contentPadding = PaddingValues(horizontal = 32.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
          ) {
            itemsIndexed(forYouSeries, key = { _, item -> "foryou_s_${item.id}" }, contentType = { _, _ -> "poster" }) { index, item ->
              StandardMediaCard(
                media = item,
                onClick = { handleRowMediaClick("section_for_you_serie", index, item) },
                modifier = Modifier.rowFocusProperties(coordinator, "section_for_you_serie", index),
                focusRequester = coordinator.getRequester("section_for_you_serie", index),
                rowKey = "section_for_you_serie",
                isRestoreTarget = (restoreMediaId == item.id && (restoreRowKey == null || restoreRowKey == "section_for_you_serie"))
              )
            }
          }
        }
        Spacer(modifier = Modifier.height(20.dp))
      }
    }

    // 10. POPOLARI FILM
    if (!hasCustomCatalogMovies && popularMovies.isNotEmpty()) {
      item(key = "section_popolari_film") {
        CarouselHeader(
          title = stringResource(R.string.home_section_popular_movies),
          badge = stringResource(R.string.home_badge_most_watched_cinema)
        )
        TvFocusRow {
          HomeMediaLazyRow(
            rowState = getRowState("section_popolari_film"),
            restoreMediaId = restoreMediaId,
            items = popularMovies,
            modifier = rowGroupModifier("section_popolari_film"),
            contentPadding = PaddingValues(horizontal = 32.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
          ) {
            itemsIndexed(popularMovies, key = { _, item -> "pop_m_${item.id}" }, contentType = { _, _ -> "poster" }) { index, item ->
              PosterMediaCard(
                media = item,
                onClick = { handleRowMediaClick("section_popolari_film", index, item) },
                modifier = Modifier.rowFocusProperties(coordinator, "section_popolari_film", index),
                focusRequester = coordinator.getRequester("section_popolari_film", index),
                rowKey = "section_popolari_film",
                isRestoreTarget = (restoreMediaId == item.id && (restoreRowKey == null || restoreRowKey == "section_popolari_film"))
              )
            }
          }
        }
        Spacer(modifier = Modifier.height(20.dp))
      }
    }

    // 11. POPOLARI SERIE
    if (!hasCustomCatalogSeries && popularSeries.isNotEmpty()) {
      item(key = "section_popolari_serie") {
        CarouselHeader(
          title = stringResource(R.string.home_section_popular_series),
          badge = stringResource(R.string.home_badge_most_discussed_series)
        )
        TvFocusRow {
          HomeMediaLazyRow(
            rowState = getRowState("section_popolari_serie"),
            restoreMediaId = restoreMediaId,
            items = popularSeries,
            modifier = rowGroupModifier("section_popolari_serie"),
            contentPadding = PaddingValues(horizontal = 32.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
          ) {
            itemsIndexed(popularSeries, key = { _, item -> "pop_s_${item.id}" }, contentType = { _, _ -> "poster" }) { index, item ->
              PosterMediaCard(
                media = item,
                onClick = { handleRowMediaClick("section_popolari_serie", index, item) },
                modifier = Modifier.rowFocusProperties(coordinator, "section_popolari_serie", index),
                focusRequester = coordinator.getRequester("section_popolari_serie", index),
                rowKey = "section_popolari_serie",
                isRestoreTarget = (restoreMediaId == item.id && (restoreRowKey == null || restoreRowKey == "section_popolari_serie"))
              )
            }
          }
        }
        Spacer(modifier = Modifier.height(20.dp))
      }
    }

    // 12. Sezioni catalogo Stremio (addon)
    homeCatalogs?.extraSections?.forEach { extraSec ->
      if (extraSec.items.isNotEmpty()) {
        item(key = "section_${extraSec.id}") {
          CarouselHeader(
            title = extraSec.title,
            badge = extraSec.addonName
          )
          TvFocusRow {
            HomeMediaLazyRow(
              rowState = getRowState("section_${extraSec.id}"),
              restoreMediaId = restoreMediaId,
              items = extraSec.items,
              modifier = rowGroupModifier("section_${extraSec.id}"),
              contentPadding = PaddingValues(horizontal = 32.dp, vertical = 8.dp),
              horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
              itemsIndexed(extraSec.items, key = { _, item -> "extra_${extraSec.id}_${item.id}" }, contentType = { _, _ -> "poster" }) { index, item ->
                PosterMediaCard(
                  media = item,
                  onClick = { handleRowMediaClick("section_${extraSec.id}", index, item) },
                  modifier = Modifier.rowFocusProperties(coordinator, "section_${extraSec.id}", index),
                  focusRequester = coordinator.getRequester("section_${extraSec.id}", index),
                  rowKey = "section_${extraSec.id}",
                  isRestoreTarget = (restoreMediaId == item.id && (restoreRowKey == null || restoreRowKey == "section_${extraSec.id}"))
                )
              }
            }
          }
          Spacer(modifier = Modifier.height(20.dp))
        }
      }
    }
  }
  }

  // Context menu per long-press su Continue Watching (posto fuori da LazyColumn per essere in composable scope)
  if (contextMenuMedia != null) {
    val media = contextMenuMedia!!
    val continueIndex = continueWatching.indexOfFirst { it.id == media.id }.coerceAtLeast(0)
    MediaContextMenu(
      media = media,
      onPlay = { handleRowPlayClick("section_continue_watching", media) },
      onDetails = { handleRowMediaClick("section_continue_watching", continueIndex, media) },
      onRemoveFromContinueWatching = {
        // PRIMO TEMPO della rimozione: si accoda e si riporta il focus DENTRO la riga. La card
        // NON si smonta qui.
        //
        // Causa dimostrata del crash: il menu contestuale è un overlay nella STESSA finestra,
        // quindi prendendo il focus lo fa USCIRE dal gruppo della riga e i `focusRestorer`
        // (riga e LazyColumn) nel loro `exit` eseguono `pinFocusedChild()`, trattenendo un PIN
        // sull'item della card (`LazyLayoutPinnableItem.pin`). Smontando la card con quel pin
        // ancora trattenuto, il suo smontaggio azzera il contatore del pin
        // (`LazyLayoutPinnableItem.onDisposed`) lasciando ai restorer un handle MORTO: alla
        // successiva `release()` — rientro nella riga, uscita, o `onDetach` quando la Home
        // viene smontata (uscita dalla schermata o selezione di una voce della sidebar) —
        // Compose lancia `IllegalStateException: "Release should only be called once"`.
        //
        // Qui si emette SOLO la richiesta di rientro nella riga (percorso autorevole:
        // richiesta pendente completata da [HomeVerticalFocusSync]); l'applicazione avviene nel
        // secondo tempo, appena il focus è confermato dentro la riga (vedi l'effetto dedicato),
        // che è la condizione in cui i restorer hanno già rilasciato i loro pin.
        if (media.id !in pendingCwRemovals) {
          pendingCwRemovals.add(media.id)
        }
        // Il menu ha appena preso il focus: la conferma di focus delle celle è STANTIA (sarebbe
        // quella della card di prima del long-press). Invalidarla è ciò che obbliga il secondo
        // tempo ad attendere una conferma NUOVA dentro la riga, cioè il vero rientro.
        coordinator.onCellFocusInvalidated()
        val cwCount = coordinator.rowItemCounts[CONTINUE_WATCHING_ROW_KEY] ?: 0
        if (cwCount > 0) {
          coordinator.requestPendingFocus(
            CONTINUE_WATCHING_ROW_KEY,
            continueIndex.coerceIn(0, cwCount - 1)
          )
        }
      },
      onDismiss = { contextMenuMedia = null }
    )
  }
}

@Composable
fun CarouselHeader(
  title: String,
  badge: String? = null,
  modifier: Modifier = Modifier
) {
  Row(
    modifier = modifier
      .fillMaxWidth()
      .padding(start = 32.dp, end = 32.dp, top = 8.dp, bottom = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
      Text(
        text = title,
        color = NovaTextPrimary,
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.3.sp
      )
      if (badge != null) {
        Text(
          text = "• $badge",
          color = NovaCyanBright,
          fontSize = 12.sp,
          fontWeight = FontWeight.SemiBold
        )
      }
    }
  }
}

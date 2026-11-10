package com.example

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.data.model.Episode
import com.example.data.model.MediaDetailUiState
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.prefs.StreamingEngineMode
import com.example.data.repository.SimklSyncManager
import com.example.data.streaming.StreamResult
import com.example.data.update.UpdateRepository
import com.example.data.update.shouldShowStartupUpdatePrompt
import com.example.ui.components.HeroCarouselState
import com.example.ui.components.AnchoredRowKeyState
import com.example.ui.components.ModalOverlayState
import com.example.ui.components.ProviderConstants
import com.example.ui.components.TvPinDialog
import com.example.ui.components.SidebarNavigation
import com.example.ui.components.TorBoxSourceSelectionScreen
import com.example.ui.components.StreamStatusOverlay
import com.example.ui.components.StreamingProvider
import com.example.ui.components.LocalLastFocusedMediaId
import com.example.ui.components.LocalLastFocusedTarget
import com.example.ui.components.LocalFocusRestoreToken
import com.example.ui.components.LocalClearLastFocusedMedia
import com.example.ui.components.LocalLastFocusedProviderId
import com.example.ui.components.LocalClearLastFocusedProvider
import com.example.ui.navigation.DetailNavArgs
import com.example.ui.navigation.Screen
import com.example.ui.screens.DetailScreen
import com.example.ui.screens.HomeBackToRowStartState
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.PlayerScreen
import com.example.ui.screens.ProviderScreen
import com.example.ui.screens.PersonDetailScreen
import com.example.ui.screens.CompanyDetailScreen
import com.example.ui.screens.TrailerErrorOverlay
import com.example.ui.screens.TrailerLoadingOverlay
import com.example.ui.screens.TrailerPlayerScreen
import com.example.ui.screens.UpdatePromptDialog
import com.example.ui.screens.splash.SplashVideoScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.NovaBackground
import com.example.ui.util.FocusTrace
import com.example.ui.viewmodel.ScreenState
import com.example.ui.viewmodel.SidebarSection
import com.example.ui.viewmodel.StreamNovaViewModel
import com.example.ui.viewmodel.StreamResolutionState
import com.example.ui.viewmodel.TrailerPlaybackState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Finestra temporale del doppio Back sulla Home per uscire dall'app (feedback breve). */
private const val EXIT_CONFIRM_WINDOW_MS = 2_000L

/**
 * Limite oltre il quale la Home viene composta anche se la splash non ha ancora
 * segnalato l'avvio del video (rete di sicurezza, non un timeout di riproduzione).
 */
private const val HOME_CONTENT_GATE_SAFETY_TIMEOUT_MS = 10_000L

/**
 * Pausa tra il primo frame del video splash e la composizione della Home: lascia
 * al decoder il tempo di avviarsi senza contesa di CPU. Non incide sulla durata
 * del video né sulla pausa finale di 4 s (la Home si compone durante il filmato).
 */
private const val HOME_CONTENT_GATE_DELAY_AFTER_VIDEO_MS = 1_500L

/**
 * Tasti di navigazione/conferma che la finestra della Home non deve ricevere mentre un
 * modale (menu contestuale di «Continua a guardare», dialog in-window) è aperto.
 *
 * Il menu contestuale è una finestra Dialog separata: normalmente i tasti arrivano lì. Nei
 * primi frame di apertura, prima che la finestra del dialogo prenda il focus, potrebbero
 * però essere consegnati alla finestra della Home. Consumare DOWN **e UP** evita che una
 * card sottostante esegua un'azione (la `TvFocusableBox` interpreta il KeyUp come click)
 * e che il D-pad muova il focus dietro al popup.
 */
private fun isModalGuardedKey(keyCode: Int): Boolean = when (keyCode) {
  KeyEvent.KEYCODE_DPAD_UP,
  KeyEvent.KEYCODE_DPAD_DOWN,
  KeyEvent.KEYCODE_DPAD_LEFT,
  KeyEvent.KEYCODE_DPAD_RIGHT,
  KeyEvent.KEYCODE_DPAD_CENTER,
  KeyEvent.KEYCODE_ENTER,
  KeyEvent.KEYCODE_NUMPAD_ENTER -> true
  else -> false
}

/**
 * Riga della Home usata come ANCORA DI CONTENUTO quando il target di ripristino non e' piu'
 * disponibile (target consumato prematuramente dalla Home ancora composta sotto la
 * destinazione Dettaglio/Provider).
 *
 * L'Hero e' l'ancora canonica della Home: e' la prima cella del contenuto ed e' sempre
 * presente quando la Home ha contenuto. Il ripristino passa dal PROTOCOLLO della Home
 * (richiesta pendente + attesa del mount + conferma del focus): il focus arriva sulla
 * cella solo quando e' realmente pronta e NON puo' finire sulla sidebar.
 */
internal const val HOME_CONTENT_ANCHOR_ROW = "hero_banner"

/**
 * Id sintetico del target di ripristino usato come ancora di contenuto.
 *
 * Non corrisponde a nessun titolo: serve solo a rendere il target NON nullo, cosi' la Home
 * avvia il protocollo di ripristino sulla riga indicata da [HOME_CONTENT_ANCHOR_ROW]. Il
 * ripristino dell'Hero e' posizionale (cella 0 della riga) e non per identita' di titolo.
 */
internal const val HOME_CONTENT_ANCHOR_ID = "home_content_anchor"

/**
 * Piano di ripristino del focus al ritorno da Dettaglio/Provider.
 *
 * - [RESTORE_SAVED_TARGET]: il target salvato esiste ancora -> la Home lo ripristina sulla
 *   riga e sulla card di origine (percorso invariato).
 * - [ANCHOR_CONTENT]: il target e' stato gia' consumato -> il focus viene affidato al
 *   CONTENUTO tramite l'ancora dell'Hero, senza mai muovere il focus alla cieca.
 */
internal enum class ContentFocusRestorePlan {
  RESTORE_SAVED_TARGET,
  ANCHOR_CONTENT
}

/**
 * Calcola il piano di ripristino del focus al ritorno da Dettaglio/Provider.
 *
 * @param savedTargetId id del titolo salvato all'apertura del Dettaglio, `null` se il target
 *   e' gia' stato consumato (la Home era ancora composta sotto la destinazione Dettaglio e
 *   ha rilasciato il target).
 */
internal fun contentFocusRestorePlan(savedTargetId: String?): ContentFocusRestorePlan =
  if (savedTargetId != null) {
    ContentFocusRestorePlan.RESTORE_SAVED_TARGET
  } else {
    ContentFocusRestorePlan.ANCHOR_CONTENT
  }

class MainActivity : ComponentActivity() {
  private val viewModel: StreamNovaViewModel by viewModels()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    StartupTrace.mark("main_activity_oncreate_enter")
    // MediaRepository è già inizializzato in StreamNovaApplication.onCreate() (con il
    // database Room): ripeterlo qui rieseguiva la stessa inizializzazione sullo stesso
    // database, quindi è stato rimosso per evitare il lavoro ridondante del bootstrap.
    // Controllo aggiornamenti all'avvio: in background (Dispatchers.IO) e una sola volta
    // per processo, quindi non blocca né rallenta la Home. Se trova una versione più
    // recente il popup compare a schermata libera; in ogni altro esito — app aggiornata,
    // offline, errore — non disturba. Nessun download parte senza la scelta dell'utente.
    UpdateRepository.checkForUpdatesOnStartup()
    StartupTrace.mark("after_update_check_scheduled")
    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    StartupTrace.mark("after_requested_orientation")
    enableEdgeToEdge()
    StartupTrace.mark("after_enable_edge_to_edge")
    setContent {
      StartupTrace.mark("compose_content_enter")
      // Primo frame realmente disegnato: è il confine tra "schermo vuoto/splash
      // nativo" e "app visibile". Lo splash video parte solo dopo questo evento.
      LaunchedEffect(Unit) {
        StartupTrace.mark("first_frame_effect")
        androidx.compose.runtime.withFrameNanos { }
        StartupTrace.mark("first_frame_drawn")
      }
      val appLanguage by viewModel.appLanguage.collectAsState()
      val context = LocalContext.current
      val currentLocale = remember(appLanguage) { java.util.Locale(appLanguage) }
      val localizedContext = remember(context, currentLocale) {
        val config = android.content.res.Configuration(context.resources.configuration).apply {
          setLocale(currentLocale)
          setLayoutDirection(currentLocale)
        }
        context.createConfigurationContext(config)
      }
      val localizedConfiguration = remember(localizedContext, currentLocale) {
        localizedContext.resources.configuration
      }

      androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalContext provides localizedContext,
        androidx.compose.ui.platform.LocalConfiguration provides localizedConfiguration
      ) {
        MyApplicationTheme {
          StreamNovaApp(viewModel = viewModel, activity = this@MainActivity)
        }
      }
      StartupTrace.mark("compose_content_exit")
    }
    StartupTrace.mark("main_activity_oncreate_exit")
  }

  /**
   * Sync leggero al ritorno in primo piano: debounce di 5 minuti e gate su
   * `GET /sync/activities`. Non blocca la UI e non fa polling: se nulla è cambiato sul
   * server, l'unica richiesta è quella delle attività.
   */
  override fun onResume() {
    super.onResume()
    lifecycleScope.launch {
      runCatching { SimklSyncManager.syncOnForeground() }
    }
  }
}

@Composable
fun StreamNovaApp(
  viewModel: StreamNovaViewModel,
  activity: Activity,
  navController: NavHostController = rememberNavController()
) {
  val context = LocalContext.current
  val screenState by viewModel.screenState.collectAsState()
  val currentSection by viewModel.currentSection.collectAsState()
  val allMedia by viewModel.allMedia.collectAsState()
  val detailUiState by viewModel.detailUiState.collectAsState()
  val streamResult by viewModel.streamResult.collectAsState()
  val streamResolutionState by viewModel.streamResolutionState.collectAsState()
  val streamingEngineMode by viewModel.streamingEngineMode.collectAsState()
  val lastFocusedMediaId by viewModel.lastFocusedMediaId.collectAsState()
  val lastFocusedTarget by viewModel.lastFocusedTarget.collectAsState()
  val lastFocusedProviderId by viewModel.lastFocusedProviderId.collectAsState()
  val focusRestoreToken by viewModel.focusRestoreToken.collectAsState()
  val startupUpdatePrompt by viewModel.startupUpdatePrompt.collectAsState()
  val updateDownloadState by viewModel.updateDownloadState.collectAsState()

  // Rientro dal player nella SCHEDA DETTAGLIO del contenuto appena riprodotto: la richiesta
  // arriva dal ViewModel (vedi [StreamNovaViewModel.playerDetailReturn]) e qui si traduce nella
  // navigazione del NavHost. La chiusura del player è DIFFERITA (vedi
  // [StreamNovaViewModel.completePlayerClose]): l'overlay resta composto — e copre la
  // destinazione sottostante — finché la scheda non è davvero composta a schermo, e viene
  // smontato solo allora. La cornice che sostituisce il player ha quindi già la scheda
  // Dettaglio: la Home non è mai visibile, nemmeno per un frame. La richiesta è l'evento di UN
  // rientro (la riproduzione avviata da una card di «Continua a guardare»), non uno stato
  // della Home.
  val playerDetailReturn by viewModel.playerDetailReturn.collectAsState()
  LaunchedEffect(playerDetailReturn) {
    val request = playerDetailReturn ?: return@LaunchedEffect
    Log.d("BACK_TRACE", "MAINACTIVITY: rientro dal player nella scheda Dettaglio")
    viewModel.consumePlayerDetailReturn()
    // Scheda Dettaglio già attiva (rientro ripetuto sullo stesso titolo): la destinazione è
    // quella, non ne va impilata una seconda, e la chiusura differita si conclude subito.
    if (navController.currentDestination?.route != Screen.Detail.route) {
      val media = request.media
      // `fromPlayer = true` apre la scheda senza transizione d'ingresso: è già opaca e
      // disegnata sopra la Home quando il player (che la copre) viene smontato.
      navController.navigate(
        Screen.Detail.createRoute(media.type, media.tmdbId ?: 0, fromPlayer = true)
      )
    } else {
      viewModel.completePlayerClose()
    }
  }

  // Traccia SOLO DEBUG della destinazione attiva: permette di distinguere "il Dettaglio è
  // ancora la destinazione" da "il Dettaglio è stato poppato verso la Home" durante il
  // ritorno dal player.
  LaunchedEffect(navController) {
    navController.currentBackStackEntryFlow.collect { entry ->
      FocusTrace.log("NAV", "route=${entry.destination.route}")
    }
  }

  // Feedback errore di risoluzione sorgenti (es. timeout o nessuna sorgente):
  // mostra un toast con il messaggio di errore e reimposta lo stato su Idle
  LaunchedEffect(streamResolutionState) {
    val error = streamResolutionState as? StreamResolutionState.Error ?: return@LaunchedEffect
    Toast.makeText(context, error.message, Toast.LENGTH_LONG).show()
    viewModel.resetStreamState()
  }

  // Timestamp dell'ultima pressione di Back sulla Home (doppio Back per uscire)
  var lastExitRequestAt by remember { mutableLongStateOf(0L) }

  // Flag: ripristina il focus sul contenuto (riga/card) quando si torna dal Dettaglio
  var pendingContentFocusRestore by remember { mutableStateOf(false) }

  // Splash Screen iniziale: intro video ufficiale StreamNova (logo 3D animato).
  // `rememberSaveable`: una ricreazione dell'Activity NON deve far ripartire il
  // filmato da capo una volta che l'intro è terminata. La Home resta composta
  // sotto la splash per tutta la riproduzione, quindi i dati si caricano in
  // parallelo al video e al termine non c'è nulla da ricaricare.
  var isSplashActive by rememberSaveable { mutableStateOf(true) }

  // Contenuto principale (Home + sidebar) composto solo DOPO il primo frame del
  // video splash.
  //
  // Perché: la splash è disegnata SOPRA la Home, ma Compose compone l'intero albero
  // prima della prima cornice. Comporre hero, righe, card e immagini prima di
  // mostrare la splash sposta in avanti di secondi la comparsa dello splash video
  // sui TV box più lenti (misurato con StartupTrace). Il primo frame deve contenere
  // SOLO la splash; la Home viene composta appena il video mostra il suo primo
  // frame, così continua a caricarsi/comporsi in parallelo alla riproduzione,
  // senza ritardarla e senza lasciare nulla da ricaricare alla fine.
  // `rememberSaveable`: alla ricreazione dell'Activity la Home è già pronta.
  var isMainContentReady by rememberSaveable { mutableStateOf(false) }

  // Primo frame del video realmente a schermo. La composizione della Home parte
  // poco dopo: il decoder deve poter mostrare il filmato senza contendere CPU
  // nella fase più delicata dell'avvio. Il resto del video e la pausa di 4 s
  // bastano comunque a completare il caricamento della Home.
  var isVideoFirstFrameRendered by remember { mutableStateOf(false) }
  LaunchedEffect(isVideoFirstFrameRendered) {
    if (!isVideoFirstFrameRendered || isMainContentReady) return@LaunchedEffect
    delay(HOME_CONTENT_GATE_DELAY_AFTER_VIDEO_MS)
    if (!isMainContentReady) {
      isMainContentReady = true
      StartupTrace.mark("home_content_gate_open_video_started")
    }
  }

  // Rete di sicurezza: se la splash non segnala mai l'avvio (player non
  // riproducibile, surface mai pronta) la Home viene comunque composta dopo
  // questo limite, invece di restare fuori per sempre.
  LaunchedEffect(Unit) {
    if (isMainContentReady) return@LaunchedEffect
    delay(HOME_CONTENT_GATE_SAFETY_TIMEOUT_MS)
    if (!isMainContentReady) {
      isMainContentReady = true
      StartupTrace.mark("home_content_gate_open_timeout")
    }
  }

  /*
   * BACK GERARCHICO DEL TELECOMANDO TV — ordine di priorità:
   *   1) modale/dialogo aperto  -> chiude prima il modale (BackHandler interno a PlayerScreen/SubScreens)
   *   2) player in riproduzione -> chiude SOLO il player: ferma la riproduzione, salva il progresso
   *                                in Room e ripristina la schermata sottostante (Home o Dettaglio)
   *   3) Detail / Provider      -> navController.popBackStack() + stato ViewModel (torna alla Home)
   *   4) sezione laterale       -> torna alla sezione Home
   *   5) Home                   -> doppio Back con feedback breve, poi uscita regolare
   * Ogni livello è disabilitato quando un livello più alto è attivo, così è SEMPRE attivo
   * un solo gestore: niente loop, niente blocchi e nessuna pressione di Back ignorata.
   * Il gestore del player è inoltre composto DOPO il NavHost (in fondo al Box) per avere
   * priorità anche rispetto ai BackHandler dichiarati dentro le schermate.
   */

  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(NovaBackground)
  ) {
    NavHost(
      navController = navController,
      startDestination = Screen.Browsing.route
    ) {
      composable(
        route = Screen.Browsing.route,
        // La Home esce SENZA transizione quando la destinazione entrante è la scheda aperta dal
        // rientro dal player (vedi Screen.Detail.ARG_FROM_PLAYER): la scheda è già opaca e
        // disegnata sopra, quindi la Home non resta in scena — nemmeno durante una sfumatura.
        // Per tutte le altre navigazioni resta la transizione di default del NavHost.
        exitTransition = {
          if (targetState.arguments?.getBoolean(Screen.Detail.ARG_FROM_PLAYER) == true) {
            ExitTransition.None
          } else {
            null
          }
        }
      ) {
        // Stato apertura sidebar: false sin dal primo frame.
        var isSidebarExpanded by remember { mutableStateOf(false) }

        // FocusRequester per la sidebar e mappa per ogni singolo item (Punto 5: memoria focus)
        val sidebarFocusRequester = remember { FocusRequester() }
        val sidebarItemFocusRequesters = remember { SidebarSection.entries.associateWith { FocusRequester() } }

        // (4) Sezione laterale != Home -> se la sidebar è chiusa la apre posizionandosi sul pulsante attivo;
        // se la sidebar è già aperta, torna alla Home
        BackHandler(
          enabled = screenState == ScreenState.BROWSING && currentSection != SidebarSection.HOME
        ) {
          if (!isSidebarExpanded) {
            isSidebarExpanded = true
            try {
              (sidebarItemFocusRequesters[currentSection] ?: sidebarFocusRequester).requestFocus()
            } catch (_: Exception) {}
          } else {
            viewModel.setSection(SidebarSection.HOME)
            try {
              (sidebarItemFocusRequesters[SidebarSection.HOME] ?: sidebarFocusRequester).requestFocus()
            } catch (_: Exception) {}
          }
        }

        // (5) Home -> doppio Back con feedback breve, poi uscita regolare (nessun loop/blocco)
        BackHandler(
          enabled = screenState == ScreenState.BROWSING && currentSection == SidebarSection.HOME
        ) {
          // La via TASTIERA della Home ha già preso in carico questa pressione (BACK su una
          // card oltre la prima scheda della fila: vedi HomeScreen.homeBackToRowStart). La
          // piattaforma recapita la STESSA pressione anche a questo callback, in parallelo ai
          // key event: senza la guardia un solo BACK mostrerebbe il feedback di uscita oltre
          // a riportare il focus alla prima scheda (stesso protocollo di
          // PlayerScreen.`backKeyDownPending`).
          if (HomeBackToRowStartState.isPending) {
            return@BackHandler
          }
          val now = SystemClock.elapsedRealtime()
          if (now - lastExitRequestAt < EXIT_CONFIRM_WINDOW_MS) {
            // Uscita regolare dell'Activity (il processo resta vivo).
            // NOTA: qui `context` è il contesto localizzato creato con
            // createConfigurationContext e NON è un'Activity, quindi si usa
            // l'Activity passata da MainActivity.
            activity.finishAndRemoveTask()
          } else {
            lastExitRequestAt = now
            Toast.makeText(context, context.getString(R.string.press_back_again_to_exit), Toast.LENGTH_SHORT).show()
          }
        }

        // FocusRequester per il Box Home (focus iniziale esplicito post-splash).
        val homeFocusRequester = remember { FocusRequester() }

        // Tracking SOLO LETTURA: diventa true quando un qualsiasi figlio della sidebar
        // ha il focus. NON viene mai usato per aprire/chiudere la sidebar.
        // Serve in onPreviewKeyEvent per distinguere "moveFocus(Left) è andato sulla sidebar"
        // da "moveFocus(Left) è andato su una card Home". Il rilevamento è sincrono:
        // onFocusChanged scatta dentro moveFocus() prima che esso ritorni.
        var sidebarHasFocus by remember { mutableStateOf(false) }

        // Focus iniziale esplicito sul contenuto Home dopo la fine della splash.
        val focusManager = LocalFocusManager.current
        // Saveable: "focus iniziale post-splash" è un evento UNA TANTUM, non un rimontaggio.
        // Al rientro dal Dettaglio la destination Browsing è ricomposta e un `remember`
        // semplice si azzererebbe, rieseguendo `requestHomeScrollToTop()` (scroll a 0 + focus
        // sull'Hero) IN CORSA col ripristino della card salvata: la corsa sottrae il focus al
        // rientro e lo lascia fuori dal contenuto (difetto risalita -> focus nella sidebar).
        var isInitialHomeFocusSet by rememberSaveable { mutableStateOf(false) }

        LaunchedEffect(screenState, isSplashActive, pendingContentFocusRestore, isInitialHomeFocusSet) {
          if (screenState == ScreenState.BROWSING && !isSplashActive) {
            if (!isInitialHomeFocusSet) {
              isInitialHomeFocusSet = true
              if (currentSection == SidebarSection.HOME) {
                // Focus iniziale sull'Hero, prima cella del contenuto Home: il
                // moveFocus(Down) da un contenitore a tutto schermo non lo raggiunge mai,
                // perche' la ricerca geometrica preferisce la card provider piu' vicina al
                // centro dello schermo. HomeScreen completa la richiesta appena i dati della
                // hero sono pronti (i cataloghi TMDB arrivano dopo la splash).
                viewModel.requestHomeScrollToTop()
              }
            } else if (pendingContentFocusRestore) {
              val savedTargetId = viewModel.lastFocusedMediaId.value
              val savedProviderId = viewModel.lastFocusedProviderId.value
              FocusTrace.log(
                "HOME",
                "pendingContentFocusRestore: plan=${contentFocusRestorePlan(savedTargetId)} " +
                  "savedTarget=$savedTargetId provider=$savedProviderId " +
                  "focusRestoreRequested=${viewModel.focusRestoreRequested.value}"
              )
              when (contentFocusRestorePlan(savedTargetId)) {
                ContentFocusRestorePlan.RESTORE_SAVED_TARGET -> {
                  // Percorso INVARIATO: il target salvato esiste ancora, la Home lo
                  // ripristina sulla riga e sulla card di origine.
                  pendingContentFocusRestore = false
                  viewModel.signalFocusRestore(savedTargetId!!)
                }
                ContentFocusRestorePlan.ANCHOR_CONTENT -> if (savedProviderId != null) {
                  // Provenienza da una CARD PROVIDER dell'hub della Home (es. HBO Max): il
                  // focus deve tornare su QUELLA card, non sull'Hero. Canale dedicato: il
                  // token avanza e la Home ripristina l'hub per identità del provider.
                  pendingContentFocusRestore = false
                  viewModel.signalFocusRestore(savedProviderId)
                } else {
                  // FALLBACK: il target e' gia' stato CONSUMATO. La Home era ancora
                  // composta sotto la destinazione Dettaglio/Provider e ha rilasciato il
                  // target (l'Hero era ancora focalizzato al momento del click), quindi al
                  // ritorno non c'e' piu' nessuna card da ripristinare.
                  //
                  // Il vecchio fallback (requestFocus sul Box + moveFocus(Down)) sparava il
                  // movimento a freddo: quando le righe della Home non sono ancora
                  // composte l'unica superficie focalizzabile e' la SIDEBAR e il movimento
                  // ci finisce dentro (difetto "al rientro il focus e' sulla sidebar").
                  //
                  // Il focus viene ora AFFIDATO al contenuto: si ri-prestabilisce un target
                  // di ripristino sulla riga dell'Hero (l'ancora canonica della Home) e si
                  // segnala il ripristino. La Home applica il SUO protocollo (richiesta
                  // pendente + attesa del mount + conferma): il focus arriva su una cella
                  // realmente pronta e non puo' finire sulla sidebar.
                  viewModel.saveLastFocusedMedia(HOME_CONTENT_ANCHOR_ROW, HOME_CONTENT_ANCHOR_ID)
                  viewModel.signalFocusRestore(HOME_CONTENT_ANCHOR_ID)
                  // La richiesta pendente viene consumata SOLO dopo che il ripristino e'
                  // stato affidato al contenuto (niente richieste consumate a vuoto).
                  pendingContentFocusRestore = false
                }
              }
            }
          }
        }

        LaunchedEffect(isSidebarExpanded) {
          if (isSidebarExpanded) {
            try {
              (sidebarItemFocusRequesters[currentSection] ?: sidebarFocusRequester).requestFocus()
            } catch (_: Exception) {}
          }
        }

        Box(
          modifier = Modifier
            .fillMaxSize()
            .focusRequester(homeFocusRequester)
            // onPreviewKeyEvent: intercetta LEFT PRIMA che i figli lo ricevano.
            //
            // PROBLEMA RISOLTO: i TvFocusableBox della sidebar sono fisicamente a sinistra
            // delle card Home anche quando la sidebar è chiusa (60dp). Quindi:
            //   - moveFocus(Left) da una card non-prima → va alla card precedente → true, sidebarHasFocus=false
            //   - moveFocus(Left) dalla prima card → va a un icon sidebar → true, sidebarHasFocus=true
            //   - moveFocus(Left) quando non c'è nulla → false
            //
            // sidebarHasFocus è aggiornato SINCRONO dentro moveFocus() (onFocusChanged del
            // modifier esterno scatta prima che moveFocus() ritorni), quindi la lettura
            // immediata dopo moveFocus() rispecchia sempre lo stato reale del focus.
            //
            // HeroCarouselState.consumesLeftKey: con il focus su un pulsante dell'Hero e un
            // poster successivo al primo, il LEFT appartiene al carosello (torna al poster
            // precedente) e questa intercettazione deve lasciarlo passare. Sul primo poster
            // resta invece false, quindi il LEFT esce regolarmente verso la Sidebar.
            .onPreviewKeyEvent { keyEvent ->
              // Mentre un modale è aperto la Home non esegue alcuna azione: se la finestra
              // del dialog non ha ancora il focus, i tasti arriverebbero qui e muoverebbero
              // il focus o attiverebbero la card sottostante.
              if (ModalOverlayState.isAnyOpen &&
                isModalGuardedKey(keyEvent.nativeKeyEvent.keyCode)
              ) {
                return@onPreviewKeyEvent true
              }
              if (!ModalOverlayState.isAnyOpen &&
                !isSidebarExpanded &&
                !HeroCarouselState.consumesLeftKey &&
                // Righe ancorate della Home: con un poster selezionato oltre il primo il LEFT
                // torna al poster precedente e deve arrivare alla riga, non aprire la sidebar.
                !AnchoredRowKeyState.consumesLeftKey &&
                keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_LEFT
              ) {
                val moved = focusManager.moveFocus(FocusDirection.Left)
                when {
                  // CASO A: focus andato su un icon sidebar (prima card Home, bordo sinistro).
                  // sidebarHasFocus è già true perché onFocusChanged ha scattato sincrono
                  // dentro moveFocus(). Apriamo la sidebar e impostiamo il focus sul primo item.
                  sidebarHasFocus -> {
                    isSidebarExpanded = true
                    try {
                      (sidebarItemFocusRequesters[currentSection] ?: sidebarFocusRequester).requestFocus()
                    } catch (_: Exception) {}
                    true
                  }
                  // CASO B: focus andato su una card Home a sinistra. Navigazione normale.
                  moved -> true
                  // CASO C: nessun elemento a sinistra (non dovrebbe accadere con la sidebar
                  // presente, ma lo gestiamo per sicurezza).
                  else -> {
                    isSidebarExpanded = true
                    try {
                      (sidebarItemFocusRequesters[currentSection] ?: sidebarFocusRequester).requestFocus()
                    } catch (_: Exception) {}
                    true
                  }
                }
              } else {
                false
              }
            }
        ) {
          // Main Screen Content — larghezza e posizione fisse, non si sposta mai.
          //
          // Composta solo quando il primo frame della splash è già a schermo (vedi
          // `isMainContentReady`): durante la splash resta comunque invisibile, ma la
          // sua composizione non deve precedere la comparsa del video.
          if (isMainContentReady) {
            CompositionLocalProvider(
              LocalLastFocusedTarget provides lastFocusedTarget,
              LocalLastFocusedMediaId provides lastFocusedMediaId,
              LocalLastFocusedProviderId provides lastFocusedProviderId,
              LocalFocusRestoreToken provides focusRestoreToken,
              LocalClearLastFocusedMedia provides { viewModel.clearLastFocusedMedia() },
              LocalClearLastFocusedProvider provides { viewModel.clearLastFocusedProvider() }
            ) {
              HomeScreen(
                viewModel = viewModel,
                onProviderClick = { provider ->
                  viewModel.openProvider(provider)
                  navController.navigate(Screen.Provider.createRoute(provider.id))
                },
                onMediaClick = { media ->
                  // NON chiamare viewModel.saveLastFocusedMedia(media.id) qui!
                  // HomeScreen.kt gestisce già internamente handleRowMediaClick salvando la rowKey specifica.
                  // Canale LIVE (addon Stremio, es. TvVoo): nessuna scheda stagioni/episodi,
                  // si aprono direttamente le sorgenti del canale.
                  if (viewModel.isLiveChannel(media.id)) {
                    viewModel.openLiveChannel(media)
                    navController.navigate(Screen.Detail.createRoute(media.type, media.tmdbId ?: 0))
                  } else {
                    viewModel.openDetail(media)
                    val tmdbId = media.tmdbId ?: 0
                    navController.navigate(Screen.Detail.createRoute(media.type, tmdbId))
                  }
                },
                modifier = Modifier
                  .fillMaxSize()
                  .padding(start = 60.dp)
              )
            }

            // Left Sidebar Menu — overlay sopra la Home.
            // onFocusChanged ESTERNO aggiorna sidebarHasFocus (solo tracking, NON collassa la sidebar).
            SidebarNavigation(
              currentSection = currentSection,
              itemFocusRequesters = sidebarItemFocusRequesters,
              onSectionSelected = { section ->
                if (section == SidebarSection.HOME) {
                  isSidebarExpanded = false
                  viewModel.requestHomeScrollToTop()
                }
                if (section == SidebarSection.CERCA) {
                  isSidebarExpanded = false
                }
                viewModel.setSection(section)
              },
              isSidebarExpanded = isSidebarExpanded,
              onCollapseRequest = { isSidebarExpanded = false },
              // La voce "Addon" è sempre presente nella sidebar, in ogni modalità del motore.
              showAddons = true,
              modifier = Modifier
                .align(Alignment.CenterStart)
                .focusRequester(sidebarFocusRequester)
                .onFocusChanged { sidebarHasFocus = it.hasFocus }
            )
          }
        }
      }

      composable(
        route = Screen.Detail.route,
        arguments = Screen.Detail.arguments,
        // Rientro dal player (vedi Screen.Detail.ARG_FROM_PLAYER): la destinazione viene
        // applicata SOTTO l'overlay del player, che resta composto finché la scheda non è
        // realmente composta a schermo. Senza transizione d'ingresso la scheda è già opaca
        // nella prima cornice in cui il player si smonta e viene disegnata SOPRA la Home:
        // nessuna cornice di Home, nessuno stato intermedio (né sfumature, né schermate vuote).
        // `null` = transizione di default del NavHost per tutte le altre aperture della scheda.
        enterTransition = {
          if (targetState.arguments?.getBoolean(Screen.Detail.ARG_FROM_PLAYER) == true) {
            EnterTransition.None
          } else {
            null
          }
        }
      ) { backStackEntry ->
        // Rientro dal player: la scheda è COMPOSTA (dietro l'overlay del player, che la copre
        // per intero). Solo ora la chiusura differita del player si conclude: da questa cornice
        // in poi la destinazione corrente è la scheda, quindi smontare l'overlay non scopre mai
        // la Home. È l'unico punto in cui la chiusura viene completata (fuori da qui il player
        // resta composto).
        if (backStackEntry.arguments?.getBoolean(Screen.Detail.ARG_FROM_PLAYER) == true) {
          LaunchedEffect(Unit) { viewModel.completePlayerClose() }
        }
        val args = DetailNavArgs.fromBundle(backStackEntry.arguments)
        val selectedMedia by viewModel.selectedMedia.collectAsState()
        var cachedMedia by remember(backStackEntry.id) { mutableStateOf<MediaItem?>(null) }
        val currentFromVm = selectedMedia?.takeIf { (args.tmdbId > 0 && it.tmdbId == args.tmdbId) || args.tmdbId <= 0 }
        if (currentFromVm != null && cachedMedia != currentFromVm) {
          cachedMedia = currentFromVm
        }
        val baseMedia = currentFromVm
          ?: cachedMedia
          ?: allMedia.find { args.tmdbId > 0 && it.tmdbId == args.tmdbId }
          ?: selectedMedia

        val selectedSeasonNumber by viewModel.selectedSeasonNumber.collectAsState()
        val seasonEpisodesUiState by viewModel.seasonEpisodesUiState.collectAsState()
        val lastFocusedCastMemberId by viewModel.lastFocusedCastMemberId.collectAsState()
        val lastFocusedCompanyId by viewModel.lastFocusedCompanyId.collectAsState()
        // Preferenza «Sfoca non visti» (Impostazioni → Continua a guardare), estesa alle
        // miniature della lista episodi della scheda: gli episodi mai visti restano sfocati
        // anche qui quando l'opzione è attiva.
        val continueWatchingPreferences by viewModel.continueWatchingPreferences.collectAsState()
        // Chiusure del selettore sorgenti avvenute mentre QUESTA scheda era composta
        // sotto l'overlay: il Dettaglio le usa per riprendere il focus su «Riproduci».
        val sourceDialogDismissedToken by viewModel.sourceDialogDismissedToken.collectAsState()
        // Chiusure del trailer interno (overlay globale sopra il NavHost) avvenute mentre
        // QUESTA scheda era composta sotto di esso: stesso ripristino del focus su
        // «Riproduci», con un segnale distinto da quello del selettore delle sorgenti.
        val trailerDismissedToken by viewModel.trailerDismissedToken.collectAsState()
        // Chiusure del player (overlay globale sopra il NavHost) avvenute mentre QUESTA
        // scheda era composta sotto di esso: al rientro la scheda riprende il focus su
        // «Riproduci». Il player non è una destinazione di navigazione, quindi la scheda
        // resta quella corrente e non c'è alcun pop da eseguire.
        val playerClosedToken by viewModel.playerClosedToken.collectAsState()

        // Uscita DAL Dettaglio verso la destinazione sottostante (Home/Provider): lo stato
        // della scheda viene azzerato solo quando la destinazione ha davvero lasciato la
        // composizione. Durante la transizione di uscita del NavHost la scheda resta
        // composta: azzerare `selectedMedia`/la stagione subito la faceva ridisegnare con
        // la lista episodi ricostruita dal catalogo (senza still TMDB) per una frazione di
        // secondo, prima che la Home diventasse visibile (flash segnalato).
        // Il flag è per destinazione (`backStackEntry.id`): una ricreazione della
        // composizione (es. Activity ricreata) non azzera nulla, perché nessun BACK è in
        // corso; solo il ritorno richiesto dall'utente lo fa.
        var detailExitPending by remember(backStackEntry.id) { mutableStateOf(false) }
        DisposableEffect(backStackEntry.id) {
          onDispose {
            if (detailExitPending) viewModel.completeDetailExit()
          }
        }

                // (2) Back dal Dettaglio: popBackStack verso la schermata precedente.
        // Se lo stack contiene più dettagli, risale alla scheda precedente
        // ripristinandone stato e metadati. Solo quando lo stack è esaurito si torna a Home/Provider.
        BackHandler(enabled = screenState != ScreenState.PLAYER) {
          FocusTrace.log(
            "DETAIL_BACK",
            "BackHandler Dettaglio: screenState=$screenState route=${navController.currentDestination?.route} " +
              "playerClosedToken=${viewModel.playerClosedToken.value}"
          )
          // Lo stato UI resta intatto finché la scheda è composta: nessun frame con la
          // lista episodi priva delle still. L'azzeramento è differito al dispose (sopra).
          val hasPreviousDetail = viewModel.popDetail(clearDetailUiState = false)
          if (!hasPreviousDetail) {
            detailExitPending = true
            pendingContentFocusRestore = true
          }
          navController.popBackStack()
        }

        if (baseMedia != null) {
          val currentMedia = when (val state = detailUiState) {
            is MediaDetailUiState.Success -> {
              val candidate = state.media
              if ((candidate.tmdbId != null && candidate.tmdbId > 0 && (candidate.tmdbId == baseMedia.tmdbId || baseMedia.tmdbId == null || baseMedia.tmdbId <= 0)) || (baseMedia.id.isNotBlank() && (candidate.id.equals(baseMedia.id, ignoreCase = true) || baseMedia.id.startsWith("tt", ignoreCase = true)))) {
                candidate
              } else {
                baseMedia
              }
            }
            is MediaDetailUiState.Loading -> state.baseMedia ?: baseMedia
            is MediaDetailUiState.Error -> state.baseMedia ?: baseMedia
            else -> baseMedia
          }

          // Canale LIVE (addon Stremio): nessun tab Episodi/Produzione, nessuna
          // richiesta TMDB. La scheda mostra il canale e le sue sorgenti.
          val isLiveChannel = viewModel.isLiveChannel(currentMedia.id)

          CompositionLocalProvider(
            LocalLastFocusedMediaId provides lastFocusedMediaId,
            LocalLastFocusedTarget provides lastFocusedTarget,
            LocalLastFocusedProviderId provides lastFocusedProviderId,
            LocalFocusRestoreToken provides focusRestoreToken,
            LocalClearLastFocusedMedia provides { viewModel.clearLastFocusedMedia() },
            LocalClearLastFocusedProvider provides { viewModel.clearLastFocusedProvider() }
          ) {
            DetailScreen(
            media = currentMedia,
            allMedia = allMedia,
            onBackClick = {
              // Stessa uscita del tasto BACK: stato conservato fino al dispose della
              // destinazione, così la scheda non si ridisegna senza still durante la
              // transizione verso la Home.
              val hasPreviousDetail = viewModel.popDetail(clearDetailUiState = false)
              if (!hasPreviousDetail) {
                detailExitPending = true
                pendingContentFocusRestore = true
              }
              navController.popBackStack()
            },
            onPlayClick = { m, ep ->
              if (isLiveChannel) {
                // Canale: le sorgenti vengono raccolte dall'addon (tipo originale
                // `tv`) e mostrate nel selettore esistente.
                viewModel.resolveLiveChannelSources(m)
              } else {
                viewModel.openStreamForMedia(m, ep)
              }
            },
            onToggleFavorite = { viewModel.toggleFavorite(it) },
            onToggleWatched = { viewModel.toggleWatched(it) },
            onPlayTrailer = { viewModel.playTrailer(it) },
            // «Seleziona fonte» accanto a Trailer: SOLO in modalità Debrid e per
            // contenuti che non siano canali live (per i canali il Play apre già
            // il selettore delle sorgenti dell'addon). Apre la schermata di scelta
            // manuale esistente senza avviare alcuna riproduzione automatica.
            onSelectSourceClick = if (isLiveChannel || streamingEngineMode != StreamingEngineMode.DEBRID_TORBOX) {
              null
            } else {
              { m: MediaItem, ep: Episode? -> viewModel.openManualSourceSelection(m, ep) }
            },
            sourceDialogDismissedToken = sourceDialogDismissedToken,
            trailerDismissedToken = trailerDismissedToken,
            playerClosedToken = playerClosedToken,
            onProviderClick = { provider ->
              viewModel.openProvider(provider)
              navController.navigate(Screen.Provider.createRoute(provider.id))
            },
            onMediaClick = { nextMedia ->
              viewModel.openDetail(nextMedia)
              val nextTmdbId = nextMedia.tmdbId ?: 0
              navController.navigate(Screen.Detail.createRoute(nextMedia.type, nextTmdbId))
            },
            detailUiState = detailUiState,
            selectedSeasonNumber = selectedSeasonNumber,
            seasonEpisodesUiState = seasonEpisodesUiState,
            onSeasonChange = { media, seasonNum ->
              viewModel.loadEpisodesForSeason(media, seasonNum)
            },
            onEpisodeClick = { media, episode ->
              viewModel.openStreamForMedia(media, episode)
            },
            onRetry = { viewModel.retryLoadDetail() },
            onCastMemberClick = { member ->
              viewModel.saveLastFocusedCastMember(member.id)
              navController.navigate(Screen.PersonDetail.createRoute(member.id, member.name))
            },
            onCompanyClick = { company ->
              viewModel.saveLastFocusedCompany(company.id)
              navController.navigate(Screen.CompanyDetail.createRoute(company.id, company.name, company.logoUrl))
            },
            targetCastMemberId = lastFocusedCastMemberId,
            onClearTargetCastMember = { viewModel.clearLastFocusedCastMember() },
targetCompanyId = lastFocusedCompanyId,
            onClearTargetCompany = { viewModel.clearLastFocusedCompany() },
            isLiveChannel = isLiveChannel,
            blurUnwatchedEpisodes = continueWatchingPreferences.blurUnwatched
          )
          }
        }
      }

      composable(
        route = Screen.Provider.route,
        arguments = Screen.Provider.arguments
      ) { backStackEntry ->
        val providerId = backStackEntry.arguments?.getString(Screen.Provider.ARG_PROVIDER_ID)
        val selectedProvider by viewModel.selectedProvider.collectAsState()
        val provider = selectedProvider
          ?: ProviderConstants.ALL.find { it.id == providerId }
          ?: ProviderConstants.NETFLIX

        ProviderScreen(
          provider = provider,
          allMedia = allMedia,
          viewModel = viewModel,
          onBackClick = {
            viewModel.closeProvider()
            // Rientro alla Home: il CONTENUTO deve riprendere il focus. Senza questa
            // richiesta la Home viene ricomposta senza alcuna superficie focalizzata e il
            // focus repair di Compose atterra sulla sidebar (difetto "tornando da una
            // schermata Provider il focus finisce sulla sidebar").
            pendingContentFocusRestore = true
            navController.popBackStack()
          },
          onMediaClick = { media ->
            viewModel.saveLastFocusedMedia(media.id)
            viewModel.openDetail(media)
            val tmdbId = media.tmdbId ?: 0
            navController.navigate(Screen.Detail.createRoute(media.type, tmdbId))
          }
        )
      }

      composable(
        route = Screen.PersonDetail.route,
        arguments = Screen.PersonDetail.arguments
      ) { backStackEntry ->
        val personId = backStackEntry.arguments?.getInt(Screen.PersonDetail.ARG_PERSON_ID) ?: 0
        val personName = backStackEntry.arguments?.getString(Screen.PersonDetail.ARG_PERSON_NAME)
        PersonDetailScreen(
          personId = personId,
          personName = personName,
          viewModel = viewModel,
          onBackClick = {
            navController.popBackStack()
          },
          onMediaClick = { media ->
            viewModel.saveLastFocusedMedia(media.id)
            viewModel.openDetail(media)
            val tmdbId = media.tmdbId ?: 0
            navController.navigate(Screen.Detail.createRoute(media.type, tmdbId))
          }
        )
      }

      composable(
        route = Screen.CompanyDetail.route,
        arguments = Screen.CompanyDetail.arguments
      ) { backStackEntry ->
        val companyId = backStackEntry.arguments?.getInt(Screen.CompanyDetail.ARG_COMPANY_ID) ?: 0
        val encodedName = backStackEntry.arguments?.getString(Screen.CompanyDetail.ARG_COMPANY_NAME).orEmpty()
        val encodedLogo = backStackEntry.arguments?.getString(Screen.CompanyDetail.ARG_LOGO_URL).orEmpty()
        val companyName = try { java.net.URLDecoder.decode(encodedName, "UTF-8") } catch (_: Exception) { encodedName }
        val logoUrl = try { java.net.URLDecoder.decode(encodedLogo, "UTF-8") } catch (_: Exception) { encodedLogo }

        CompanyDetailScreen(
          companyId = companyId,
          companyName = companyName.takeIf { it.isNotBlank() },
          logoUrl = logoUrl.takeIf { it.isNotBlank() },
          viewModel = viewModel,
          onBackClick = {
            navController.popBackStack()
          },
          onMediaClick = { media ->
            viewModel.saveLastFocusedMedia(media.id)
            viewModel.openDetail(media)
            val tmdbId = media.tmdbId ?: 0
            navController.navigate(Screen.Detail.createRoute(media.type, tmdbId))
          }
        )
      }
    }

    BackHandler(enabled = screenState == ScreenState.PLAYER) {
      Log.d("BACK_TRACE", "MAINACTIVITY: ingresso del BackHandler")
      Log.d("BACK_TRACE", "MAINACTIVITY: valore di screenState: $screenState")
      FocusTrace.log(
        "MAIN_BACK",
        "BackHandler player: route=${navController.currentDestination?.route} " +
          "playerClosedToken=${viewModel.playerClosedToken.value}"
      )
      // Chiude il player: la riproduzione si ferma con il dispose di PlayerScreen e il
      // progresso viene salvato in Room (closePlayer).
      //
      // Il player è un OVERLAY composto sopra il NavHost: la destinazione sottostante
      // (Dettaglio, Provider o Home) non è mai stata sostituita, quindi il BACK chiude SOLO
      // il player e NON deve eseguire alcun pop. Il vecchio pop condizionato a "player
      // aperto dal Dettaglio" era codice morto (closePlayer() azzera l'origine PRIMA della
      // lettura) e, se mai riattivato, avrebbe rimosso la scheda Dettaglio riportando alla
      // Home dopo la chiusura del player.
      Log.d("BACK_TRACE", "MAINACTIVITY: chiamata a closePlayer()")
      FocusTrace.log(
        "MAIN_BACK",
        "closePlayer: route=${navController.currentDestination?.route} " +
          "screenState=$screenState token=${viewModel.playerClosedToken.value}"
      )
      viewModel.resetStreamState()
      viewModel.closePlayer()
      FocusTrace.log(
        "MAIN_BACK",
        "closePlayer done: route=${navController.currentDestination?.route} " +
          "screenState=${viewModel.screenState.value} token=${viewModel.playerClosedToken.value}"
      )
      Log.d("BACK_TRACE", "MAINACTIVITY: player chiuso, destinazione sottostante invariata")
    }

    // BackHandler per annullare la ricerca in corso se l'utente preme BACK sul telecomando
    BackHandler(enabled = streamResolutionState is StreamResolutionState.Loading) {
      Log.d("BACK_TRACE", "MAINACTIVITY: cancellazione ricerca stream al BACK")
      FocusTrace.log("MAIN_BACK", "BackHandler risoluzione stream: resetStreamState")
      viewModel.resetStreamState()
    }

    // Feedback "Ricerca sorgenti in corso..." durante l'estrazione del provider:
    // Visibile SOLO se streamResolutionState is StreamResolutionState.Loading.
    if (streamResolutionState is StreamResolutionState.Loading) {
      StreamStatusOverlay(streamingEngineMode = streamingEngineMode)
    }

    val showSourceDialog by viewModel.showSourceDialog.collectAsState()
    val availableSources by viewModel.availableSources.collectAsState()
    val sourceDialogMedia by viewModel.sourceDialogMedia.collectAsState()
    // Preferenze di riproduzione: guidano l'ordinamento della lista sorgenti
    // ("Privilegia tracce italiane", "Risoluzione preferita").
    val playbackSettings by viewModel.playbackSettings.collectAsState()

    val showParentalPinDialog by viewModel.showParentalPinDialog.collectAsState()
    val parentalPinErrorResId by viewModel.parentalPinErrorResId.collectAsState()
    if (showParentalPinDialog) {
      TvPinDialog(
        title = stringResource(R.string.parental_playback_restricted_title),
        subtitle = stringResource(R.string.parental_playback_restricted_subtitle),
        errorMessage = parentalPinErrorResId?.let { stringResource(it) },
        onPinSubmit = { pin ->
          viewModel.verifyParentalPinForPlayback(pin)
        },
        onDismiss = {
          viewModel.dismissParentalPinDialog()
        }
      )
    }

    // Popup dell'aggiornamento trovato dal controllo AUTOMATICO di avvio. Compare una
    // sola volta per sessione e solo a schermata libera: durante la splash, in
    // riproduzione o con un altro modale aperto resta in attesa, senza mai interrompere
    // la Home. Le due scelte sono «Aggiorna» (scarica: è la scelta esplicita che serve
    // per il download) e «Più tardi» (non riproporre in questa sessione).
    val pendingStartupUpdate = startupUpdatePrompt
    if (pendingStartupUpdate != null &&
      shouldShowStartupUpdatePrompt(
        hasPrompt = true,
        isSplashActive = isSplashActive,
        isPlayerActive = screenState == ScreenState.PLAYER,
        isAnotherModalVisible = ModalOverlayState.isAnyOpen ||
          (showSourceDialog && availableSources.isNotEmpty() && sourceDialogMedia != null) ||
          showParentalPinDialog
      )
    ) {
      UpdatePromptDialog(
        update = pendingStartupUpdate,
        downloadState = updateDownloadState,
        onUpdate = { viewModel.downloadUpdate(context) },
        onLater = { viewModel.dismissStartupUpdatePrompt() }
      )
    }

    // Player Screen overlay when media playback is requested
    if (screenState == ScreenState.PLAYER) {
      PlayerScreen(viewModel = viewModel)
    }

    // Scelta MANUALE delle sorgenti: composta DOPO il player. Deve restare sopra
    // l'overlay di riproduzione quando viene aperta dal player (es. dopo un fallimento
    // dell'autoplay del prossimo episodio: "Nessuna fonte disponibile… Seleziona una
    // fonte manualmente"). Prima era sotto il player e quindi invisibile in quel caso.
    if (showSourceDialog && availableSources.isNotEmpty() && sourceDialogMedia != null) {
      TorBoxSourceSelectionScreen(
        sources = availableSources,
        target = sourceDialogMedia,
        settings = playbackSettings,
        onSelect = { source -> viewModel.selectSource(source) },
        onDismiss = { viewModel.dismissSourceDialog() }
      )
    }

    // Trailer interno (YouTube embedded): sopra ogni schermata, mai Intent esterno.
    val trailerState by viewModel.trailerState.collectAsState()
    when (val trailer = trailerState) {
      is TrailerPlaybackState.Playing -> TrailerPlayerScreen(
        youtubeKey = trailer.youtubeKey,
        onClose = { viewModel.closeTrailer() }
      )
      is TrailerPlaybackState.Error -> TrailerErrorOverlay(onClose = { viewModel.closeTrailer() })
      is TrailerPlaybackState.Loading -> TrailerLoadingOverlay(onClose = { viewModel.closeTrailer() })
      TrailerPlaybackState.Idle -> Unit
    }

    if (isSplashActive) {
      // Intro video: riproduzione integrale, poi pausa di 4 s sull'ultimo frame,
      // poi Home. Errore reale o impossibilità di avviare il video chiudono
      // subito la splash: nessun percorso la lascia sopra la Home.
      SplashVideoScreen(
        // Il primo frame del video è realmente a schermo: la Home può essere
        // composta dopo una breve pausa (vedi `isVideoFirstFrameRendered`),
        // senza rubare CPU al decoder nella fase di avvio.
        onVideoStarted = {
          isVideoFirstFrameRendered = true
        },
        onFinished = {
          Log.i("SplashIntro", "HOME_VISIBLE t=${SystemClock.elapsedRealtime()}")
          // Anche un'uscita anomala (player non disponibile) deve lasciare una
          // Home composta: la splash non è mai l'ultima schermata.
          isMainContentReady = true
          isSplashActive = false
        }
      )
    }
  }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
  Text(text = "Hello $name!", modifier = modifier)
}

@Preview(name = "TV 16:9 1080p", device = "id:tv_1080p", showBackground = true)
@Preview(name = "TV 16:9 720p", device = "id:tv_720p", showBackground = true)
@Composable
fun StreamNovaTv16NinePreview() {
  MyApplicationTheme {
    Box(modifier = Modifier.fillMaxSize().background(NovaBackground)) {
      HomeScreen(
        viewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
        modifier = Modifier.fillMaxSize().padding(start = 60.dp)
      )
      SidebarNavigation(
        currentSection = SidebarSection.HOME,
        onSectionSelected = {},
        isSidebarExpanded = false,
        onCollapseRequest = {},
        modifier = Modifier.align(Alignment.CenterStart)
      )
    }
  }
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
  MyApplicationTheme { Greeting("StreamNova TV") }
}

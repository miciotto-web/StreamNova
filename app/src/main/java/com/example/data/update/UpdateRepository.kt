package com.example.data.update

import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import com.example.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Stati del controllo aggiornamenti esposti alla UI. */
sealed interface UpdateCheckState {
  data object Idle : UpdateCheckState
  data object Checking : UpdateCheckState
  data class UpToDate(val currentVersion: String) : UpdateCheckState
  data class UpdateAvailable(
    val currentVersion: String,
    val newVersion: String,
    val tagName: String,
    val assetName: String?,
    val changelog: String?,
    val releaseUrl: String?
  ) : UpdateCheckState

  /**
   * Release pubblicata ma non coerente: la versione estratta dal nome dell'asset
   * APK non corrisponde al tag della release (es. tag `v1.0.0` con asset
   * `StreamNova-V0.1.0-*.apk`). Un aggiornamento così pubblicato NON viene
   * offerto: non è installabile in modo verificabile.
   */
  data class VersionMismatch(
    val currentVersion: String,
    val tagName: String,
    val tagVersion: String,
    val assetVersion: String?,
    val assetName: String?,
    val releaseUrl: String?
  ) : UpdateCheckState

  data class Error(val reason: UpdateCheckErrorReason) : UpdateCheckState
}

/**
 * Stati del download APK, separati dal controllo versione: [Idle] prima del
 * tap su "Scarica aggiornamento", [Downloading] con percentuale (null = ignota),
 * [Completed] con il file verificato pronto per l'installer, [Error] con motivo.
 */
sealed interface UpdateDownloadState {
  data object Idle : UpdateDownloadState
  data class Downloading(val percent: Int?) : UpdateDownloadState
  data class Completed(val apkPath: String) : UpdateDownloadState
  data class Error(val reason: DownloadErrorReason) : UpdateDownloadState
}

/**
 * ABI su cui basare la selezione dell'APK, in ordine di preferenza: le ABI
 * dichiarate dal sistema che ospiteranno l'APK (`Build.SUPPORTED_ABIS`) con
 * davanti quelle della larghezza di parola **del processo di questa app**
 * (`Process.is64Bit()`), non della CPU. Un dispositivo a 64 bit che esegue una
 * installazione a 32 bit dichiara `arm64-v8a` prima di `armeabi-v7a`: seguendo
 * quell'ordine si offrirebbe l'APK a 64 bit a una app a 32 bit, cambiandole
 * architettura con l'aggiornamento. Una lista vuota (ambiente senza ABI
 * dichiarate) non è un errore: senza corrispondenza la selezione non sceglie
 * nulla e il download non parte.
 */
private fun installationAbis(): List<String> = abiPreferenceOrder(
  installedIs64BitProcess = Process.is64Bit(),
  supportedAbis = Build.SUPPORTED_ABIS?.toList().orEmpty(),
  supported32BitAbis = Build.SUPPORTED_32_BIT_ABIS?.toList().orEmpty(),
  supported64BitAbis = Build.SUPPORTED_64_BIT_ABIS?.toList().orEmpty()
)

/**
 * Decisione pura sul risultato del controllo, separata dall'I/O così da poter
 * essere verificata deterministicamente senza rete.
 *
 * [selectedAsset] è l'APK già scelto per il dispositivo da [selectApkAsset]:
 * la decisione non ripete la selezione, così l'asset valutato qui è
 * esattamente quello che verrà scaricato.
 *
 * Regole:
 *  - se un asset APK porta una versione estraibile diversa dal tag, la release è
 *    incoerente e non viene offerta ([UpdateCheckState.VersionMismatch]);
 *  - una versione uguale o inferiore a quella installata non è un aggiornamento;
 *  - una release più recente ma senza un APK installabile su questo dispositivo
 *    non viene offerta: [UpdateCheckState.Error] con
 *    [UpdateCheckErrorReason.NO_COMPATIBLE_APK];
 *  - la versione proposta è quella dell'asset quando nota, altrimenti il tag.
 */
internal fun evaluateUpdate(
  installed: AppVersion,
  latest: LatestRelease,
  selectedAsset: ReleaseAsset?
): UpdateCheckState {
  val apkAssets = latest.assets.filter { it.name.endsWith(".apk", ignoreCase = true) }

  val conflicting = apkAssets.firstOrNull { it.version != null && it.version != latest.version }
  if (conflicting != null) {
    return UpdateCheckState.VersionMismatch(
      currentVersion = installed.toString(),
      tagName = latest.tagName,
      tagVersion = latest.version.toString(),
      assetVersion = conflicting.version?.toString(),
      assetName = conflicting.name,
      releaseUrl = latest.releaseUrl
    )
  }

  val proposed = selectedAsset?.version ?: latest.version

  if (proposed <= installed) return UpdateCheckState.UpToDate(installed.toString())

  if (selectedAsset == null) {
    return UpdateCheckState.Error(UpdateCheckErrorReason.NO_COMPATIBLE_APK)
  }

  return UpdateCheckState.UpdateAvailable(
    currentVersion = installed.toString(),
    newVersion = proposed.toString(),
    tagName = latest.tagName,
    assetName = selectedAsset.name,
    changelog = latest.body,
    releaseUrl = latest.releaseUrl
  )
}

/**
 * Visibilità del popup dell'aggiornamento rilevato dal controllo AUTOMATICO di
 * avvio: serve un aggiornamento da proporre, nessuna schermata che l'utente sta
 * già usando e nessun altro modale aperto.
 *
 * La Home non viene mai interrotta: finché la splash è in corso, il player è in
 * riproduzione o c'è un dialogo/pannello aperto, il popup resta in attesa
 * (lo stato del controllo sopravvive) e compare appena la schermata è libera.
 */
internal fun shouldShowStartupUpdatePrompt(
  hasPrompt: Boolean,
  isSplashActive: Boolean,
  isPlayerActive: Boolean,
  isAnotherModalVisible: Boolean
): Boolean = hasPrompt && !isSplashActive && !isPlayerActive && !isAnotherModalVisible

/**
 * Repository dedicato al controllo degli aggiornamenti via GitHub Releases
 * (miciotto-web/StreamNova).
 *
 * Due percorsi distinti e indipendenti:
 *  - il controllo MANUALE, avviato dall'utente in Impostazioni, che scrive su
 *    [state] e mostra il proprio dialog con esito e download;
 *  - il controllo AUTOMATICO di avvio, che parte una sola volta per sessione in
 *    background, non tocca [state] e, se trova un aggiornamento, rende
 *    disponibile [startupPrompt]: il popup che l'utente vede all'avvio.
 *
 * Il download resta in ogni caso subordinato a una scelta esplicita dell'utente
 * (pulsante «Aggiorna» o «Scarica aggiornamento»): non esiste alcun download
 * automatico. Logica di installazione in [ApkInstaller].
 */
object UpdateRepository {

  private const val TAG = "UpdateRepository"

  /**
   * Collaboratori del controllo e del download. In produzione restano l'istanza
   * di default di entrambi — endpoint pubblico di GitHub e policy di download
   * reale (soli host GitHub, HTTPS) — mentre i test JVM li sostituiscono con
   * istanze puntate a un MockWebServer locale, come già fanno
   * [GitHubUpdateClient] e [ApkDownloader] tramite i loro parametri di
   * costruzione.
   */
  internal var client: GitHubUpdateClient = GitHubUpdateClient()
  internal var downloader: ApkDownloader = ApkDownloader()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private val _state = MutableStateFlow<UpdateCheckState>(UpdateCheckState.Idle)
  val state: StateFlow<UpdateCheckState> = _state.asStateFlow()

  private val _downloadState =
    MutableStateFlow<UpdateDownloadState>(UpdateDownloadState.Idle)
  val downloadState: StateFlow<UpdateDownloadState> = _downloadState.asStateFlow()

  /**
   * Aggiornamento offerto dal controllo AUTOMATICO di avvio, da mostrare nel popup
   * una sola volta per sessione. Null quando non c'è nulla da proporre, quando il
   * controllo non è riuscito o quando l'utente ha già scelto «Più tardi».
   *
   * Canale separato da [state]: il controllo automatico non deve far comparire il
   * dialog delle Impostazioni né accendere l'indicatore della riga «Controlla
   * aggiornamenti», che restano l'esito di un'azione manuale.
   */
  private val _startupPrompt = MutableStateFlow<UpdateCheckState.UpdateAvailable?>(null)
  val startupPrompt: StateFlow<UpdateCheckState.UpdateAvailable?> = _startupPrompt.asStateFlow()

  /**
   * APK compatibile con il dispositivo validato dal check più recente che ha
   * prodotto un'offerta: unica sorgente ammessa per il download. La selezione
   * avviene una sola volta, nel controllo (manuale o automatico), così il file
   * scaricato è esattamente quello valutato lì e non una scelta tardiva
   * potenzialmente diversa.
   */
  @Volatile
  private var validatedAsset: ReleaseAsset? = null

  /** true quando il controllo automatico di questa sessione (processo) è già partito. */
  @Volatile
  private var startupCheckLaunched = false

  /** true quando l'utente ha già risposto al popup di questa sessione. */
  @Volatile
  private var startupPromptAnswered = false

  private var downloadJob: Job? = null

  /** Avvia il controllo manuale confrontando la versione installata con l'ultima release. */
  fun checkForUpdates(currentVersion: String = BuildConfig.VERSION_NAME) {
    if (_state.value is UpdateCheckState.Checking) return
    scope.launch {
      validatedAsset = null
      resetDownload()
      _state.value = UpdateCheckState.Checking
      try {
        val outcome = runCheck(currentVersion)
        // Solo una release offribile diventa la sorgente del download: una
        // release incoerente, non più recente o senza un APK compatibile con
        // il dispositivo non può alimentare l'installer.
        validatedAsset = outcome.selectedAsset
        _state.value = outcome.state
      } catch (e: CancellationException) {
        throw e
      } catch (error: Exception) {
        _state.value = loggedCheckError(error)
      }
    }
  }

  /**
   * Controllo automatico di avvio, in background su Dispatchers.IO: parte una sola
   * volta per sessione e non blocca mai la Home.
   *
   * Se la release più recente è un aggiornamento offribile per questo dispositivo,
   * l'APK scelto viene validato per il download e il popup diventa disponibile su
   * [startupPrompt]. In ogni altro esito — app già aggiornata, release incoerente,
   * nessun APK compatibile, assenza di rete, timeout, errore HTTP, JSON non valido —
   * non viene mostrato nulla: un controllo che l'utente non ha chiesto non deve
   * interromperlo con un errore, e senza scelta esplicita non parte alcun download.
   *
   * La guardia [startupCheckLaunched] rende innocui i richiami: la ricreazione
   * dell'Activity allo stesso avvio non produce una seconda richiesta a GitHub.
   */
  fun checkForUpdatesOnStartup(currentVersion: String = BuildConfig.VERSION_NAME) {
    if (startupCheckLaunched) return
    startupCheckLaunched = true
    scope.launch {
      val outcome = try {
        runCheck(currentVersion)
      } catch (e: CancellationException) {
        throw e
      } catch (error: Exception) {
        Log.d(TAG, "Controllo aggiornamenti all'avvio non concluso: ${error.message}")
        return@launch
      }
      Log.d(
        TAG,
        "Controllo automatico all'avvio concluso: ${outcome.state::class.simpleName} " +
          "(installata $currentVersion)"
      )
      val available = outcome.state as? UpdateCheckState.UpdateAvailable ?: return@launch
      if (startupPromptAnswered) return@launch
      validatedAsset = outcome.selectedAsset
      _startupPrompt.value = available
      // Unica traccia lasciata dal controllo automatico: su una TV l'utente non ha
      // altro modo di sapere che il controllo è avvenuto e non c'era nulla da proporre.
      Log.i(TAG, "Aggiornamento disponibile: ${available.newVersion} (installata ${available.currentVersion})")
    }
  }

  /**
   * L'utente ha scelto «Più tardi» (o ha chiuso il popup): l'aggiornamento non
   * viene più riproposto in questa sessione. L'APK validato resta invece
   * scaricabile dal controllo manuale in Impostazioni, che è un'altra azione
   * esplicita dell'utente.
   */
  fun dismissStartupPrompt() {
    startupPromptAnswered = true
    _startupPrompt.value = null
  }

  /**
   * Scarica l'APK validato dalla release già individuata. Parte solo su
   * richiesta esplicita dell'utente; tutto gira su Dispatcher.IO (mai il main
   * thread). Se il controllo non ha validato un APK installabile su questo
   * dispositivo non parte alcun download.
   */
  fun downloadUpdate(context: Context) {
    if (downloadJob?.isActive == true) return
    val asset = validatedAsset
    if (asset == null) {
      // Nessun APK compatibile validato dal check: la release non ha un file
      // installabile per questo dispositivo, quindi non c'è nulla da scaricare.
      _downloadState.value = UpdateDownloadState.Error(DownloadErrorReason.NO_APK_IN_RELEASE)
      return
    }

    val appContext = context.applicationContext
    downloadJob = scope.launch {
      _downloadState.value = UpdateDownloadState.Downloading(null)
      _downloadState.value = try {
        val file = downloader.download(appContext, asset) { percent ->
          if (!isActive) throw CancellationException("Download annullato")
          _downloadState.value = UpdateDownloadState.Downloading(percent)
        }
        UpdateDownloadState.Completed(file.absolutePath)
      } catch (e: DownloadException) {
        Log.w(TAG, "Download APK fallito: ${e.reason} ${e.message}")
        UpdateDownloadState.Error(e.reason)
      } catch (e: CancellationException) {
        // Il reset dell'utente ha già riportato lo stato a Idle
        throw e
      } catch (e: Exception) {
        Log.w(TAG, "Download APK fallito: ${e.message}")
        UpdateDownloadState.Error(DownloadErrorReason.UNKNOWN)
      }
    }
  }

  /**
   * Riporta lo stato a Idle dopo la chiusura del feedback mostrato all'utente.
   * Invalida anche l'APK validato: l'asset vive quanto il risultato del check
   * che lo ha scelto, così un download non può partire da una verifica ormai
   * scartata.
   */
  fun reset() {
    validatedAsset = null
    _state.value = UpdateCheckState.Idle
    resetDownload()
  }

  /**
   * Azzera solo lo stato del download (usato quando si chiude il feedback del
   * download o all'inizio di un nuovo controllo). L'asset validato resta: è la
   * sorgente dei tentativi successivi, come il "riprova" dopo un errore.
   */
  fun resetDownload() {
    downloadJob?.cancel()
    downloadJob = null
    _downloadState.value = UpdateDownloadState.Idle
  }

  /**
   * Riporta allo stato iniziale anche la sessione del controllo automatico: popup
   * pendente e guardie «già avviato» / «già risposto». Riservato ai test, che
   * devono poter osservare più avvii nella stessa JVM: in produzione la sessione
   * dura quanto il processo e non è riazzerabile.
   */
  internal fun resetSessionForTests() {
    startupCheckLaunched = false
    startupPromptAnswered = false
    _startupPrompt.value = null
    reset()
  }

  /**
   * Composizione unica del controllo: lettura dell'ultima release, scelta dell'APK
   * per questo dispositivo e decisione. Condivisa dal percorso manuale e da quello
   * automatico, così i due non possono divergere nella scelta dell'asset né nelle
   * regole di offerta.
   */
  private fun runCheck(currentVersion: String): CheckOutcome {
    val installed = AppVersion.parse(currentVersion)
      ?: throw UpdateCheckException(
        UpdateCheckErrorReason.INVALID_RELEASE,
        "Versione installata non valida: $currentVersion"
      )
    val latest = client.fetchLatestRelease()
    val selected = selectApkAsset(latest.assets, installationAbis())
    val state = evaluateUpdate(installed, latest, selected)
    return CheckOutcome(
      state = state,
      selectedAsset = if (state is UpdateCheckState.UpdateAvailable) selected else null
    )
  }

  /** Logga l'errore del controllo e lo traduce nello stato mostrato all'utente. */
  private fun loggedCheckError(error: Throwable): UpdateCheckState.Error {
    Log.w(TAG, "Controllo aggiornamenti fallito: ${error.message}")
    return UpdateCheckState.Error(
      (error as? UpdateCheckException)?.reason ?: UpdateCheckErrorReason.UNKNOWN
    )
  }

  /** Esito del controllo: stato da mostrare e asset scaricabile (solo se offribile). */
  private data class CheckOutcome(
    val state: UpdateCheckState,
    val selectedAsset: ReleaseAsset?
  )
}

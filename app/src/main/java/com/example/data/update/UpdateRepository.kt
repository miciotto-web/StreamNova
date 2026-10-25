package com.example.data.update

import android.content.Context
import android.os.Build
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
 * ABI supportate dal dispositivo nell'ordine di preferenza dichiarato dal
 * sistema: è l'ordine con cui [selectApkAsset] sceglie lo split da scaricare.
 * Una lista vuota (ambiente senza ABI dichiarate) non è un errore: senza
 * corrispondenza la selezione non sceglie nulla e il download non parte.
 */
private fun deviceSupportedAbis(): List<String> = Build.SUPPORTED_ABIS?.toList().orEmpty()

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
 * Repository dedicato al controllo degli aggiornamenti via GitHub Releases
 * (miciotto-web/StreamNova). Nessun controllo automatico all'avvio e nessun
 * download in background: check e download partono solo da azioni esplicite
 * dell'utente in Impostazioni. Logica di installazione in [ApkInstaller].
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
   * APK compatibile con il dispositivo validato dal check più recente: unica
   * sorgente ammessa per il download. La selezione avviene una sola volta, nel
   * controllo, così il file scaricato è esattamente quello valutato lì e non
   * una scelta tardiva potenzialmente diversa.
   */
  @Volatile
  private var validatedAsset: ReleaseAsset? = null

  private var downloadJob: Job? = null

  /** Avvia il controllo manuale confrontando la versione installata con l'ultima release. */
  fun checkForUpdates(currentVersion: String = BuildConfig.VERSION_NAME) {
    if (_state.value is UpdateCheckState.Checking) return
    scope.launch {
      validatedAsset = null
      resetDownload()
      _state.value = UpdateCheckState.Checking
      _state.value = runCatching {
        val installed = AppVersion.parse(currentVersion)
          ?: throw UpdateCheckException(
            UpdateCheckErrorReason.INVALID_RELEASE,
            "Versione installata non valida: $currentVersion"
          )
        val latest = client.fetchLatestRelease()
        val selected = selectApkAsset(latest.assets, deviceSupportedAbis())
        evaluateUpdate(installed, latest, selected).also { result ->
          // Solo una release offribile diventa la sorgente del download: una
          // release incoerente, non più recente o senza un APK compatibile con
          // il dispositivo non può alimentare l'installer.
          if (result is UpdateCheckState.UpdateAvailable) validatedAsset = selected
        }
      }.getOrElse { error ->
        Log.w(TAG, "Controllo aggiornamenti fallito: ${error.message}")
        UpdateCheckState.Error(
          (error as? UpdateCheckException)?.reason ?: UpdateCheckErrorReason.UNKNOWN
        )
      }
    }
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
}

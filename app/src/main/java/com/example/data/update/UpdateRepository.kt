package com.example.data.update

import android.content.Context
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
    val releaseUrl: String?
  ) : UpdateCheckState
  data object Error : UpdateCheckState
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
 * Repository dedicato al controllo degli aggiornamenti via GitHub Releases
 * (miciotto-web/StreamNova). Nessun controllo automatico all'avvio e nessun
 * download in background: check e download partono solo da azioni esplicite
 * dell'utente in Impostazioni. Logica di installazione in [ApkInstaller].
 */
object UpdateRepository {

  private const val TAG = "UpdateRepository"

  private val client = GitHubUpdateClient()
  private val downloader = ApkDownloader()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private val _state = MutableStateFlow<UpdateCheckState>(UpdateCheckState.Idle)
  val state: StateFlow<UpdateCheckState> = _state.asStateFlow()

  private val _downloadState =
    MutableStateFlow<UpdateDownloadState>(UpdateDownloadState.Idle)
  val downloadState: StateFlow<UpdateDownloadState> = _downloadState.asStateFlow()

  /** Ultima release individuata dal check: unica sorgente ammessa per il download. */
  @Volatile
  private var latestRelease: LatestRelease? = null

  private var downloadJob: Job? = null

  /** Avvia il controllo manuale confrontando la versione installata con l'ultima release. */
  fun checkForUpdates(currentVersion: String = BuildConfig.VERSION_NAME) {
    if (_state.value is UpdateCheckState.Checking) return
    scope.launch {
      latestRelease = null
      resetDownload()
      _state.value = UpdateCheckState.Checking
      _state.value = runCatching {
        val installed = AppVersion.parse(currentVersion)
          ?: throw UpdateCheckException("Versione installata non valida: $currentVersion")
        val latest = client.fetchLatestRelease()
        if (latest.version > installed) {
          latestRelease = latest
          UpdateCheckState.UpdateAvailable(currentVersion, latest.tagName, latest.releaseUrl)
        } else {
          UpdateCheckState.UpToDate(currentVersion)
        }
      }.getOrElse { error ->
        Log.w(TAG, "Controllo aggiornamenti fallito: ${error.message}")
        UpdateCheckState.Error
      }
    }
  }

  /**
   * Scarica l'APK della release già individuata. Parte solo su richiesta
   * esplicita dell'utente; tutto gira su Dispatcher.IO (mai il main thread).
   */
  fun downloadUpdate(context: Context) {
    if (downloadJob?.isActive == true) return
    val release = latestRelease
    val asset = release?.let { selectApkAsset(it.assets) }
    if (asset == null) {
      val reason = if (release == null) {
        DownloadErrorReason.UNKNOWN
      } else {
        DownloadErrorReason.NO_APK_IN_RELEASE
      }
      _downloadState.value = UpdateDownloadState.Error(reason)
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

  /** Riporta lo stato a Idle dopo la chiusura del feedback mostrato all'utente. */
  fun reset() {
    _state.value = UpdateCheckState.Idle
    resetDownload()
  }

  fun resetDownload() {
    downloadJob?.cancel()
    downloadJob = null
    _downloadState.value = UpdateDownloadState.Idle
  }
}


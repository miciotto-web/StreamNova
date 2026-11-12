package com.example

import android.app.Application
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import com.example.data.image.ImageCache
import com.example.data.local.StreamNovaDatabase
import com.example.data.prefs.AppSettingsRepository
import com.example.data.prefs.PersistentSubtitleRepository
import com.example.data.prefs.SettingsRepository
import com.example.data.profiles.ProfileStorage
import com.example.data.profiles.ProfilesRepository
import com.example.data.repository.MediaRepository
import com.example.data.repository.SimklRepository
import com.example.data.repository.SimklSyncManager
import com.example.data.repository.LastWorkingStreamRepository
import com.example.data.stremio.StremioAddonRepository
import com.example.data.stremio.provider.ProviderCatalogResolvers
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class StreamNovaApplication : Application(), ImageLoaderFactory {

  private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  /**
   * Configurazione ESPLICITA dell'ImageLoader di Coil usato da tutta l'app.
   *
   * Prima l'app usava il singleton implicito di Coil, creato alla prima immagine con
   * valori interni alla libreria. Qui la configurazione diventa di proprietà dell'app,
   * così la directory della disk cache è nota anche a "Svuota cache" (vedi [ImageCache])
   * e i valori possono essere rivisti in un solo punto.
   *
   * Cosa NON viene sovrascritto, e perché (verificato sui sorgenti di Coil 2.7.0):
   *  - **cache in memoria**: il default è già adattivo al dispositivo
   *    (`defaultMemoryCacheSizePercent`: 25% della memory class, ridotto per i device
   *    "low RAM"), quindi una percentuale fissa peggiorerebbe i TV box piccoli o
   *    sprecherebbe memoria su quelli grandi senza misure a supporto;
   *  - **dimensione della disk cache**: default 2% dello spazio libero, limitato tra
   *    10 MB e 250 MB — adatto anche a device con storage ridotto;
   *  - **crossfade**: resta deciso dalla singola richiesta (le card usano `crossfade(true)`,
   *    l'hero `crossfade(350)`): un default globale cambierebbe le animazioni esistenti;
   *  - **`allowRgb565`**: dimezzerebbe la memoria dei bitmap ma degraderebbe i gradienti
   *    (poster e sfumature Neon), quindi non è un compromesso sicuro per la UI;
   *  - **OkHttp**: Coil crea il proprio client; l'app non ha un client condiviso
   *    (ogni componente ne costruisce uno), quindi non c'è nulla da riusare.
   */
  override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
    .diskCache {
      DiskCache.Builder()
        .directory(ImageCache.directory(this))
        .build()
    }
    .build()

  override fun onCreate() {
    super.onCreate()
    // Il processo del trampolino di riavvio (cambio profilo) NON deve inizializzare
    // Room, DataStore o Sync: girerebbe in parallelo al processo principale sugli
    // stessi file. Gli serve solo mostrare un'attesa e lanciare MainActivity.
    if (isAuxiliaryProcess()) return
    StartupTrace.mark("application_oncreate_enter")
    // Profili: PRIMA di Room. La scelta del profilo determina il file del database e i
    // DataStore personali (account SIMKL, selezioni sottotitoli). Il profilo principale
    // conserva i nomi storici, quindi l'aggiornamento non tocca i dati esistenti.
    val profiles = StartupTrace.trace("profiles_init") { ProfilesRepository.init(this) }
    val profileId = profiles.activeProfileId
    StartupTrace.mark("profile_active_$profileId")

    // Initialize Room Database (un file per profilo)
    val db = StartupTrace.trace("room_init") {
      StreamNovaDatabase.init(this, ProfileStorage.databaseName(profileId))
    }

    // Initialize MediaRepository with Room database
    StartupTrace.trace("media_repository_init") { MediaRepository.init(this, db) }

    // Cache dell'ultimo flusso funzionante ("Riusa l'ultimo link") sullo stesso DB Room.
    StartupTrace.trace("last_working_stream_init") { LastWorkingStreamRepository.init(db) }

    // Initialize persistent playback settings (DataStore Preferences)
    StartupTrace.trace("settings_repository_init") { SettingsRepository.init(this) }

    // Preferenze Debrid/TorBox, credenziali SIMKL + lista addon Stremio installati (DataStore)
    StartupTrace.trace("app_settings_repository_init") { AppSettingsRepository.init(this) }
    StartupTrace.trace("persistent_subtitle_repository_init") {
      PersistentSubtitleRepository.init(this, profileId)
    }
    StartupTrace.trace("simkl_repository_init") { SimklRepository.init(this, profileId = profileId) }
    StartupTrace.trace("stremio_addon_repository_init") { StremioAddonRepository.init() }
    // Contesto per il resolver catalogo -> provider: senza questo la sorgente
    // REGISTRY (res/raw/stremio_provider_bindings.json) resterebbe sempre vuota,
    // mentre i binding USER letti da DataStore funzionerebbero lo stesso.
    StartupTrace.trace("provider_catalog_resolvers_init") { ProviderCatalogResolvers.init(this) }
    // Modalità Sync (Impostazioni): l'init legge solo la preferenza persistita; con
    // la Sync attiva server HTTP e annuncio NSD ripartono sul thread IO.
    StartupTrace.trace("sync_manager_init") { com.example.data.sync.SyncManager.init(this) }
    StartupTrace.mark("application_oncreate_exit")

    // Preload cached media immediately on background thread
    appScope.launch {
      MediaRepository.loadFromCache()
      // Sincronizzazione SIMKL all'avvio: il pull è gate su GET /sync/activities, quindi
      // con l'account fermo costa una sola richiesta. Si attende che il catalogo locale
      // sia in memoria, altrimenti i titoli già presenti verrebbero reimportati.
      if (SimklRepository.accountState.value.isLoggedIn) {
        withTimeoutOrNull(15_000L) { MediaRepository.mediaList.first { it.isNotEmpty() } }
        // Stesso debounce del ritorno in primo piano: un riavvio ravvicinato non deve
        // produrre una seconda sincronizzazione completa.
        runCatching { SimklSyncManager.syncOnForeground() }
      }
    }
  }

  /** True nei processi di servizio dichiarati nel manifest (es. `:profile_restart`). */
  private fun isAuxiliaryProcess(): Boolean {
    val processName = runCatching {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        Application.getProcessName()
      } else {
        File("/proc/self/cmdline").readText().trim('\u0000', '\n')
      }
    }.getOrNull().orEmpty()
    return processName.endsWith(":profile_restart")
  }
}

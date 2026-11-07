package com.example

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import com.example.data.image.ImageCache
import com.example.data.local.StreamNovaDatabase
import com.example.data.prefs.AppSettingsRepository
import com.example.data.prefs.PersistentSubtitleRepository
import com.example.data.prefs.SettingsRepository
import com.example.data.repository.MediaRepository
import com.example.data.repository.SimklRepository
import com.example.data.repository.SimklSyncManager
import com.example.data.repository.LastWorkingStreamRepository
import com.example.data.stremio.StremioAddonRepository
import com.example.data.stremio.provider.ProviderCatalogResolvers
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
    StartupTrace.mark("application_oncreate_enter")
    // Initialize Room Database
    val db = StartupTrace.trace("room_init") { StreamNovaDatabase.init(this) }

    // Initialize MediaRepository with Room database
    StartupTrace.trace("media_repository_init") { MediaRepository.init(this, db) }

    // Cache dell'ultimo flusso funzionante ("Riusa l'ultimo link") sullo stesso DB Room.
    StartupTrace.trace("last_working_stream_init") { LastWorkingStreamRepository.init(db) }

    // Initialize persistent playback settings (DataStore Preferences)
    StartupTrace.trace("settings_repository_init") { SettingsRepository.init(this) }

    // Preferenze Debrid/TorBox, credenziali SIMKL + lista addon Stremio installati (DataStore)
    StartupTrace.trace("app_settings_repository_init") { AppSettingsRepository.init(this) }
    StartupTrace.trace("persistent_subtitle_repository_init") { PersistentSubtitleRepository.init(this) }
    StartupTrace.trace("simkl_repository_init") { SimklRepository.init(this) }
    StartupTrace.trace("stremio_addon_repository_init") { StremioAddonRepository.init() }
    // Contesto per il resolver catalogo -> provider: senza questo la sorgente
    // REGISTRY (res/raw/stremio_provider_bindings.json) resterebbe sempre vuota,
    // mentre i binding USER letti da DataStore funzionerebbero lo stesso.
    StartupTrace.trace("provider_catalog_resolvers_init") { ProviderCatalogResolvers.init(this) }
    StartupTrace.mark("application_oncreate_exit")

    // Preload cached media immediately on background thread
    appScope.launch {
      MediaRepository.loadFromCache()
      // Sincronizzazione SIMKL all'avvio: il pull è gate su GET /sync/activities, quindi
      // con l'account fermo costa una sola richiesta. Si attende che il catalogo locale
      // sia in memoria, altrimenti i titoli già presenti verrebbero reimportati.
      if (SimklRepository.accountState.value.isLoggedIn) {
        withTimeoutOrNull(15_000L) { MediaRepository.mediaList.first { it.isNotEmpty() } }
        runCatching { SimklSyncManager.sync() }
      }
    }
  }
}

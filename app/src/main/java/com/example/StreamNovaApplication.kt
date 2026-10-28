package com.example

import android.app.Application
import com.example.data.local.StreamNovaDatabase
import com.example.data.prefs.AppSettingsRepository
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

class StreamNovaApplication : Application() {

  private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  override fun onCreate() {
    super.onCreate()
    // Initialize Room Database
    val db = StreamNovaDatabase.init(this)

    // Initialize MediaRepository with Room database
    MediaRepository.init(this, db)

    // Cache dell'ultimo flusso funzionante ("Riusa l'ultimo link") sullo stesso DB Room.
    LastWorkingStreamRepository.init(db)

    // Initialize persistent playback settings (DataStore Preferences)
    SettingsRepository.init(this)

    // Preferenze Debrid/TorBox, credenziali SIMKL + lista addon Stremio installati (DataStore)
    AppSettingsRepository.init(this)
    SimklRepository.init(this)
    StremioAddonRepository.init()
    // Contesto per il resolver catalogo -> provider: senza questo la sorgente
    // REGISTRY (res/raw/stremio_provider_bindings.json) resterebbe sempre vuota,
    // mentre i binding USER letti da DataStore funzionerebbero lo stesso.
    ProviderCatalogResolvers.init(this)

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

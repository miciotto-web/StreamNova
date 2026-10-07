package com.example

import android.app.Application
import com.example.data.local.StreamNovaDatabase
import com.example.data.prefs.AppSettingsRepository
import com.example.data.prefs.SettingsRepository
import com.example.data.repository.MediaRepository
import com.example.data.stremio.StremioAddonRepository
import com.example.data.stremio.provider.ProviderCatalogResolvers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class StreamNovaApplication : Application() {

  private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  override fun onCreate() {
    super.onCreate()
    // Initialize Room Database
    val db = StreamNovaDatabase.init(this)

    // Initialize MediaRepository with Room database
    MediaRepository.init(this, db)

    // Initialize persistent playback settings (DataStore Preferences)
    SettingsRepository.init(this)

    // Preferenze Debrid/TorBox + lista addon Stremio installati (DataStore)
    AppSettingsRepository.init(this)
    StremioAddonRepository.init()
    // Contesto per il resolver catalogo -> provider: senza questo la sorgente
    // REGISTRY (res/raw/stremio_provider_bindings.json) resterebbe sempre vuota,
    // mentre i binding USER letti da DataStore funzionerebbero lo stesso.
    ProviderCatalogResolvers.init(this)

    // Preload cached media immediately on background thread
    appScope.launch {
      MediaRepository.loadFromCache()
    }
  }
}

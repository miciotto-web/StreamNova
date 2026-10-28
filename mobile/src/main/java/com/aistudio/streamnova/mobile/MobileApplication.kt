package com.aistudio.streamnova.mobile

import android.app.Application
import com.example.data.config.MediaSampleAssets
import com.example.data.config.StreamNovaClientConfig
import com.example.data.config.StreamNovaConfig
import com.example.data.local.StreamNovaDatabase
import com.example.data.prefs.AppSettingsRepository
import com.example.data.prefs.SettingsRepository
import com.example.data.repository.LastWorkingStreamRepository
import com.example.data.repository.MediaRepository
import com.example.data.repository.SimklRepository
import com.example.data.stremio.StremioAddonRepository
import com.example.data.stremio.provider.ProviderCatalogResolvers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application dell'app mobile.
 *
 * Installa la configurazione esplicita del client prima di toccare il core
 * condiviso: versione, credenziali risolte dalla build e asset demo dell'app.
 * Nessuna credenziale è scritta nel sorgente.
 */
class MobileApplication : Application() {

  private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  override fun onCreate() {
    super.onCreate()

    StreamNovaConfig.install(
      StreamNovaClientConfig(
        appId = "mobile",
        versionName = BuildConfig.VERSION_NAME,
        isDebug = BuildConfig.DEBUG,
        tmdbApiKeyCandidates = listOf(
          BuildConfig.TMDB_API_KEY,
          BuildConfig.ENV_TMDB_API_KEY
        ),
        simklClientIdChannels = listOf(
          SimklRepository.SimklClientIdChannel(
            "build:ENV_SIMKL_CLIENT_ID",
            BuildConfig.ENV_SIMKL_CLIENT_ID
          ),
          SimklRepository.SimklClientIdChannel(
            "build:SIMKL_CLIENT_ID",
            BuildConfig.SIMKL_CLIENT_ID
          )
        ),
        mediaSampleAssets = MediaSampleAssets(
          lastOfUs = R.drawable.banner_lastofus,
          cosmos = R.drawable.banner_cosmos,
          dune = R.drawable.banner_dune
        )
      )
    )

    val db = StreamNovaDatabase.init(this)
    MediaRepository.init(this, db)
    LastWorkingStreamRepository.init(db)
    SettingsRepository.init(this)
    AppSettingsRepository.init(this)
    SimklRepository.init(this)
    StremioAddonRepository.init()
    ProviderCatalogResolvers.init(this)

    appScope.launch {
      MediaRepository.loadFromCache()
    }
  }
}

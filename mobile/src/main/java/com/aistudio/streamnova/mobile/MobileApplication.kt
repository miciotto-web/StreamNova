package com.aistudio.streamnova.mobile

import android.app.Application
import com.example.data.config.StreamNovaConfig
import com.example.data.local.StreamNovaDatabase
import com.example.data.prefs.AppSettingsRepository
import com.example.data.prefs.SettingsRepository
import com.example.data.repository.LastWorkingStreamRepository
import com.example.data.repository.MediaRepository
import com.example.data.repository.SimklRepository
import com.example.data.repository.SimklSyncManager
import com.example.data.stremio.StremioAddonRepository
import com.aistudio.streamnova.mobile.ui.MobilePlayerPreferences
import com.example.data.stremio.provider.ProviderCatalogResolvers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Application dell'app mobile.
 *
 * Installa la configurazione esplicita del client prima di toccare il core
 * condiviso: versione e credenziali risolte dalla build. Mobile disattiva il
 * catalogo seed/demo e la generazione di episodi provvisori: usa solo le
 * sorgenti reali (Cinemeta, TMDB, addon Stremio). Nessuna credenziale è scritta
 * nel sorgente.
 */
class MobileApplication : Application() {

  private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  override fun onCreate() {
    super.onCreate()

    StreamNovaConfig.install(
      mobileClientConfig(
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
        )
      )
    )

    val db = StreamNovaDatabase.init(this)
    MediaRepository.init(this, db)
    LastWorkingStreamRepository.init(db)
    SettingsRepository.init(this)
    AppSettingsRepository.init(this)
    // La lingua persistita deve essere nota PRIMA che l'Activity costruisca il
    // proprio base context (altrimenti il primo frame, e i dialog, userebbero la
    // lingua di sistema). Lettura locale breve, nessuna rete.
    runBlocking { AppSettingsRepository.awaitLoaded(1_000L) }
    SimklRepository.init(this)
    StremioAddonRepository.init()
    ProviderCatalogResolvers.init(this)
    // Preferenze del player touch (gesture, schermo in pausa, buffering extra):
    // locali al modulo Mobile, non condivise con la TV.
    MobilePlayerPreferences.init(this)

    appScope.launch {
      MediaRepository.loadFromCache()
      // Stessa sincronizzazione SIMKL d'avvio della TV: il pull è gate su
      // /sync/activities, quindi con l'account fermo costa una sola richiesta.
      // Si attende il catalogo locale per evitare reimport dei titoli già presenti.
      if (SimklRepository.accountState.value.isLoggedIn) {
        withTimeoutOrNull(15_000L) {
          MediaRepository.mediaList.first { it.isNotEmpty() }
        }
        runCatching { SimklSyncManager.sync() }
      }
    }
  }
}

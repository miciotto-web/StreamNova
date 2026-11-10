package com.aistudio.streamnova.mobile

import com.example.data.config.StreamNovaClientConfig
import com.example.data.repository.SimklRepository

/**
 * Configurazione del client Mobile, separata dall'[MobileApplication] per poter
 * verificare senza Android che il percorso Mobile non attivi mai seed/demo o
 * episodi sintetici.
 */
internal fun mobileClientConfig(
  versionName: String,
  isDebug: Boolean,
  tmdbApiKeyCandidates: List<String?>,
  simklClientIdChannels: List<SimklRepository.SimklClientIdChannel>
): StreamNovaClientConfig = StreamNovaClientConfig(
  appId = "mobile",
  versionName = versionName,
  isDebug = isDebug,
  tmdbApiKeyCandidates = tmdbApiKeyCandidates,
  simklClientIdChannels = simklClientIdChannels,
  // Mobile non ha un catalogo demo: senza sorgenti reali mostra lo stato
  // vuoto/errore, non titoli o episodi inventati.
  includeDemoCatalog = false,
  generateSyntheticEpisodes = false,
  // Canale release dedicato all'app Mobile: l'updater in-app non deve mai
  // vedere le release della TV (repo: miciotto-web/StreamNova-Mobile).
  updateRepoSlug = "miciotto-web/StreamNova-Mobile"
)

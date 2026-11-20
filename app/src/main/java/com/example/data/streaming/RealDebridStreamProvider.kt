package com.example.data.streaming

import android.util.Log
import com.example.data.debrid.DebridCacheGateway
import com.example.data.debrid.DebridStreamResolver
import com.example.data.realdebrid.RealDebridRepository
import com.example.data.stremio.StremioAddonRepository

/**
 * [StreamProvider] dedicato a **Real-Debrid Instant Debrid**.
 *
 * Interviene quando Real-Debrid è il servizio selezionato nelle Impostazioni
 * (toggle ON + chiave API) ed esiste una sorgente torrent/infoHash disponibile,
 * proveniente dagli **addon Stremio** installati (risorsa `stream` con campo
 * `infoHash`).
 *
 * Tutti gli infoHash raccolti vengono verificati con `instantAvailability`
 * (per-hash) tramite [com.example.data.debrid.DebridStreamResolver]: gli stream
 * confermati `cached = true` restano con `streamUrl = null` e vengono sbloccati
 * on-demand al click (addMagnet → selectFiles → unrestrict/link).
 */
open class RealDebridStreamProvider(
  private val gateway: DebridCacheGateway = RealDebridRepository,
  private val addonRepository: StremioAddonRepository = StremioAddonRepository
) : StreamProvider {

  override val providerId: String = "RealDebridStreamProvider"

  companion object {
    private const val TAG = "RealDebridStreamProvider"
    private const val SERVICE_NAME = "Real-Debrid"
  }

  open override suspend fun getStreams(
    tmdbId: Int,
    isTv: Boolean,
    season: Int?,
    episode: Int?,
    title: String?,
    year: Int?
  ): List<StreamSource> = getStreams(tmdbId, isTv, season, episode, title, year, null)

  open suspend fun getStreams(
    tmdbId: Int,
    isTv: Boolean,
    season: Int?,
    episode: Int?,
    title: String?,
    year: Int?,
    imdbId: String?
  ): List<StreamSource> {
    if (!gateway.isConfigured()) {
      Log.d(TAG, "Real-Debrid non configurato (toggle/chiave): nessuna risoluzione debrid")
      return emptyList()
    }
    val candidates = DebridStreamResolver.fetchAddonCandidates(
      addonRepository = addonRepository,
      tmdbId = tmdbId,
      isTv = isTv,
      season = season,
      episode = episode,
      imdbId = imdbId,
      serviceName = SERVICE_NAME
    )
    if (candidates.isEmpty()) return emptyList()
    return DebridStreamResolver.resolveCachedSources(gateway, candidates, SERVICE_NAME)
  }
}

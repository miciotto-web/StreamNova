package com.example.data.streaming

import android.util.Log
import com.example.data.debrid.DebridCacheGateway
import com.example.data.debrid.DebridStreamResolver
import com.example.data.premiumize.PremiumizeRepository
import com.example.data.stremio.StremioAddonRepository

/**
 * [StreamProvider] dedicato a **Premiumize Instant Debrid**.
 *
 * Interviene quando Premiumize è il servizio selezionato nelle Impostazioni
 * (toggle ON + chiave API) ed esiste una sorgente torrent/infoHash disponibile,
 * proveniente dagli **addon Stremio** installati (risorsa `stream` con campo
 * `infoHash`).
 *
 * Tutti gli infoHash raccolti vengono verificati con `cache/check`
 * (magnet → booleani) tramite [com.example.data.debrid.DebridStreamResolver]:
 * gli stream confermati `cached = true` restano con `streamUrl = null` e vengono
 * sbloccati on-demand al click (transfer/create → item/details → stream_link).
 */
open class PremiumizeStreamProvider(
  private val gateway: DebridCacheGateway = PremiumizeRepository,
  private val addonRepository: StremioAddonRepository = StremioAddonRepository
) : StreamProvider {

  override val providerId: String = "PremiumizeStreamProvider"

  companion object {
    private const val TAG = "PremiumizeStreamProvider"
    private const val SERVICE_NAME = "Premiumize"
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
      Log.d(TAG, "Premiumize non configurato (toggle/chiave): nessuna risoluzione debrid")
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

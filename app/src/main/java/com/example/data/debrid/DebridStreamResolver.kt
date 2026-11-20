package com.example.data.debrid

import android.util.Log
import com.example.data.streaming.StreamSource
import com.example.data.stremio.StremioAddonRepository
import com.example.data.stremio.StremioStreamCandidate
import com.example.data.stremio.StremioSubtitleAdapter
import com.example.data.stremio.StremioSubtitleBridge
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Risoluzione condivisa dei candidati torrent per i servizi Debrid accostati a
 * TorBox (Real-Debrid, Premiumize).
 *
 * STRATEGIA (identica a `TorBoxStreamProvider.resolveItems`, modello NuvioTV):
 *  1. tutti gli infoHash raccolti dagli addon Stremio vengono verificati con
 *     UNA sola interrogazione multi-hash del gateway del servizio;
 *  2. gli stream confermati `cached = true` diventano [StreamSource] con i
 *     metadati (addon, qualità, nome file, dimensione, codec, tipo rilascio)
 *     ma CON `streamUrl = null` (nessuno sblocco preventivo);
 *  3. lo sblocco avviene ON-DEMAND al click dell'utente via
 *     [DebridServiceRouter.resolveInfoHash].
 *
 * Gli hash non in cache non generano alcuna chiamata di sblocco.
 */
object DebridStreamResolver {

  private const val TAG = "DebridStreamResolver"
  // DIAG TEMPORANEO (solo logging), stesso tag del percorso TorBox.
  private const val DIAG_TAG = "COMET_DIAG"

  /**
   * Risolve una lista di candidati torrent in [StreamSource] per la selezione,
   * usando il gateway del servizio Debrid selezionato.
   *
   * @param serviceName etichetta del servizio (diagnostica/log).
   */
  suspend fun resolveCachedSources(
    gateway: DebridCacheGateway,
    candidates: List<StremioStreamCandidate>,
    serviceName: String
  ): List<StreamSource> {
    if (candidates.isEmpty() || !gateway.isConfigured()) return emptyList()

    // Deduplica per installazione + coppia (infoHash, fileIdx): preserva file
    // differenti dello stesso torrent (1080p vs 720p) e, soprattutto, i risultati
    // di due CONFIGURAZIONI dello stesso addon (Torrentio globale e Torrentio IT),
    // che condividono `manifest.id` e possono restituire gli stessi torrent.
    val distinct = candidates.distinctBy {
      "${it.baseUrl.trimEnd('/')}|${it.item.infoHash?.trim()?.lowercase()}_${it.item.fileIdx ?: -1}"
    }
    val hashes = distinct.mapNotNull { it.item.infoHash?.trim()?.lowercase() }.distinct()
    if (hashes.isEmpty()) return emptyList()
    Log.i(TAG, "$serviceName: ${hashes.size} hash da addon da verificare")

    // UN solo check multi-hash per tutti gli hash della lista.
    val entries = try {
      gateway.checkCached(hashes)
    } catch (e: Exception) {
      Log.w(TAG, "$serviceName: check multi-hash fallito: ${e.message}")
      emptyMap()
    }
    val cachedHashes = entries.filterValues { it.cached }.keys.toSet()
    Log.i(TAG, "$serviceName: ${hashes.size} hash da addon, ${cachedHashes.size} in cache istantanea")
    Log.i(
      DIAG_TAG,
      "DIAG-B $serviceName totals candidates=${candidates.size} after_dedup=${distinct.size} " +
        "hashes_sent=${hashes.size} cached=${cachedHashes.size}"
    )
    if (cachedHashes.isEmpty()) return emptyList()

    // Costruisce StreamSource CON streamUrl = NULL per OGNI hash in cache:
    // lo sblocco avverra' on-demand al click dell'utente.
    val sources = mutableListOf<StreamSource>()
    for (candidate in distinct) {
      val normalizedHash = candidate.item.infoHash?.trim()?.lowercase() ?: continue
      if (normalizedHash !in cachedHashes) continue

      val quality = candidate.quality
      // Identità STABILE del provider = titolo del manifest dell'addon Stremio
      // (es. "Torrentio"): NON la prima riga di `stream.name`, che in alcune
      // configurazioni include già qualità/HDR/DV.
      val providerName = candidate.addonName
      val addonNameFromStream = candidate.addonNameFromStream
      val instantTag = candidate.instantTag
      val releaseTitle = candidate.releaseTitle
      val details = candidate.sizeAndPeers
      val codec = candidate.codec
      val releaseType = candidate.releaseType
      val isItalian = StreamSource.isItalianSource(
        listOfNotNull(addonNameFromStream, releaseTitle).joinToString(" ")
      )
      val subtitles = StremioSubtitleAdapter.toSubtitles(
        StremioSubtitleBridge.fromStream(candidate.item),
        addonName = providerName
      )

      Log.i(TAG, "$serviceName: in cache ($quality) da $providerName - $releaseTitle")
      sources.add(
        StreamSource(
          streamUrl = null,
          quality = quality,
          serverName = "$providerName 🧲 $instantTag",
          headers = emptyMap(),
          declaredQuality = quality,
          isProgressive = true,
          isItalian = isItalian,
          addonName = providerName,
          // Identità dell'ADDON di origine (manifest id + logo ufficiale), distinta
          // dal servizio Debrid che sbloccherà il torrent.
          addonId = candidate.addonId?.trim()?.takeIf { it.isNotBlank() },
          addonLogo = candidate.addonLogo?.trim()?.takeIf { it.isNotBlank() },
          addonInstallId = candidate.baseUrl.trimEnd('/'),
          instantTag = instantTag,
          releaseTitle = releaseTitle,
          details = details,
          infoHash = normalizedHash,
          fileIdx = candidate.item.fileIdx,
          codec = codec,
          isCached = true,
          releaseType = releaseType,
          subtitles = subtitles
        )
      )
    }

    // Ordina: prima italiani, poi per qualità decrescente (4K > 1080p > 720p).
    return sources.sortedWith(
      compareByDescending<StreamSource> { it.isItalian }
        .thenByDescending { it.effectiveHeight }
    )
  }

  /** DIAG TEMPORANEO: solo hostname dell'addon, mai path/query/credenziali. */
  internal fun diagHostLabel(baseUrl: String): String =
    baseUrl.trimEnd('/').toHttpUrlOrNull()?.host ?: "unknown-host"

  /**
   * Raccoglie i candidati torrent (infoHash) dagli addon Stremio installati.
   * Condiviso dai provider dei servizi Debrid accostati a TorBox.
   */
  suspend fun fetchAddonCandidates(
    addonRepository: StremioAddonRepository,
    tmdbId: Int,
    isTv: Boolean,
    season: Int?,
    episode: Int?,
    imdbId: String?,
    serviceName: String
  ): List<StremioStreamCandidate> {
    val candidates = try {
      addonRepository.fetchStreams(tmdbId, isTv, season, episode, imdbId)
    } catch (e: Exception) {
      Log.w(TAG, "$serviceName: lettura candidati dagli addon fallita: ${e.message}")
      emptyList()
    }
    val torrentCandidates = candidates.filter { it.item.infoHash?.isNotBlank() == true }
    if (torrentCandidates.isEmpty()) {
      Log.i(DIAG_TAG, "DIAG-B $serviceName: nessun candidato torrent (infoHash) da alcun addon")
    } else {
      Log.i(TAG, "$serviceName: ${torrentCandidates.size} candidati torrent da risolvere")
      torrentCandidates.groupBy { it.baseUrl }.forEach { (baseUrl, items) ->
        Log.i(
          DIAG_TAG,
          "DIAG-B $serviceName addon=${diagHostLabel(baseUrl)} candidati_torrent=${items.size}"
        )
      }
    }
    return torrentCandidates
  }
}

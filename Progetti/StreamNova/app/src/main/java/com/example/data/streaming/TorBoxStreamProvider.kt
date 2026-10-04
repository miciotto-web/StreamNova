package com.example.data.streaming

import android.util.Log
import com.example.data.stremio.StremioAddonRepository
import com.example.data.stremio.StremioStreamCandidate
import com.example.data.torbox.TorBoxGateway
import com.example.data.torbox.TorBoxRepository

/**
 * Candidato torrent "grezzo" da risolvere con TorBox Instant Debrid.
 *
 * @param infoHash hash torrent (dagli addon Stremio o dagli scraper).
 * @param fileIdx indice file dichiarato dalla sorgente (Stremio `fileIdx`).
 * @param label etichetta/descrizione originale da cui ricavare la qualità.
 * @param sourceName nome della sorgente di origine (addon/scraper).
 */
data class TorrentCandidate(
  val infoHash: String,
  val fileIdx: Int? = null,
  val label: String = "",
  val sourceName: String = ""
)

/**
 * [StreamProvider] dedicato a **TorBox Instant Debrid**.
 *
 * Interviene quando TorBox è abilitato nelle Impostazioni (toggle ON + chiave
 * API) ed esiste una sorgente torrent/infoHash disponibile, proveniente da:
 *  - uno **scraper** (candidati iniettati via [extraCandidates]), oppure
 *  - dagli **addon Stremio** installati (risorsa `stream` con campo `infoHash`).
 *
 * Tutti gli infoHash raccolti vengono verificati con **un unico** `checkcached`
 * multi-hash (`?hash=h1,h2,...&format=object`): tutti gli stream confermati
 * `cached = true` vengono restituiti con i relativi metadati (nome file, dimensione,
 * codec, tipo rilascio) per la selezione nell'interfaccia utente.
 */
class TorBoxStreamProvider(
  private val torBox: TorBoxGateway = TorBoxRepository,
  private val addonRepository: StremioAddonRepository = StremioAddonRepository,
  /**
   * Callback opzionale per aggiungere candidati torrent provenienti dagli
   * scraper interni (es. provider che estraggono magnet dalle pagine).
   */
  private val extraCandidates: (suspend (tmdbId: Int, isTv: Boolean, season: Int?, episode: Int?, title: String?, year: Int?) -> List<TorrentCandidate>)? = null
) : StreamProvider {

  override suspend fun getStreams(
    tmdbId: Int,
    isTv: Boolean,
    season: Int?,
    episode: Int?,
    title: String?,
    year: Int?
  ): List<StreamSource> {
    if (!torBox.isConfigured()) {
      Log.d(TAG, "TorBox non configurato (toggle/chiave): nessuna risoluzione debrid")
      return emptyList()
    }

    val candidates = ArrayList<StremioStreamCandidate>()

    // 1) Candidati provenienti dagli addon Stremio installati (infoHash grezzi).
    try {
      val addonCandidates = addonRepository.fetchStreams(tmdbId, isTv, season, episode)
      candidates += addonCandidates.filter { it.item.infoHash?.isNotBlank() == true }
    } catch (e: Exception) {
      Log.w(TAG, "Lettura candidati dagli addon fallita: ${e.message}")
    }

    if (candidates.isEmpty()) return emptyList()
    Log.i(TAG, "${candidates.size} candidati torrent da risolvere con TorBox")
    return resolveItems(candidates)
  }

  /**
   * Risolve una lista di candidati torrent in [StreamSource] per la selezione.
   *
   * STRATEGIA (modello NuvioTV):
   *  1. tutti gli infoHash vengono raccolti e verificati con **un unico**
   *     `GET /torrents/checkcached?hash=h1,h2,...&format=object`;
   *  2. tutti gli stream confermati `cached = true` vengono aggiunti alla lista
   *     con i metadati (addon, qualita', nome file, dimensione, codec, tipo rilascio)
   *     ma CON `streamUrl = null` (nessuno sblocco preventivo!);
   *  3. lo sblocco avviene ON-DEMAND al click dell'utente in [selectSource].
   *
   * Gli hash non in cache non generano alcuna chiamata di sblocco.
   */
  suspend fun resolveItems(candidates: List<StremioStreamCandidate>): List<StreamSource> {
    if (candidates.isEmpty() || !torBox.isConfigured()) return emptyList()

    // Deduplica per coppia (infoHash, fileIdx) per preservare file differenti
    // all'interno dello stesso torrent (es. 1080p vs 720p dello stesso episodio).
    val distinct = candidates.distinctBy {
      "${it.item.infoHash?.trim()?.lowercase()}_${it.item.fileIdx ?: -1}"
    }
    val hashes = distinct.mapNotNull { it.item.infoHash?.trim()?.lowercase() }

    if (hashes.isEmpty()) return emptyList()

    // 2) UN solo checkcached per tutti gli hash della lista.
    val entries = try {
      torBox.checkCached(hashes)
    } catch (e: Exception) {
      Log.w(TAG, "checkcached multi-hash fallito: ${e.message}")
      emptyMap()
    }
    val cachedHashes = entries.filterValues { it.cached }.keys.toSet()
    Log.i(
      TAG,
      "TorBox: ${hashes.size} hash da addon, ${cachedHashes.size} trovati in cache istantanea"
    )
    if (cachedHashes.isEmpty()) return emptyList()

    // 3) Costruisce StreamSource CON streamUrl = NULL per OGNI hash in cache.
    //    Lo sblocco avverra' on-demand al click dell'utente.
    val sources = mutableListOf<StreamSource>()
    for (candidate in distinct) {
      val normalizedHash = candidate.item.infoHash?.trim()?.lowercase() ?: continue
      if (normalizedHash !in cachedHashes) continue

      val entry = entries[normalizedHash] ?: continue
      val quality = candidate.quality
      val addonName = candidate.addonNameFromStream
      val instantTag = candidate.instantTag
      val releaseTitle = candidate.releaseTitle
      val details = candidate.sizeAndPeers
      val codec = candidate.codec
      val releaseType = candidate.releaseType
      val isItalian = StreamSource.isItalianSource(addonName)

      Log.i(TAG, "TorBox: in cache ($quality) da $addonName - $releaseTitle")
      Log.i(
        "[StreamNova-TorBox-Debug]",
        "1. DATI STREAM DALL'ADDON -> stream name='${candidate.item.name}', title='${candidate.item.title}', infoHash=$normalizedHash, filename/displayName='$releaseTitle', quality/resolution='$quality', seeders/details='$details', addon='$addonName'"
      )
      sources.add(
        StreamSource(
          streamUrl = null,
          quality = quality,
          serverName = "$addonName 🧲 $instantTag",
          headers = emptyMap(),
          declaredQuality = quality,
          isProgressive = true,
          isItalian = isItalian,
          addonName = addonName,
          instantTag = instantTag,
          releaseTitle = releaseTitle,
          details = details,
          infoHash = normalizedHash,
          fileIdx = candidate.item.fileIdx,
          codec = codec,
          isCached = true,
          releaseType = releaseType
        )
      )
    }

    // Ordina: prima italiani, poi per qualita' decrescente (4K > 1080p > 720p)
    return sources.sortedWith(
      compareByDescending<StreamSource> { it.isItalian }
        .thenByDescending { it.effectiveHeight }
    )
  }

  companion object {
    private const val TAG = "TorBoxStreamProvider"

    /** Prefisso del badge sorgente mostrato anche nel player. */
    const val BADGE_PREFIX = "[TorBox Instant "

    /**
     * Seleziona e ordina i candidati **confermati in cache** dal `checkcached`
     * multi-hash, dal flusso a risoluzione più alta alla più bassa
     * (**4K > 1080p > 720p**). Funzione pura: verificabile dai test.
     *
     * @param candidates tutti gli stream raccolti dagli addon/scraper.
     * @param cachedHashes hash confermati `cached = true` dal batch.
     * @return candidati in cache ordinati per qualità decrescente (migliore in testa).
     */
    internal fun selectBestCached(
      candidates: List<TorrentCandidate>,
      cachedHashes: Set<String>
    ): List<TorrentCandidate> = candidates
      .filter { cachedHashes.contains(it.infoHash.trim().lowercase()) }
      .sortedByDescending { StreamManager.qualityScore(parseQualityLabel(it.label)) }

    /**
     * Ricava la qualità da un'etichetta libera (nome/titolo dello stream):
     * "4K"/"2160p" → 4K, "1080p" → 1080p, "720p" → 720p, altrimenti "Auto".
     */
    fun parseQualityLabel(label: String): String {
      val l = label.lowercase()
      return when {
        l.contains("4k") || l.contains("2160") || l.contains("uhd") -> "4K"
        l.contains("1080") || l.contains("fhd") -> "1080p"
        l.contains("720") || l.contains("hd") && !l.contains("sd") -> "720p"
        l.contains("480") || l.contains("360") || l.contains("sd") -> "480p"
        else -> "Auto"
      }
    }
  }
}

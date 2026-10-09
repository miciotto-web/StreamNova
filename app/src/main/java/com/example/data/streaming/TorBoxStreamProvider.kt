package com.example.data.streaming

import android.util.Log
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import com.example.data.stremio.StremioAddonRepository
import com.example.data.stremio.StremioStreamCandidate
import com.example.data.stremio.StremioSubtitleAdapter
import com.example.data.stremio.StremioSubtitleBridge
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
open class TorBoxStreamProvider(
  private val torBox: TorBoxGateway = TorBoxRepository,
  private val addonRepository: StremioAddonRepository = StremioAddonRepository,
  /**
   * Callback opzionale per aggiungere candidati torrent provenienti dagli
   * scraper interni (es. provider che estraggono magnet dalle pagine).
   */
  private val extraCandidates: (suspend (tmdbId: Int, isTv: Boolean, season: Int?, episode: Int?, title: String?, year: Int?) -> List<TorrentCandidate>)? = null
) : StreamProvider {

  open override suspend fun getStreams(
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

    if (candidates.isEmpty()) {
      Log.i(DIAG_TAG, "DIAG-B nessun candidato torrent (infoHash) da alcun addon")
      return emptyList()
    }
    Log.i(TAG, "${candidates.size} candidati torrent da risolvere con TorBox")
    // DIAG TEMPORANEO (B): candidati torrent per addon INTERROGATO (hostname, mai URL completo).
    candidates.groupBy { it.baseUrl }.forEach { (baseUrl, items) ->
      Log.i(DIAG_TAG, "DIAG-B addon=${diagHostLabel(baseUrl)} candidati_torrent=${items.size}")
    }
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

    // DIAG TEMPORANEO (B): prima/dopo la deduplicazione per addon INTERROGATO.
    candidates.groupBy { it.baseUrl }.forEach { (baseUrl, items) ->
      val kept = items.distinctBy {
        "${it.item.infoHash?.trim()?.lowercase()}_${it.item.fileIdx ?: -1}"
      }
      Log.i(
        DIAG_TAG,
        "DIAG-B addon=${diagHostLabel(baseUrl)} before_dedup=${items.size} " +
          "after_dedup=${kept.size} dropped_dup=${items.size - kept.size}"
      )
    }

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
    // DIAG TEMPORANEO (B): hash effettivamente inviati e trovati in cache, per addon INTERROGATO.
    candidates.groupBy { it.baseUrl }.forEach { (baseUrl, items) ->
      val addonHashes = items.mapNotNull { it.item.infoHash?.trim()?.lowercase() }.distinct()
      val addonCached = addonHashes.count { cachedHashes.contains(it) }
      Log.i(
        DIAG_TAG,
        "DIAG-B addon=${diagHostLabel(baseUrl)} hashes_sent=${addonHashes.size} cached=$addonCached"
      )
    }
    Log.i(
      DIAG_TAG,
      "DIAG-B totals candidates=${candidates.size} after_dedup=${distinct.size} " +
        "dropped_dup=${candidates.size - distinct.size} hashes_sent=${hashes.size} cached=${cachedHashes.size}"
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
      // Identità STABILE del provider = titolo del manifest dell'addon Stremio
      // (es. "Torrentio"). NON la prima riga di `stream.name`, che in alcune
      // configurazioni include già qualità/HDR/DV (es. "Torrentio 4k DV | HDR")
      // e faceva comparire un chip distinto per ogni variante.
      val providerName = candidate.addonName
      // Riga originale dello stream + metadati release conservati separatamente
      // per la visualizzazione (non più usati come identità del provider).
      val addonNameFromStream = candidate.addonNameFromStream
      val instantTag = candidate.instantTag
      val releaseTitle = candidate.releaseTitle
      val details = candidate.sizeAndPeers
      val codec = candidate.codec
      val releaseType = candidate.releaseType
      // Rilevazione "italiano" invariata (resta sulla riga dello stream): così
      // ordinamento e selezione non cambiano.
      val isItalian = StreamSource.isItalianSource(addonNameFromStream)
      // Sottotitoli dichiarati dallo stream (`stream.subtitles[]`): gia' allineati alla
      // release riprodotta, quindi hanno precedenza. Il bridge non esegue alcuna
      // richiesta di rete, quindi qui non viene introdotto alcun lavoro di rete.
      val subtitles = StremioSubtitleAdapter.toSubtitles(
        StremioSubtitleBridge.fromStream(candidate.item),
        addonName = providerName
      )

      Log.i(TAG, "TorBox: in cache ($quality) da $providerName - $releaseTitle")
      Log.i(
        "[StreamNova-TorBox-Debug]",
        "1. DATI STREAM DALL'ADDON -> stream name='${candidate.item.name}', title='${candidate.item.title}', infoHash=$normalizedHash, filename/displayName='$releaseTitle', quality/resolution='$quality', seeders/details='$details', addon='$providerName', stream-addon-line='$addonNameFromStream'"
      )
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

    // Ordina: prima italiani, poi per qualita' decrescente (4K > 1080p > 720p)
    return sources.sortedWith(
      compareByDescending<StreamSource> { it.isItalian }
        .thenByDescending { it.effectiveHeight }
    )
  }

  companion object {
    private const val TAG = "TorBoxStreamProvider"
    // DIAG TEMPORANEO (solo logging).
    private const val DIAG_TAG = "COMET_DIAG"

    /** DIAG TEMPORANEO: solo hostname dell'addon, mai path/query/credenziali. */
    private fun diagHostLabel(baseUrl: String): String =
      baseUrl.trimEnd('/').toHttpUrlOrNull()?.host ?: "unknown-host"

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

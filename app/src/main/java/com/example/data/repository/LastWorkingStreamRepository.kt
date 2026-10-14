package com.example.data.repository

import android.util.Log
import com.example.data.local.LastWorkingStreamEntity
import com.example.data.local.StreamNovaDatabase
import com.example.data.prefs.StreamingEngineMode
import com.example.data.streaming.StreamSource
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types

/**
 * Ultimo flusso funzionante salvato in cache (funzione "Riusa l'ultimo link").
 *
 * I metadati sono quelli del [StreamSource] che è arrivato allo stato READY nel player
 * senza errori HTTP fatali (401/403/404).
 */
data class CachedWorkingStream(
  val mediaId: String,
  val seasonNumber: Int,
  val episodeNumber: Int,
  val streamUrl: String,
  val streamTitle: String? = null,
  val streamQuality: String? = null,
  val serverName: String? = null,
  val headers: Map<String, String> = emptyMap(),
  val isProgressive: Boolean? = null,
  /**
   * Modalità del motore che ha generato il flusso ([StreamingEngineMode.name]).
   * Null per le voci salvate prima dell'introduzione del tracciamento: in quel caso
   * la modalità viene inferita dalla natura della fonte (vedi
   * [LastWorkingStreamRepository.resolveEngineMode]).
   */
  val streamMode: String? = null,
  val cachedAtTimestamp: Long = System.currentTimeMillis()
) {
  /** Ricostruisce la sorgente riproducibile del flusso salvato. */
  fun toStreamSource(): StreamSource {
    val quality = streamQuality?.takeIf { it.isNotBlank() } ?: "Auto"
    return StreamSource(
      streamUrl = streamUrl,
      quality = quality,
      serverName = serverName?.takeIf { it.isNotBlank() } ?: "Ultimo flusso",
      headers = headers,
      declaredQuality = quality,
      isProgressive = isProgressive,
      releaseTitle = streamTitle?.takeIf { it.isNotBlank() },
      // Link già pronto alla riproduzione.
      isCached = true
    )
  }
}

/**
 * Persistenza Room dell'ultimo flusso funzionante per (media, stagione, episodio).
 *
 * La logica di validità (`isCacheValid`) e la ricostruzione della sorgente
 * (`CachedWorkingStream.toStreamSource`) sono **pure** e verificabili senza Android/Room.
 * Quando il database non è inizializzato (es. test JVM) le operazioni di persistenza
 * degradano silenziosamente senza lanciare eccezioni.
 */
object LastWorkingStreamRepository {

  /** Valore sentinella per film o campi episodio non applicabili. */
  const val NO_EPISODE = -1

  private const val TAG = "LastStreamCache"
  private const val MILLIS_PER_HOUR = 3_600_000L

  /**
   * Marker testuali (case-insensitive) riconducibili a una fonte Debrid/torrent.
   * Usati per inferire la modalità di una voce di cache legacy che non traccia ancora
   * esplicitamente `streamMode`.
   */
  private val DEBRID_TEXT_MARKERS = listOf(
    "torbox",
    "real-debrid",
    "realdebrid",
    "real debrid",
    "alldebrid",
    "premiumize",
    "debrid",
    "magnet:",
    "torrent:"
  )

  @Volatile
  private var database: StreamNovaDatabase? = null

  private val headersAdapter by lazy {
    Moshi.Builder()
      .build()
      .adapter<Map<String, String>>(
        Types.newParameterizedType(Map::class.java, String::class.java, String::class.java)
      )
  }

  /** Inizializza il repository con il database Room condiviso dell'app. */
  fun init(db: StreamNovaDatabase?) {
    database = db
  }

  /** Iniezione del database per i test (o per azzerarne la dipendenza). */
  fun setInstanceForTesting(db: StreamNovaDatabase?) {
    database = db
  }

  /** Finestra di validità in millisecondi per una durata espressa in ore. */
  fun cacheDurationMs(durationHours: Int): Long =
    durationHours.coerceAtLeast(0).toLong() * MILLIS_PER_HOUR

  /**
   * Un flusso in cache è valido se non è futuro e non ha superato la finestra:
   * `(now - cachedAtTimestamp) <= durationHours * 3600 * 1000`.
   */
  fun isCacheValid(
    cachedAtTimestamp: Long,
    durationHours: Int,
    now: Long = System.currentTimeMillis()
  ): Boolean {
    if (durationHours <= 0) return false
    val age = now - cachedAtTimestamp
    return age in 0..cacheDurationMs(durationHours)
  }

  /**
   * Restituisce il flusso salvato e ancora valido per il titolo, oppure null se
   * assente, scaduto o con URL non riproducibile.
   */
  suspend fun getValidStream(
    mediaId: String,
    seasonNumber: Int,
    episodeNumber: Int,
    durationHours: Int,
    now: Long = System.currentTimeMillis()
  ): CachedWorkingStream? {
    val entity = try {
      database?.lastWorkingStreamDao()?.getStream(mediaId, seasonNumber, episodeNumber)
    } catch (e: Exception) {
      Log.w(TAG, "Lettura cache ultimo flusso fallita: ${e.message}")
      null
    } ?: return null
    if (entity.streamUrl.isBlank()) return null
    if (!isCacheValid(entity.cachedAtTimestamp, durationHours, now)) {
      Log.i(TAG, "Cache ultimo flusso scaduta per $mediaId S$seasonNumber E$episodeNumber")
      return null
    }
    return entity.toCachedWorkingStream()
  }

  /** Salva (o sovrascrive) l'ultimo flusso funzionante per il titolo. */
  suspend fun saveStream(
    mediaId: String,
    seasonNumber: Int,
    episodeNumber: Int,
    streamUrl: String,
    streamTitle: String?,
    streamQuality: String?,
    serverName: String?,
    headers: Map<String, String>,
    isProgressive: Boolean?,
    streamMode: String? = null,
    cachedAtTimestamp: Long = System.currentTimeMillis()
  ) {
    if (mediaId.isBlank() || streamUrl.isBlank()) return
    try {
      database?.lastWorkingStreamDao()?.saveStream(
        LastWorkingStreamEntity(
          mediaId = mediaId,
          seasonNumber = seasonNumber,
          episodeNumber = episodeNumber,
          streamUrl = streamUrl,
          streamTitle = streamTitle?.takeIf { it.isNotBlank() },
          streamQuality = streamQuality?.takeIf { it.isNotBlank() },
          serverName = serverName?.takeIf { it.isNotBlank() },
          headersJson = encodeHeaders(headers),
          isProgressive = isProgressive,
          streamMode = streamMode?.takeIf { it.isNotBlank() },
          cachedAtTimestamp = cachedAtTimestamp
        )
      )
      Log.i(TAG, "Salvato ultimo flusso funzionante per $mediaId S$seasonNumber E$episodeNumber")
    } catch (e: Exception) {
      Log.w(TAG, "Salvataggio ultimo flusso fallito: ${e.message}")
    }
  }

  /** Invalida (rimuove) la voce in cache per il titolo, es. dopo un fallimento del link. */
  suspend fun invalidate(mediaId: String, seasonNumber: Int, episodeNumber: Int) {
    try {
      database?.lastWorkingStreamDao()?.deleteStream(mediaId, seasonNumber, episodeNumber)
      Log.i(TAG, "Cache ultimo flusso invalidata per $mediaId S$seasonNumber E$episodeNumber")
    } catch (e: Exception) {
      Log.w(TAG, "Invalidazione cache ultimo flusso fallita: ${e.message}")
    }
  }

  /** Svuota l'intera cache degli ultimi flussi. */
  suspend fun clearAll() {
    try {
      database?.lastWorkingStreamDao()?.clearAll()
    } catch (e: Exception) {
      Log.w(TAG, "Svuotamento cache ultimi flussi fallito: ${e.message}")
    }
  }

  internal fun encodeHeaders(headers: Map<String, String>): String? {
    if (headers.isEmpty()) return null
    return try {
      headersAdapter.toJson(headers)
    } catch (e: Exception) {
      null
    }
  }

  internal fun decodeHeaders(json: String?): Map<String, String> {
    if (json.isNullOrBlank()) return emptyMap()
    return try {
      headersAdapter.fromJson(json) ?: emptyMap()
    } catch (e: Exception) {
      emptyMap()
    }
  }

  /** true se almeno uno dei testi contiene un marker Debrid/torrent. */
  internal fun looksLikeDebridSource(vararg text: String?): Boolean {
    val haystack = text.filterNotNull().joinToString(" ").lowercase()
    if (haystack.isBlank()) return false
    return DEBRID_TEXT_MARKERS.any { haystack.contains(it) }
  }

  /** Interpreta il nome di una [StreamingEngineMode], ignorando maiuscole e spazi. */
  internal fun parseEngineMode(name: String?): StreamingEngineMode? =
    name?.trim()?.takeIf { it.isNotBlank() }?.let { value ->
      StreamingEngineMode.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }

  /**
   * Determina la modalità del motore con cui è stata generata una sorgente **live**,
   * da registrare insieme al flusso funzionante.
   *
   * Precedenza:
   *  1. Natura intrinseca della sorgente: `infoHash`/magnet/torrent o marker Debrid nel
   *     server/addon → Debrid.
   *  2. In assenza di indizi → [fallbackEngineMode] (modalità attiva al momento della
   *     risoluzione): una fonte HTTP in HTTP resta HTTP, una fonte Debrid in Debrid
   *     resta Debrid.
   */
  fun classifySourceMode(
    source: StreamSource?,
    fallbackEngineMode: StreamingEngineMode
  ): StreamingEngineMode {
    if (source == null) return fallbackEngineMode
    if (!source.infoHash.isNullOrBlank()) return StreamingEngineMode.DEBRID_TORBOX
    if (looksLikeDebridSource(source.streamUrl, source.serverName, source.addonName, source.instantTag)) {
      return StreamingEngineMode.DEBRID_TORBOX
    }
    return fallbackEngineMode
  }

  /**
   * Modalità di una voce di cache già risolta.
   *
   * Se [CachedWorkingStream.streamMode] è valorizzato (voci nuove) viene rispettato alla
   * lettera. Per le voci legacy (prima del tracciamento) la modalità viene inferita dalla
   * natura della fonte; in assenza di indizi si assume HTTP, così una fonte
   * potenzialmente HTTP non viene mai riusata in modalità Debrid.
   */
  fun resolveEngineMode(cached: CachedWorkingStream): StreamingEngineMode {
    parseEngineMode(cached.streamMode)?.let { return it }
    return if (looksLikeDebridSource(cached.streamUrl, cached.serverName, cached.streamTitle)) {
      StreamingEngineMode.DEBRID_TORBOX
    } else {
      StreamingEngineMode.HTTP_WEB
    }
  }

  private fun LastWorkingStreamEntity.toCachedWorkingStream(): CachedWorkingStream =
    CachedWorkingStream(
      mediaId = mediaId,
      seasonNumber = seasonNumber,
      episodeNumber = episodeNumber,
      streamUrl = streamUrl,
      streamTitle = streamTitle,
      streamQuality = streamQuality,
      serverName = serverName,
      headers = decodeHeaders(headersJson),
      isProgressive = isProgressive,
      streamMode = streamMode,
      cachedAtTimestamp = cachedAtTimestamp
    )
}

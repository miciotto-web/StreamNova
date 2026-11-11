package com.example.data.sync

import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi

/**
 * Versione dello schema del payload di sincronizzazione.
 *
 * Il ricevente rifiuta un payload con versione SUPERIORE alla sua (build più vecchia
 * che non conosce i campi nuovi): un payload con versione inferiore o uguale viene
 * accettato e i campi mancanti valgono i default. Incrementare a ogni cambio
 * incompatibile dei modelli sottostanti.
 */
const val SYNC_SCHEMA_VERSION = 1

/** Porta HTTP del server Sync (distinta dalla 8080 del sync addon in browser). */
const val SYNC_SERVER_PORT = 8090

/** Tipo di servizio NSD/mDNS annunciato e ricercato dai dispositivi StreamNova. */
const val SYNC_NSD_SERVICE_TYPE = "_streamnova._tcp."

/** Tempo (ms) entro cui un trasferimento approvato deve partire dalla richiesta. */
const val SYNC_REQUEST_TTL_MS = 2 * 60 * 1000L

/**
 * Categorie di contenuto che un trasferimento può portare. Sono le stesse voci
 * mostrate come caselle nella UI di sincronizzazione e viaggiano nel corpo della
 * richiesta (`POST /api/sync/request`) e nel query string del download.
 */
enum class SyncCategory {
  /** Impostazioni generali + riproduzione/avanzate (incluse le chiavi API). */
  SETTINGS,

  /** Lista addon Stremio installati (URL + stato abilitato). */
  ADDONS,

  /** Credenziali dell'account SIMKL collegato (se presente sulla sorgente). */
  SIMKL,

  /** Preferiti, "Continua a guardare" e progressi degli episodi. */
  LIBRARY;

  companion object {
    /** Lettura tollerante dei nomi arrivati in rete: un valore ignoto viene scartato. */
    fun parseList(raw: List<*>?): Set<SyncCategory> =
      raw.orEmpty().mapNotNull { it as? String }
        .mapNotNull { name -> entries.firstOrNull { it.name == name } }
        .toSet()
  }
}

/** Direzione del trasferimento rispetto al dispositivo che lo AVVIA. */
enum class SyncDirection {
  /** Questo dispositivo PESCA la configurazione dal peer. */
  PULL,

  /** Questo dispositivo SPINGE la propria configurazione sul peer. */
  PUSH;

  companion object {
    fun fromName(raw: String?): SyncDirection? =
      entries.firstOrNull { it.name == raw }
  }
}

// ── Modelli del payload ────────────────────────────────────────────────────────
// Ogni campo è nullable: l'assenza significa "non incluso nel trasferimento" e il
// ricevente mantiene il proprio valore. Il builder della sorgente popola sempre
// tutti i campi delle categorie richieste, quindi l'assenza si vede solo con
// payload più vecchi (schema precedente) o corrotti.

@JsonClass(generateAdapter = true)
data class SyncConfigPayload(
  val schemaVersion: Int = SYNC_SCHEMA_VERSION,
  /** Nome del dispositivo sorgente (solo informativo, mostrato in UI). */
  val deviceName: String = "",
  val createdAtEpochMs: Long = 0L,
  val appSettings: SyncAppSettingsPayload? = null,
  val playbackSettings: SyncPlaybackSettingsPayload? = null,
  val addons: List<SyncAddonPayload> = emptyList(),
  val simklAccount: SyncSimklAccountPayload? = null,
  val mediaItems: List<SyncMediaItemPayload> = emptyList(),
  val episodeProgress: List<SyncEpisodeProgressPayload> = emptyList()
)

/** Impostazioni generali (DataStore `streamnova_app_settings`). */
@JsonClass(generateAdapter = true)
data class SyncAppSettingsPayload(
  val torBoxApiKey: String?,
  val openSubtitlesApiKey: String?,
  val simklClientId: String?,
  val torBoxInstantDebridEnabled: Boolean,
  val streamingEngineMode: String,
  val autoplayEnabled: Boolean,
  val appLanguage: String,
  val parentalControlEnabled: Boolean,
  /** Hash SHA-256 del PIN: il PIN in chiaro non esce mai dal dispositivo sorgente. */
  val parentalControlPinHash: String,
  val parentalControlLevel: String,
  val tmdbGenreFallbackEnabled: Boolean,
  val hideUnreleasedContent: Boolean,
  /** Associazioni catalogo → provider dichiarate dall'utente (JSON). */
  val userProviderBindingsJson: String,
  val cwShowRow: Boolean,
  val cwLayout: String,
  val cwEpisodeThumbnails: Boolean,
  val cwBlurUnwatched: Boolean,
  val cwNextFromFurthest: Boolean,
  val cwShowUnaired: Boolean,
  val cwSort: String
)

/** Impostazioni di riproduzione e avanzate (DataStore `streamnova_settings`). */
@JsonClass(generateAdapter = true)
data class SyncPlaybackSettingsPayload(
  val preferredResolution: String,
  val autoPlayNextEpisodeEnabled: Boolean,
  val autoResume: Boolean,
  val reuseLastWorkingStreamEnabled: Boolean,
  val lastWorkingStreamCacheDurationHours: Int,
  val prioritizeItalianAudio: Boolean,
  val preferredAudioLanguage: String,
  val subtitlesEnabled: Boolean,
  val forcedSubtitlesEnabled: Boolean,
  val preferredSubtitleLanguage: String,
  val subtitleSize: String,
  val subtitleBackground: String,
  val subtitleOpacity: String,
  val subtitlePosition: String,
  val loadingOverlayEnabled: Boolean,
  val loadingStatusEnabled: Boolean,
  val pauseOverlayEnabled: Boolean,
  val parentalGuideEnabled: Boolean,
  val targetBuffer: String,
  val initialBuffer: String,
  val backBuffer: String,
  val audioPassthroughEnabled: Boolean,
  val audioTunnelingEnabled: Boolean,
  val decoderFallbackMode: String,
  val autoFrameRateMatching: Boolean,
  val dolbyVisionFallbackEnabled: Boolean,
  val debugOverlayEnabled: Boolean
)

/** Un addon Stremio installato: basta URL + stato, il manifest viene riscaricato. */
@JsonClass(generateAdapter = true)
data class SyncAddonPayload(
  val url: String,
  val name: String,
  val enabled: Boolean
)

/** Credenziali SIMKL: replicare l'account evita il login QR sul nuovo dispositivo. */
@JsonClass(generateAdapter = true)
data class SyncSimklAccountPayload(
  val accessToken: String?,
  val refreshToken: String?,
  val accountId: Long?,
  val username: String?,
  val accountType: String?,
  val tokenExpiresAt: String?,
  val tokenExpiresAtEpochMs: Long?,
  val scope: String?,
  /** Nome dell'[com.example.data.remote.simkl.SimklAuthFlow]. */
  val flow: String?,
  val sessionExpired: Boolean
)

/** Riga di `cached_media_items` (preferiti + progresso + metadati del catalogo). */
@JsonClass(generateAdapter = true)
data class SyncMediaItemPayload(
  val id: String,
  val tmdbId: Int?,
  val title: String,
  val originalTitle: String,
  val synopsis: String,
  val videoUrl: String,
  val resolution: String,
  val qualityTags: String,
  val backdropUrl: String?,
  val posterUrl: String?,
  val logoUrl: String?,
  val type: String,
  val year: Int,
  val durationMinutes: Int,
  val seasonsCount: Int?,
  val rating: Float,
  val genres: String,
  val director: String?,
  val cast: String,
  val provider: String?,
  val isTrending: Boolean,
  val isTop10: Boolean,
  val isFavorite: Boolean,
  val currentProgressMs: Long,
  val totalDurationMs: Long,
  val lastWatchedSeason: Int?,
  val lastWatchedEpisode: Int?,
  val lastWatchedAt: Long?,
  val inContinueWatching: Boolean,
  val completedAt: Long?,
  val ageRating: Int?,
  val favoriteSource: String?,
  val progressSource: String?,
  val importedFromSimkl: Boolean,
  val cachedAt: Long
)

/** Riga di `episode_progress`: posizione per singolo episodio. */
@JsonClass(generateAdapter = true)
data class SyncEpisodeProgressPayload(
  val mediaId: String,
  val seasonNumber: Int,
  val episodeNumber: Int,
  val progressMs: Long,
  val durationMs: Long,
  val updatedAt: Long,
  val source: String?
)

// ── Serializzazione ────────────────────────────────────────────────────────────

/** Adapter condiviso per (de)serializzare il payload sul filo HTTP. */
val SyncConfigPayloadAdapter: com.squareup.moshi.JsonAdapter<SyncConfigPayload> =
  Moshi.Builder().build().adapter(SyncConfigPayload::class.java)

/**
 * Parsing tollerante del nome di un enum arrivato in rete: un valore ignoto (build
 * più recente sulla sorgente) vale il fallback del ricevente invece di un crash.
 */
fun <T : Enum<T>> syncEnumFromName(values: Array<T>, raw: String?, fallback: T): T =
  values.firstOrNull { it.name == raw } ?: fallback

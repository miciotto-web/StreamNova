package com.example.data.sync

import android.content.Context
import android.util.Log
import com.example.data.local.StreamNovaDatabase
import com.example.data.prefs.AppSettingsRepository
import com.example.data.prefs.BackBufferOption
import com.example.data.prefs.DecoderFallbackMode
import com.example.data.prefs.InitialBufferOption
import com.example.data.prefs.PreferredResolution
import com.example.data.prefs.SettingsRepository
import com.example.data.prefs.StreamingEngineMode
import com.example.data.prefs.SubtitleBackground
import com.example.data.prefs.SubtitleOpacity
import com.example.data.prefs.SubtitlePosition
import com.example.data.prefs.SubtitleSize
import com.example.data.prefs.TargetBufferOption
import com.example.data.repository.SimklRepository
import com.example.data.remote.simkl.SimklAccountState
import com.example.data.remote.simkl.SimklAuthFlow
import com.example.data.stremio.StremioAddonRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "SyncConfigApplier"

/** Esito dell'applicazione di un payload: contatori per il riepilogo in UI. */
data class SyncApplyResult(
  val settingsApplied: Boolean,
  val addonsInstalled: Int,
  val addonsRemoved: Int,
  val addonErrors: Int,
  val simklImported: Boolean,
  val mediaAdded: Int,
  val mediaUpdated: Int,
  val episodesAdded: Int,
  val episodesUpdated: Int
) {
  fun describe(): String = buildString {
    if (settingsApplied) append("Impostazioni aggiornate. ")
    if (addonsInstalled > 0) append("$addonsInstalled addon installati. ")
    if (addonsRemoved > 0) append("$addonsRemoved addon rimossi. ")
    if (addonErrors > 0) append("$addonErrors addon non installati. ")
    if (simklImported) append("Account SIMKL replicato. ")
    if (mediaAdded > 0) append("$mediaAdded titoli aggiunti. ")
    if (mediaUpdated > 0) append("$mediaUpdated titoli aggiornati. ")
    if (episodesAdded > 0) append("$episodesAdded episodi sincronizzati. ")
    if (isEmpty()) append("Nessuna modifica applicata.")
  }
}

/**
 * Applica un [SyncConfigPayload] ricevuto sulla configurazione LOCALE.
 *
 * È l'unica controparte in scrittura di [SyncConfigBuilder]: le categorie presenti
 * nel payload guidano cosa viene toccato (una sezione assente non produce azioni).
 *
 * Note deliberate:
 *  - l'account SIMKL viene importato SOLO se presente nel payload: una sorgente non
 *    collegata non disconnette il ricevente;
 *  - la libreria è fusa senza cancellazioni (vedi [mergeLibrary]): le righe esistenti
 *    solo sul ricevente restano;
 *  - le scritture preferenze riusano i setter esistenti, quindi i flag "scrittura
 *    locale" dei repository evitano che i read-back di DataStore riportino indietro
 *    i valori appena applicati.
 */
object SyncConfigApplier {

  suspend fun apply(
    context: Context,
    payload: SyncConfigPayload,
    categories: Set<SyncCategory>
  ): SyncApplyResult = withContext(Dispatchers.IO) {
    if (payload.schemaVersion > SYNC_SCHEMA_VERSION) {
      throw IllegalArgumentException(
        "Payload di sincronizzazione più recente di questa build (schema ${payload.schemaVersion})"
      )
    }

    var settingsApplied = false
    var addonsInstalled = 0
    var addonsRemoved = 0
    var addonErrors = 0
    var simklImported = false
    var mediaAdded = 0
    var mediaUpdated = 0
    var episodesAdded = 0
    var episodesUpdated = 0

    AppSettingsRepository.awaitLoaded()

    // ── IMPOSTAZIONI ────────────────────────────────────────────────────────
    payload.appSettings?.takeIf { SyncCategory.SETTINGS in categories }?.let { a ->
      applyAppSettings(a)
      settingsApplied = true
    }
    payload.playbackSettings?.takeIf { SyncCategory.SETTINGS in categories }?.let { p ->
      applyPlaybackSettings(p)
      settingsApplied = true
    }

    // ── ADDON ───────────────────────────────────────────────────────────────
    if (SyncCategory.ADDONS in categories) {
      val diff = reconcileAddons(payload.addons)
      addonsInstalled = diff.first
      addonsRemoved = diff.second
      addonErrors = diff.third
    }

    // ── ACCOUNT SIMKL ───────────────────────────────────────────────────────
    if (SyncCategory.SIMKL in categories) {
      payload.simklAccount?.let { account ->
        SimklRepository.importAccount(
          SimklAccountState(
            accessToken = account.accessToken,
            refreshToken = account.refreshToken,
            accountId = account.accountId,
            username = account.username,
            accountType = account.accountType,
            tokenExpiresAt = account.tokenExpiresAt,
            tokenExpiresAtEpochMs = account.tokenExpiresAtEpochMs,
            scope = account.scope,
            flow = account.flow?.let { name ->
              SimklAuthFlow.entries.firstOrNull { it.name == name }
            },
            sessionExpired = account.sessionExpired
          )
        )
        simklImported = true
      }
    }

    // ── LIBRERIA ────────────────────────────────────────────────────────────
    if (SyncCategory.LIBRARY in categories &&
      (payload.mediaItems.isNotEmpty() || payload.episodeProgress.isNotEmpty())
    ) {
      val db = StreamNovaDatabase.getInstance(context)
      if (db != null) {
        val mediaDao = db.cachedMediaDao()
        val localMedia = mediaDao.getAllCachedMedia().map { it.toPayload() }
        val localEpisodes = db.episodeProgressDao().getAllProgress().map { it.toPayload() }
        val merged = mergeLibrary(
          localMedia = localMedia,
          incomingMedia = payload.mediaItems,
          localEpisodes = localEpisodes,
          incomingEpisodes = payload.episodeProgress
        )
        mediaDao.insertOrUpdate(merged.mediaRows.map { it.toEntity() })
        if (merged.episodeRows.isNotEmpty()) {
          db.episodeProgressDao().saveEpisodeProgress(merged.episodeRows.map { it.toEntity() })
        }
        mediaAdded = merged.mediaAdded
        mediaUpdated = merged.mediaUpdated
        episodesAdded = merged.episodesAdded
        episodesUpdated = merged.episodesUpdated
        // La UI osserva il flusso Room del DAO: le righe scritte compaiono da sole,
        // nessun reload esplicito del catalogo è necessario.
      } else {
        Log.w(TAG, "Database non disponibile: la libreria non è stata aggiornata")
      }
    }

    SyncApplyResult(
      settingsApplied = settingsApplied,
      addonsInstalled = addonsInstalled,
      addonsRemoved = addonsRemoved,
      addonErrors = addonErrors,
      simklImported = simklImported,
      mediaAdded = mediaAdded,
      mediaUpdated = mediaUpdated,
      episodesAdded = episodesAdded,
      episodesUpdated = episodesUpdated
    )
  }

  private fun applyAppSettings(a: SyncAppSettingsPayload) {
    AppSettingsRepository.setTorBoxApiKey(a.torBoxApiKey)
    AppSettingsRepository.setOpenSubtitlesApiKey(a.openSubtitlesApiKey)
    AppSettingsRepository.setSimklClientId(a.simklClientId)
    AppSettingsRepository.setTorBoxInstantDebridEnabled(a.torBoxInstantDebridEnabled)
    AppSettingsRepository.setStreamingEngineMode(
      syncEnumFromName(StreamingEngineMode.entries.toTypedArray(), a.streamingEngineMode, StreamingEngineMode.HTTP_WEB)
    )
    AppSettingsRepository.setAutoplayEnabled(a.autoplayEnabled)
    AppSettingsRepository.setAppLanguage(a.appLanguage)
    AppSettingsRepository.setParentalControlEnabled(a.parentalControlEnabled)
    if (a.parentalControlPinHash.isNotBlank()) {
      AppSettingsRepository.setParentalControlPinHash(a.parentalControlPinHash)
    }
    AppSettingsRepository.setParentalControlLevel(a.parentalControlLevel)
    AppSettingsRepository.setTmdbGenreFallbackEnabled(a.tmdbGenreFallbackEnabled)
    AppSettingsRepository.setHideUnreleasedContent(a.hideUnreleasedContent)
    AppSettingsRepository.setUserProviderBindingsJson(a.userProviderBindingsJson)
    AppSettingsRepository.setContinueWatchingShowRow(a.cwShowRow)
    AppSettingsRepository.setContinueWatchingLayout(
      com.example.data.prefs.ContinueWatchingLayout.entries
        .firstOrNull { it.name == a.cwLayout } ?: com.example.data.prefs.ContinueWatchingLayout.CARD
    )
    AppSettingsRepository.setContinueWatchingEpisodeThumbnails(a.cwEpisodeThumbnails)
    AppSettingsRepository.setContinueWatchingBlurUnwatched(a.cwBlurUnwatched)
    AppSettingsRepository.setContinueWatchingNextEpisodeFromFurthest(a.cwNextFromFurthest)
    AppSettingsRepository.setContinueWatchingShowUnairedEpisodes(a.cwShowUnaired)
    AppSettingsRepository.setContinueWatchingSort(
      com.example.data.prefs.ContinueWatchingSort.entries
        .firstOrNull { it.name == a.cwSort } ?: com.example.data.prefs.ContinueWatchingSort.DEFAULT
    )
  }

  private fun applyPlaybackSettings(p: SyncPlaybackSettingsPayload) {
    SettingsRepository.setPreferredResolution(
      syncEnumFromName(PreferredResolution.entries.toTypedArray(), p.preferredResolution, PreferredResolution.AUTO)
    )
    SettingsRepository.setAutoPlayNextEpisode(p.autoPlayNextEpisodeEnabled)
    SettingsRepository.setAutoResume(p.autoResume)
    SettingsRepository.setReuseLastWorkingStreamEnabled(p.reuseLastWorkingStreamEnabled)
    SettingsRepository.setLastWorkingStreamCacheDurationHours(p.lastWorkingStreamCacheDurationHours)
    SettingsRepository.setPrioritizeItalianAudio(p.prioritizeItalianAudio)
    SettingsRepository.setPreferredAudioLanguage(p.preferredAudioLanguage)
    SettingsRepository.setSubtitlesEnabled(p.subtitlesEnabled)
    SettingsRepository.setForcedSubtitlesEnabled(p.forcedSubtitlesEnabled)
    SettingsRepository.setPreferredSubtitleLanguage(p.preferredSubtitleLanguage)
    SettingsRepository.setSubtitleSize(
      syncEnumFromName(SubtitleSize.entries.toTypedArray(), p.subtitleSize, SubtitleSize.MEDIUM)
    )
    SettingsRepository.setSubtitleBackground(
      syncEnumFromName(SubtitleBackground.entries.toTypedArray(), p.subtitleBackground, SubtitleBackground.BLACK)
    )
    SettingsRepository.setSubtitleOpacity(
      syncEnumFromName(SubtitleOpacity.entries.toTypedArray(), p.subtitleOpacity, SubtitleOpacity.FIFTY)
    )
    SettingsRepository.setSubtitlePosition(
      syncEnumFromName(SubtitlePosition.entries.toTypedArray(), p.subtitlePosition, SubtitlePosition.BOTTOM)
    )
    SettingsRepository.setLoadingOverlayEnabled(p.loadingOverlayEnabled)
    SettingsRepository.setLoadingStatusEnabled(p.loadingStatusEnabled)
    SettingsRepository.setPauseOverlayEnabled(p.pauseOverlayEnabled)
    SettingsRepository.setParentalGuideEnabled(p.parentalGuideEnabled)
    SettingsRepository.setTargetBuffer(
      syncEnumFromName(TargetBufferOption.entries.toTypedArray(), p.targetBuffer, TargetBufferOption.DEFAULT)
    )
    SettingsRepository.setInitialBuffer(
      syncEnumFromName(InitialBufferOption.entries.toTypedArray(), p.initialBuffer, InitialBufferOption.STANDARD)
    )
    SettingsRepository.setBackBuffer(
      syncEnumFromName(BackBufferOption.entries.toTypedArray(), p.backBuffer, BackBufferOption.STANDARD)
    )
    SettingsRepository.setAudioPassthroughEnabled(p.audioPassthroughEnabled)
    SettingsRepository.setAudioTunnelingEnabled(p.audioTunnelingEnabled)
    SettingsRepository.setDecoderFallbackMode(
      syncEnumFromName(DecoderFallbackMode.entries.toTypedArray(), p.decoderFallbackMode, DecoderFallbackMode.ON)
    )
    SettingsRepository.setAutoFrameRateMatching(p.autoFrameRateMatching)
    SettingsRepository.setDolbyVisionFallbackEnabled(p.dolbyVisionFallbackEnabled)
    SettingsRepository.setDebugOverlayEnabled(p.debugOverlayEnabled)
  }

  /**
   * Confronta gli addon installati con la lista della sorgente: rimuove le
   * installazioni locali che la sorgente non ha (MAI gli addon di sistema) e
   * installa/abilita quelle mancanti o disallineate.
   *
   * @return Triple(installati, rimossi, errori)
   */
  private suspend fun reconcileAddons(desired: List<SyncAddonPayload>): Triple<Int, Int, Int> {
    var installed = 0
    var removed = 0
    var errors = 0

    val desiredUrls = desired.map { StremioAddonRepository.normalizeUrl(it.url).lowercase() }.toSet()
    StremioAddonRepository.getInstalledAddons()
      .filter { local ->
        !StremioAddonRepository.isSystemAddon(local.id) &&
          StremioAddonRepository.normalizeUrl(local.baseUrl).lowercase() !in desiredUrls
      }
      .forEach { local ->
        StremioAddonRepository.removeAddon(local.installId)
        removed++
      }

    desired.forEach { wanted ->
      val existing = StremioAddonRepository.getInstalledAddons()
        .firstOrNull { StremioAddonRepository.normalizeUrl(it.baseUrl).lowercase() == StremioAddonRepository.normalizeUrl(wanted.url).lowercase() }
      if (existing == null) {
        try {
          val addon = StremioAddonRepository.installAddon(wanted.url)
          if (!wanted.enabled) {
            StremioAddonRepository.setAddonEnabled(addon.installId, false)
          }
          installed++
        } catch (e: Exception) {
          errors++
          Log.w(TAG, "Addon non installabile da ${wanted.url}: ${e.message}")
        }
      } else if (existing.isEnabled != wanted.enabled) {
        StremioAddonRepository.setAddonEnabled(existing.installId, wanted.enabled)
      }
    }
    return Triple(installed, removed, errors)
  }
}

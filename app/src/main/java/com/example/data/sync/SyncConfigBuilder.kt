package com.example.data.sync

import android.content.Context
import android.os.Build
import android.util.Log
import com.example.data.local.StreamNovaDatabase
import com.example.data.prefs.AppSettingsRepository
import com.example.data.prefs.SettingsRepository
import com.example.data.repository.SimklRepository
import com.example.data.stremio.StremioAddonRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "SyncConfigBuilder"

/**
 * Costruisce il [SyncConfigPayload] da trasferire, leggendo lo stato corrente dei
 * repository e del database Room. Nessuna scrittura: il builder è pura lettura.
 *
 * Le categorie richieste guidano quali sezioni vengono riempite: una categoria
 * esclusa lascia la sezione vuota/nulla nel payload, così il ricevente non tocca
 * i propri dati per quell'area.
 */
object SyncConfigBuilder {

  suspend fun build(context: Context, deviceName: String, categories: Set<SyncCategory>): SyncConfigPayload =
    withContext(Dispatchers.IO) {
      // Attende il caricamento da disco: una scrittura fatta prima del load
      // sovrascriverebbe i valori appena letti con i default.
      AppSettingsRepository.awaitLoaded()

      val db = StreamNovaDatabase.getInstance(context)

      val appSettings = if (SyncCategory.SETTINGS in categories) buildAppSettings() else null
      val playbackSettings = if (SyncCategory.SETTINGS in categories) buildPlaybackSettings() else null
      val addons = if (SyncCategory.ADDONS in categories) {
        StremioAddonRepository.getInstalledAddons().map { addon ->
          SyncAddonPayload(
            url = addon.baseUrl,
            name = addon.manifest.displayTitle,
            enabled = addon.isEnabled
          )
        }
      } else {
        emptyList()
      }
      val simklAccount = if (SyncCategory.SIMKL in categories) {
        SimklRepository.accountState.value
          .takeIf { it.isLoggedIn }
          ?.let { state ->
            SyncSimklAccountPayload(
              accessToken = state.accessToken,
              refreshToken = state.refreshToken,
              accountId = state.accountId,
              username = state.username,
              accountType = state.accountType,
              tokenExpiresAt = state.tokenExpiresAt,
              tokenExpiresAtEpochMs = state.tokenExpiresAtEpochMs,
              scope = state.scope,
              flow = state.flow?.name,
              sessionExpired = state.sessionExpired
            )
          }
      } else {
        null
      }

      // Preferiti e progressi: solo le schede che hanno uno STATO UTENTE (preferito,
      // posizione, riga "Continua a guardare", completamento). Il resto del catalogo
      // in cache è dato di navigazione, non configurazione: resterebbe locale.
      var mediaPayloads: List<SyncMediaItemPayload> = emptyList()
      var episodePayloads: List<SyncEpisodeProgressPayload> = emptyList()
      if (SyncCategory.LIBRARY in categories && db != null) {
        val mediaDao = db.cachedMediaDao()
        val rows = mediaDao.getAllCachedMedia().filter { row ->
          row.isFavorite ||
            row.currentProgressMs > 0L ||
            row.inContinueWatching ||
            row.completedAt != null ||
            row.lastWatchedAt != null
        }
        val includedIds = rows.map { it.id }.toSet()
        val episodes = db.episodeProgressDao()
          .getAllProgress()
          .filter { it.mediaId in includedIds }
        mediaPayloads = rows.map { it.toPayload() }
        episodePayloads = episodes.map { it.toPayload() }
      } else if (SyncCategory.LIBRARY in categories) {
        Log.w(TAG, "Database non disponibile: la categoria LIBRARY viene omessa")
      }

      SyncConfigPayload(
        schemaVersion = SYNC_SCHEMA_VERSION,
        deviceName = deviceName.ifBlank { Build.MODEL ?: "StreamNova" },
        createdAtEpochMs = System.currentTimeMillis(),
        appSettings = appSettings,
        playbackSettings = playbackSettings,
        addons = addons,
        simklAccount = simklAccount,
        mediaItems = mediaPayloads,
        episodeProgress = episodePayloads
      )
    }

  private fun buildAppSettings(): SyncAppSettingsPayload {
    val cw = AppSettingsRepository.continueWatchingPreferences.value
    return SyncAppSettingsPayload(
      torBoxApiKey = AppSettingsRepository.torBoxApiKey.value,
      openSubtitlesApiKey = AppSettingsRepository.openSubtitlesApiKey.value,
      simklClientId = AppSettingsRepository.simklClientId.value,
      torBoxInstantDebridEnabled = AppSettingsRepository.torBoxInstantDebridEnabled.value,
      streamingEngineMode = AppSettingsRepository.streamingEngineMode.value.name,
      autoplayEnabled = AppSettingsRepository.autoplayEnabled.value,
      appLanguage = AppSettingsRepository.appLanguage.value,
      parentalControlEnabled = AppSettingsRepository.parentalControlEnabled.value,
      parentalControlPinHash = AppSettingsRepository.parentalControlPin.value,
      parentalControlLevel = AppSettingsRepository.parentalControlLevel.value,
      tmdbGenreFallbackEnabled = AppSettingsRepository.tmdbGenreFallbackEnabled.value,
      hideUnreleasedContent = AppSettingsRepository.hideUnreleasedContent.value,
      userProviderBindingsJson = AppSettingsRepository.userProviderBindingsJson.value,
      cwShowRow = cw.showRow,
      cwLayout = cw.layout.name,
      cwEpisodeThumbnails = cw.useEpisodeThumbnails,
      cwBlurUnwatched = cw.blurUnwatched,
      cwNextFromFurthest = cw.nextEpisodeFromFurthest,
      cwShowUnaired = cw.showUnairedEpisodes,
      cwSort = cw.sort.name
    )
  }

  private fun buildPlaybackSettings(): SyncPlaybackSettingsPayload {
    val s = SettingsRepository.settings.value
    return SyncPlaybackSettingsPayload(
      preferredResolution = s.preferredResolution.name,
      autoPlayNextEpisodeEnabled = s.autoPlayNextEpisodeEnabled,
      autoResume = s.autoResume,
      reuseLastWorkingStreamEnabled = s.reuseLastWorkingStreamEnabled,
      lastWorkingStreamCacheDurationHours = s.lastWorkingStreamCacheDurationHours,
      prioritizeItalianAudio = s.prioritizeItalianAudio,
      preferredAudioLanguage = s.preferredAudioLanguage,
      subtitlesEnabled = s.subtitlesEnabled,
      forcedSubtitlesEnabled = s.forcedSubtitlesEnabled,
      preferredSubtitleLanguage = s.preferredSubtitleLanguage,
      subtitleSize = s.subtitleSize.name,
      subtitleBackground = s.subtitleBackground.name,
      subtitleOpacity = s.subtitleOpacity.name,
      subtitlePosition = s.subtitlePosition.name,
      loadingOverlayEnabled = s.loadingOverlayEnabled,
      loadingStatusEnabled = s.loadingStatusEnabled,
      pauseOverlayEnabled = s.pauseOverlayEnabled,
      parentalGuideEnabled = s.parentalGuideEnabled,
      targetBuffer = s.targetBuffer.name,
      initialBuffer = s.initialBuffer.name,
      backBuffer = s.backBuffer.name,
      audioPassthroughEnabled = s.audioPassthroughEnabled,
      audioTunnelingEnabled = s.audioTunnelingEnabled,
      decoderFallbackMode = s.decoderFallbackMode.name,
      autoFrameRateMatching = s.autoFrameRateMatching,
      dolbyVisionFallbackEnabled = s.dolbyVisionFallbackEnabled,
      debugOverlayEnabled = s.debugOverlayEnabled
    )
  }
}

/** Conversione riga Room → payload (tutti i campi, fedele alla sorgente). */
fun com.example.data.local.CachedMediaItemEntity.toPayload(): SyncMediaItemPayload =
  SyncMediaItemPayload(
    id = id,
    tmdbId = tmdbId,
    title = title,
    originalTitle = originalTitle,
    synopsis = synopsis,
    videoUrl = videoUrl,
    resolution = resolution,
    qualityTags = qualityTags,
    backdropUrl = backdropUrl,
    posterUrl = posterUrl,
    logoUrl = logoUrl,
    type = type,
    year = year,
    durationMinutes = durationMinutes,
    seasonsCount = seasonsCount,
    rating = rating,
    genres = genres,
    director = director,
    cast = cast,
    provider = provider,
    isTrending = isTrending,
    isTop10 = isTop10,
    isFavorite = isFavorite,
    currentProgressMs = currentProgressMs,
    totalDurationMs = totalDurationMs,
    lastWatchedSeason = lastWatchedSeason,
    lastWatchedEpisode = lastWatchedEpisode,
    lastWatchedAt = lastWatchedAt,
    inContinueWatching = inContinueWatching,
    completedAt = completedAt,
    ageRating = ageRating,
    favoriteSource = favoriteSource,
    progressSource = progressSource,
    importedFromSimkl = importedFromSimkl,
    cachedAt = cachedAt
  )

/** Conversione payload → riga Room (usata dall'applier sul dispositivo ricevente). */
fun SyncMediaItemPayload.toEntity(): com.example.data.local.CachedMediaItemEntity =
  com.example.data.local.CachedMediaItemEntity(
    id = id,
    tmdbId = tmdbId,
    title = title,
    originalTitle = originalTitle,
    synopsis = synopsis,
    videoUrl = videoUrl,
    resolution = resolution,
    qualityTags = qualityTags,
    backdropUrl = backdropUrl,
    posterUrl = posterUrl,
    logoUrl = logoUrl,
    type = type,
    year = year,
    durationMinutes = durationMinutes,
    seasonsCount = seasonsCount,
    rating = rating,
    genres = genres,
    director = director,
    cast = cast,
    provider = provider,
    isTrending = isTrending,
    isTop10 = isTop10,
    isFavorite = isFavorite,
    currentProgressMs = currentProgressMs,
    totalDurationMs = totalDurationMs,
    lastWatchedSeason = lastWatchedSeason,
    lastWatchedEpisode = lastWatchedEpisode,
    lastWatchedAt = lastWatchedAt,
    inContinueWatching = inContinueWatching,
    completedAt = completedAt,
    ageRating = ageRating,
    favoriteSource = favoriteSource,
    progressSource = progressSource,
    importedFromSimkl = importedFromSimkl,
    cachedAt = cachedAt
  )

/** Conversione progresso episodio Room → payload. */
fun com.example.data.local.EpisodeProgressEntity.toPayload(): SyncEpisodeProgressPayload =
  SyncEpisodeProgressPayload(
    mediaId = mediaId,
    seasonNumber = seasonNumber,
    episodeNumber = episodeNumber,
    progressMs = progressMs,
    durationMs = durationMs,
    updatedAt = updatedAt,
    source = source
  )

/** Conversione payload progresso episodio → riga Room. */
fun SyncEpisodeProgressPayload.toEntity(): com.example.data.local.EpisodeProgressEntity =
  com.example.data.local.EpisodeProgressEntity(
    mediaId = mediaId,
    seasonNumber = seasonNumber,
    episodeNumber = episodeNumber,
    progressMs = progressMs,
    durationMs = durationMs,
    updatedAt = updatedAt,
    source = source
  )

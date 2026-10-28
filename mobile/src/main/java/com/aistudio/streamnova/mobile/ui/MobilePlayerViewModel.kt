package com.aistudio.streamnova.mobile.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.Episode
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.prefs.AppSettingsRepository
import com.example.data.prefs.StreamingEngineMode
import com.example.data.repository.MediaRepository
import com.example.data.streaming.StreamManager
import com.example.data.streaming.StreamSource
import com.example.data.torbox.TorBoxRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class MobilePlayerUiState(
  val loading: Boolean = true,
  val media: MediaItem? = null,
  val episode: Episode? = null,
  val source: StreamSource? = null,
  val error: String? = null
)

/**
 * ViewModel mobile del player: risolve la sorgente con il motore condiviso
 * (`:core:player` + `:core:data`) e persiste il progresso di visione.
 *
 * I controlli touch restano nella UI mobile; qui vive solo la logica di fonte
 * e avanzamento, riusabile anche per una futura variante tablet.
 */
class MobilePlayerViewModel : ViewModel() {

  private val streamManager = StreamManager()

  private val _state = MutableStateFlow(MobilePlayerUiState())
  val state: StateFlow<MobilePlayerUiState> = _state.asStateFlow()

  fun prepare(mediaId: String, season: Int?, episode: Int?) {
    _state.value = MobilePlayerUiState(loading = true)
    viewModelScope.launch {
      val media = MediaRepository.getMediaById(mediaId)
        ?: MediaRepository.mediaList.value.firstOrNull { it.id == mediaId }
      if (media == null) {
        _state.value = MobilePlayerUiState(loading = false, error = "Titolo non trovato")
        return@launch
      }

      val isTv = media.type == MediaType.SERIE_TV
      val resolvedEpisode = if (isTv) {
        val selectedSeason = season ?: media.lastWatchedSeason ?: 1
        val selectedEpisode = episode ?: media.lastWatchedEpisode ?: 1
        media.episodes.firstOrNull {
          it.seasonNumber == selectedSeason && it.episodeNumber == selectedEpisode
        } ?: Episode(
          id = "${media.id}_s${selectedSeason}e${selectedEpisode}",
          seasonNumber = selectedSeason,
          episodeNumber = selectedEpisode,
          title = "Episodio $selectedEpisode",
          synopsis = "",
          durationMinutes = 50,
          videoUrl = "",
          seriesImdbId = media.imdbId
        )
      } else null

      // I titoli demo del catalogo hanno un flusso di verifica incorporato:
      // usarlo direttamente rende il test del player deterministico e non
      // dipende dai provider terzi.
      val bundledDemoUrl = resolvedEpisode?.videoUrl?.takeIf { it.isNotBlank() }
        ?: media.videoUrl.takeIf { it.isNotBlank() }
      if (MediaRepository.isDemoSeedItem(media.id) && bundledDemoUrl != null) {
        _state.value = MobilePlayerUiState(
          loading = false,
          media = media,
          episode = resolvedEpisode,
          source = StreamSource(
            streamUrl = bundledDemoUrl,
            quality = "Auto",
            serverName = "StreamNova Demo",
            isProgressive = !bundledDemoUrl.contains(".m3u8", ignoreCase = true)
          )
        )
        return@launch
      }

      val engineMode = AppSettingsRepository.streamingEngineMode.value
      val sources = runCatching {
        streamManager.resolve(
          tmdbId = media.tmdbId?.takeIf { it > 0 } ?: 0,
          isTv = isTv,
          season = if (isTv) resolvedEpisode?.seasonNumber ?: 1 else null,
          episode = if (isTv) resolvedEpisode?.episodeNumber ?: 1 else null,
          title = media.title.ifBlank { media.originalTitle },
          year = media.year,
          originalTitle = media.originalTitle.takeIf { it.isNotBlank() },
          providerTag = media.provider,
          genres = media.genres,
          streamingEngineMode = engineMode,
          imdbId = media.imdbId?.takeIf { it.isNotBlank() }
            ?: resolvedEpisode?.seriesImdbId?.takeIf { it.isNotBlank() }
        )
      }.getOrElse { emptyList() }

      var chosen = sources.firstOrNull { !it.streamUrl.isNullOrBlank() }
      if (chosen == null && engineMode == StreamingEngineMode.DEBRID_TORBOX) {
        val debridCandidate = sources.firstOrNull { !it.infoHash.isNullOrBlank() }
        val infoHash = debridCandidate?.infoHash
        if (debridCandidate != null && infoHash != null) {
          val unlocked = runCatching {
            TorBoxRepository.resolveInfoHash(infoHash, debridCandidate.fileIdx)
          }.getOrNull()
          if (!unlocked.isNullOrBlank()) {
            chosen = debridCandidate.copy(streamUrl = unlocked, isCached = true)
          }
        }
      }

      if (chosen == null && bundledDemoUrl != null) {
        chosen = StreamSource(
          streamUrl = bundledDemoUrl,
          quality = "Auto",
          serverName = "StreamNova Demo",
          isProgressive = !bundledDemoUrl.contains(".m3u8", ignoreCase = true)
        )
      }

      _state.value = if (chosen?.streamUrl.isNullOrBlank()) {
        MobilePlayerUiState(
          loading = false,
          media = media,
          episode = resolvedEpisode,
          error = "Nessuna sorgente riproducibile"
        )
      } else {
        MobilePlayerUiState(
          loading = false,
          media = media,
          episode = resolvedEpisode,
          source = chosen
        )
      }
    }
  }

  /** Salva il progresso reale misurato dal player sul record film o episodio. */
  fun saveProgress(positionMs: Long, durationMs: Long) {
    val media = _state.value.media ?: return
    val episode = _state.value.episode
    if (episode != null) {
      MediaRepository.updateEpisodeProgress(
        mediaId = media.id,
        seasonNumber = episode.seasonNumber,
        episodeNumber = episode.episodeNumber,
        progressMs = positionMs,
        durationMs = durationMs
      )
    } else {
      MediaRepository.updateProgress(
        id = media.id,
        progressMs = positionMs,
        durationMs = durationMs
      )
    }
  }
}

package com.example.data.sync

/**
 * Regole di fusione della libreria (preferiti + progressi), PURE: nessuna dipendenza
 * Android, testabili su JVM.
 *
 * La sincronizzazione NON è distruttiva: le righe esistenti solo sul ricevente
 * restano. Per le righe presenti su ENTRAMBI i lati vince quella con la visione più
 * recente (`lastWatchedAt`); il preferito è in UNIONE (basta che un lato lo abbia)
 * e vince come `favoriteSource` quello del lato che ha portato il preferito.
 *
 * Gli episodi si fondono per chiave (mediaId, stagione, episodio): vince la riga
 * con `updatedAt` più recente, in parità arriva quella in arrivo.
 */

/** Risultato della fusione della libreria: contatori per il riepilogo in UI. */
data class SyncLibraryMergeResult(
  val mediaRows: List<SyncMediaItemPayload>,
  val episodeRows: List<SyncEpisodeProgressPayload>,
  val mediaAdded: Int,
  val mediaUpdated: Int,
  val episodesAdded: Int,
  val episodesUpdated: Int
)

fun mergeLibrary(
  localMedia: List<SyncMediaItemPayload>,
  incomingMedia: List<SyncMediaItemPayload>,
  localEpisodes: List<SyncEpisodeProgressPayload>,
  incomingEpisodes: List<SyncEpisodeProgressPayload>
): SyncLibraryMergeResult {
  val localById = localMedia.associateBy { it.id }
  var added = 0
  var updated = 0

  val mergedMedia = incomingMedia.map { incoming ->
    val local = localById[incoming.id]
    when {
      local == null -> {
        added++
        incoming
      }
      (local.lastWatchedAt ?: 0L) > (incoming.lastWatchedAt ?: 0L) -> {
        // La visione locale è più recente: resta la riga locale, ma un preferito
        // arrivato dalla sorgente non viene perso.
        updated++
        if (incoming.isFavorite && !local.isFavorite) {
          local.copy(isFavorite = true, favoriteSource = incoming.favoriteSource)
        } else {
          local
        }
      }
      else -> {
        updated++
        // Vince la riga in arrivo, ma un preferito solo locale resta tale.
        if (local.isFavorite && !incoming.isFavorite) {
          incoming.copy(isFavorite = true, favoriteSource = local.favoriteSource)
        } else {
          incoming
        }
      }
    }
  }

  val localEpisodesByKey = localEpisodes.associateBy { it.mediaId to it.seasonNumber to it.episodeNumber }
  var episodesAdded = 0
  var episodesUpdated = 0

  val mergedEpisodes = incomingEpisodes.map { incoming ->
    val key = incoming.mediaId to incoming.seasonNumber to incoming.episodeNumber
    val local = localEpisodesByKey[key]
    when {
      local == null -> {
        episodesAdded++
        incoming
      }
      incoming.updatedAt >= local.updatedAt -> {
        episodesUpdated++
        incoming
      }
      else -> {
        // La posizione locale è più recente: resta invariata.
        local
      }
    }
  }

  return SyncLibraryMergeResult(
    mediaRows = mergedMedia,
    episodeRows = mergedEpisodes,
    mediaAdded = added,
    mediaUpdated = updated,
    episodesAdded = episodesAdded,
    episodesUpdated = episodesUpdated
  )
}

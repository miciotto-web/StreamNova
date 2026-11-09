package com.example.data.repository

import com.example.data.repository.SimklRepository.SimklScrobbleEvent

/**
 * Una scrittura scrobble in attesa di essere consegnata a SIMKL.
 *
 * L'outbox è **persistente** (DataStore) e legato all'account e alla sessione:
 * `accountId` + `sessionEpoch` impediscono che un evento prodotto prima di una
 * disconnessione venga inviato con le credenziali di un'altra sessione.
 *
 * `createdAt` definisce l'ordine FIFO (start → pause → stop dello stesso titolo);
 * `nextAttemptAt` è il momento prima del quale non ritentare (lock SIMKL di 20 s,
 * backoff di rete).
 */
internal data class SimklOutboxEntry(
  val id: String,
  val accountId: Long?,
  val sessionEpoch: Int,
  val event: SimklScrobbleEvent,
  val media: SimklMediaPayload,
  val progressPercent: Double,
  val createdAt: Long,
  val attempts: Int = 0,
  /** Tentativi dovuti al lock di 20 s: separati dal budget degli errori di rete. */
  val lockRetries: Int = 0,
  val nextAttemptAt: Long = 0L
)

/**
 * Serializzazione dell'outbox senza dipendenze da `org.json` (stub nei test JVM).
 * Una voce per riga, campi separati da `\u001F`, come [SimklPendingPlaybackStore].
 */
internal object SimklScrobbleOutboxStore {

  private const val FIELD_SEPARATOR = '\u001F'
  private const val MIN_FIELDS = 16

  fun encode(entries: List<SimklOutboxEntry>): String =
    entries.joinToString("\n") { entry ->
      val media = entry.media
      listOf(
        entry.id,
        entry.accountId?.toString().orEmpty(),
        entry.sessionEpoch.toString(),
        entry.event.name,
        sanitize(media.title),
        media.year?.toString().orEmpty(),
        media.tmdbId?.toString().orEmpty(),
        media.imdbId.orEmpty(),
        sanitize(media.mediaId.orEmpty()),
        media.episode?.season?.toString().orEmpty(),
        media.episode?.number?.toString().orEmpty(),
        entry.progressPercent.toString(),
        entry.createdAt.toString(),
        entry.attempts.toString(),
        entry.lockRetries.toString(),
        entry.nextAttemptAt.toString()
      ).joinToString(FIELD_SEPARATOR.toString())
    }

  fun decode(raw: String?): List<SimklOutboxEntry> {
    val value = raw?.trim().orEmpty()
    if (value.isEmpty()) return emptyList()
    return value.lineSequence().mapNotNull { parseRecord(it) }.toList()
  }

  private fun parseRecord(line: String): SimklOutboxEntry? {
    if (line.isBlank()) return null
    val fields = line.split(FIELD_SEPARATOR)
    if (fields.size < MIN_FIELDS) return null
    val event = runCatching { SimklScrobbleEvent.valueOf(fields[3]) }.getOrNull() ?: return null
    val title = fields[4].takeIf { it.isNotBlank() } ?: return null
    val progress = fields[11].toDoubleOrNull() ?: return null
    val season = fields[9].toIntOrNull()
    val episode = fields[10].toIntOrNull()
    val media = SimklMediaPayload(
      title = title,
      year = fields[5].toIntOrNull(),
      tmdbId = fields[6].toIntOrNull(),
      imdbId = fields[7].takeIf { it.isNotBlank() },
      episode = if (season != null && episode != null) SimklEpisodePayload(season, episode) else null,
      mediaId = fields[8].takeIf { it.isNotBlank() }
    )
    return SimklOutboxEntry(
      id = fields[0],
      accountId = fields[1].toLongOrNull(),
      sessionEpoch = fields[2].toIntOrNull() ?: 0,
      event = event,
      media = media,
      progressPercent = progress,
      createdAt = fields[12].toLongOrNull() ?: 0L,
      attempts = fields[13].toIntOrNull() ?: 0,
      lockRetries = fields[14].toIntOrNull() ?: 0,
      nextAttemptAt = fields[15].toLongOrNull() ?: 0L
    )
  }

  /** Il formato è line-based: un titolo con newline o separatore non deve rompere il record. */
  private fun sanitize(raw: String): String =
    raw.replace('\n', ' ').replace('\r', ' ').replace(FIELD_SEPARATOR, ' ').trim()
}

package com.example.ui.util

import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.model.NextEpisodeInfo
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Testi del badge "Prossima puntata" della card Continua a guardare.
 * [airDateText] è già nel formato italiano `gg/MM/yyyy`, [seasonEpisodeText] in `S02E05`.
 */
data class NextEpisodeBadgeData(
  val airDateText: String,
  val seasonEpisodeText: String
)

/**
 * Unica fonte di verità su QUANDO mostrare il badge della prossima puntata.
 *
 * Il badge compare solo con dati TMDB completi e coerenti:
 * - contenuto di tipo serie TV (mai film/documentari);
 * - stato TMDB esattamente `Returning Series` (serie in onda);
 * - prossima puntata effettiva restituita da TMDB;
 * - stagione in ripresa nota e UGUALE a quella della prossima puntata: chi sta guardando
 *   la prima stagione non deve ricevere informazioni sulle uscite della quarta;
 * - data nel formato `yyyy-MM-dd` interpretabile e non già trascorsa.
 *
 * La stagione in ripresa è solo quella salvata in "Continua a guardare"
 * ([MediaItem.lastWatchedSeason]): nessun fallback alla stagione 1, alla prima stagione
 * della serie o all'ultimo episodio trasmesso. Se il dato manca o non è attendibile il
 * badge resta nascosto.
 *
 * Non esiste alcuna derivazione: la data non viene stimata dalla periodicità degli
 * episodi né sostituita con quella dell'ultimo episodio andato in onda.
 */
object NextEpisodeBadgeRules {

  /** Stato TMDB delle serie attualmente in onda: l'unico che abilita il badge. */
  const val TV_STATUS_RETURNING = "Returning Series"

  private val airDatePattern = Regex("""^\d{4}-\d{2}-\d{2}$""")

  fun resolve(media: MediaItem, today: Date = todayStart()): NextEpisodeBadgeData? = resolve(
    isTv = media.type == MediaType.SERIE_TV,
    status = media.tvStatus,
    nextEpisode = media.nextEpisode,
    resumeSeason = media.lastWatchedSeason,
    today = today
  )

  fun resolve(
    isTv: Boolean,
    status: String?,
    nextEpisode: NextEpisodeInfo?,
    resumeSeason: Int?,
    today: Date = todayStart()
  ): NextEpisodeBadgeData? {
    if (!isTv) return null
    if (!status.orEmpty().trim().equals(TV_STATUS_RETURNING, ignoreCase = true)) return null

    val next = nextEpisode ?: return null
    if (next.seasonNumber < 0 || next.episodeNumber <= 0) return null

    // Stagione dell'episodio in ripresa: senza un numero salvato e valido non esiste un
    // confronto attendibile, quindi il badge non viene mostrato.
    val watchingSeason = resumeSeason?.takeIf { it >= 0 } ?: return null
    if (next.seasonNumber != watchingSeason) return null

    val airDate = parseAirDate(next.airDate) ?: return null
    if (airDate.before(todayStart(today))) return null

    return NextEpisodeBadgeData(
      airDateText = SimpleDateFormat("dd/MM/yyyy", Locale.ITALY).format(airDate),
      seasonEpisodeText = String.format(Locale.US, "S%02dE%02d", next.seasonNumber, next.episodeNumber)
    )
  }

  /**
   * Interpreta `air_date` TMDB in modo STRETTO: dopo aver ripulito gli spazi, il valore
   * deve rispettare esattamente `yyyy-MM-dd` e il parsing non è leniente (`2026-02-30` o
   * `2026-13-45` sono rifiutati). `null` = data non verificabile.
   */
  fun parseAirDate(raw: String?): Date? {
    val value = raw?.trim().orEmpty()
    if (!airDatePattern.matches(value)) return null
    return try {
      SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }.parse(value)
    } catch (_: Exception) {
      null
    }
  }

  /** Mezzanotte locale del giorno di [now]: il confronto "già trascorsa" è per giorno. */
  fun todayStart(now: Date = Date()): Date {
    return Calendar.getInstance().apply {
      time = now
      set(Calendar.HOUR_OF_DAY, 0)
      set(Calendar.MINUTE, 0)
      set(Calendar.SECOND, 0)
      set(Calendar.MILLISECOND, 0)
    }.time
  }
}

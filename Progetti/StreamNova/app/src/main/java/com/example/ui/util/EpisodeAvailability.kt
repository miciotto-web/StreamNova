package com.example.ui.util

import com.example.data.model.Episode
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Messaggio mostrato quando NON esiste una sorgente reale per il contenuto in riproduzione
 * (nessun fallback a video demo). Distingue il caso "episodio non ancora trasmesso"
 * (airDate futura) dal generico "stream non disponibile".
 *
 * Regole:
 * - Film → messaggio standard del titolo.
 * - Serie TV con airDate futura → "Episodio non ancora disponibile" + "Disponibile dal …".
 * - Serie TV con airDate odierna/passata, nulla, vuota o non interpretabile → messaggio episodio.
 */
fun streamUnavailableMessage(isTv: Boolean, episode: Episode?): String {
  if (!isTv) return "Stream non disponibile per questo titolo."

  val unavailableEpisode = "Stream non disponibile per questo episodio."

  val raw = episode?.airDate?.trim().orEmpty()
  if (raw.isEmpty()) return unavailableEpisode

  val parsed: Date? = try {
    SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }.parse(raw)
  } catch (e: Exception) {
    null
  }
  if (parsed == null) return unavailableEpisode

  val todayMidnight = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
  }.time

  return if (parsed.after(todayMidnight)) {
    val display = SimpleDateFormat("dd/MM/yyyy", Locale.ITALY).format(parsed)
    "Episodio non ancora disponibile\n\nDisponibile dal $display"
  } else {
    unavailableEpisode
  }
}

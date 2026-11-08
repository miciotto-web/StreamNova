package com.example.data.util

import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Normalizzazione e confronto delle date di rilascio usate dalla Home.
 *
 * Regole uniche per tutto il data layer:
 *  - formato accettato: **solo** `yyyy-MM-dd` completo e valido (es. `2026-03-14`);
 *  - un anno da solo (`2026`), una data parziale o una data impossibile
 *    (`2026-02-30`) NON sono dati affidabili e vengono scartati;
 *  - le date ISO si confrontano lessicograficamente, quindi il confronto è
 *    indipendente dal fuso orario e non richiede `java.time` (minSdk 24).
 */
object ReleaseDateUtils {

  private const val ISO_DATE_PATTERN = "yyyy-MM-dd"
  private val ISO_DATE_REGEX = Regex("""^\d{4}-\d{2}-\d{2}$""")

  /**
   * Restituisce la data in formato `yyyy-MM-dd` se [raw] è una data ISO completa e
   * valida, altrimenti `null`.
   *
   * Non usa il parsing lenient: date come `2026-13-40` o `2026-02-30` sono rifiutate
   * invece di essere "corrette" in un valore diverso.
   */
  fun normalizeIsoDate(raw: String?): String? {
    val value = raw?.trim().orEmpty()
    if (!ISO_DATE_REGEX.matches(value)) return null
    val format = isoFormatter()
    val position = ParsePosition(0)
    format.parse(value, position)
    // Il parse deve consumare l'intera stringa (ParsePosition resta a 0 se fallisce).
    if (position.index != value.length) return null
    return value
  }

  /** Data corrente (`yyyy-MM-dd`) nel fuso orario del dispositivo. */
  fun todayIso(
    nowMillis: Long = System.currentTimeMillis(),
    timeZone: TimeZone = TimeZone.getDefault()
  ): String = SimpleDateFormat(ISO_DATE_PATTERN, Locale.US)
    .apply { this.timeZone = timeZone }
    .format(Date(nowMillis))

  /**
   * true se [isoDate] (già normalizzata) è successiva a [todayIso].
   * Date uguali o precedenti a oggi sono considerate già rilasciate.
   */
  fun isAfter(isoDate: String, todayIso: String): Boolean = isoDate > todayIso

  private fun isoFormatter(): SimpleDateFormat =
    SimpleDateFormat(ISO_DATE_PATTERN, Locale.US).apply {
      isLenient = false
      timeZone = TimeZone.getTimeZone("UTC")
    }
}

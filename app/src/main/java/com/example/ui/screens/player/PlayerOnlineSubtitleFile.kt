package com.example.ui.screens.player

import java.io.File
import java.io.IOException

/**
 * Validazione del file di sottotitolo **scaricato** da OpenSubtitles prima di
 * allegarlo al player come traccia esterna.
 *
 * Un file non valido (percorso sbagliato, file vuoto, pagina HTML scaricata al posto
 * del sottotitolo) è la **causa** del fallimento della traccia: allegarlo comunque fa
 * fallire il caricamento della traccia e, con il renderer di testo attivo, può portare
 * il player a mostrare un errore di riproduzione. Meglio non allegarlo e segnalare il
 * problema all'utente.
 *
 * La validazione è deliberatamente conservativa: un formato non riconosciuto ma
 * plausibile (SRT, WebVTT, ASS/SSA, TTML) viene ACCETTATO, perché rifiutare un
 * sottotitolo valido sarebbe un errore peggiore.
 */
internal object PlayerOnlineSubtitleFile {

  /** Esito della validazione del file scaricato. */
  enum class Validity {
    /** Il file è un sottotitolo utilizzabile. */
    OK,

    /** Il file non esiste più (cache pulita, download interrotto). */
    MISSING,

    /** Il file è vuoto. */
    EMPTY,

    /** Il file non è un sottotitolo (per es. una pagina HTML). */
    NOT_A_SUBTITLE
  }

  /**
   * Verifica il file scaricato.
   *
   * @param file file locale del sottotitolo (cache dell'app).
   */
  fun validate(file: File): Validity {
    if (!file.exists() || !file.isFile) return Validity.MISSING
    if (file.length() == 0L) return Validity.EMPTY
    val head = readHead(file) ?: return Validity.NOT_A_SUBTITLE
    return if (looksLikeSubtitle(head)) Validity.OK else Validity.NOT_A_SUBTITLE
  }

  /**
   * Estrae il percorso locale dal URI `file://` usato dal player.
   *
   * @return il percorso assoluto, oppure `null` se l'URI non è un file locale.
   */
  fun localPathOf(uri: String): String? {
    val trimmed = uri.trim()
    if (!trimmed.startsWith("file://")) return null
    return trimmed.removePrefix("file://").takeIf { it.isNotBlank() }
  }

  private fun readHead(file: File): String? = try {
    file.inputStream().use { input ->
      val buffer = ByteArray(HEAD_BYTES)
      val read = input.read(buffer)
      if (read <= 0) null else String(buffer, 0, read, Charsets.UTF_8)
    }
  } catch (e: IOException) {
    null
  } catch (e: SecurityException) {
    null
  }

  private fun looksLikeSubtitle(head: String): Boolean {
    val trimmed = head.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
    if (trimmed.isEmpty()) return false
    // Pagina HTML scaricata al posto del sottotitolo.
    if (trimmed.startsWith("<!doctype html", ignoreCase = true) ||
      trimmed.startsWith("<html", ignoreCase = true)
    ) {
      return false
    }
    // SRT: almeno un timestamp "hh:mm:ss,mmm --> hh:mm:ss,mmm".
    if (SRT_TIMESTAMP.containsMatchIn(trimmed)) return true
    // WebVTT.
    if (trimmed.startsWith("WEBVTT", ignoreCase = true)) return true
    // ASS / SSA.
    if (trimmed.startsWith("[Script Info]", ignoreCase = true) ||
      trimmed.contains("[V4+ Styles]") ||
      trimmed.contains("[V4 Styles]")
    ) {
      return true
    }
    // TTML.
    if (trimmed.startsWith("<?xml", ignoreCase = true) &&
      (trimmed.contains("<tt ") || trimmed.contains("ttml"))
    ) {
      return true
    }
    // Formato non riconosciuto ma con la struttura di un SRT: accettato.
    return trimmed.contains("-->")
  }

  private val SRT_TIMESTAMP =
    Regex("""\d{1,2}:\d{2}:\d{2}[,.]\d{1,3}\s*-->\s*\d{1,2}:\d{2}:\d{2}[,.]\d{1,3}""")

  private const val HEAD_BYTES = 4096
}

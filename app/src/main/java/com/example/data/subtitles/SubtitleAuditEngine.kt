package com.example.data.subtitles

/**
 * Formato di un file di sottotitoli riconosciuto dal contenuto (non dal nome).
 *
 * Il nome del file mente spesso (`sub.ass` che contiene SRT, URL senza estensione,
 * sottotitoli scaricati da ZIP): il riconoscimento avviene SOLO sulle prime righe
 * significative del testo.
 */
enum class SubtitleContainerFormat { SUBRIP, WEBVTT, ASS, SSA, TTML, UNKNOWN }

/** Codifica rilevata dai BOM: utile perché ASS in UTF-16 rompe il parser Media3. */
enum class SubtitleEncoding { UTF_8, UTF_8_BOM, UTF_16_LE, UTF_16_BE, UNKNOWN }

/**
 * Caratteristica ASS/SSA che il renderer a cue generici NON è in grado di mostrare.
 *
 * Ogni voce corrisponde a un avviso mostrato all'utente: nessuna informazione
 * inventata, solo ciò che è realmente presente nel file.
 */
enum class SubtitleAuditWarning(val message: String) {
  EMPTY_FILE("File vuoto o non leggibile"),
  NO_PLAYRES("Manca la risoluzione di riferimento (PlayResX/Y): il posizionamento può risultare errato"),
  MANY_STYLES("Molti stili nel file: il player applica uno stile unico, la resa sarà semplificata"),
  KARAOKE_FLATTENED("Il file usa il karaoke ASS: gli effetti per sillaba non sono riproducibili e verranno appiattiti"),
  DRAWING_FLATTENED("Il file contiene disegni vettoriali ASS: le forme non verranno mostrate"),
  POSITIONING_IGNORED("Il file usa posizionamento/movimento ASS: le posizioni verranno ignorate o approssimate"),
  EFFECTS_FLATTENED("Il file usa effetti ASS (fade/blur/trasformazioni): verranno ignorati"),
  FONTS_NOT_EMBEDDED("Il file referenzia font non incorporati: la resa dipende dai font di sistema"),
  INVALID_TIMESTAMPS("Timestamp non riconosciuti: alcune battute potrebbero non comparire")
}

/**
 * Esito dell'audit di un file di sottotitoli.
 *
 * È un modello **informativo**: non blocca la riproduzione e non modifica il file.
 * Serve a dire all'utente, in modo onesto, cosa il player potrà mostrare e cosa
 * andrà perso (tipico dei file ASS/SSA ricchi di stili).
 */
data class SubtitleAudit(
  val format: SubtitleContainerFormat,
  val encoding: SubtitleEncoding = SubtitleEncoding.UTF_8,
  /** Tipo di script ASS (`v4.00+`, `v4.00`), null per gli altri formati. */
  val scriptType: String? = null,
  val playResX: Int? = null,
  val playResY: Int? = null,
  val styleCount: Int = 0,
  /** Font dichiarati dagli stili e non incorporati nel file. */
  val referencedFonts: List<String> = emptyList(),
  val embeddedFonts: List<String> = emptyList(),
  /** Battute/cue stimate (righe Dialogue per ASS, timestamp per SRT/VTT). */
  val cueCount: Int = 0,
  val hasKaraoke: Boolean = false,
  val hasPositioning: Boolean = false,
  val hasDrawing: Boolean = false,
  val hasEffects: Boolean = false,
  val hasOverrides: Boolean = false,
  val warnings: List<SubtitleAuditWarning> = emptyList()
) {
  /** `true` per i formati con stili ricchi (ASS/SSA). */
  val isAssFamily: Boolean
    get() = format == SubtitleContainerFormat.ASS || format == SubtitleContainerFormat.SSA

  /** `true` quando il file contiene funzionalità che il renderer appiattirà. */
  val hasLossyFeatures: Boolean
    get() = hasKaraoke || hasDrawing || hasPositioning || hasEffects

  /** Riepilogo breve per la UI: "ASS · 1920x1080 · 12 stili · 480 battute · karaoke". */
  val summary: String
    get() = buildString {
      append(
        when (format) {
          SubtitleContainerFormat.SUBRIP -> "SRT"
          SubtitleContainerFormat.WEBVTT -> "WebVTT"
          SubtitleContainerFormat.ASS -> "ASS"
          SubtitleContainerFormat.SSA -> "SSA"
          SubtitleContainerFormat.TTML -> "TTML"
          SubtitleContainerFormat.UNKNOWN -> "Formato sconosciuto"
        }
      )
      if (playResX != null && playResY != null) append(" · ${playResX}x$playResY")
      if (styleCount > 0) append(" · $styleCount stili")
      if (cueCount > 0) append(" · $cueCount battute")
      when {
        hasKaraoke -> append(" · karaoke")
        hasDrawing -> append(" · disegni vettoriali")
        hasPositioning -> append(" · posizionamento")
      }
    }
}

/**
 * Audit statico dei sottotitoli (Subtitle Engine Upgrade).
 *
 * Analizza il TESTO già in mano (nessuna richiesta di rete, nessuna dipendenza
 * Android) e classifica formato, codifica e funzionalità ASS/SSA che il player
 * Media3 a cue generici non può rendere. Il limite di scansione evita di leggere
 * file enormi per intero: oltre [MAX_AUDIT_CHARS] l'audit è dichiaratamente
 * parziale ma i conteggi principali restano validi.
 */
object SubtitleAuditEngine {

  /** Caratteri massimi analizzati (circa 2 MB di testo UTF-8). */
  const val MAX_AUDIT_CHARS = 2_000_000

  /** Soglia oltre la quale il numero di stili è considerato "molti". */
  const val MANY_STYLES_THRESHOLD = 20

  private val SRT_TIMESTAMP = Regex(
    """\d{1,2}:\d{2}:\d{2}[,.]\d{1,3}\s*-->\s*\d{1,2}:\d{2}:\d{2}[,.]\d{1,3}"""
  )
  private val VTT_TIMESTAMP = Regex(
    """(?:\d{1,2}:)?\d{2}:\d{2}\.\d{3}\s*-->\s*(?:\d{1,2}:)?\d{2}:\d{2}\.\d{3}"""
  )
  private val KARAOKE = Regex("""\\k[fKo]?\d+""")
  private val POSITIONING = Regex("""\\(?:pos|move|org|clip|iclip)\s*\(""")
  private val DRAWING = Regex("""\\p[1-9]""")
  private val EFFECTS = Regex("""\\(?:t|fad|fade|blur)\s*\(""")
  private val OVERRIDE_BLOCK = Regex("""\{[^}]*\\[^}]*\}""")

  /** Audit da byte: rileva il BOM e decodifica di conseguenza. */
  fun audit(bytes: ByteArray): SubtitleAudit {
    if (bytes.isEmpty()) {
      return SubtitleAudit(
        format = SubtitleContainerFormat.UNKNOWN,
        warnings = listOf(SubtitleAuditWarning.EMPTY_FILE)
      )
    }
    val (encoding, offset) = detectEncoding(bytes)
    val text = when (encoding) {
      SubtitleEncoding.UTF_16_LE -> String(bytes, offset, bytes.size - offset, Charsets.UTF_16LE)
      SubtitleEncoding.UTF_16_BE -> String(bytes, offset, bytes.size - offset, Charsets.UTF_16BE)
      else -> String(bytes, offset, bytes.size - offset, Charsets.UTF_8)
    }
    return audit(text, encoding)
  }

  /** Audit dal testo già decodificato. */
  fun audit(
    text: String,
    encoding: SubtitleEncoding = SubtitleEncoding.UTF_8
  ): SubtitleAudit {
    val content = if (text.length > MAX_AUDIT_CHARS) text.substring(0, MAX_AUDIT_CHARS) else text
    if (content.isBlank()) {
      return SubtitleAudit(
        format = SubtitleContainerFormat.UNKNOWN,
        encoding = encoding,
        warnings = listOf(SubtitleAuditWarning.EMPTY_FILE)
      )
    }

    val format = detectFormat(content)
    return when (format) {
      SubtitleContainerFormat.ASS,
      SubtitleContainerFormat.SSA -> auditAss(content, format, encoding)
      SubtitleContainerFormat.SUBRIP,
      SubtitleContainerFormat.WEBVTT -> auditCueBased(content, format, encoding)
      SubtitleContainerFormat.TTML -> SubtitleAudit(
        format = format,
        encoding = encoding,
        cueCount = Regex("<p[ >]").findAll(content).count(),
        warnings = if (content.contains("<p ") || content.contains("<p>")) emptyList() else
          listOf(SubtitleAuditWarning.INVALID_TIMESTAMPS)
      )
      SubtitleContainerFormat.UNKNOWN -> SubtitleAudit(
        format = format,
        encoding = encoding,
        warnings = listOf(SubtitleAuditWarning.INVALID_TIMESTAMPS)
      )
    }
  }

  /** Riconosce il formato dalle prime righe significative. */
  fun detectFormat(text: String): SubtitleContainerFormat {
    val head = text.take(8_192)
    val lower = head.lowercase()
    return when {
      lower.contains("[script info]") ||
        lower.contains("[v4+ styles]") ||
        lower.contains("[v4 styles]") ||
        Regex("(?im)^dialogue:\\s*\\d").containsMatchIn(head) -> {
        // SSA storico: ScriptType v4.00 senza "+". ASS usa v4.00+.
        if (Regex("(?im)^scripttype:\\s*v4\\.00\\s*$").containsMatchIn(head) &&
          !Regex("(?im)^scripttype:\\s*v4\\.00\\+").containsMatchIn(head)
        ) {
          SubtitleContainerFormat.SSA
        } else {
          SubtitleContainerFormat.ASS
        }
      }
      lower.startsWith("webvtt") || lower.contains("\nwebvtt") -> SubtitleContainerFormat.WEBVTT
      lower.contains("<?xml") && (lower.contains("<tt") || lower.contains("<tt ")) ->
        SubtitleContainerFormat.TTML
      SRT_TIMESTAMP.containsMatchIn(head) -> SubtitleContainerFormat.SUBRIP
      VTT_TIMESTAMP.containsMatchIn(head) -> SubtitleContainerFormat.WEBVTT
      else -> SubtitleContainerFormat.UNKNOWN
    }
  }

  private fun auditAss(
    content: String,
    format: SubtitleContainerFormat,
    encoding: SubtitleEncoding
  ): SubtitleAudit {
    val lines = content.lineSequence().toList()
    val scriptType = lines.firstOrNull { it.trimStart().startsWith("ScriptType:", ignoreCase = true) }
      ?.substringAfter(':')?.trim()

    val playResX = lines.firstOrNull { it.trimStart().startsWith("PlayResX:", ignoreCase = true) }
      ?.substringAfter(':')?.trim()?.toIntOrNull()
    val playResY = lines.firstOrNull { it.trimStart().startsWith("PlayResY:", ignoreCase = true) }
      ?.substringAfter(':')?.trim()?.toIntOrNull()

    var inStyles = false
    var inFonts = false
    var inEvents = false
    var styleCount = 0
    var dialogueLines = 0
    val referencedFonts = LinkedHashSet<String>()
    val embeddedFonts = LinkedHashSet<String>()
    var hasKaraoke = false
    var hasPositioning = false
    var hasDrawing = false
    var hasEffects = false
    var hasOverrides = false
    var invalidDialogue = 0

    val dialogueTimestamp = Regex("""Dialogue:\s*\d+,\d{1,2}:\d{2}:\d{2}[.,]\d+,""")

    lines.forEach { raw ->
      val line = raw.trim()
      if (line.startsWith("[")) {
        inStyles = line.equals("[V4+ Styles]", ignoreCase = true) ||
          line.equals("[V4 Styles]", ignoreCase = true)
        inFonts = line.equals("[Fonts]", ignoreCase = true)
        inEvents = line.equals("[Events]", ignoreCase = true)
        return@forEach
      }
      when {
        inStyles && line.startsWith("Style:", ignoreCase = true) -> {
          styleCount++
          // Formato V4+: Name, Fontname, Fontsize, ... Il font è la seconda colonna.
          val columns = line.substringAfter(':').split(',')
          val font = columns.getOrNull(1)?.trim().orEmpty()
          if (font.isNotBlank() && !font.equals("Arial", ignoreCase = true)) {
            referencedFonts += font
          }
        }
        inFonts && line.isNotBlank() -> {
          val name = line.substringAfter(':', line).trim()
          if (name.isNotBlank()) embeddedFonts += name
        }
        inEvents && line.startsWith("Dialogue:", ignoreCase = true) -> {
          dialogueLines++
          if (!dialogueTimestamp.containsMatchIn(line)) invalidDialogue++
          val textPart = line.substringAfter(',', "").let { rest ->
            // Ultimo campo = testo: si analizza l'intera riga, è sufficiente.
            rest
          }
          if (KARAOKE.containsMatchIn(textPart)) hasKaraoke = true
          if (POSITIONING.containsMatchIn(textPart)) hasPositioning = true
          if (DRAWING.containsMatchIn(textPart)) hasDrawing = true
          if (EFFECTS.containsMatchIn(textPart)) hasEffects = true
          if (OVERRIDE_BLOCK.containsMatchIn(textPart)) hasOverrides = true
        }
      }
    }

    val warnings = buildList {
      if (playResX == null || playResY == null) add(SubtitleAuditWarning.NO_PLAYRES)
      if (styleCount > MANY_STYLES_THRESHOLD) add(SubtitleAuditWarning.MANY_STYLES)
      if (hasKaraoke) add(SubtitleAuditWarning.KARAOKE_FLATTENED)
      if (hasDrawing) add(SubtitleAuditWarning.DRAWING_FLATTENED)
      if (hasPositioning) add(SubtitleAuditWarning.POSITIONING_IGNORED)
      if (hasEffects) add(SubtitleAuditWarning.EFFECTS_FLATTENED)
      if (referencedFonts.isNotEmpty() && embeddedFonts.isEmpty()) {
        add(SubtitleAuditWarning.FONTS_NOT_EMBEDDED)
      }
      if (dialogueLines > 0 && invalidDialogue > dialogueLines / 2) {
        add(SubtitleAuditWarning.INVALID_TIMESTAMPS)
      }
    }

    return SubtitleAudit(
      format = format,
      encoding = encoding,
      scriptType = scriptType,
      playResX = playResX,
      playResY = playResY,
      styleCount = styleCount,
      referencedFonts = referencedFonts.toList(),
      embeddedFonts = embeddedFonts.toList(),
      cueCount = dialogueLines,
      hasKaraoke = hasKaraoke,
      hasPositioning = hasPositioning,
      hasDrawing = hasDrawing,
      hasEffects = hasEffects,
      hasOverrides = hasOverrides,
      warnings = warnings
    )
  }

  private fun auditCueBased(
    content: String,
    format: SubtitleContainerFormat,
    encoding: SubtitleEncoding
  ): SubtitleAudit {
    val timestamps = if (format == SubtitleContainerFormat.WEBVTT) {
      VTT_TIMESTAMP.findAll(content).count()
    } else {
      SRT_TIMESTAMP.findAll(content).count()
    }
    // Un cue ha due timestamp sulla stessa riga: il conteggio vero è l'occorrenza
    // di righe-freccia, non dei singoli timestamp.
    val cueCount = content.lineSequence().count { line ->
      (format == SubtitleContainerFormat.WEBVTT && VTT_TIMESTAMP.containsMatchIn(line)) ||
        (format == SubtitleContainerFormat.SUBRIP && SRT_TIMESTAMP.containsMatchIn(line))
    }
    val hasHtmlTags = Regex("""</?[a-zA-Z][^>]*>""").containsMatchIn(content)
    return SubtitleAudit(
      format = format,
      encoding = encoding,
      cueCount = cueCount,
      hasOverrides = hasHtmlTags,
      warnings = if (cueCount == 0 && timestamps == 0) {
        listOf(SubtitleAuditWarning.INVALID_TIMESTAMPS)
      } else {
        emptyList()
      }
    )
  }

  /** BOM → codifica e offset dei dati. Nessun BOM: UTF-8. */
  private fun detectEncoding(bytes: ByteArray): Pair<SubtitleEncoding, Int> {
    fun at(index: Int): Int = bytes[index].toInt() and 0xFF
    if (bytes.size >= 3 && at(0) == 0xEF && at(1) == 0xBB && at(2) == 0xBF) {
      return SubtitleEncoding.UTF_8_BOM to 3
    }
    if (bytes.size >= 2 && at(0) == 0xFF && at(1) == 0xFE) {
      return SubtitleEncoding.UTF_16_LE to 2
    }
    if (bytes.size >= 2 && at(0) == 0xFE && at(1) == 0xFF) {
      return SubtitleEncoding.UTF_16_BE to 2
    }
    return SubtitleEncoding.UTF_8 to 0
  }
}

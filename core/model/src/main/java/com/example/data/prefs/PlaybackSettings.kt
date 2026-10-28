package com.example.data.prefs

/**
 * Risoluzione preferita persistente. [targetHeight] è l'altezza nominale richiesta
 * (0 = Auto, che mantiene il comportamento attuale del player).
 */
enum class PreferredResolution(val label: String, val targetHeight: Int) {
  AUTO("Auto", 0),
  UHD_4K("4K", 2160),
  FULL_HD_1080P("1080p", 1080),
  HD_720P("720p", 720)
}

/**
 * Durata di validità della cache dell'ultimo flusso funzionante ("Riusa l'ultimo link").
 * Oltre la finestra la voce salvata viene ignorata e la risoluzione riparte dalla lista.
 */
enum class LastStreamCacheDuration(val label: String, val hours: Int) {
  SIX_HOURS("6 ore", 6),
  TWELVE_HOURS("12 ore", 12),
  ONE_DAY("1 giorno", 24),
  THREE_DAYS("3 giorni", 72),
  SEVEN_DAYS("7 giorni", 168)
}

/**
 * Dimensione del testo dei sottotitoli. [textSizeFraction] è la frazione dell'altezza
 * della vista video (PlayerView) usata da Media3 come dimensione del testo:
 * 0.0533f coincide con il default di Media3 (SubtitleView.DEFAULT_TEXT_SIZE_FRACTION).
 */
enum class SubtitleSize(val label: String, val textSizeFraction: Float) {
  SMALL("Piccola", 0.038f),
  MEDIUM("Media", 0.0533f),
  LARGE("Grande", 0.075f),
  VERY_LARGE("Molto grande", 0.10f)
}

/**
 * Sfondo del testo dei sottotitoli nel player. [backgroundColor] è il colore ARGB
 * applicato via SubtitleView.setStyle(CaptionStyleCompat) di Media3: con alpha 0
 * (Nessuno) SubtitlePainter non disegna alcun background dietro il testo.
 */
enum class SubtitleBackground(val label: String, val backgroundColor: Int) {
  NONE("Nessuno", 0x00000000),
  BLACK("Nero", 0xFF000000.toInt()),
  SEMI_TRANSPARENT("Nero semi-trasparente", 0x80000000.toInt())
}

/**
 * Posizione verticale dei sottotitoli nel player. [bottomPaddingFraction] è il
 * parametro nativo Media3 1.5.1 (SubtitleView.setBottomPaddingFraction): distanza del
 * cue dal bordo inferiore, come frazione dell'altezza della vista, applicata ai cue
 * senza posizione esplicita (Cue.line == Cue.DIMEN_UNSET, caso standard).
 */
enum class SubtitlePosition(val label: String, val bottomPaddingFraction: Float) {
  BOTTOM("Basso", 0.08f),
  CENTER("Centro", 0.5f),
  TOP("Alto", 0.9f)
}

/** Dimensione target del buffer memoria ExoPlayer. */
enum class TargetBufferOption(val label: String, val sizeMb: Int) {
  DEFAULT("Predefinito (175 MB)", 175),
  MINIMUM("Minimo (150 MB)", 150),
  BALANCED("Bilanciato (300 MB)", 300),
  EXTENDED("Esteso (500 MB)", 500)
}

/** Buffer iniziale prima di avviare la riproduzione (Avvio rapido vs Stabilità). */
enum class InitialBufferOption(val label: String, val durationMs: Int) {
  FAST("Avvio Rapido (1.5s)", 1500),
  STANDARD("Standard (2.5s)", 2500),
  STABLE("Stabile (3.5s)", 3500)
}

/** Durata del back buffer mantenuto per seek istantaneo all'indietro. */
enum class BackBufferOption(val label: String, val durationMs: Int) {
  OFF("Disattivato", 0),
  SHORT("10 secondi", 10_000),
  STANDARD("15 secondi (Default)", 15_000),
  EXTENDED("30 secondi", 30_000),
  MAXIMUM("60 secondi", 60_000)
}

/** Modalità del fallback software per i codec (FFmpeg). */
enum class DecoderFallbackMode(val label: String, val mode: Int) {
  OFF("Disattivato (Solo Hardware)", 0),
  ON("Hardware con Fallback Software", 1),
  PREFER("Preferisci Software (FFmpeg)", 2)
}

/** Impostazioni della sezione RIPRODUZIONE e AVANZATE. */
data class PlaybackSettings(
  val preferredResolution: PreferredResolution = PreferredResolution.AUTO,
  val autoPlayNextEpisodeEnabled: Boolean = true,
  val autoResume: Boolean = true,
  /**
   * "Riusa l'ultimo link": riproduce automaticamente l'ultimo flusso funzionante
   * salvato per lo stesso film/episodio, se la cache è ancora valida.
   */
  val reuseLastWorkingStreamEnabled: Boolean = true,
  /** Validità in ore della cache dell'ultimo flusso riutilizzabile (default 24h = 1 giorno). */
  val lastWorkingStreamCacheDurationHours: Int = 24,
  val prioritizeItalianAudio: Boolean = true,
  /**
   * Lingua preferita dell'audio (codice ISO, default "it").
   * "" = "Originale / Qualsiasi": nessuna preferenza linguistica verso Media3.
   */
  val preferredAudioLanguage: String = "it",
  /** Sottotitoli automatici: default OFF (nessuna attivazione forzata nel player). */
  val subtitlesEnabled: Boolean = false,
  /** Sottotitoli forced (es. dialoghi in lingua straniera con audio IT): default ON. */
  val forcedSubtitlesEnabled: Boolean = true,
  /**
   * Lingua preferita dei sottotitoli (codice ISO, default "it").
   * "" = "Originale / Qualsiasi": nessun vincolo di lingua verso Media3.
   */
  val preferredSubtitleLanguage: String = "it",
  /** Dimensione del testo dei sottotitoli nel player (default MEDIUM). */
  val subtitleSize: SubtitleSize = SubtitleSize.MEDIUM,
  /** Sfondo dietro il testo dei sottotitoli nel player (default SEMI_TRANSPARENT). */
  val subtitleBackground: SubtitleBackground = SubtitleBackground.SEMI_TRANSPARENT,
  /** Posizione verticale dei sottotitoli nel player (default BOTTOM). */
  val subtitlePosition: SubtitlePosition = SubtitlePosition.BOTTOM,

  // ── IMPOSTAZIONI AVANZATE ───────────────────────────────────────────
  val targetBuffer: TargetBufferOption = TargetBufferOption.DEFAULT,
  val initialBuffer: InitialBufferOption = InitialBufferOption.STANDARD,
  val backBuffer: BackBufferOption = BackBufferOption.STANDARD,
  val audioPassthroughEnabled: Boolean = false,
  val audioTunnelingEnabled: Boolean = false,
  val decoderFallbackMode: DecoderFallbackMode = DecoderFallbackMode.ON,
  val autoFrameRateMatching: Boolean = false,
  val dolbyVisionFallbackEnabled: Boolean = true,
  val debugOverlayEnabled: Boolean = false
)

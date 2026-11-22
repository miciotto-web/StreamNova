package com.example.data.streaming

import com.example.data.prefs.PlaybackSettings
import com.example.data.prefs.PreferredResolution

/**
 * Affidabilità osservata dell'addon che ha prodotto una sorgente.
 *
 * Arriva dall'Addon Health Monitor (health-check dei manifest) e serve SOLO come
 * segnale di fiducia nel ranking: un addon irraggiungibile non viene mai escluso
 * d'ufficio (la sorgente potrebbe essere ancora valida), ma perde punti rispetto
 * a una sorgente equivalente servita da un addon sano.
 */
enum class AddonTrust { HEALTHY, DEGRADED, UNREACHABLE, UNKNOWN }

/**
 * Motivo leggibile di un punto assegnato/tolto dal ranking.
 *
 * [impact] è il contributo (positivo o negativo) al punteggio: viene mostrato in
 * forma compatta nella UI ("Verificata +50") e permette di capire perché una
 * sorgente è stata preferita a un'altra senza scatole nere.
 */
data class SmartSourceReason(val label: String, val impact: Int)

/**
 * Sorgente con il suo punteggio e le sue motivazioni.
 *
 * [source] è la sorgente ORIGINALE (nessuna copia, nessuna modifica): il ranking
 * è pura lettura e non altera URL, header o metadati.
 */
data class SmartSourceScore(
  val source: StreamSource,
  val total: Int,
  val reasons: List<SmartSourceReason>
) {
  /** `true` quando la verifica ha dichiarato la sorgente non utilizzabile. */
  val isUnavailable: Boolean
    get() = source.availability == SourceAvailability.Unavailable

  /** Etichetta breve del motivo principale, per badge e log. */
  val topReason: String?
    get() = reasons.maxByOrNull { kotlin.math.abs(it.impact) }?.label
}

/**
 * Politica di ranking: preferenze utente realmente esistenti, nessun default
 * nascosto diverso da quello delle impostazioni di riproduzione.
 */
data class SmartSourcePolicy(
  val preferredResolution: PreferredResolution = PreferredResolution.AUTO,
  val prioritizeItalianAudio: Boolean = true,
  /** Provider HTTP scelto dall'utente (le sue sorgenti restano favorite). */
  val preferredProviderId: String? = null,
  /** Affidabilità per `addonInstallId`/`addonId` (chiavi entrambe accettate). */
  val addonTrust: Map<String, AddonTrust> = emptyMap()
) {
  companion object {
    /**
     * Costruisce la politica dalle impostazioni di riproduzione correnti.
     * `null` = impostazioni non ancora caricate: si usano i default della classe.
     */
    fun fromSettings(
      settings: PlaybackSettings?,
      preferredProviderId: String? = null,
      addonTrust: Map<String, AddonTrust> = emptyMap()
    ): SmartSourcePolicy = SmartSourcePolicy(
      preferredResolution = settings?.preferredResolution ?: PreferredResolution.AUTO,
      prioritizeItalianAudio = settings?.prioritizeItalianAudio ?: true,
      preferredProviderId = preferredProviderId,
      addonTrust = addonTrust
    )
  }
}

/**
 * Selezione intelligente della sorgente migliore (Smart Source Selection).
 *
 * Il motore è **puro e deterministico**: stesso input → stesso ordine, nessuna
 * rete, nessun accesso a stato globale. Il punteggio combina SOLO dati già
 * disponibili sulla sorgente, con pesi espliciti e motivazioni verificabili:
 *
 *  1. **Riproducibilità verificata** (peso più alto): una sorgente il cui
 *     manifest/segmento è stato raggiunto (`Verified`) batte una mai verificata;
 *     una dichiarata `Unavailable` scende in fondo e non viene mai auto-scelta.
 *  2. **Cache Debrid**: un file già in cache parte subito, senza attesa di
 *     sblocco/indicizzazione.
 *  3. **Risoluzione**: rispetta la preferenza utente (1080p/720p/4K/Auto) con la
 *     stessa scala usata dall'ordinamento storico, così il ranking non contraddice
 *     le impostazioni.
 *  4. **Lingua italiana** (se l'utente la privilegia).
 *  5. **Qualità tecnica dichiarata**: codec efficiente (HEVC/AV1), HDR, numero di
 *     metadati presenti, coerenza dimensione/risoluzione.
 *  6. **Fiducia nell'addon** (health monitor) e **provider HTTP preferito**.
 *
 * A parità di punteggio l'ordine di ingresso viene conservato (sort stabile):
 * il chiamante può quindi passare una lista già ordinata dall'utente e ottenere
 * un raffinamento senza ribaltarne i criteri.
 */
object SmartSourceSelector {

  // Pesi espliciti: cambiarli qui cambia il comportamento in modo tracciabile.
  const val WEIGHT_AVAILABILITY_VERIFIED = 50
  const val WEIGHT_AVAILABILITY_UNAVAILABLE = -500
  const val WEIGHT_CACHE_CACHED = 35
  const val WEIGHT_CACHE_NOT_CACHED = -5
  const val WEIGHT_ITALIAN = 25
  const val WEIGHT_PROVIDER_PREFERRED = 20
  const val WEIGHT_CODEC_EFFICIENT = 8
  const val WEIGHT_CODEC_H264 = 2
  const val WEIGHT_HDR = 5
  const val WEIGHT_MULTI_AUDIO = 4
  const val WEIGHT_ADDON_HEALTHY = 6
  const val WEIGHT_ADDON_DEGRADED = -6
  const val WEIGHT_ADDON_UNREACHABLE = -30
  const val WEIGHT_SIZE_SUSPICIOUS_4K = -15
  const val WEIGHT_SIZE_SUSPICIOUS_1080 = -10
  const val WEIGHT_SIZE_SUSPICIOUS_720 = -8
  const val WEIGHT_SIZE_HUGE = -5

  /**
   * Ordina le sorgenti dalla migliore alla peggiore.
   *
   * Le sorgenti `Unavailable` restano nella lista (il selettore manuale le mostra
   * comunque) ma finiscono sempre in fondo: il chiamante che auto-seleziona deve
   * scartarle esplicitamente ([SmartSourceScore.isUnavailable]).
   */
  fun rank(
    sources: List<StreamSource>,
    policy: SmartSourcePolicy = SmartSourcePolicy()
  ): List<SmartSourceScore> = sources
    .map { score(it, policy) }
    .sortedWith(
      compareByDescending<SmartSourceScore> { it.total }
        // A parità: prima le sorgenti riproducibili, poi l'ordine di ingresso.
        .thenByDescending { !it.isUnavailable }
    )

  /** Migliore sorgente auto-selezionabile, oppure null se non ce n'è nessuna. */
  fun best(
    sources: List<StreamSource>,
    policy: SmartSourcePolicy = SmartSourcePolicy()
  ): SmartSourceScore? = rank(sources, policy).firstOrNull { !it.isUnavailable }

  /** Punteggio di UNA sorgente con le relative motivazioni. */
  fun score(source: StreamSource, policy: SmartSourcePolicy): SmartSourceScore {
    val reasons = mutableListOf<SmartSourceReason>()

    fun add(label: String, impact: Int) {
      if (impact != 0) reasons += SmartSourceReason(label, impact)
    }

    // 1. Riproducibilità verificata.
    when (source.availability) {
      SourceAvailability.Verified -> add("Riproducibilità verificata", WEIGHT_AVAILABILITY_VERIFIED)
      SourceAvailability.Unavailable -> add("Sorgente non disponibile", WEIGHT_AVAILABILITY_UNAVAILABLE)
      SourceAvailability.Unknown -> Unit // nessun punto: il dato manca, non è una colpa
    }

    // 2. Cache Debrid (Unknown non è mai trattato come cached né penalizzato).
    when (source.cacheState) {
      CacheState.Cached -> add("File in cache Debrid", WEIGHT_CACHE_CACHED)
      CacheState.NotCached -> add("Non in cache Debrid", WEIGHT_CACHE_NOT_CACHED)
      CacheState.Unknown -> Unit
    }

    // 3. Risoluzione rispetto alla preferenza utente.
    val height = source.effectiveHeight
    add(resolutionReason(height, policy.preferredResolution), resolutionScore(height, policy.preferredResolution))

    // 4. Lingua italiana.
    if (policy.prioritizeItalianAudio && source.isItalian) {
      add("Audio italiano", WEIGHT_ITALIAN)
    }

    // 5. Qualità tecnica dichiarata.
    val codec = source.codec?.lowercase().orEmpty()
    when {
      codec.contains("hevc") || codec.contains("h265") || codec.contains("x265") ||
        codec.contains("av1") || codec.contains("vp9") ->
        add("Codec efficiente (${source.codec})", WEIGHT_CODEC_EFFICIENT)
      codec.contains("h264") || codec.contains("x264") || codec.contains("avc") ->
        add("Codec H.264", WEIGHT_CODEC_H264)
    }
    TorBoxSourceOrdering.hdrLabel(source)?.let { add("HDR dichiarato: $it", WEIGHT_HDR) }

    val audioLanguages = source.detectedAudioLanguages
    if (audioLanguages.size >= 2) {
      add("Audio multilingua (${audioLanguages.joinToString("/")})", WEIGHT_MULTI_AUDIO)
    }

    val completeness = listOf(
      source.releaseTitle,
      source.details,
      source.codec,
      source.releaseType,
      source.instantTag,
      source.addonName
    ).count { !it.isNullOrBlank() }
    if (completeness >= 3) {
      add("Metadati completi ($completeness/6)", completeness)
    }

    // Coerenza dimensione/risoluzione: un 4K da 900 MB è quasi certamente un
    // falso 4K o un file molto compresso; non è un divieto, solo un segnale.
    val sizeBytes = TorBoxSourceOrdering.sizeBytesOf(source)
    if (sizeBytes > 0) {
      val gib = sizeBytes / (1024.0 * 1024 * 1024)
      when {
        height >= 2000 && gib < 2.0 -> add("Dimensione ridotta per il 4K", WEIGHT_SIZE_SUSPICIOUS_4K)
        height in 1000..1500 && gib < 0.5 -> add("Dimensione ridotta per il 1080p", WEIGHT_SIZE_SUSPICIOUS_1080)
        height in 700..900 && gib < 0.2 -> add("Dimensione ridotta per il 720p", WEIGHT_SIZE_SUSPICIOUS_720)
        gib > 60.0 -> add("File molto grande (>60 GB)", WEIGHT_SIZE_HUGE)
      }
    }

    // 6. Provider preferito e fiducia nell'addon.
    if (policy.preferredProviderId != null && source.sourceProviderId == policy.preferredProviderId) {
      add("Provider scelto dall'utente", WEIGHT_PROVIDER_PREFERRED)
    }
    val trust = trustOf(source, policy.addonTrust)
    when (trust) {
      AddonTrust.HEALTHY -> add("Addon sano", WEIGHT_ADDON_HEALTHY)
      AddonTrust.DEGRADED -> add("Addon lento/degradato", WEIGHT_ADDON_DEGRADED)
      AddonTrust.UNREACHABLE -> add("Addon irraggiungibile", WEIGHT_ADDON_UNREACHABLE)
      AddonTrust.UNKNOWN -> Unit
    }

    return SmartSourceScore(
      source = source,
      total = reasons.sumOf { it.impact },
      reasons = reasons
    )
  }

  /**
   * Priorità della risoluzione (stessa scala di [TorBoxSourceOrdering]) ma con
   * l'etichetta esplicita del motivo.
   */
  private fun resolutionScore(height: Int, preferred: PreferredResolution): Int =
    TorBoxSourceOrdering.resolutionScoreForHeight(height, preferred)

  private fun resolutionReason(height: Int, preferred: PreferredResolution): String {
    val label = if (height > 0) StreamResolution.labelForHeight(height) else "qualità ignota"
    val prefLabel = when (preferred) {
      PreferredResolution.AUTO -> "Auto"
      PreferredResolution.FULL_HD_1080P -> "1080p"
      PreferredResolution.HD_720P -> "720p"
      PreferredResolution.UHD_4K -> "4K"
    }
    return "Risoluzione $label (preferenza $prefLabel)"
  }

  /** Fiducia dell'addon per installId, poi manifest id, poi nome (fallback). */
  private fun trustOf(source: StreamSource, map: Map<String, AddonTrust>): AddonTrust {
    if (map.isEmpty()) return AddonTrust.UNKNOWN
    val candidates = listOfNotNull(source.addonInstallId, source.addonId, source.addonName)
    return candidates.firstNotNullOfOrNull { key -> map[key] } ?: AddonTrust.UNKNOWN
  }

  /**
   * Spiegazione compatta del ranking, utile nei log e nel pannello Inspector:
   * "Migliore: 1080p TB (score 143) · Risoluzione 1080p (preferenza Auto) +80 ·
   * File in cache Debrid +35 · Riproducibilità verificata +50".
   */
  fun explain(score: SmartSourceScore, maxReasons: Int = 4): String = buildString {
    append(score.source.serverName.ifBlank { "sorgente" })
    val quality = PlayerSourceQuality.labelOf(score.source)
    if (quality != null) append(" ($quality)")
    append(" · score ${score.total}")
    val top = score.reasons.sortedByDescending { kotlin.math.abs(it.impact) }.take(maxReasons)
    if (top.isNotEmpty()) {
      append(" · ")
      append(top.joinToString(" · ") { "${it.label} ${if (it.impact > 0) "+" else ""}${it.impact}" })
    }
  }
}

/**
 * Etichetta di qualità per i log del ranking. Non inventa nulla: usa la stessa
 * risoluzione effettiva di [StreamSource] e restituisce null quando è ignota.
 */
internal object PlayerSourceQuality {
  fun labelOf(source: StreamSource): String? {
    val height = source.effectiveHeight
    return if (height > 0) StreamResolution.labelForHeight(height) else null
  }
}

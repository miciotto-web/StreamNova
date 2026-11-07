package com.example.ui.components

import androidx.annotation.DrawableRes
import com.example.R
import com.example.data.streaming.StreamSource
import com.example.data.streaming.TorBoxSourceOrdering
import java.util.Locale

/**
 * Provider Debrid riconosciuto dai metadati della sorgente.
 *
 * L'app usa **un solo** motore Debrid reale (TorBox Instant): gli altri provider non
 * sono supportati e non vengono inventati. Se il provider non è identificabile, la
 * card NON mostra alcun logo provider (fallback neutro: nessuna attribuzione).
 *
 * Il provider Debrid è il SERVIZIO usato per sbloccare/riprodurre lo stream: non è
 * l'addon che ha restituito la sorgente e non va mai usato come logo dell'addon.
 */
internal enum class DebridProvider(
  val label: String,
  @param:DrawableRes val logoResId: Int
) {
  TORBOX(label = "TorBox", logoResId = R.drawable.logo_provider_torbox)
}

/**
 * Identità dell'ADDON che ha restituito la sorgente (es. IlCorsaroViola, Torrentio).
 *
 * È un concetto distinto dal [DebridProvider]: [name] identifica l'addon,
 * [logoUrl]/[logoResId] sono la sua identità grafica. Se nessun logo è
 * disponibile, la UI mostra le iniziali di [name] come fallback neutro: non
 * viene mai attribuito il logo di un altro provider.
 */
internal data class AddonIdentity(
  val name: String,
  /** Logo ufficiale dichiarato dal manifest (`manifest.logo`), se disponibile. */
  val logoUrl: String? = null,
  /** Asset ufficiale già incluso nell'app per gli addon riconosciuti. */
  @param:DrawableRes val logoResId: Int? = null
) {
  val hasLogo: Boolean
    get() = !logoUrl.isNullOrBlank() || logoResId != null
}

/**
 * Addon riconosciuto tramite identità STABILI del manifest/installazione.
 *
 * Le corrispondenze sono esatte (manifest id, host dell'installazione o nome
 * normalizzato dell'addon): mai sottostringhe del filename o dell'`infoHash`.
 */
private data class KnownAddon(
  val manifestIds: Set<String>,
  val installHosts: Set<String>,
  val nameAliases: Set<String>,
  @param:DrawableRes val logoResId: Int
)

private val KNOWN_ADDONS = listOf(
  KnownAddon(
    manifestIds = setOf("community.ilcorsaroviola.ita"),
    installHosts = setOf("icv.stremio-italia.eu"),
    nameAliases = setOf("ilcorsaroviola", "ilcorsaroviolait", "ilcorsaroviolaita"),
    logoResId = R.drawable.logo_addon_ilcorsaroviola
  ),
  KnownAddon(
    manifestIds = setOf("com.stremio.torrentio.addon"),
    installHosts = setOf("torrentio.strem.fun", "torrentioita.stremio-italia.eu"),
    nameAliases = setOf("torrentio", "torrentioit", "torrentioita"),
    logoResId = R.drawable.logo_addon_torrentio
  )
)

/** Etichette HDR riconosciute nel testo della release (ordine di visualizzazione). */
internal enum class HdrBadgeLabel(val text: String) {
  DOLBY_VISION("Dolby Vision"),
  HDR10_PLUS("HDR10+"),
  HDR10("HDR10"),
  HDR("HDR")
}

/**
 * Mapping **solo di presentazione** dei metadati già presenti in [StreamSource] verso
 * i campi mostrati dalla card Debrid (layout a righe del riferimento grafico).
 *
 * Nessuna funzione esegue rete, modifica la sorgente o inventa valori: quando un
 * dato non esiste la funzione restituisce `null`/lista vuota e la UI omette il campo
 * (oppure mostra "N/A" per i valori che il riferimento espone esplicitamente).
 *
 * È un oggetto puro per poter essere verificato nei test unitari senza Compose.
 */
internal object DebridSourceCardPresentation {

  /** Icone/simboli usati dalle righe informative (stesse del riferimento grafico). */
  const val UNKNOWN_VALUE = "N/A"

  private val PEERS_REGEX = Regex("(?:👤|👥)\\s*(\\d+(?:[.,]\\d+)?\\s*[kKmM]?)")
  private val DOLBY_VISION_TOKEN = Regex("\\bdv\\b", RegexOption.IGNORE_CASE)
  private val DOLBY_DIGITAL_PLUS_REGEX =
    Regex("\\bddp\\s*\\d?|\\bdd\\+|\\beac3\\b|digital\\s*plus", RegexOption.IGNORE_CASE)
  private val TORBOX_TAG_REGEX = Regex("\\btb\\b", RegexOption.IGNORE_CASE)
  private val AUDIO_FORMAT_TOKEN = Regex(
    "(atmos|ddp\\s*\\d?(?:\\.\\d)?|dd\\+|e-?ac3|ac3|dts(?:[- ]?hd)?(?:\\s*ma)?(?:\\s*\\d\\.\\d)?" +
      "|truehd|aac(?:\\s*\\d\\.\\d)?|flac|\\d\\.\\d)",
    RegexOption.IGNORE_CASE
  )
  private const val MAX_AUDIO_FORMAT_CHARS = 16

  /**
   * Titolo della card: il tag istantanea dichiarato dall'addon (es. "2160p TB Instant").
   * Se manca, si usa la risoluzione reale: nessuna stringa composta a mano.
   */
  fun headline(source: StreamSource): String =
    source.instantTag?.takeIf { it.isNotBlank() } ?: resolutionLabel(source)

  /** Nome completo del file (fallback già previsto dal modello: `serverName`). */
  fun fileName(source: StreamSource): String = source.title.trim()

  /** Dimensione leggibile dal dato reale (`details`/nome file), o null se ignota. */
  fun sizeLabel(source: StreamSource, decimals: Int = 2): String? =
    formatSize(TorBoxSourceOrdering.sizeBytesOf(source).takeIf { it > 0L }, decimals)

  /**
   * Codici delle lingue audio riconosciute. `isItalian` (dato reale della sorgente)
   * aggiunge "IT" quando il nome release non contiene un token lingua esplicito.
   *
   * I descrittori neutri "Multi"/"Dual" non sono lingue: vengono mostrati solo se
   * non esiste alcun codice concreto, come già fa il modello (`parseAudioLanguages`).
   */
  fun audioLanguageCodes(source: StreamSource): List<String> {
    val detected = source.detectedAudioLanguages.map(::languageCode)
    val concrete = detected.filterNot { it == "MULTI" || it == "DUAL" }

    val codes = LinkedHashSet<String>()
    if (source.isItalian) codes += "IT"
    concrete.forEach { codes += it }
    if (codes.isEmpty()) detected.forEach { codes += it }
    return codes.toList()
  }

  /** Codici delle lingue dei sottotitoli dichiarati dallo stream (nessun valore dedotto). */
  fun subtitleLanguageCodes(source: StreamSource): List<String> =
    source.subtitles
      .mapNotNull { subtitle -> subtitle.getDisplayLanguage().trim().takeIf { it.isNotBlank() } }
      .map(::languageCode)
      .distinct()

  /** Peer/seed dichiarati da `details` (es. "👤 382"), o null se assenti. */
  fun peersLabel(source: StreamSource): String? {
    val details = source.details?.takeIf { it.isNotBlank() } ?: return null
    return PEERS_REGEX.find(details)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
  }

  /** Risoluzione mostrata nella riga tecnica: "2160p" per il 4K (come nel riferimento). */
  fun resolutionLabel(source: StreamSource): String =
    when (val height = TorBoxSourceOrdering.resolutionHeight(source)) {
      in 2160..Int.MAX_VALUE -> "2160p"
      1080 -> "1080p"
      720 -> "720p"
      480 -> "480p"
      else -> if (height > 0) "${height}p" else source.resolution
    }

  /**
   * Formato audio dichiarato nella riga `details` (es. "DDP5.1", "Atmos", "2.0").
   * Solo se presente: un campo mancante non produce testo.
   *
   * Il segmento dopo 🔊 può contenere anche lingue e dimensione (formati addon
   * diversi): viene estratto SOLO il token audio riconosciuto, così la riga resta
   * compatta e non invade la colonna destra della card.
   */
  fun audioFormatLabel(source: StreamSource): String? {
    val details = source.details?.takeIf { it.isNotBlank() } ?: return null
    val segment = details.split("|")
      .map { it.trim() }
      .firstOrNull { it.contains("🔊") }
      ?: return null
    val raw = segment.split("🔊")
      .getOrNull(1)
      .orEmpty()
      .split("💾", "📦", "👤", "👥")
      .firstOrNull()
      .orEmpty()
      .trim(' ', '·', '-', '•', '|')
    if (raw.isBlank()) return null
    return AUDIO_FORMAT_TOKEN.find(raw)?.value?.trim()?.takeIf { it.isNotBlank() }
      ?: raw.take(MAX_AUDIO_FORMAT_CHARS)
  }

  /** Tipo di rilascio dichiarato (es. "WEB-DL"), o null se non riconosciuto. */
  fun releaseTypeLabel(source: StreamSource): String? =
    source.releaseType?.trim()?.takeIf { it.isNotBlank() }

  /** true se la release è un WEB-DL (stesso riconoscimento già usato dal modello). */
  fun hasWebDl(source: StreamSource): Boolean {
    val releaseType = source.releaseType.orEmpty()
    val text = releaseText(source)
    return releaseType.contains("WEB", ignoreCase = true) ||
      text.contains("web-dl") ||
      text.contains("webdl") ||
      text.contains("web rip")
  }

  /** true se la sorgente è 4K/UHD (altezza effettiva reale o dichiarata). */
  fun isUltraHd(source: StreamSource): Boolean =
    TorBoxSourceOrdering.resolutionHeight(source) >= 2160

  /** true se il codec video dichiarato è HEVC/H.265 (o equivalente dichiarato nel testo). */
  fun isHevc(source: StreamSource): Boolean {
    val codec = source.codec.orEmpty()
    if (codec.contains("HEVC", ignoreCase = true)) return true
    val text = releaseText(source)
    return text.contains("hevc") || text.contains("x265") || text.contains("h265") ||
      text.contains("hvc1")
  }

  /** true se la release dichiara una traccia Dolby Atmos. */
  fun hasDolbyAtmos(source: StreamSource): Boolean = releaseText(source).contains("atmos")

  /** true se la release dichiara Dolby Digital Plus (DDP/DD+/E-AC3). */
  fun hasDolbyDigitalPlus(source: StreamSource): Boolean =
    DOLBY_DIGITAL_PLUS_REGEX.containsMatchIn(releaseText(source))

  /**
   * Badge HDR presenti nella release, nell'ordine del riferimento grafico.
   *
   * Il token `DV` (usato dai nomi release per Dolby Vision) viene riconosciuto solo
   * come parola intera, così "DVD"/"DVDRip" non producono falsi positivi.
   */
  fun hdrBadges(source: StreamSource): List<HdrBadgeLabel> {
    val text = releaseText(source)
    val badges = mutableListOf<HdrBadgeLabel>()

    val dolbyVision = text.contains("dolby vision") || text.contains("dovi") ||
      DOLBY_VISION_TOKEN.containsMatchIn(text)
    if (dolbyVision) badges += HdrBadgeLabel.DOLBY_VISION

    when {
      text.contains("hdr10+") -> badges += HdrBadgeLabel.HDR10_PLUS
      text.contains("hdr10") -> badges += HdrBadgeLabel.HDR10
      (text.contains("hdr") || text.contains("hlg")) && !dolbyVision ->
        badges += HdrBadgeLabel.HDR
    }
    return badges
  }

  /**
   * Nome dell'addon di origine: `addonName` quando presente, con fallback sul
   * `serverName` ripulito dal suffisso torrent (" 🧲 ...").
   *
   * È l'identità TESTUALE dell'addon, non del provider Debrid.
   */
  fun addonNameOf(source: StreamSource): String {
    source.addonName?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
    val cleaned = source.serverName.substringBefore(" \ud83e\uddf2").trim()
    return cleaned.ifBlank { source.serverName.trim() }
  }

  /**
   * Identità dell'ADDON che ha restituito la sorgente, separata dal provider Debrid.
   *
   * Ordine del logo:
   *  1. `manifest.logo` dell'addon (URL ufficiale dichiarato dal manifest);
   *  2. asset ufficiale incluso nell'app, SOLO per addon riconosciuti tramite
   *     manifest id, host dell'installazione o nome esatto;
   *  3. nessun logo → la UI mostra le iniziali del nome come fallback neutro.
   *
   * `infoHash` NON partecipa a questa risoluzione: identifica una sorgente
   * torrent, non l'addon che l'ha restituita.
   */
  fun addonIdentityOf(source: StreamSource): AddonIdentity {
    val known = knownAddonOf(source)
    return AddonIdentity(
      name = addonNameOf(source),
      logoUrl = absoluteLogoUrl(source.addonLogo, source.addonInstallId),
      logoResId = known?.logoResId
    )
  }

  /**
   * Provider Debrid della sorgente, ricavato dai dati reali:
   *  - `infoHash` presente → sorgente torrent risolta dal motore TorBox Instant
   *    (stessa attribuzione di `LastWorkingStreamRepository.classifySourceMode`);
   *  - tag "TB"/"TorBox" nei metadati dichiarati dall'addon.
   *
   * Qualsiasi altra sorgente resta senza provider: nessun logo errato o dedotto.
   * Questo è il SERVIZIO Debrid, non l'addon di origine: la UI non lo usa come
   * logo identificativo della sorgente.
   */
  fun providerOf(source: StreamSource): DebridProvider? {
    if (!source.infoHash.isNullOrBlank()) return DebridProvider.TORBOX
    val text = listOfNotNull(source.instantTag, source.serverName, source.addonName, source.details)
      .joinToString(" ")
      .lowercase(Locale.ROOT)
    if (text.contains("torbox") || TORBOX_TAG_REGEX.containsMatchIn(text)) {
      return DebridProvider.TORBOX
    }
    return null
  }

  /** Iniziali (max 2) del nome del provider/addon, usate come avatar neutro. */
  fun initials(name: String): String {
    val words = name
      // "IlCorsaroViola" -> "Il Corsaro Viola": le iniziali restano leggibili
      // anche per i nomi addon scritti in CamelCase.
      .replace(Regex("([a-z\\p{Ll}])([A-Z\\p{Lu}])"), "$1 $2")
      .replace(Regex("[^\\p{L}\\p{N} ]"), " ")
      .trim()
      .split(Regex("\\s+"))
      .filter { it.isNotBlank() }
    if (words.isEmpty()) return "?"
    if (words.size == 1) return words[0].take(2).uppercase(Locale.ROOT)
    return (words[0].first().toString() + words[1].first().toString()).uppercase(Locale.ROOT)
  }

  /**
   * Converte l'etichetta lingua restituita dal modello ("Italiano", "English"...) nel
   * codice compatto mostrato nella card ("IT", "EN"...). Etichette non mappate restano
   * invariate: nessuna lingua viene inventata.
   */
  fun languageCode(label: String): String = when (label.trim()) {
    "Italiano" -> "IT"
    "English" -> "EN"
    "Español" -> "ES"
    "Français" -> "FR"
    "Deutsch" -> "DE"
    "Português" -> "PT"
    "日本語" -> "JA"
    "한국어" -> "KO"
    "中文" -> "ZH"
    "Русский" -> "RU"
    "العربية" -> "AR"
    "हिन्दी" -> "HI"
    "Polski" -> "PL"
    "Nederlands" -> "NL"
    "Svenska" -> "SV"
    "Norsk" -> "NO"
    "Dansk" -> "DA"
    "Suomi" -> "FI"
    "Ελληνικά" -> "EL"
    "Türkçe" -> "TR"
    "עברית" -> "HE"
    "ไทย" -> "TH"
    "Tiếng Việt" -> "VI"
    "Bahasa Indonesia" -> "ID"
    "Bahasa Melayu" -> "MS"
    "Română" -> "RO"
    "Magyar" -> "HU"
    "Čeština" -> "CS"
    "Slovenčina" -> "SK"
    "Українська" -> "UK"
    "Български" -> "BG"
    "Hrvatski" -> "HR"
    "Српски" -> "SR"
    "Slovenščina" -> "SL"
    "Eesti" -> "ET"
    "Latviešu" -> "LV"
    "Lietuvių" -> "LT"
    "Multi" -> "MULTI"
    "Dual" -> "DUAL"
    else -> label.trim().uppercase(Locale.ROOT)
  }

  /** Formatta una dimensione in byte ("2.18 GB"); null se assente/zero. */
  fun formatSize(bytes: Long?, decimals: Int = 2): String? {
    if (bytes == null || bytes <= 0L) return null
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var index = 0
    while (value >= 1024.0 && index < units.lastIndex) {
      value /= 1024.0
      index++
    }
    return if (index == 0) {
      "$bytes B"
    } else {
      String.format(Locale.US, "%.${decimals}f %s", value, units[index])
    }
  }

  /**
   * Addon noto per identità stabile (manifest id → host installazione → nome esatto).
   * Nessuna corrispondenza parziale e nessun coinvolgimento di `infoHash`.
   */
  private fun knownAddonOf(source: StreamSource): KnownAddon? {
    val manifestId = source.addonId?.trim()?.lowercase(Locale.ROOT)
    if (!manifestId.isNullOrBlank()) {
      KNOWN_ADDONS.firstOrNull { manifestId in it.manifestIds }?.let { return it }
    }
    val host = installHostOf(source.addonInstallId)
    if (host != null) {
      KNOWN_ADDONS.firstOrNull { host in it.installHosts }?.let { return it }
    }
    val name = normalizedAddonName(source.addonName)
    if (name.isNotBlank()) {
      KNOWN_ADDONS.firstOrNull { name in it.nameAliases }?.let { return it }
    }
    return null
  }

  /** Nome addon normalizzato ("Torrentio 🇮🇹" → "torrentio") per alias esatti. */
  private fun normalizedAddonName(name: String?): String =
    name.orEmpty().lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

  /** Host dell'installazione Stremio (`addonInstallId`), senza schema/percorso/porta. */
  private fun installHostOf(baseUrl: String?): String? {
    val raw = baseUrl?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val withoutScheme = raw.substringAfter("://", raw)
    val host = withoutScheme.substringBefore('/').substringBefore('?').substringBefore('#')
    val clean = host.substringBefore('@').substringBefore(':')
    return clean.lowercase(Locale.ROOT).takeIf { it.isNotBlank() }
  }

  /**
   * Rende assoluto il logo dichiarato dal manifest. Accetta URL http(s), URL
   * protocol-relative e path relativi alla base dell'addon; qualsiasi altra forma
   * viene ignorata (meglio il fallback neutro di un'immagine non valida).
   */
  private fun absoluteLogoUrl(logo: String?, baseUrl: String?): String? {
    val raw = logo?.trim()?.takeIf { it.isNotBlank() } ?: return null
    if (raw.startsWith("http://", ignoreCase = true) ||
      raw.startsWith("https://", ignoreCase = true)
    ) {
      return raw
    }
    if (raw.startsWith("//")) return "https:$raw"
    val base = baseUrl?.trim()?.takeIf { it.isNotBlank() } ?: return null
    return try {
      java.net.URI(base).resolve(raw).toString()
        .takeIf { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
    } catch (_: Exception) {
      null
    }
  }

  /** Testo release normalizzato (solo metadati presenti), usato dai riconoscimenti. */
  private fun releaseText(source: StreamSource): String =
    listOfNotNull(source.releaseType, source.releaseTitle, source.details, source.instantTag)
      .joinToString(" ")
      .lowercase(Locale.ROOT)
}

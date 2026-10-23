package com.example.data.stremio

/**
 * Classificazione dei canali LIVE esposti dagli addon Stremio (es. TvVoo).
 *
 * Un canale live NON è una serie TV, anche se il catalogo che lo elenca dichiara
 * `type = tv`: non ha stagioni, episodi, cast né trailer. L'identità del canale è
 * data dall'id Stremio (`vavoo_rai1`, ...) e il tipo ORIGINALE con cui l'addon lo
 * dichiara (`tv`) è quello che va usato nelle richieste `meta`/`stream`.
 *
 * L'idea è riconoscere la forma dell'id, non i singoli addon: un id senza
 * identificatore IMDb/TMDB appartiene al namespace dell'addon, quindi è un
 * elemento non cinematografico.
 */
object StremioLiveChannelClassifier {

  /** Prefisso della chiave di navigazione del canale: `channel:<addonId>|<idStremio>`. */
  const val CHANNEL_ID_PREFIX = "channel:"

  private const val CHANNEL_KEY_SEPARATOR = '|'

  /** Risolve l'addon installato che espone un certo id (vedi [StremioAddonRepository]). */
  fun interface AddonResolver {
    fun addonFor(mediaId: String): InstalledAddon?
  }

  /**
   * true se [mediaId] è un id di contenuto Stremio non cinematografico:
   * né IMDb (`tt...`) né TMDB (`tmdb:`, `tmdb_*`, numerico). Sono gli id dei
   * catalogo `tv`/`channel` degli addon live.
   */
  fun isChannelStyleId(mediaId: String?): Boolean {
    val id = mediaId?.trim().orEmpty()
    if (id.isEmpty()) return false
    if (id.startsWith(CHANNEL_ID_PREFIX, ignoreCase = true)) return true
    if (id.startsWith("tt", ignoreCase = true)) return false
    if (id.startsWith("tmdb", ignoreCase = true)) return false
    if (id.toIntOrNull() != null) return false
    return true
  }

  /** Chiave di navigazione del canale per [addonId] + [mediaId]. */
  fun channelKey(addonId: String, mediaId: String): String =
    "$CHANNEL_ID_PREFIX$addonId$CHANNEL_KEY_SEPARATOR${mediaId.trim()}"

  /** Estrae `(addonId, idStremio)` da una chiave di canale, o null se non è una chiave. */
  fun parseChannelKey(channelKey: String?): Pair<String, String>? {
    val key = channelKey?.trim().orEmpty()
    if (!key.startsWith(CHANNEL_ID_PREFIX, ignoreCase = true)) return null
    val body = key.substring(CHANNEL_ID_PREFIX.length)
    val separator = body.indexOf(CHANNEL_KEY_SEPARATOR)
    if (separator <= 0 || separator == body.lastIndex) return null
    val addonId = body.substring(0, separator)
    val mediaId = body.substring(separator + 1)
    if (addonId.isBlank() || mediaId.isBlank()) return null
    return addonId to mediaId
  }

  /**
   * Tipo ORIGINALE con cui l'addon dichiara il catalogo che ha prodotto il canale.
   *
   * Mai dedotto: è il `type` della [StremioCatalogDefinition] del manifest installato.
   * `null` quando l'addon non dichiara quel catalogo (nessun tipo da assumere).
   */
  fun catalogType(addon: InstalledAddon, catalogId: String?): String? {
    val cleanId = catalogId?.trim().orEmpty()
    if (cleanId.isEmpty()) return null
    return addon.manifest.validCatalogs()
      .firstOrNull { it.effectiveId.equals(cleanId, ignoreCase = true) }
      ?.effectiveType
      ?.takeIf { it.isNotBlank() }
  }

  /**
   * Chiave del canale per [mediaId], risolta sull'addon installato che lo espone.
   * `null` quando l'id non è un id di canale o nessun addon lo dichiara: in quel
   * caso resta il percorso di dettaglio tradizionale.
   */
  fun channelKeyFor(mediaId: String?, resolver: AddonResolver): String? {
    val id = mediaId?.trim().orEmpty()
    if (id.isEmpty()) return null
    if (parseChannelKey(id) != null) return id
    if (!isChannelStyleId(id)) return null
    val addon = resolver.addonFor(id) ?: return null
    return channelKey(addon.id, id)
  }

  /**
   * true se [channelKey] identifica un canale live risolvibile: chiave valida,
   * addon dichiarato presente nell'elenco fornito e abilitato.
   */
  fun isLiveChannel(channelKey: String?, installedAddons: List<InstalledAddon>): Boolean {
    val (addonId, _) = parseChannelKey(channelKey) ?: return false
    val addon = installedAddons.firstOrNull { it.id.equals(addonId, ignoreCase = true) } ?: return false
    return addon.isEnabled
  }
}

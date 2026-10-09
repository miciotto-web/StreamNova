package com.example.data.stremio

import com.squareup.moshi.Json

/**
 * Costanti del protocollo Stremio (risorse, tipi, parametri extra).
 */
const val RESOURCE_STREAM = "stream"
const val RESOURCE_CATALOG = "catalog"
const val RESOURCE_META = "meta"
const val RESOURCE_SUBTITLES = "subtitles"
const val RESOURCE_ADDON_CATALOG = "addon_catalog"

const val TYPE_MOVIE = "movie"
const val TYPE_SERIES = "series"

const val EXTRA_SEARCH = "search"
const val EXTRA_GENRE = "genre"
const val EXTRA_SKIP = "skip"

/** Parametri extra ufficiali della risorsa `subtitles`. */
const val EXTRA_VIDEO_HASH = "videoHash"
const val EXTRA_VIDEO_SIZE = "videoSize"
const val EXTRA_FILENAME = "filename"

/**
 * Risorsa dichiarata da uno addon Stremio normalizzata.
 *
 * Nel protocollo Stremio le risorse possono essere dichiarate come semplici stringhe
 * (es. `["stream", "catalog"]`) oppure come oggetti che specificano tipi e prefissi ID
 * supportati a livello di singola risorsa (es. `[{"name": "stream", "types": ["movie"], "idPrefixes": ["tt"]}]`).
 *
 * @param name nome della risorsa ("stream", "catalog", "meta", ecc.).
 * @param types tipi supportati dalla risorsa (se null, eredita i tipi del manifest).
 * @param idPrefixes prefissi ID supportati dalla risorsa (se null, eredita quelli del manifest).
 */
data class StremioResource(
  val name: String,
  val types: List<String>? = null,
  val idPrefixes: List<String>? = null
) {
  fun supportsType(type: String): Boolean =
    types.isNullOrEmpty() || types.any { it.equals(type, ignoreCase = true) }

  fun supportsIdPrefix(idPrefix: String): Boolean =
    idPrefixes.isNullOrEmpty() || idPrefixes.any { it.equals(idPrefix, ignoreCase = true) }

  fun matchesId(id: String): Boolean {
    if (idPrefixes.isNullOrEmpty()) return true
    return idPrefixes.any { prefix -> id.startsWith(prefix, ignoreCase = true) }
  }
}

/**
 * Parametro extra accettato da un catalogo Stremio (es. "search", "genre", "skip").
 */
data class StremioCatalogExtra(
  val name: String? = null,
  @Json(name = "isRequired") val isRequired: Boolean = false,
  val options: List<String>? = null,
  val optionsLimit: Int? = null
)

/**
 * Definizione di un catalogo esposto da uno addon Stremio.
 *
 * @param type tipo di contenuto ("movie", "series", "anime", ecc.).
 * @param id identificatore del catalogo (es. "top", "trending", "imdbRating").
 * @param name nome descrittivo mostrato all'utente.
 * @param extra parametri extra supportati (oggetti o stringhe).
 * @param extraSupported elenco parametri supportati (formato legacy Stremio).
 * @param extraRequired elenco parametri obbligatori (formato legacy Stremio).
 * @param pageSize dimensione pagina opzionale.
 */
data class StremioCatalogDefinition(
  val type: String? = null,
  val id: String? = null,
  val name: String? = null,
  val extra: List<Any?>? = null,
  val extraSupported: List<String>? = null,
  val extraRequired: List<String>? = null,
  val pageSize: Int? = null
) {
  val isValid: Boolean
    get() = !type.isNullOrBlank() && !id.isNullOrBlank()

  val displayTitle: String
    get() = name?.takeIf { it.isNotBlank() } ?: id?.takeIf { it.isNotBlank() } ?: "Catalog"

  val effectiveType: String
    get() = type.orEmpty().trim()

  val effectiveId: String
    get() = id.orEmpty().trim()

  val isMovie: Boolean
    get() = effectiveType.equals(TYPE_MOVIE, ignoreCase = true)

  val isSeries: Boolean
    get() = effectiveType.equals(TYPE_SERIES, ignoreCase = true)

  /**
   * Converte [extra] in una lista tipizzata di [StremioCatalogExtra], gestendo sia
   * oggetti JSON che semplici stringhe.
   */
  fun extraList(): List<StremioCatalogExtra> = extra.orEmpty().mapNotNull { entry ->
    when (entry) {
      is String -> StremioCatalogExtra(name = entry)
      is Map<*, *> -> {
        val n = entry["name"] as? String ?: return@mapNotNull null
        val req = entry["isRequired"] as? Boolean ?: false
        val opt = (entry["options"] as? List<*>)?.mapNotNull { it as? String }
        val lim = (entry["optionsLimit"] as? Number)?.toInt()
        StremioCatalogExtra(name = n, isRequired = req, options = opt, optionsLimit = lim)
      }
      is StremioCatalogExtra -> entry
      else -> null
    }
  }

  val extras: List<StremioCatalogExtra>
    get() = extraList()

  /** Nomi di tutti i parametri extra supportati (unendo [extra] ed [extraSupported]). */
  fun supportedExtraNames(): List<String> {
    val fromExtra = extraList().mapNotNull { it.name?.trim()?.lowercase() }
    val fromLegacy = extraSupported.orEmpty().mapNotNull { it.trim().lowercase() }
    return (fromExtra + fromLegacy).distinct()
  }

  /** Nomi dei parametri extra obbligatori (unendo [extra] con isRequired ed [extraRequired]). */
  fun requiredExtraNames(): List<String> {
    val fromExtra = extraList().filter { it.isRequired }.mapNotNull { it.name?.trim()?.lowercase() }
    val fromLegacy = extraRequired.orEmpty().mapNotNull { it.trim().lowercase() }
    return (fromExtra + fromLegacy).distinct()
  }

  /** true se il parametro extra specificato è supportato. */
  fun supportsExtra(paramName: String): Boolean =
    supportedExtraNames().contains(paramName.trim().lowercase())

  /** true se il parametro extra specificato è obbligatorio. */
  fun isExtraRequired(paramName: String): Boolean =
    requiredExtraNames().contains(paramName.trim().lowercase())

  /** true se supporta la ricerca testuale (parametro "search"). */
  fun supportsSearch(): Boolean = supportsExtra(EXTRA_SEARCH)

  /** true se la ricerca testuale è obbligatoria per interrogare questo catalogo. */
  fun requiresSearch(): Boolean = isExtraRequired(EXTRA_SEARCH)

  /** true se supporta il filtro per genere (parametro "genre"). */
  fun supportsGenre(): Boolean = supportsExtra(EXTRA_GENRE)

  /** Opzioni genere dichiarate dal catalogo per il parametro "genre". */
  fun genreOptions(): List<String> =
    extraList().firstOrNull { it.name.equals(EXTRA_GENRE, ignoreCase = true) }?.options.orEmpty()

  /** true se supporta la paginazione / offset tramite "skip". */
  fun supportsSkip(): Boolean = supportsExtra(EXTRA_SKIP)

  /** true se il catalogo può essere interrogato senza parametri extra obbligatori. */
  fun canLoadWithoutArgs(): Boolean = requiredExtraNames().isEmpty()
}

/**
 * Manifest di uno addon Stremio (`GET $baseUrl/manifest.json`).
 *
 * @param resources risorse dichiarate dall'addon ("stream", "meta", "catalog"...).
 *        Nel protocollo Stremio ogni elemento può essere una stringa **oppure** un
 *        oggetto `{"name": "stream", "types": [...], "idPrefixes": [...]}`: per
 *        questo è tipizzato come [Any] e letto tramite [parsedResources].
 * @param idPrefixes prefissi degli id supportati a livello globale ("tt" = IMDb, "tmdb"...).
 * @param catalogs cataloghi esposti dall'addon.
 * @param addonCatalogs cataloghi per la scoperta di addon (opzionali).
 * @param behaviorHints hint opzionali (es. `configurable`/`configurationRequired`).
 */
data class StremioManifest(
  val id: String? = null,
  val name: String? = null,
  val version: String? = null,
  val description: String? = null,
  val resources: List<Any?>? = null,
  val types: List<String>? = null,
  val idPrefixes: List<String>? = null,
  val catalogs: List<StremioCatalogDefinition>? = null,
  val addonCatalogs: List<StremioCatalogDefinition>? = null,
  val behaviorHints: StremioManifestBehaviorHints? = null
) {
  /** Nome mostrato all'utente, con fallback su id. */
  val displayTitle: String
    get() = name?.takeIf { it.isNotBlank() } ?: id?.takeIf { it.isNotBlank() } ?: "Addon"

  /** true se l'addon dichiara di essere configurabile via web (Stremio addon v3). */
  val isConfigurable: Boolean
    get() = behaviorHints?.configurable == true || behaviorHints?.configurationRequired == true

  /**
   * Converte la lista [resources] in una lista tipizzata di [StremioResource].
   * Se la singola risorsa omette `types` o `idPrefixes`, eredita i valori globali del manifest.
   */
  fun parsedResources(): List<StremioResource> = resources.orEmpty().mapNotNull { entry ->
    when (entry) {
      is String -> StremioResource(
        name = entry,
        types = this.types,
        idPrefixes = this.idPrefixes
      )
      is Map<*, *> -> {
        val rName = entry["name"] as? String ?: return@mapNotNull null
        val rTypes = (entry["types"] as? List<*>)?.mapNotNull { it as? String } ?: this.types
        val rPrefixes = (entry["idPrefixes"] as? List<*>)?.mapNotNull { it as? String } ?: this.idPrefixes
        StremioResource(
          name = rName,
          types = rTypes,
          idPrefixes = rPrefixes
        )
      }
      is StremioResource -> entry
      else -> null
    }
  }

  /** Risorse dichiarate normalizzate a stringhe (`["stream", "meta", ...]`). */
  fun resourceNames(): List<String> = parsedResources().map { it.name }

  /** Trova la definizione di una specifica risorsa per nome (case-insensitive). */
  fun findResource(name: String): StremioResource? =
    parsedResources().firstOrNull { it.name.equals(name, ignoreCase = true) }

  /**
   * Tipi supportati per una risorsa: rispetta la dichiarazione specifica della risorsa
   * con fallback sui [types] globali del manifest.
   */
  fun typesForResource(resourceName: String): List<String>? =
    findResource(resourceName)?.types ?: this.types

  /**
   * Prefissi ID supportati per una risorsa: rispetta la dichiarazione specifica della risorsa
   * con fallback sugli [idPrefixes] globali del manifest.
   */
  fun idPrefixesForResource(resourceName: String): List<String>? =
    findResource(resourceName)?.idPrefixes ?: this.idPrefixes

  /**
   * Verifica se una risorsa supporta un dato tipo ("movie", "series", ecc.).
   * Se la risorsa o il manifest non dichiarano tipi specifici, assume supportato (true).
   */
  fun supportsTypeForResource(resourceName: String, type: String): Boolean {
    val res = findResource(resourceName)
    if (!resources.isNullOrEmpty() && res == null) return false
    val allowed = res?.types ?: this.types
    return allowed.isNullOrEmpty() || allowed.any { it.equals(type, ignoreCase = true) }
  }

  /**
   * Verifica se una risorsa supporta un dato prefisso ID ("tt", "tmdb", ecc.).
   * Se non sono specificati prefissi, assume supportato (true).
   */
  fun supportsIdPrefixForResource(resourceName: String, idPrefix: String): Boolean {
    val res = findResource(resourceName)
    if (!resources.isNullOrEmpty() && res == null) return false
    val allowed = res?.idPrefixes ?: this.idPrefixes
    return allowed.isNullOrEmpty() || allowed.any { it.equals(idPrefix, ignoreCase = true) }
  }

  /** true se l'addon espone la risorsa "stream". */
  fun supportsStream(): Boolean {
    val names = resourceNames()
    return names.isEmpty() || names.any { it.equals(RESOURCE_STREAM, ignoreCase = true) }
  }

  /** true se l'addon supporta la risorsa "stream" per il tipo indicato ("movie", "series"). */
  fun supportsStreamForType(type: String): Boolean {
    if (!supportsStream()) return false
    return supportsTypeForResource(RESOURCE_STREAM, type)
  }

  /** true se il tipo è supportato a livello globale dal manifest. */
  fun supportsType(type: String): Boolean =
    types.isNullOrEmpty() || types.any { it.equals(type, ignoreCase = true) }

  /**
   * true se l'addon espone la risorsa `subtitles`.
   * Un manifest che non dichiara risorse (addon legacy) viene considerato compatibile:
   * la verifica definitiva resta la risposta dell'endpoint.
   */
  fun supportsSubtitles(): Boolean {
    val names = resourceNames()
    return names.isEmpty() || names.any { it.equals(RESOURCE_SUBTITLES, ignoreCase = true) }
  }

  /** true se l'addon espone `subtitles` per il tipo indicato ("movie", "series"). */
  fun supportsSubtitlesForType(type: String): Boolean {
    if (!supportsSubtitles()) return false
    return supportsTypeForResource(RESOURCE_SUBTITLES, type)
  }

  /** true se l'addon espone la risorsa "catalog" o dichiara cataloghi espliciti. */
  fun supportsCatalog(): Boolean {
    if (!catalogs.isNullOrEmpty()) return true
    val names = resourceNames()
    return names.isEmpty() || names.any { it.equals(RESOURCE_CATALOG, ignoreCase = true) }
  }

  /** true se l'addon supporta cataloghi per il tipo indicato ("movie", "series"). */
  fun supportsCatalogForType(type: String): Boolean {
    if (!supportsCatalog()) return false
    if (!catalogs.isNullOrEmpty()) {
      return catalogs.any { it.isValid && it.type.equals(type, ignoreCase = true) }
    }
    return supportsTypeForResource(RESOURCE_CATALOG, type)
  }

  /** Elenco dei cataloghi validi esposti dall'addon. */
  fun validCatalogs(): List<StremioCatalogDefinition> =
    catalogs.orEmpty().filter { it.isValid }

  /** Elenco dei cataloghi addon validi. */
  fun validAddonCatalogs(): List<StremioCatalogDefinition> =
    addonCatalogs.orEmpty().filter { it.isValid }

  /** Cataloghi filtrati per tipo ("movie", "series", ecc.). */
  fun catalogsForType(type: String): List<StremioCatalogDefinition> =
    validCatalogs().filter { it.effectiveType.equals(type, ignoreCase = true) }

  /** Cataloghi film. */
  fun movieCatalogs(): List<StremioCatalogDefinition> =
    catalogsForType(TYPE_MOVIE)

  /** Cataloghi serie TV. */
  fun seriesCatalogs(): List<StremioCatalogDefinition> =
    catalogsForType(TYPE_SERIES)

  /** Cerca un catalogo specifico per type e id. */
  fun findCatalog(type: String, id: String): StremioCatalogDefinition? =
    validCatalogs().firstOrNull {
      it.effectiveType.equals(type, ignoreCase = true) && it.effectiveId.equals(id, ignoreCase = true)
    }
}

/** Risposta dell'endpoint catalog (`GET $baseUrl/catalog/$type/$id[/$extra].json`). */
data class StremioCatalogResponse(
  val metas: List<StremioMetaItem>? = null
)

/**
 * Singolo elemento (metadato) restituito da un catalogo Stremio.
 */
data class StremioMetaItem(
  val id: String? = null,
  val type: String? = null,
  val name: String? = null,
  val poster: String? = null,
  val posterShape: String? = null,
  val background: String? = null,
  val banner: String? = null,
  val logo: String? = null,
  val description: String? = null,
  val releaseInfo: Any? = null,
  val imdbRating: Any? = null,
  val genres: List<String>? = null,
  val genre: List<String>? = null
) {
  val displayTitle: String
    get() = name?.takeIf { it.isNotBlank() } ?: id.orEmpty()

  val effectivePoster: String?
    get() = poster?.takeIf { it.isNotBlank() }

  val effectiveBackdrop: String?
    get() = background?.takeIf { it.isNotBlank() } ?: banner?.takeIf { it.isNotBlank() }

  val isMovie: Boolean
    get() = type.equals(TYPE_MOVIE, ignoreCase = true)

  val isSeries: Boolean
    get() = type.equals(TYPE_SERIES, ignoreCase = true)

  val formattedReleaseInfo: String?
    get() = when (val ri = releaseInfo) {
      is String -> ri.takeIf { it.isNotBlank() }
      is Number -> ri.toString()
      else -> null
    }

  val year: Int?
    get() = formattedReleaseInfo?.take(4)?.toIntOrNull()

  val formattedRating: String?
    get() = when (val r = imdbRating) {
      is String -> r.takeIf { it.isNotBlank() }
      is Number -> r.toString()
      else -> null
    }

  val ratingFloat: Float?
    get() = when (val r = imdbRating) {
      is Number -> r.toFloat()
      is String -> r.toFloatOrNull()
      else -> null
    }

  val genreList: List<String>
    get() = genres?.filter { it.isNotBlank() }
      ?: genre?.filter { it.isNotBlank() }
      ?: emptyList()
}

/** Risposta di `$baseUrl/stream/$type/$id.json`. */
data class StremioStreamResponse(
  val streams: List<StremioStreamItem>? = null
)

/**
 * Singolo stream restituito da uno addon.
 *
 * Gli addon possono restituire:
 *  - `url` diretto (addon già configurato con un debrid/CDN),
 *  - `infoHash` + `fileIdx` (torrent "grezzo") da risolvere con TorBox,
 *  - `subtitles[]`: sottotitoli esterni già abbinati a quello stream.
 *
 * `subtitles` e' dichiarato con elementi nullable perche' un addon può mandare un
 * `null` nell'array: in quel caso l'elemento viene scartato da [subtitleList] senza
 * far fallire il parsing dell'intero stream.
 *
 * `behaviorHints` (in particolare `proxyHeaders.request`) contiene header HTTP
 * opzionali (User-Agent/Referer) necessari a riprodurre stream protetti: vanno
 * propagati al player per evitare errori `HTTP 403`.
 */
data class StremioStreamItem(
  val name: String? = null,
  val title: String? = null,
  /**
   * Descrizione estesa dello stream. Nel protocollo Stremio `title` è deprecato
   * in favore di `description`: alcuni addon (es. Comet) popolano **solo**
   * `description` con il titolo formattato e i dettagli (qualità, size, peer).
   */
  val description: String? = null,
  val url: String? = null,
  val infoHash: String? = null,
  @Json(name = "fileIdx") val fileIdx: Int? = null,
  val behaviorHints: StremioStreamBehaviorHints? = null,
  val subtitles: List<StremioSubtitle?>? = null
) {
  /** Etichetta combinata usata per estrarre la qualità e mostrare l'origine. */
  val label: String
    get() = listOfNotNull(name, title, description).filter { it.isNotBlank() }.joinToString(" • ")

  /**
   * Testo combinato di tutti i campi descrittivi (nome, titolo, descrizione,
   * filename degli `behaviorHints`) usato per l'estrazione euristica di qualità,
   * codec e tipo di rilascio: nessun addon è tagliato fuori a seconda di quale
   * campo popola.
   */
  val descriptiveText: String
    get() = listOfNotNull(name, title, description, behaviorHints?.filename)
      .filter { it.isNotBlank() }
      .joinToString(" ")

  /**
   * Sottotitoli utilizzabili dichiarati dallo stream: elementi `null` o incompleti
   * (senza `id`, `url` o `lang`) vengono scartati, gli altri restano.
   *
   * E' una SORGENTE distinta da `/subtitles/{type}/{id}.json`: qui i sottotitoli sono
   * gia' abbinati allo stream, li' sono richiesti per il solo contenuto.
   */
  val subtitleList: List<StremioSubtitle> get() = StremioSubtitleParser.usable(subtitles)

  /** true se lo stream dichiara almeno un sottotitolo utilizzabile. */
  val hasSubtitles: Boolean get() = subtitleList.isNotEmpty()

  /**
   * Header HTTP richiesti dallo stream (`behaviorHints.proxyHeaders.request`),
   * già pronti per essere passati al player. Mappa vuota se non dichiarati.
   */
  val proxyHeaders: Map<String, String>
    get() = behaviorHints?.proxyHeaders?.request.orEmpty()
}

/**
 * Hint opzionali di un addon (manifest `behaviorHints`, protocollo Stremio v3).
 *
 * @param configurable true se l'addon si configura tramite una pagina web.
 * @param configurationRequired true se il manifest richiede una configurazione.
 * @param newEpisodeNotifications true se l'addon invia notifiche di nuovi episodi.
 */
data class StremioManifestBehaviorHints(
  val configurable: Boolean? = null,
  @Json(name = "configurationRequired") val configurationRequired: Boolean? = null,
  @Json(name = "newEpisodeNotifications") val newEpisodeNotifications: Boolean? = null
)

/**
 * Hint opzionali di uno stream (`behaviorHints`, protocollo Stremio v3).
 *
 * @param notWebReady true se lo stream non è riproducibile via web.
 * @param bingeGroup gruppo di binge-watching.
 * @param countryWhitelist paesi in cui lo stream è disponibile.
 * @param proxyHeaders header HTTP di richiesta/risposta richiesti dallo stream.
 * @param videoHash hash del file video (utile per i sottotitoli).
 * @param videoSize dimensione del file video in byte.
 * @param filename nome del file video.
 */
data class StremioStreamBehaviorHints(
  @Json(name = "notWebReady") val notWebReady: Boolean? = null,
  @Json(name = "bingeGroup") val bingeGroup: String? = null,
  @Json(name = "countryWhitelist") val countryWhitelist: List<String>? = null,
  @Json(name = "proxyHeaders") val proxyHeaders: StremioProxyHeaders? = null,
  @Json(name = "videoHash") val videoHash: String? = null,
  @Json(name = "videoSize") val videoSize: Long? = null,
  @Json(name = "filename") val filename: String? = null
)

/**
 * Header proxy dichiarati da uno stream Stremio.
 *
 * @param request header da inviare nella richiesta HTTP del player (User-Agent, Referer...).
 * @param response header attesi nella risposta (informativo).
 */
data class StremioProxyHeaders(
  val request: Map<String, String>? = null,
  val response: Map<String, String>? = null
)

/**
 * Risposta di `$baseUrl/subtitles/$type/$id.json`.
 *
 * @param subtitles elenco sottotitoli: assente o `null` significa "nessun sottotitolo".
 */
data class StremioSubtitleResponse(
  val subtitles: List<StremioSubtitle>? = null
)

/**
 * Sottotitolo esterno restituito da un addon (protocollo Stremio, risorsa `subtitles`).
 *
 * @param id identificativo del sottotitolo secondo l'addon.
 * @param url URL assoluto del file sottotitolo.
 * @param lang lingua dichiarata (es. "ita", "eng").
 * @param label etichetta opzionale mostrata all'utente.
 */
data class StremioSubtitle(
  val id: String? = null,
  val url: String? = null,
  val lang: String? = null,
  val label: String? = null
) {
  val effectiveId: String?
    get() = id?.trim()?.takeIf { it.isNotBlank() }

  val effectiveUrl: String?
    get() = url?.trim()?.takeIf { it.isNotBlank() }

  val effectiveLang: String?
    get() = lang?.trim()?.takeIf { it.isNotBlank() }

  /** Etichetta mostrata: quella dell'addon quando presente, altrimenti la lingua. */
  val displayLabel: String
    get() = label?.takeIf { it.isNotBlank() } ?: effectiveLang.orEmpty()

  /** Lingua normalizzata, con fallback su "und" quando l'addon non la dichiara. */
  val normalizedLang: String
    get() = effectiveLang?.lowercase() ?: LANG_UNKNOWN

  /**
   * true se l'elemento è utilizzabile: servono `id`, `url` e `lang`.
   * Un elemento incompleto viene scartato, senza invalidare gli altri.
   */
  val isValid: Boolean
    get() = effectiveId != null && effectiveUrl != null && effectiveLang != null

  companion object {
    /** Lingua usata quando l'addon non dichiara `lang` (RFC 5646 "undetermined"). */
    const val LANG_UNKNOWN = "und"
  }
}

/**
 * Parametri extra ufficiali della risorsa `subtitles`.
 *
 * Sono opzionali: quando l'integratore conosce i dati del file video li invia, cosi'
 * l'addon puo' restituire sottotitoli allineati alla release esatta. Nessun valore
 * vuoto viene mandato: il path dell'endpoint resta pulito.
 *
 * @param videoHash hash del file video.
 * @param videoSize dimensione del file video in byte.
 * @param filename nome del file video.
 */
data class StremioSubtitleOptions(
  val videoHash: String? = null,
  val videoSize: Long? = null,
  val filename: String? = null
) {
  /**
   * Parametri extra utilizzabili nel path, con i valori vuoti scartati.
   * `videoSize` e' accettato solo se positivo.
   */
  fun toExtra(): Map<String, String> {
    val extra = LinkedHashMap<String, String>()
    videoHash?.trim()?.takeIf { it.isNotEmpty() }?.let { extra[EXTRA_VIDEO_HASH] = it }
    videoSize?.takeIf { it > 0L }?.let { extra[EXTRA_VIDEO_SIZE] = it.toString() }
    filename?.trim()?.takeIf { it.isNotEmpty() }?.let { extra[EXTRA_FILENAME] = it }
    return extra
  }
}

/**
 * Addon installato e persistito dall'utente.
 *
 * @param baseUrl URL normalizzato della base dell'addon (senza `/manifest.json`).
 * @param manifest manifest scaricato e validato all'installazione.
 * @param isEnabled stato dello switch nella schermata Addon.
 */
data class InstalledAddon(
  val baseUrl: String,
  val manifest: StremioManifest,
  val isEnabled: Boolean = true
) {
  /** Chiave stabile per la rimozione/identità (manifest.id, con fallback sull'URL). */
  val id: String
    get() = manifest.id?.takeIf { it.isNotBlank() } ?: baseUrl
}

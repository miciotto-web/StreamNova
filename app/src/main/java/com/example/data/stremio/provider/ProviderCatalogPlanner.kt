package com.example.data.stremio.provider

import com.example.data.model.MediaType
import com.example.data.stremio.EXTRA_GENRE
import com.example.data.stremio.StremioCatalogTarget
import com.example.data.stremio.TYPE_MOVIE
import com.example.data.stremio.TYPE_SERIES

/**
 * Provenienza di un singolo titolo all'interno di un catalogo Provider.
 *
 * Un titolo può arrivare da più addon e più cataloghi: la deduplicazione deve
 * CONSERVARE queste origini invece di scartarle. [MediaItem] non viene modificato,
 * quindi la provenienza vive qui, a livello data layer.
 */
data class CatalogProvenance(
  val addonManifestId: String,
  val catalogId: String,
  val type: String
) {
  val stableId: String get() = "$addonManifestId|$type|$catalogId"
}

/**
 * Catalogo Stremio associato a un provider da un binding DICHIARATO.
 *
 * @param target target del catalogo (addon + definizione).
 * @param binding binding confermato che ha prodotto l'associazione.
 * @param platformExtra extra di PIATTAFORMA dichiarati dal manifest del cui valore
 *        coincide col provider (es. `provider=Prime Video`): vanno inviati altrimenti
 *        il catalogo restituirebbe contenuti di TUTTE le piattaforme.
 */
data class ResolvedProviderTarget(
  val target: StremioCatalogTarget,
  val binding: ProviderBinding,
  val platformExtra: Map<String, String> = emptyMap()
) {
  /** Chiave completa: addonManifestId + type + catalogId. */
  val catalogKey: CatalogKey get() = binding.key

  /** Tipo Stremio del catalogo ("movie" / "series"). */
  val type: String get() = target.type

  /** Tipo MediaType dell'app, `null` per tipi non supportati. */
  val mediaType: MediaType?
    get() = when (type.trim().lowercase()) {
      TYPE_MOVIE -> MediaType.FILM
      TYPE_SERIES -> MediaType.SERIE_TV
      else -> null
    }

  /** Identificatore univoco del target: usato come chiave di paginazione. */
  val targetId: String get() = target.id

  val supportsSkip: Boolean get() = target.supportsSkip
}

/**
 * Piano di caricamento di un provider: i soli cataloghi Stremio **confermati**.
 *
 * @param providerId slug normalizzato del provider.
 * @param mediaType tipo richiesto, `null` per entrambi.
 * @param resolved target risolti con il loro binding.
 * @param unmappedTargets target dichiarati ma non associabili al provider.
 * @param fallbackToDefault true se l'intero piano deriva dal FALLBACK di sistema
 *        (Cinemeta): usato SOLO quando non è attivo nessun addon di catalogo
 *        personalizzato e nessun binding dichiarato copre il provider. Non può
 *        valere copertura `FULL`.
 */
data class ProviderCatalogPlan(
  val providerId: String,
  val mediaType: MediaType? = null,
  val resolved: List<ResolvedProviderTarget> = emptyList(),
  val unmappedTargets: Int = 0,
  val fallbackToDefault: Boolean = false
) {
  /** true se esiste almeno un binding confermato: abilita l'override sul catalogo nativo. */
  val hasConfirmedBindings: Boolean get() = resolved.isNotEmpty()

  /** Cataloghi di tipo film (separati dai serie per costruzione). */
  val movieTargets: List<ResolvedProviderTarget>
    get() = resolved.filter { it.mediaType == MediaType.FILM }

  /** Cataloghi di tipo serie. */
  val seriesTargets: List<ResolvedProviderTarget>
    get() = resolved.filter { it.mediaType == MediaType.SERIE_TV }
}

/**
 * Pianificatore puro: trasforma i cataloghi dichiarati dagli addon in un piano di
 * caricamento per provider, usando SOLO binding confermati (dichiarati con
 * [ProviderCatalogResolver.resolve] oppure iniettati da [onBindMissing]).
 *
 * È deliberatamente una funzione pura (nessuna rete, nessun singleton): tutto il
 * decisione di associazione è verificabile in test JVM.
 *
 * REGOLE
 * 1. **Tipo obbligatorio**: con [mediaType] non nullo si caricano solo cataloghi di
 *    quel tipo. Un binding `movie` non vale mai per `series` e viceversa.
 * 2. **Multi-addon**: la chiave resta `addonManifestId + type + catalogId`, quindi due
 *    addon che espongono lo stesso `catalogId` producono due target distinti.
 * 3. **Nessuna euristica**: `resolve` può restituire solo USER o REGISTRY, quindi
 *    [HeuristicProviderCatalogProposer] non può mai contribuire a un piano.
 * 4. **Binding di terze parti scartato**: se il binding restituito punta a un altro
 *    provider, il target viene ignorato invece di essere caricato per il provider sbagliato.
 * 5. **Cataloghi non interrogabili senza argomenti** (richiedono `search`) o con
 *    `genre` obbligatorio senza opzioni sono scartati: non possono alimentare un
 *    catalogo Provider.
 * 6. **Binding iniettato**: solo se il resolver non ha una dichiarazione per la
 *    chiave può intervenire [onBindMissing], che il chiamante usa SOLO per
 *    (a) il match sui cataloghi di un addon di catalogo personalizzato attivo e
 *    (b) il fallback Cinemeta quando nessun addon personalizzato è attivo
 *    (vedi [ProviderCatalogSourcePolicy]). Un binding iniettato sbagliato per
 *    provider o per chiave viene scartato dalla stessa difesa del punto 4.
 */
object ProviderCatalogPlanner {

  fun plan(
    providerId: String,
    targets: List<StremioCatalogTarget>,
    resolver: ProviderCatalogResolver,
    mediaType: MediaType? = null,
    onBindMissing: (StremioCatalogTarget) -> ProviderBinding? = { null },
    platformExtra: (StremioCatalogTarget) -> Map<String, String> = { emptyMap() }
  ): ProviderCatalogPlan {
    val normalizedProviderId = providerId.trim().lowercase()
    var unmapped = 0

    val resolved = targets
      .mapNotNull { target ->
        // 1. La separazione film/serie resta strutturale: un catalogo di tipo diverso da
        //    quello richiesto non entra mai nel piano, ma resta conteggiato tra i non mappati.
        if (!matchesRequestedType(target.type, mediaType)) {
          unmapped++
          return@mapNotNull null
        }
        val key = CatalogKey.of(target.addonId, target.type, target.catalogId)
        if (key == null) {
          unmapped++
          return@mapNotNull null
        }
        // 3. Prima la dichiarazione (USER/REGISTRY); il resolver non sintetizza nulla.
        // 6. Solo se assente interviene il binding iniettato dal chiamante.
        val binding = resolver.resolve(key) ?: onBindMissing(target)
        if (binding == null) {
          unmapped++
          return@mapNotNull null
        }
        // 4. Difesa: il binding (dichiarato o iniettato) deve riferirsi a QUESTO
        //    provider e a QUESTA chiave, mai a un altro catalogo o piattaforma.
        if (binding.normalizedProviderId != normalizedProviderId ||
          binding.key.stableId != key.stableId
        ) {
          unmapped++
          return@mapNotNull null
        }
        // 5. Catalogo utilizzabile come listato provider?
        val definition = target.catalog
        if (definition.requiresSearch()) return@mapNotNull null
        if (definition.isExtraRequired(EXTRA_GENRE) && definition.genreOptions().isEmpty()) {
          return@mapNotNull null
        }
        ResolvedProviderTarget(
          target = target,
          binding = binding,
          platformExtra = platformExtra(target)
        )
      }
      // Ordine stabile: multi-addon riproducibile tra build diverse.
      .sortedWith(compareBy({ it.target.addonId }, { it.type }, { it.target.catalogId }))

    return ProviderCatalogPlan(
      providerId = normalizedProviderId,
      mediaType = mediaType,
      resolved = resolved,
      unmappedTargets = unmapped
    )
  }

  /**
   * Extra da inviare per un target risolto.
   *
   * 1. **Extra di piattaforma** ([ResolvedProviderTarget.platformExtra]): valori
   *    dichiarati dal manifest (`provider` / `platform` / `genre`) che nominano il
   *    provider: senza inviarli il catalogo restituirebbe contenuti di TUTTE le
   *    piattaforme etichettati come del provider.
   * 2. **`genre` obbligatorio**: prima opzione dichiarata come valore neutro, così
   *    l'endpoint resta valido. Decide in modo dichiarato dal manifest, senza
   *    supposizioni sul provider.
   */
  fun extraFor(target: ResolvedProviderTarget): Map<String, String> {
    val catalog = target.target.catalog
    val result = LinkedHashMap<String, String>()

    result.putAll(target.platformExtra)

    if (catalog.isExtraRequired(EXTRA_GENRE) && !result.containsKey(EXTRA_GENRE)) {
      val first = catalog.genreOptions().firstOrNull()?.takeIf { it.isNotBlank() }
      if (first != null) result[EXTRA_GENRE] = first
    }
    return result
  }

  private fun matchesRequestedType(catalogType: String, requested: MediaType?): Boolean {
    val type = catalogType.trim().lowercase()
    return when (requested) {
      null -> true
      MediaType.FILM -> type == TYPE_MOVIE
      MediaType.SERIE_TV -> type == TYPE_SERIES
      else -> false
    }
  }
}

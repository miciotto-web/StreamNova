package com.example.data.repository

import com.example.data.model.MediaItem

/**
 * Identità di un titolo nel catalogo: riconosce quando due schede diverse sono lo stesso
 * contenuto. È il punto unico che decide "stesso titolo", condiviso da:
 *
 * - **Continua a guardare** e **Preferiti**: una sola card per titolo anche quando il
 *   catalogo contiene due schede dello stesso film/serie (una con id TMDB, una con id
 *   IMDb/Stremio) — la causa reale di un duplicato a schermo;
 * - **Rimozione da Continua a guardare**: la card rimossa è il TITOLO, non una singola riga,
 *   quindi la rimozione deve coprire tutte le schede dello stesso contenuto (altrimenti la
 *   scheda "gemella" con un progresso rimasto la fa riapparire al giro successivo);
 * - **Sincronizzazione SIMKL**: la posizione remota va scritta sulla scheda che l'utente ha
 *   usato per ultima ([newestStateById]), non su una riga qualsiasi del gruppo.
 *
 * Due livelli di chiavi, con scopi diversi:
 *
 * - [strongKeys] — solo identificativi forti (`tmdb`, `imdb`). È il livello su cui si
 *   confronta lo STATO UTENTE (progresso, posizione, preferito): unire per titolo due
 *   contenuti diversi perderebbe progressi reali, quindi qui non si indovina mai.
 * - [displayKeys] — chiavi forti più titolo normalizzato (titolo e titolo originale) + anno.
 *   È il livello su cui si deduplica ciò che si vede: la stessa regola con cui il catalogo
 *   unisce Stremio e TMDB ([StremioCatalogRepository.deduplicateCrossSource]) e con cui la
 *   sincronizzazione riconosce un titolo quando manca l'identificativo ([SimklLocalMatcher]).
 *   Un anno noto da entrambe le parti deve coincidere, così un remake non viene confuso con
 *   l'originale.
 *
 * L'id IMDb non è una colonna di `CachedMediaItemEntity` ([MediaItem.imdbId] si perde al
 * riavvio): quando è assente si usa l'id stesso se ha la forma `tt…`, che è come il catalogo
 * Stremio/Cinemeta identifica i suoi titoli.
 */
object MediaIdentity {

  /** Id IMDb della scheda: il campo esplicito, oppure l'id quando è già un `tt…`. */
  fun imdbIdOf(item: MediaItem): String? {
    item.imdbId?.takeIf { it.isNotBlank() }?.let { return it.lowercase() }
    val id = item.id.trim().substringBefore(':')
    return id.lowercase().takeIf { it.startsWith("tt") && it.length > 2 }
  }

  /** Chiavi di identità FORTE: TMDB o IMDb, mai il titolo. */
  fun strongKeys(item: MediaItem): Set<String> {
    val keys = mutableSetOf<String>()
    item.tmdbId?.takeIf { it > 0 }?.let { keys += "${typeKey(item)}|tmdb:$it" }
    imdbIdOf(item)?.let { keys += "${typeKey(item)}|imdb:$it" }
    return keys
  }

  /** Chiavi per la deduplicazione a schermo: forti + titolo normalizzato + anno. */
  fun displayKeys(item: MediaItem): Set<String> = buildSet {
    addAll(strongKeys(item))
    titleKey(item, item.title)?.let { add(it) }
    titleKey(item, item.originalTitle)?.let { add(it) }
  }

  private fun typeKey(item: MediaItem): String = item.type.name.lowercase()

  /** La chiave porta il TIPO: un film e una serie con lo stesso titolo non sono la stessa card. */
  private fun titleKey(item: MediaItem, rawTitle: String): String? {
    val normalized = normalizeTitle(rawTitle)
    if (normalized.isEmpty()) return null
    return "${typeKey(item)}|${item.year.takeIf { it > 0 } ?: 0}|title:$normalized"
  }

  /**
   * Minuscole, senza punteggiatura, spazi compattati: "Stranger Things" == "stranger  things".
   */
  fun normalizeTitle(raw: String): String = raw
    .lowercase()
    .map { if (it.isLetterOrDigit()) it else ' ' }
    .joinToString("")
    .trim()
    .replace(Regex("\\s+"), " ")

  /** true se le due schede sono lo stesso titolo secondo le chiavi a schermo. */
  fun isSameTitle(a: MediaItem, b: MediaItem): Boolean {
    if (a.type != b.type) return false
    val aKeys = displayKeys(a)
    return displayKeys(b).any { it in aKeys }
  }

  /**
   * Le schede dello stesso contenuto di [seed] (stesso tipo, una chiave a schermo in comune).
   * Il confronto è diretto con [seed]: una catena di somiglianze non allarga il gruppo.
   */
  fun group(items: List<MediaItem>, seed: MediaItem): List<MediaItem> {
    val keys = displayKeys(seed)
    return items.filter { candidate -> isSameType(candidate, seed) && displayKeys(candidate).any { it in keys } }
  }

  /** Le schede dello stesso contenuto a partire da un id; `emptyList()` se l'id è ignoto. */
  fun groupById(items: List<MediaItem>, id: String): List<MediaItem> {
    val seed = items.firstOrNull { it.id == id } ?: return emptyList()
    return group(items, seed)
  }

  /** Le schede dello stesso contenuto secondo le chiavi FORTI (mai per titolo). */
  fun strongGroup(items: List<MediaItem>, seed: MediaItem): List<MediaItem> {
    val keys = strongKeys(seed)
    if (keys.isEmpty()) return listOf(seed)
    return items.filter { candidate ->
      isSameType(candidate, seed) && strongKeys(candidate).any { it in keys }
    }
  }

  private fun isSameType(candidate: MediaItem, seed: MediaItem): Boolean = candidate.type == seed.type

  /**
   * La scheda con lo stato utente **più recente** (maggiore `lastWatchedAt` fra quelle con
   * progresso; a parità di data, il progresso maggiore). `null` se nessuna scheda del gruppo
   * ha un progresso.
   */
  fun newestState(items: List<MediaItem>): MediaItem? = items
    .filter { it.currentProgressMs > 0L }
    .maxWithOrNull(compareBy({ it.lastWatchedAt ?: 0L }, { it.currentProgressMs }))

  /**
   * La scheda dello stesso contenuto con lo stato utente più recente, a partire da un id:
   * la riga su cui scrivere il progresso che la UI mostrerà. Quando nessuna scheda del
   * gruppo ha un progresso si conserva la scheda indicata (nessuna riga viene spostata a
   * vuoto). Un id ignoto restituisce `null`.
   */
  fun newestStateById(items: List<MediaItem>, id: String): MediaItem? {
    val seed = items.firstOrNull { it.id == id } ?: return null
    return newestState(strongGroup(items, seed)) ?: seed
  }

  /**
   * Deduplicazione di «Continua a guardare»: una scheda per titolo, quella con il progresso
   * più recente. Le schede senza progresso non entrano nell'elenco e l'ordine della lista
   * d'ingresso è preservato (il consumatore ordina poi per data di visione).
   */
  fun dedupeByProgress(items: List<MediaItem>): List<MediaItem> {
    val winners = identityGroups(items).mapNotNull { newestState(it)?.id }.toSet()
    return items.filter { it.id in winners }
  }

  /**
   * Deduplicazione dei Preferiti: una scheda per titolo, scelta fra quelle preferite (il
   * preferito più recente); un gruppo senza preferiti non entra nell'elenco.
   */
  fun dedupeFavorites(items: List<MediaItem>): List<MediaItem> {
    val winners = identityGroups(items).mapNotNull { group ->
      val favorites = group.filter { it.isFavorite }
      if (favorites.isEmpty()) null else (newestState(favorites) ?: favorites.first()).id
    }.toSet()
    return items.filter { it.id in winners }
  }

  /**
   * Raggruppa per identità a schermo. Le chiavi di ogni scheda vengono registrate sul gruppo
   * della prima chiave già vista: due schede con una chiave in comune finiscono nello stesso
   * gruppo; una scheda con due chiavi appartenenti a gruppi diversi si unisce al primo
   * (nessuna fusione di gruppi già formati, quindi nessun allargamento a catena).
   */
  private fun identityGroups(items: List<MediaItem>): List<List<MediaItem>> {
    val groupOfKey = HashMap<String, Int>()
    val groups = mutableListOf<MutableList<MediaItem>>()
    for (item in items) {
      val keys = displayKeys(item)
      val existing = keys.firstNotNullOfOrNull { groupOfKey[it] }
      val index = if (existing != null) {
        existing
      } else {
        groups.add(mutableListOf())
        groups.lastIndex
      }
      groups[index] += item
      keys.forEach { key -> groupOfKey.putIfAbsent(key, index) }
    }
    return groups
  }
}

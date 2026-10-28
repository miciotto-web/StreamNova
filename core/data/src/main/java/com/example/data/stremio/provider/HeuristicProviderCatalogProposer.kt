package com.example.data.stremio.provider

import com.example.data.stremio.StremioManifest
import com.example.data.stremio.TYPE_MOVIE
import com.example.data.stremio.TYPE_SERIES

/**
 * Proposer euristico: analizza un manifest e RESTITUISCE SOLO [ProviderBindingProposal].
 *
 * REGOLA FONDAMENTALE
 * Questo componente non produce [ProviderBinding] e non scrive in nessuna sorgente.
 * Una proposta che coincidesse con un binding applicabile sarebbe indistinguibile
 * da una scelta dell'utente: per questo i due tipi sono separati a livello di tipo.
 *
 * SEGNALI UTILIZZATI (nessuno sufficiente da solo, salvo soglia)
 * - alias del provider, con **confine di token** (mai match di sottostringa grezza:
 *   "max" non deve matchare "maxwell", "apple" non deve matchare "pineapple");
 * - presenza dell'alias nel `catalogId`;
 * - presenza dell'alias nel titolo visualizzato del catalogo;
 * - presenza dell'alias nel nome dell'addon (segnale debole: da solo non basta);
 * - copertura `movie` + `series` dello stesso brand nel manifest (segnale di gruppo);
 * - tipo del catalogo (usato per la copertura e per segnalare i cataloghi search-only).
 */
class HeuristicProviderCatalogProposer : ProviderBindingProposer {

  override fun propose(
    manifest: StremioManifest,
    providers: List<ProviderAliasProvider>
  ): List<ProviderBindingProposal> {
    val addonId = manifest.id?.trim().orEmpty()
    if (addonId.isEmpty()) return emptyList()

    val catalogs = manifest.validCatalogs()
    if (catalogs.isEmpty() || providers.isEmpty()) return emptyList()

    val addonName = manifest.displayTitle

    // 1) Score grezzo per ogni (catalogo, provider).
    //    `matchedTypesByProvider` raccoglie i tipi coperti: serve al bonus movie+series.
    val scored = mutableListOf<ScoredMatch>()
    val matchedTypesByProvider = mutableMapOf<String, MutableSet<String>>()

    for (provider in providers) {
      val providerId = provider.providerId.trim()
      if (providerId.isEmpty()) continue
      val aliases = providerAliases(provider)
      if (aliases.isEmpty()) continue

      for (catalog in catalogs) {
        val key = CatalogKey.of(addonId, catalog.effectiveType, catalog.effectiveId) ?: continue
        val idText = catalog.effectiveId
        val titleText = catalog.displayTitle
        // Il titolo coincide con l'id quando il manifest non dichiara `name`:
        // in quel caso il segnale non va contato due volte.
        val titleIsDistinct = !titleText.equals(idText, ignoreCase = true)

        var score = 0.0
        val reasons = mutableListOf<String>()

        val idAlias = aliases.firstOrNull { matchesTokenBoundary(it, idText) }
        if (idAlias != null) {
          score += WEIGHT_CATALOG_ID
          reasons += "catalogId contiene l'alias '$idAlias'"
        }

        var titleAlias: String? = null
        if (titleIsDistinct) {
          titleAlias = aliases.firstOrNull { matchesTokenBoundary(it, titleText) }
          if (titleAlias != null) {
            score += WEIGHT_TITLE
            reasons += "titolo '$titleText' contiene l'alias '$titleAlias'"
          }
        }

        // Segnale debole: da solo non raggiunge la soglia, quindi un addon dal nome
        // simile a un brand non genera proposte per tutti i suoi cataloghi.
        val addonAlias = aliases.firstOrNull { matchesTokenBoundary(it, addonName) }
        if (addonAlias != null) {
          score += WEIGHT_ADDON_NAME
          reasons += "nome addon '$addonName' contiene l'alias '$addonAlias'"
        }

        if (score <= 0.0) continue

        // La copertura movie+series si calcola sulle corrispondenze strutturali
        // (id o titolo), non sul nome addon: altrimenti un addon omonimo
        // farebbe apparire completo qualunque provider.
        if (idAlias != null || titleAlias != null) {
          matchedTypesByProvider
            .getOrPut(providerId.lowercase()) { mutableSetOf() }
            .add(catalog.effectiveType.trim().lowercase())
        }

        if (catalog.requiresSearch()) {
          reasons += "il catalogo richiede il parametro 'search': caricamento solo su query"
        }

        scored += ScoredMatch(key, providerId, score, reasons)
      }
    }

    // 2) Bonus di gruppo per i provider coperti da entrambi i tipi.
    val enriched = scored.map { match ->
      val types = matchedTypesByProvider[match.providerId.lowercase()].orEmpty()
      val coversBoth = types.contains(TYPE_MOVIE) && types.contains(TYPE_SERIES)
      if (!coversBoth) {
        match
      } else {
        match.copy(
          score = (match.score + PAIR_BONUS).coerceAtMost(1.0),
          reasons = match.reasons + "il provider '${match.providerId}' copre sia movie sia series nel manifest"
        )
      }
    }

    // 3) Soglia di confidenza + deduplica per (catalogo, provider).
    return ProviderBindingProposal.sortedByConfidence(
      enriched
        .filter { it.score >= MIN_SCORE }
        .map {
          ProviderBindingProposal(
            key = it.key,
            providerId = it.providerId,
            score = it.score.coerceIn(0.0, 1.0),
            reasons = it.reasons.distinct()
          )
        }
        .distinctBy { "${it.key.stableId}|${it.normalizedProviderId}" }
    )
  }

  /**
   * Alias effettivi del provider: quelli dichiarativi più [ProviderAliasProvider.providerId]
   * e il nome visualizzato. Sono dati, non regole: nessun `when` sul nome del provider.
   */
  private fun providerAliases(provider: ProviderAliasProvider): Set<String> =
    (provider.providerAliases + provider.providerId + provider.providerName)
      .asSequence()
      .map { normalize(it) }
      .filter { it.isNotEmpty() }
      .toSet()

  /**
   * Match con confine di token: normalizza alias e oggetto e verifica che l'alias sia
   * presente come sequenza di parole complete. Impedisce i falsi positivi del vecchio
   * `contains()` ("max" in "maxwell", "prime" in "springfield").
   */
  private fun matchesTokenBoundary(alias: String, candidate: String): Boolean {
    val normalizedAlias = normalize(alias)
    if (normalizedAlias.isEmpty()) return false
    val normalizedCandidate = normalize(candidate)
    if (normalizedCandidate.isEmpty()) return false
    return " $normalizedCandidate ".contains(" $normalizedAlias ")
  }

  /** Minuscole, sequenze non alfanumeriche ridotte a un singolo spazio, spazi trimmed. */
  private fun normalize(value: String): String =
    value.lowercase()
      .replace(NON_ALPHANUMERIC, " ")
      .replace(REPEATED_SPACES, " ")
      .trim()

  private data class ScoredMatch(
    val key: CatalogKey,
    val providerId: String,
    val score: Double,
    val reasons: List<String>
  )

  companion object {
    /** Soglia minima di confidenza per emettere una proposta. */
    const val MIN_SCORE: Double = 0.5

    private const val WEIGHT_CATALOG_ID = 0.6
    private const val WEIGHT_TITLE = 0.5
    private const val WEIGHT_ADDON_NAME = 0.2
    private const val PAIR_BONUS = 0.05

    private val NON_ALPHANUMERIC = Regex("[^a-z0-9]+")
    private val REPEATED_SPACES = Regex("\\s+")
  }
}
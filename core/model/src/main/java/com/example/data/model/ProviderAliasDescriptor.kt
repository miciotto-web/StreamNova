package com.example.data.model

/**
 * Metadati dichiarativi di un provider, privi di dipendenze UI/Compose.
 *
 * È la vista dati usata dal layer `:core:data` per associare i cataloghi Stremio
 * ai provider. I dettagli di presentazione (colori, logo, drawable) restano nel
 * design system, che implementa questa interfaccia.
 */
interface ProviderAliasDescriptor {
  val providerId: String
  val providerName: String
  val providerAliases: Set<String>
}

data class DefaultProviderAlias(
  override val providerId: String,
  override val providerName: String,
  override val providerAliases: Set<String>
) : ProviderAliasDescriptor

/**
 * Unica fonte degli id, dei nomi e degli alias dei provider StreamNova.
 *
 * Il data layer la usa direttamente; il design system ne deriva gli alias per
 * `StreamingProvider`. In questo modo aggiungere un provider o una variante di
 * nome richiede di modificare un solo catalogo, senza duplicazioni tra moduli.
 */
object ProviderAliasRegistry {
  val ALL: List<ProviderAliasDescriptor> = listOf(
    DefaultProviderAlias("netflix", "Netflix", setOf("netflix")),
    DefaultProviderAlias("hbo", "HBO Max", setOf("hbo", "hbo max", "max")),
    DefaultProviderAlias("disney", "Disney+", setOf("disney", "disney+", "disney plus")),
    DefaultProviderAlias("prime", "Prime Video", setOf("prime", "prime video", "amazon", "amazon prime")),
    DefaultProviderAlias("apple", "Apple TV+", setOf("apple", "apple tv", "apple tv+", "appletv")),
    DefaultProviderAlias("paramount", "Paramount+", setOf("paramount", "paramount+", "paramount plus")),
    DefaultProviderAlias("crunchyroll", "Crunchyroll", setOf("crunchyroll"))
  )

  fun byId(providerId: String): ProviderAliasDescriptor? =
    ALL.firstOrNull { it.providerId == providerId }

  fun nameOf(providerId: String): String? = byId(providerId)?.providerName

  fun aliasesOf(providerId: String): Set<String> = byId(providerId)?.providerAliases.orEmpty()
}

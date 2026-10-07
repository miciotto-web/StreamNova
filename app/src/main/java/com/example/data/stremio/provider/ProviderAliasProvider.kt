package com.example.data.stremio.provider

import com.example.ui.components.StreamingProvider

/**
 * Vista dati di un provider, limitata a ciò che serve per associare cataloghi.
 *
 * Esiste per due motivi:
 * 1. il data layer non deve conoscere i tipi visuali (`StreamingProvider` porta con sé
 *    `Color`, `@DrawableRes` e risorse Compose);
 * 2. i test possono costruire provider sintetici senza dipendere da Compose.
 *
 * Gli alias sono DATI del provider (vedi `StreamingProvider.aliases`), non regole
 * codificate nel resolver: aggiungere un provider o una variante di nome non richiede
 * di toccare l'euristica.
 */
interface ProviderAliasProvider {
  /** Slug interno del provider (es. "disney"): coincide con `StreamingProvider.id`. */
  val providerId: String

  /** Nome visualizzato (es. "Disney+"). */
  val providerName: String

  /** Alias dichiarativi del brand (es. "disney plus", "prime video", "amazon"). */
  val providerAliases: Set<String>
}

/**
 * Adattatore dal modello visuale [StreamingProvider] alla vista dati.
 * È l'unico punto in cui il resolver incontra il modello provider dell'app.
 */
fun StreamingProvider.asAliasProvider(): ProviderAliasProvider = object : ProviderAliasProvider {
  override val providerId: String get() = id
  override val providerName: String get() = name
  override val providerAliases: Set<String> get() = aliases
}
package com.example.data.stremio.provider

import com.example.data.model.ProviderAliasDescriptor

/** Vista data-only dei metadati provider: nessun tipo visuale o risorsa Android. */
interface ProviderAliasProvider : ProviderAliasDescriptor

/** Adattatore leggero dal descriptor condiviso all'interfaccia del resolver data. */
fun ProviderAliasDescriptor.asAliasProvider(): ProviderAliasProvider =
  if (this is ProviderAliasProvider) this else object : ProviderAliasProvider {
    override val providerId: String get() = this@asAliasProvider.providerId
    override val providerName: String get() = this@asAliasProvider.providerName
    override val providerAliases: Set<String> get() = this@asAliasProvider.providerAliases
  }

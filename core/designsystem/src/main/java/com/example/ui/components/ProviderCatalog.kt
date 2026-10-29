package com.example.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.ui.graphics.Color
import com.example.core.designsystem.R
import com.example.data.model.ProviderAliasDescriptor
import com.example.data.model.ProviderAliasRegistry

/**
 * Vista presentazionale di un provider: colori, logo e tag promo.
 *
 * Id, nome e alias NON sono duplicati qui: il design system implementa
 * [ProviderAliasDescriptor] e li deriva da [ProviderAliasRegistry] in `:core:model`,
 * unica fonte condivisa con il data layer. Aggiungere un alias richiede quindi una
 * sola modifica e non può divergere tra matching dei cataloghi e interfaccia.
 */
data class StreamingProvider(
  val id: String,
  val tmdbProviderId: Int,
  val tag: String,
  val primaryColor: Color,
  val secondaryColor: Color,
  val accentColor: Color,
  val logoUrl: String? = null,
  @param:DrawableRes val logoResId: Int? = null
) : ProviderAliasDescriptor {
  override val providerId: String get() = id

  override val providerName: String get() = ProviderAliasRegistry.nameOf(id) ?: id

  override val providerAliases: Set<String> get() = ProviderAliasRegistry.aliasesOf(id)

  /** Nome visualizzato del brand (alias storico di [providerName]). */
  val name: String get() = providerName

  /** Alias dichiarativi del brand (alias storico di [providerAliases]). */
  val aliases: Set<String> get() = providerAliases
}

object ProviderConstants {
  val NETFLIX = StreamingProvider(
    id = "netflix",
    tmdbProviderId = 8,
    tag = "NETFLIX ORIGINALS",
    primaryColor = Color(0xFFE50914),
    secondaryColor = Color(0xFF141414),
    accentColor = Color(0xFFFF3333),
    logoUrl = "https://image.tmdb.org/t/p/w500/rK1KljqmbvO9HQa1PBFLILWah72.png",
    logoResId = R.drawable.ic_provider_netflix
  )

  val HBO = StreamingProvider(
    id = "hbo",
    tmdbProviderId = 384,
    tag = "HBO EXCLUSIVES",
    primaryColor = Color(0xFF7326D1),
    secondaryColor = Color(0xFF110726),
    accentColor = Color(0xFFB57AFF),
    logoUrl = "https://image.tmdb.org/t/p/w500/skypuy7SXuugIQeYg0IglmzoKaS.png",
    logoResId = R.drawable.ic_provider_hbo
  )

  val DISNEY = StreamingProvider(
    id = "disney",
    tmdbProviderId = 337,
    tag = "DISNEY • PIXAR • MARVEL",
    primaryColor = Color(0xFF113CCF),
    secondaryColor = Color(0xFF040A26),
    accentColor = Color(0xFF00D1FF),
    logoUrl = "https://image.tmdb.org/t/p/w500/5eZ872CghnHFLB1j8grszbrx0dx.png",
    logoResId = R.drawable.ic_provider_disney
  )

  val PRIME_VIDEO = StreamingProvider(
    id = "prime",
    tmdbProviderId = 119,
    tag = "AMAZON ORIGINALS",
    primaryColor = Color(0xFF00A8E1),
    secondaryColor = Color(0xFF0B1726),
    accentColor = Color(0xFF00E5FF),
    logoUrl = "https://image.tmdb.org/t/p/w500/gMZdpavHmxFNnLpMHwVxfqeux2g.png",
    logoResId = R.drawable.ic_provider_prime
  )

  val APPLE_TV = StreamingProvider(
    id = "apple",
    tmdbProviderId = 350,
    tag = "APPLE ORIGINALS",
    primaryColor = Color(0xFF2C2C2E),
    secondaryColor = Color(0xFF141414),
    accentColor = Color(0xFFFFFFFF),
    logoUrl = "https://image.tmdb.org/t/p/w500/2E03UQsvMmR4qgM4856E3u9c969.png",
    logoResId = R.drawable.ic_provider_appletv
  )

  val PARAMOUNT_PLUS = StreamingProvider(
    id = "paramount",
    tmdbProviderId = 531,
    tag = "PARAMOUNT+ ORIGINALS",
    primaryColor = Color(0xFF0064FF),
    secondaryColor = Color(0xFF041E3D),
    accentColor = Color(0xFF9CC7FF)
  )

  val CRUNCHYROLL = StreamingProvider(
    id = "crunchyroll",
    tmdbProviderId = 283,
    tag = "CRUNCHYROLL ANIME",
    primaryColor = Color(0xFFF47521),
    secondaryColor = Color(0xFF2A1206),
    accentColor = Color(0xFFFF9A3E)
  )

  val ALL = listOf(NETFLIX, HBO, DISNEY, PRIME_VIDEO, APPLE_TV, PARAMOUNT_PLUS, CRUNCHYROLL)
}

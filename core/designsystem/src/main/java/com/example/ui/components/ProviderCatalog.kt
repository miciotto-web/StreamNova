package com.example.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.ui.graphics.Color
import com.example.core.designsystem.R

data class StreamingProvider(
  val id: String,
  val name: String,
  val tmdbProviderId: Int,
  val tag: String,
  val primaryColor: Color,
  val secondaryColor: Color,
  val accentColor: Color,
  val logoUrl: String? = null,
  @param:DrawableRes val logoResId: Int? = null,
  /**
   * Alias dichiarativi del brand (es. "disney plus", "prime video", "amazon").
   *
   * Sono DATI del provider, usati dal layer di associazione catalogo → provider
   * (`data/stremio/provider`) per i suggerimenti: nessun resolver deve codificare
   * `when (id) { "netflix" -> ... }`. Aggiungere un provider o una variante di nome
   * non richiede modifiche al resolver.
   */
  val aliases: Set<String> = emptySet()
)

object ProviderConstants {
  val NETFLIX = StreamingProvider(
    id = "netflix",
    name = "Netflix",
    tmdbProviderId = 8,
    tag = "NETFLIX ORIGINALS",
    primaryColor = Color(0xFFE50914),
    secondaryColor = Color(0xFF141414),
    accentColor = Color(0xFFFF3333),
    logoUrl = "https://image.tmdb.org/t/p/w500/rK1KljqmbvO9HQa1PBFLILWah72.png",
    logoResId = R.drawable.ic_provider_netflix,
    aliases = setOf("netflix")
  )

  val HBO = StreamingProvider(
    id = "hbo",
    name = "HBO Max",
    tmdbProviderId = 384,
    tag = "HBO EXCLUSIVES",
    primaryColor = Color(0xFF7326D1),
    secondaryColor = Color(0xFF110726),
    accentColor = Color(0xFFB57AFF),
    logoUrl = "https://image.tmdb.org/t/p/w500/skypuy7SXuugIQeYg0IglmzoKaS.png",
    logoResId = R.drawable.ic_provider_hbo,
    aliases = setOf("hbo", "hbo max", "max")
  )

  val DISNEY = StreamingProvider(
    id = "disney",
    name = "Disney+",
    tmdbProviderId = 337,
    tag = "DISNEY • PIXAR • MARVEL",
    primaryColor = Color(0xFF113CCF),
    secondaryColor = Color(0xFF040A26),
    accentColor = Color(0xFF00D1FF),
    logoUrl = "https://image.tmdb.org/t/p/w500/5eZ872CghnHFLB1j8grszbrx0dx.png",
    logoResId = R.drawable.ic_provider_disney,
    aliases = setOf("disney", "disney+", "disney plus")
  )

  val PRIME_VIDEO = StreamingProvider(
    id = "prime",
    name = "Prime Video",
    tmdbProviderId = 119,
    tag = "AMAZON ORIGINALS",
    primaryColor = Color(0xFF00A8E1),
    secondaryColor = Color(0xFF0B1726),
    accentColor = Color(0xFF00E5FF),
    logoUrl = "https://image.tmdb.org/t/p/w500/gMZdpavHmxFNnLpMHwVxfqeux2g.png",
    logoResId = R.drawable.ic_provider_prime,
    aliases = setOf("prime", "prime video", "amazon", "amazon prime")
  )

  val APPLE_TV = StreamingProvider(
    id = "apple",
    name = "Apple TV+",
    tmdbProviderId = 350,
    tag = "APPLE ORIGINALS",
    primaryColor = Color(0xFF2C2C2E),
    secondaryColor = Color(0xFF141414),
    accentColor = Color(0xFFFFFFFF),
    logoUrl = "https://image.tmdb.org/t/p/w500/2E03UQsvMmR4qgM4856E3u9c969.png",
    logoResId = R.drawable.ic_provider_appletv,
    aliases = setOf("apple", "apple tv", "apple tv+", "appletv")
  )

  val PARAMOUNT_PLUS = StreamingProvider(
    id = "paramount",
    name = "Paramount+",
    tmdbProviderId = 531,
    tag = "PARAMOUNT+ ORIGINALS",
    primaryColor = Color(0xFF0064FF),
    secondaryColor = Color(0xFF041E3D),
    accentColor = Color(0xFF9CC7FF),
    aliases = setOf("paramount", "paramount+", "paramount plus")
  )

  val CRUNCHYROLL = StreamingProvider(
    id = "crunchyroll",
    name = "Crunchyroll",
    tmdbProviderId = 283,
    tag = "CRUNCHYROLL ANIME",
    primaryColor = Color(0xFFF47521),
    secondaryColor = Color(0xFF2A1206),
    accentColor = Color(0xFFFF9A3E),
    aliases = setOf("crunchyroll")
  )

  val ALL = listOf(NETFLIX, HBO, DISNEY, PRIME_VIDEO, APPLE_TV, PARAMOUNT_PLUS, CRUNCHYROLL)
}

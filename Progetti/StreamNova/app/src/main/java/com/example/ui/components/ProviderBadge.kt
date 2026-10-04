package com.example.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.R
import com.example.ui.theme.NovaCyan
import com.example.ui.theme.NovaCyanBright

data class StreamingProvider(
  val id: String,
  val name: String,
  val tmdbProviderId: Int,
  val tag: String,
  val primaryColor: Color,
  val secondaryColor: Color,
  val accentColor: Color,
  val logoUrl: String? = null,
  @param:DrawableRes val logoResId: Int? = null
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
    logoResId = R.drawable.ic_provider_netflix
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
    logoResId = R.drawable.ic_provider_hbo
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
    logoResId = R.drawable.ic_provider_disney
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
    logoResId = R.drawable.ic_provider_prime
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
    logoResId = R.drawable.ic_provider_appletv
  )

  val PARAMOUNT_PLUS = StreamingProvider(
    id = "paramount",
    name = "Paramount+",
    tmdbProviderId = 531,
    tag = "PARAMOUNT+ ORIGINALS",
    primaryColor = Color(0xFF0064FF),
    secondaryColor = Color(0xFF041E3D),
    accentColor = Color(0xFF9CC7FF)
  )

  val CRUNCHYROLL = StreamingProvider(
    id = "crunchyroll",
    name = "Crunchyroll",
    tmdbProviderId = 283,
    tag = "CRUNCHYROLL ANIME",
    primaryColor = Color(0xFFF47521),
    secondaryColor = Color(0xFF2A1206),
    accentColor = Color(0xFFFF9A3E)
  )

  val ALL = listOf(NETFLIX, HBO, DISNEY, PRIME_VIDEO, APPLE_TV, PARAMOUNT_PLUS, CRUNCHYROLL)
}

/**
 * Badge elegante del provider per le card o intestazioni carosello
 */
@Composable
fun ProviderBadge(
  provider: StreamingProvider,
  modifier: Modifier = Modifier,
  onClick: (() -> Unit)? = null
) {
  val shape = RoundedCornerShape(50)
  if (onClick != null) {
    TvFocusableBox(
      modifier = modifier,
      shape = shape,
      focusedScale = 1.06f,
      onClick = onClick
    ) { isFocused ->
      Box(
        modifier = Modifier
          .background(
            brush = Brush.horizontalGradient(
              listOf(provider.primaryColor, provider.secondaryColor)
            ),
            shape = shape
          )
          .border(
            width = if (isFocused) 1.5.dp else 1.dp,
            color = if (isFocused) NovaCyanBright else provider.accentColor.copy(alpha = 0.6f),
            shape = shape
          )
          .padding(horizontal = 10.dp, vertical = 4.dp)
      ) {
        Text(
          text = provider.name,
          color = Color.White,
          fontSize = 11.sp,
          fontWeight = FontWeight.Black,
          letterSpacing = 0.5.sp
        )
      }
    }
  } else {
    Box(
      modifier = modifier
        .background(
          brush = Brush.horizontalGradient(
            listOf(provider.primaryColor, provider.secondaryColor)
          ),
          shape = shape
        )
        .border(1.dp, provider.accentColor.copy(alpha = 0.6f), shape)
        .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
      Text(
        text = provider.name,
        color = Color.White,
        fontSize = 11.sp,
        fontWeight = FontWeight.Black,
        letterSpacing = 0.5.sp
      )
    }
  }
}

/**
 * Card selezionabile del Provider di streaming per aprire la scheda dedicata
 */
@Composable
fun ProviderHubCard(
  provider: StreamingProvider,
  isSelected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier
) {
  TvFocusableBox(
    modifier = modifier.width(190.dp),
    shape = RoundedCornerShape(50),
    focusedScale = 1.06f,
    onClick = onClick
  ) { isFocused ->
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .height(64.dp)
        .background(
          brush = Brush.horizontalGradient(
            colors = listOf(
              provider.secondaryColor,
              provider.primaryColor.copy(alpha = if (isFocused || isSelected) 0.85f else 0.45f)
            )
          ),
          shape = RoundedCornerShape(50)
        )
        .border(
          width = if (isFocused || isSelected) 2.dp else 1.dp,
          color = if (isFocused) NovaCyanBright else if (isSelected) provider.accentColor else Color(0x33475569),
          shape = RoundedCornerShape(50)
        )
        .padding(horizontal = 14.dp, vertical = 8.dp),
      contentAlignment = Alignment.CenterStart
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        if (provider.logoResId != null) {
          Image(
            painter = painterResource(id = provider.logoResId),
            contentDescription = provider.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
              .size(36.dp)
              .clip(RoundedCornerShape(8.dp))
          )
        } else if (!provider.logoUrl.isNullOrBlank()) {
          AsyncImage(
            model = provider.logoUrl,
            contentDescription = provider.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
              .size(36.dp)
              .clip(RoundedCornerShape(8.dp))
          )
        }

        Text(
          text = provider.name,
          color = Color.White,
          fontSize = 14.sp,
          fontWeight = FontWeight.ExtraBold,
          letterSpacing = 0.3.sp,
          maxLines = 1
        )
      }
    }
  }
}

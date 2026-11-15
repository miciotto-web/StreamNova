package com.example.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.profiles.ProfileAvatar
import com.example.data.profiles.ProfileImageAvatar
import com.example.ui.theme.NovaCyan
import com.example.ui.theme.NovaCyanBright

/**
 * Cerchio-avatar condiviso da picker/gestione profili e sidebar.
 *
 * Mostra in ordine di precedenza: l'avatar-immagine del personaggio (se scelto), poi
 * l'emoji scelta, altrimenti l'iniziale del nome; il colore è sempre quello del profilo
 * e resta visibile dietro emoji e iniziale. Estratto in `ui.components` per evitare due
 * rendering diversi dello stesso avatar tra la gestione profili e la sidebar.
 */
@Composable
fun ProfileAvatarBadge(
  emoji: String?,
  initial: String,
  avatar: ProfileAvatar,
  size: Dp,
  fontSize: TextUnit,
  modifier: Modifier = Modifier,
  image: ProfileImageAvatar? = null
) {
  Box(
    modifier = modifier
      .size(size)
      .clip(CircleShape)
      .background(Brush.linearGradient(avatar.gradientColors()), CircleShape)
      .border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape),
    contentAlignment = Alignment.Center
  ) {
    if (image != null) {
      // L'immagine copre l'intero cerchio: i PNG/WebP di partenza sono già ritratti
      // circolari con angoli trasparenti, il clip garantisce il contenimento.
      Image(
        painter = painterResource(image.drawableRes()),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize()
      )
    } else {
      Text(
        text = emoji ?: initial,
        color = if (emoji == null) Color.White else Color.Unspecified,
        // Le emoji occupano meno altezza rispetto alla loro dimensione nominale:
        // un leggero aumento le rende leggibili a distanza sul televisore.
        fontSize = if (emoji == null) fontSize else fontSize * 1.15f,
        fontWeight = if (emoji == null) FontWeight.Bold else FontWeight.Normal
      )
    }
  }
}

/** Gradiente del colore-avatar, condiviso da cerchi e selettore colore. */
fun ProfileAvatar.gradientColors(): List<Color> = when (this) {
  ProfileAvatar.CYAN -> listOf(NovaCyan, NovaCyanBright)
  ProfileAvatar.VIOLET -> listOf(Color(0xFF7B2FF7), Color(0xFFB388FF))
  ProfileAvatar.AMBER -> listOf(Color(0xFFFF8A00), Color(0xFFFFC837))
  ProfileAvatar.GREEN -> listOf(Color(0xFF00A86B), Color(0xFF57E389))
  ProfileAvatar.ROSE -> listOf(Color(0xFFD81B60), Color(0xFFFF6F91))
  ProfileAvatar.BLUE -> listOf(Color(0xFF2962FF), Color(0xFF64B5F6))
}

/**
 * Risorsa drawable dell'avatar-immagine. Vive nella UI perché il livello dati resta
 * indipendente da Android (gli enum di `data.profiles` sono verificabili nei test JVM).
 */
fun ProfileImageAvatar.drawableRes(): Int = when (this) {
  ProfileImageAvatar.SPIDERMAN -> R.drawable.avatar_cinema_fantasy_spiderman
  ProfileImageAvatar.NAVI -> R.drawable.avatar_cinema_fantasy_navi
  ProfileImageAvatar.TIANA -> R.drawable.avatar_cinema_fantasy_tiana
  ProfileImageAvatar.HULK -> R.drawable.avatar_cinema_fantasy_hulk
  ProfileImageAvatar.BLACK_PANTHER -> R.drawable.avatar_cinema_fantasy_black_panther
  ProfileImageAvatar.SULLEY -> R.drawable.avatar_cinema_fantasy_sulley
  ProfileImageAvatar.BB8 -> R.drawable.avatar_cinema_fantasy_bb8
  ProfileImageAvatar.MANDALORIAN -> R.drawable.avatar_cinema_fantasy_mandalorian
  ProfileImageAvatar.HOMER -> R.drawable.avatar_simpsons_homer
  ProfileImageAvatar.MARGE -> R.drawable.avatar_simpsons_marge
  ProfileImageAvatar.BART -> R.drawable.avatar_simpsons_bart
  ProfileImageAvatar.LISA -> R.drawable.avatar_simpsons_lisa
  ProfileImageAvatar.MAGGIE -> R.drawable.avatar_simpsons_maggie
  ProfileImageAvatar.KRUSTY -> R.drawable.avatar_simpsons_krusty
  ProfileImageAvatar.RALPH -> R.drawable.avatar_simpsons_ralph
  ProfileImageAvatar.HIBBERT -> R.drawable.avatar_simpsons_hibbert
  ProfileImageAvatar.ELSA -> R.drawable.avatar_disney_elsa
  ProfileImageAvatar.ANNA -> R.drawable.avatar_disney_anna
  ProfileImageAvatar.OLAF -> R.drawable.avatar_disney_olaf
  ProfileImageAvatar.YZMA -> R.drawable.avatar_disney_yzma
  ProfileImageAvatar.RALPH_SPACCATUTTO -> R.drawable.avatar_disney_ralph
  ProfileImageAvatar.MICKEY -> R.drawable.avatar_disney_mickey
  ProfileImageAvatar.DAISY -> R.drawable.avatar_disney_daisy
  ProfileImageAvatar.BEAST -> R.drawable.avatar_disney_beast
  ProfileImageAvatar.BUGS_BUNNY -> R.drawable.avatar_cartoon_classic_bugs
  ProfileImageAvatar.SCOOBY_DOO -> R.drawable.avatar_cartoon_classic_scooby
  ProfileImageAvatar.TAZ -> R.drawable.avatar_cartoon_classic_taz
  ProfileImageAvatar.MARVIN -> R.drawable.avatar_cartoon_classic_marvin
  ProfileImageAvatar.DAFFY -> R.drawable.avatar_cartoon_classic_daffy
  ProfileImageAvatar.FRED_FLINTSTONE -> R.drawable.avatar_cartoon_classic_fred
  ProfileImageAvatar.ROSARIO -> R.drawable.avatar_cartoon_classic_rosario
  ProfileImageAvatar.TADDEO -> R.drawable.avatar_cartoon_classic_taddeo
  ProfileImageAvatar.PUPPY -> R.drawable.avatar_black_mirror_puppy
  ProfileImageAvatar.PIXEL_WOLF -> R.drawable.avatar_black_mirror_pixel_wolf
  ProfileImageAvatar.COOKIE -> R.drawable.avatar_black_mirror_cookie
  ProfileImageAvatar.CROSS -> R.drawable.avatar_black_mirror_cross
  ProfileImageAvatar.DOLL -> R.drawable.avatar_black_mirror_doll
  ProfileImageAvatar.MONKEY -> R.drawable.avatar_black_mirror_monkey
  ProfileImageAvatar.BEAR -> R.drawable.avatar_black_mirror_bear
}

package com.aistudio.streamnova.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MovieCreation
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.aistudio.streamnova.mobile.R
import com.example.data.model.CastMember
import com.example.data.model.Episode
import com.example.data.model.ProductionCompany
import com.example.data.model.WatchProgress
import com.example.ui.components.StreamingProvider
import com.example.ui.theme.NovaCardBg
import com.example.ui.theme.NovaCyan
import com.example.ui.theme.NovaCyanBright
import com.example.ui.theme.NovaGold
import com.example.ui.theme.NovaGreen
import com.example.ui.theme.NovaSurface
import com.example.ui.theme.NovaSurfaceVariant
import com.example.ui.theme.NovaTextMuted
import com.example.ui.theme.NovaTextPrimary
import com.example.ui.theme.NovaTextSecondary

/** Badge compatto identico alla TV per anno, durata, classificazione e rating. */
@Composable
fun MobileQualityBadge(
  text: String,
  modifier: Modifier = Modifier,
  highlighted: Boolean = false,
  leadingIcon: ImageVector? = null,
  leadingIconTint: Color = if (highlighted) NovaCyanBright else NovaTextPrimary
) {
  Row(
    modifier = modifier
      .background(
        color = if (highlighted) NovaCyan.copy(alpha = 0.2f) else Color(0x3320293A),
        shape = RoundedCornerShape(4.dp)
      )
      .border(
        width = 1.dp,
        color = if (highlighted) NovaCyan.copy(alpha = 0.7f) else Color(0x4464748B),
        shape = RoundedCornerShape(4.dp)
      )
      .padding(horizontal = 6.dp, vertical = 3.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(4.dp)
  ) {
    if (leadingIcon != null) {
      Icon(
        imageVector = leadingIcon,
        contentDescription = null,
        tint = leadingIconTint,
        modifier = Modifier.size(12.dp)
      )
    }
    Text(
      text = text,
      color = if (highlighted) NovaCyanBright else NovaTextPrimary,
      fontSize = 11.sp,
      fontWeight = FontWeight.SemiBold,
      letterSpacing = 0.5.sp
    )
  }
}

/** Badge provider puramente visivo, come la riga provider della scheda TV. */
@Composable
fun MobileProviderBadge(
  provider: StreamingProvider,
  modifier: Modifier = Modifier
) {
  val shape = RoundedCornerShape(50)
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

/**
 * Barra azioni: pulsante principale + Preferiti + Guardato/Da guardare + Trailer
 * (+ Seleziona fonte, solo in modalità Debrid).
 *
 * Ordine, icone e stati sono quelli di `MediaActionBar` TV 1.0.4. Il pulsante
 * principale occupa lo spazio rimanente; i cerchi restano sempre visibili e
 * toccabili anche su schermi stretti.
 *
 * [onSelectSource] è `null` in modalità HTTP/direct: il pulsante non viene
 * composto e la barra resta quella storica a quattro controlli. Quando è
 * presente, il pulsante è l'ultimo della fila (come sulla TV) e il pulsante
 * principale, che è l'unico elemento flessibile, cede la sua quota di
 * larghezza al nuovo cerchio.
 */
@Composable
fun MobileDetailActionBar(
  playLabel: String,
  onPlay: () -> Unit,
  isFavorite: Boolean,
  onToggleFavorite: () -> Unit,
  isWatched: Boolean,
  onToggleWatched: () -> Unit,
  onPlayTrailer: () -> Unit,
  modifier: Modifier = Modifier,
  onSelectSource: (() -> Unit)? = null
) {
  Row(
    modifier = modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp)
  ) {
    Button(
      onClick = onPlay,
      modifier = Modifier
        .weight(1f, fill = false)
        .widthIn(min = 160.dp, max = 380.dp)
        .height(48.dp),
      shape = CircleShape,
      contentPadding = PaddingValues(horizontal = 12.dp),
      colors = ButtonDefaults.buttonColors(
        containerColor = NovaCyan,
        contentColor = Color.Black
      )
    ) {
      Icon(
        imageVector = Icons.Default.PlayArrow,
        contentDescription = null,
        modifier = Modifier.size(20.dp)
      )
      Spacer(modifier = Modifier.width(4.dp))
      Text(
        text = playLabel,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center
      )
    }

    MobileCircleAction(
      onClick = onToggleFavorite,
      contentDescription = stringResource(
        if (isFavorite) R.string.detail_remove_favorite else R.string.detail_add_favorite
      )
    ) {
      Icon(
        imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
        contentDescription = null,
        tint = if (isFavorite) Color(0xFFF43F5E) else NovaTextSecondary,
        modifier = Modifier.size(24.dp)
      )
    }

    MobileCircleAction(
      onClick = onToggleWatched,
      contentDescription = stringResource(
        if (isWatched) R.string.detail_action_mark_unwatched else R.string.detail_action_mark_watched
      )
    ) {
      Icon(
        imageVector = if (isWatched) Icons.Default.Visibility else Icons.Default.VisibilityOff,
        contentDescription = null,
        tint = if (isWatched) NovaGreen else NovaTextSecondary,
        modifier = Modifier.size(24.dp)
      )
    }

    MobileCircleAction(
      onClick = onPlayTrailer,
      contentDescription = stringResource(R.string.detail_action_trailer)
    ) {
      Box(contentAlignment = Alignment.Center) {
        Icon(
          imageVector = Icons.Default.MovieCreation,
          contentDescription = null,
          tint = NovaTextSecondary,
          modifier = Modifier.size(25.dp)
        )
        Icon(
          imageVector = Icons.Default.PlayArrow,
          contentDescription = null,
          tint = NovaTextSecondary,
          modifier = Modifier.size(12.dp)
        )
      }
    }

    // Selezione manuale della sorgente (solo modalità Debrid): quinto cerchio con
    // l'icona di regolazione, come sulla TV. La riproduzione parte SOLO con la
    // conferma dell'utente nella schermata di scelta.
    if (onSelectSource != null) {
      MobileCircleAction(
        onClick = onSelectSource,
        contentDescription = stringResource(R.string.detail_action_select_source)
      ) {
        Icon(
          imageVector = Icons.Default.Tune,
          contentDescription = null,
          tint = NovaTextSecondary,
          modifier = Modifier.size(24.dp)
        )
      }
    }
  }
}

@Composable
private fun MobileCircleAction(
  onClick: () -> Unit,
  contentDescription: String,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit
) {
  Box(
    modifier = modifier
      .size(48.dp)
      .clip(CircleShape)
      .background(NovaSurfaceVariant, CircleShape)
      .border(1.dp, Color(0xFF1E293B), CircleShape)
      .clickable(role = Role.Button, onClick = onClick)
      .semantics { this.contentDescription = contentDescription },
    contentAlignment = Alignment.Center
  ) {
    content()
  }
}

/** Banner di errore parziale del dettaglio TMDB, con retry come sulla TV. */
@Composable
fun MobileDetailErrorBanner(
  message: String,
  onRetry: () -> Unit,
  modifier: Modifier = Modifier
) {
  Row(
    modifier = modifier
      .fillMaxWidth()
      .background(Color(0xFF3B1515).copy(alpha = 0.9f), RoundedCornerShape(12.dp))
      .border(1.dp, Color(0xFFEF4444).copy(alpha = 0.6f), RoundedCornerShape(12.dp))
      .padding(horizontal = 14.dp, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(10.dp)
  ) {
    Icon(
      imageVector = Icons.Default.Warning,
      contentDescription = null,
      tint = Color(0xFFEF4444),
      modifier = Modifier.size(20.dp)
    )
    Text(
      text = stringResource(R.string.detail_partial_error, message),
      color = Color.White,
      fontSize = 12.sp,
      fontWeight = FontWeight.Medium,
      modifier = Modifier.weight(1f)
    )
    TextButton(onClick = onRetry) {
      Icon(
        imageVector = Icons.Default.Refresh,
        contentDescription = null,
        tint = NovaCyanBright,
        modifier = Modifier.size(16.dp)
      )
      Spacer(modifier = Modifier.width(4.dp))
      Text(
        text = stringResource(R.string.action_retry),
        color = NovaCyanBright,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold
      )
    }
  }
}

/** Card di stato (loading / empty / errore locale) con lo stile della scheda TV. */
@Composable
fun MobileDetailStatusCard(
  text: String,
  modifier: Modifier = Modifier,
  showSpinner: Boolean = false,
  isError: Boolean = false,
  onRetry: (() -> Unit)? = null
) {
  Column(
    modifier = modifier
      .fillMaxWidth()
      .background(
        if (isError) Color(0xFF7F1D1D).copy(alpha = 0.4f) else NovaSurfaceVariant.copy(alpha = 0.6f),
        RoundedCornerShape(12.dp)
      )
      .border(
        1.dp,
        if (isError) Color(0xFFEF4444).copy(alpha = 0.5f) else NovaCyan.copy(alpha = 0.3f),
        RoundedCornerShape(12.dp)
      )
      .padding(horizontal = 18.dp, vertical = 16.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(10.dp)
  ) {
    if (showSpinner) {
      CircularProgressIndicator(
        modifier = Modifier.size(24.dp),
        color = NovaCyanBright,
        strokeWidth = 2.5.dp
      )
    }
    Text(
      text = text,
      color = if (isError) Color(0xFFFCA5A5) else NovaCyanBright,
      fontSize = 13.sp,
      fontWeight = FontWeight.Medium,
      textAlign = TextAlign.Center
    )
    if (onRetry != null) {
      TextButton(onClick = onRetry) {
        Icon(
          imageVector = Icons.Default.Refresh,
          contentDescription = null,
          tint = NovaCyanBright,
          modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
          text = stringResource(R.string.action_retry),
          color = NovaCyanBright,
          fontSize = 12.sp,
          fontWeight = FontWeight.Bold
        )
      }
    }
  }
}

/** Intestazione di sezione: stessa icona, maiuscolo e spaziatura della TV. */
@Composable
fun MobileDetailSectionHeader(
  text: String,
  modifier: Modifier = Modifier,
  icon: ImageVector? = null
) {
  Row(
    modifier = modifier,
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp)
  ) {
    if (icon != null) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        tint = NovaCyanBright,
        modifier = Modifier.size(18.dp)
      )
    }
    Text(
      text = text,
      color = NovaTextMuted,
      fontSize = 13.sp,
      fontWeight = FontWeight.Bold,
      letterSpacing = 1.sp
    )
  }
}

/** Dialog di sola lettura per la sinossi completa (stesso comportamento TV). */
@Composable
fun MobileFullSynopsisDialog(
  title: String,
  text: String,
  onDismiss: () -> Unit
) {
  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false)
  ) {
    Column(
      modifier = Modifier
        .widthIn(max = 640.dp)
        .fillMaxWidth(0.92f)
        .background(NovaSurface, RoundedCornerShape(20.dp))
        .border(1.5.dp, NovaCyan, RoundedCornerShape(20.dp))
        .padding(22.dp)
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          text = title,
          color = NovaTextPrimary,
          fontSize = 18.sp,
          fontWeight = FontWeight.Bold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier
            .weight(1f, fill = false)
            .padding(end = 12.dp)
        )
        IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
          Icon(
            imageVector = Icons.Default.Close,
            contentDescription = stringResource(R.string.action_close),
            tint = NovaTextSecondary,
            modifier = Modifier.size(22.dp)
          )
        }
      }
      Spacer(modifier = Modifier.height(12.dp))
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(max = 420.dp)
          .verticalScroll(rememberScrollState())
      ) {
        Text(
          text = text,
          color = NovaTextSecondary,
          fontSize = 15.sp,
          lineHeight = 23.sp
        )
      }
    }
  }
}

/** Carosello cast: foto circolare, nome e ruolo, come la scheda TV. */
@Composable
fun MobileCastCarousel(
  cast: List<CastMember>,
  modifier: Modifier = Modifier
) {
  if (cast.isEmpty()) return
  Column(modifier = modifier.fillMaxWidth()) {
    MobileDetailSectionHeader(
      text = stringResource(R.string.detail_main_cast),
      icon = Icons.Default.Person,
      modifier = Modifier.padding(horizontal = 20.dp)
    )
    Spacer(modifier = Modifier.height(10.dp))
    LazyRow(
      contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
      horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
      items(cast, key = { "${it.id}_${it.name}" }) { member ->
        CastMemberCard(member)
      }
    }
  }
}

@Composable
private fun CastMemberCard(member: CastMember) {
  Column(
    modifier = Modifier.width(96.dp),
    horizontalAlignment = Alignment.CenterHorizontally
  ) {
    Box(
      modifier = Modifier
        .size(80.dp)
        .clip(CircleShape)
        .background(Color(0xFF0F172A), CircleShape)
        .border(1.dp, Color(0xFF334155).copy(alpha = 0.6f), CircleShape),
      contentAlignment = Alignment.Center
    ) {
      if (!member.profileUrl.isNullOrBlank()) {
        AsyncImage(
          model = member.profileUrl,
          contentDescription = member.name,
          contentScale = ContentScale.Crop,
          modifier = Modifier
            .fillMaxSize()
            .clip(CircleShape)
        )
      } else {
        Icon(
          imageVector = Icons.Default.Person,
          contentDescription = null,
          tint = NovaTextMuted,
          modifier = Modifier.size(38.dp)
        )
      }
    }
    Spacer(modifier = Modifier.height(8.dp))
    Text(
      text = member.name,
      color = NovaTextPrimary,
      fontSize = 12.sp,
      fontWeight = FontWeight.Bold,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      textAlign = TextAlign.Center,
      modifier = Modifier.fillMaxWidth()
    )
    if (member.character.isNotBlank()) {
      Spacer(modifier = Modifier.height(2.dp))
      Text(
        text = if (member.character == "Cast principale") {
          stringResource(R.string.cast_main_role)
        } else {
          member.character
        },
        color = NovaTextSecondary,
        fontSize = 10.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
      )
    }
  }
}

/** Sezione case di produzione: card chiare con logo originale o nome reale. */
@Composable
fun MobileProductionSection(
  companies: List<ProductionCompany>,
  modifier: Modifier = Modifier
) {
  Column(modifier = modifier.fillMaxWidth()) {
    MobileDetailSectionHeader(text = stringResource(R.string.production_section_title))
    Spacer(modifier = Modifier.height(12.dp))
    if (companies.isEmpty()) {
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .background(NovaCardBg, RoundedCornerShape(12.dp))
          .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(12.dp))
          .padding(24.dp),
        contentAlignment = Alignment.Center
      ) {
        Text(
          text = stringResource(R.string.production_empty),
          color = NovaTextSecondary,
          fontSize = 14.sp,
          textAlign = TextAlign.Center
        )
      }
    } else {
      LazyRow(
        contentPadding = PaddingValues(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp)
      ) {
        items(companies, key = { "${it.id}_${it.name}" }) { company ->
          ProductionCompanyCard(company)
        }
      }
    }
  }
}

@Composable
private fun ProductionCompanyCard(company: ProductionCompany) {
  Box(
    modifier = Modifier
      .width(160.dp)
      .height(78.dp)
      .background(Color(0xFFEEEEEE), RoundedCornerShape(12.dp))
      .border(1.dp, Color(0xFFD7DCE5), RoundedCornerShape(12.dp))
      .padding(8.dp),
    contentAlignment = Alignment.Center
  ) {
    if (!company.logoUrl.isNullOrBlank()) {
      AsyncImage(
        model = company.logoUrl,
        contentDescription = company.name,
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize()
      )
    } else {
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
      ) {
        Text(
          text = company.name,
          color = Color(0xFF0F172A),
          fontSize = 13.sp,
          fontWeight = FontWeight.Bold,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
          textAlign = TextAlign.Center
        )
        val country = company.originCountry
        if (!country.isNullOrBlank()) {
          Spacer(modifier = Modifier.height(2.dp))
          Text(
            text = country,
            color = NovaTextMuted,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium
          )
        }
      }
    }
  }
}

/** Chip stagione con conteggio episodi reale (o "…" durante il caricamento). */
@Composable
fun MobileSeasonChip(
  label: String,
  episodeCountLabel: String?,
  isSelected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier
) {
  val shape = RoundedCornerShape(50)
  Row(
    modifier = modifier
      .clip(shape)
      .background(
        color = if (isSelected) NovaCyan.copy(alpha = 0.22f) else NovaSurfaceVariant,
        shape = shape
      )
      .border(
        width = if (isSelected) 1.5.dp else 1.dp,
        color = if (isSelected) NovaCyan else Color(0xFF1E293B),
        shape = shape
      )
      .clickable(role = Role.Tab, onClick = onClick)
      .padding(horizontal = 14.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp)
  ) {
    Text(
      text = label,
      color = if (isSelected) NovaCyanBright else NovaTextSecondary,
      fontSize = 13.sp,
      fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
    )
    if (episodeCountLabel != null) {
      Box(
        modifier = Modifier
          .background(
            if (isSelected) NovaCyan.copy(alpha = 0.35f) else Color.Black.copy(alpha = 0.45f),
            RoundedCornerShape(6.dp)
          )
          .padding(horizontal = 7.dp, vertical = 2.dp)
      ) {
        Text(
          text = episodeCountLabel,
          color = if (isSelected) NovaCyanBright else NovaTextMuted,
          fontSize = 10.sp,
          fontWeight = FontWeight.SemiBold
        )
      }
    }
  }
}

/** Riga di stato della stagione; nessun dato audio statico inventato. */
@Composable
fun MobileSeasonStatusBar(
  text: String,
  modifier: Modifier = Modifier
) {
  Row(
    modifier = modifier
      .fillMaxWidth()
      .background(NovaCardBg.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
      .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(8.dp))
      .padding(horizontal = 14.dp, vertical = 9.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Text(
      text = text,
      color = NovaTextPrimary,
      fontSize = 13.sp,
      fontWeight = FontWeight.SemiBold
    )
  }
}

/**
 * Card episodio: thumbnail 16:9, badge E, titolo, durata reale, stato visto /
 * minuti rimanenti, data, sinossi e badge SIMKL. Stessi dati e azioni della TV.
 */
@Composable
fun MobileEpisodeCard(
  episode: Episode,
  isSimklWatched: Boolean,
  fallbackBackdropUrl: String?,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  expanded: Boolean = false,
  blurThumbnail: Boolean = false
) {
  val shape = RoundedCornerShape(14.dp)
  Row(
    modifier = modifier
      .fillMaxWidth()
      .clip(shape)
      .background(NovaCardBg, shape)
      .border(1.dp, Color(0xFF1E293B), shape)
      .clickable(role = Role.Button, onClick = onClick)
      .padding(10.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    EpisodeThumbnail(
      episode = episode,
      isSimklWatched = isSimklWatched,
      fallbackBackdropUrl = fallbackBackdropUrl,
      width = if (expanded) 176.dp else 128.dp,
      blurThumbnail = blurThumbnail
    )
    Spacer(modifier = Modifier.width(12.dp))
    Column(
      modifier = Modifier.weight(1f),
      verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        Box(
          modifier = Modifier
            .background(NovaCyan.copy(alpha = 0.18f), RoundedCornerShape(6.dp))
            .border(1.dp, NovaCyan.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp)
        ) {
          Text(
            text = "E%02d".format(episode.episodeNumber),
            color = NovaCyanBright,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
          )
        }
        Text(
          text = episode.title.ifBlank {
            stringResource(R.string.detail_episode_number_format, episode.episodeNumber)
          },
          color = NovaTextPrimary,
          fontSize = 14.sp,
          fontWeight = FontWeight.Bold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f)
        )
      }

      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        if (episode.durationMinutes > 0) {
          Text(
            text = stringResource(R.string.detail_duration_min, episode.durationMinutes),
            color = NovaTextMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
          )
        }
        if (episode.isCompleted) {
          Box(
            modifier = Modifier
              .background(Color(0xFF10B981).copy(alpha = 0.2f), RoundedCornerShape(4.dp))
              .padding(horizontal = 6.dp, vertical = 2.dp)
          ) {
            Text(
              text = stringResource(R.string.detail_watched_badge),
              color = Color(0xFF10B981),
              fontSize = 10.sp,
              fontWeight = FontWeight.Bold
            )
          }
        } else if (episode.currentProgressMs > 0) {
          val remaining = WatchProgress.remainingMinutes(
            totalDurationMs = episode.totalDurationMs,
            resumePositionMs = episode.currentProgressMs,
            runtimeMinutes = episode.durationMinutes
          )
          if (remaining != null) {
            Box(
              modifier = Modifier
                .background(NovaCyan.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
              Text(
                text = stringResource(R.string.badge_minutes_remaining, remaining),
                color = NovaCyanBright,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold
              )
            }
          }
        }
      }

      val airDate = episode.airDate
      if (!airDate.isNullOrBlank()) {
        Text(
          text = stringResource(R.string.detail_air_date_format, airDate),
          color = NovaTextMuted,
          fontSize = 10.sp,
          fontWeight = FontWeight.Medium
        )
      }

      if (episode.synopsis.isNotBlank()) {
        Text(
          text = episode.synopsis,
          color = NovaTextSecondary,
          fontSize = 12.sp,
          lineHeight = 17.sp,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis
        )
      }
    }
  }
}

@Composable
private fun EpisodeThumbnail(
  episode: Episode,
  isSimklWatched: Boolean,
  fallbackBackdropUrl: String?,
  width: Dp,
  blurThumbnail: Boolean
) {
  Box(
    modifier = Modifier
      .width(width)
      .aspectRatio(16f / 9f)
      .clip(RoundedCornerShape(10.dp))
      .background(Color(0xFF0F172A))
  ) {
    val thumbUrl = episode.thumbnailUrl?.takeIf { it.isNotBlank() }
      ?: fallbackBackdropUrl?.takeIf { it.isNotBlank() }
    if (thumbUrl != null) {
      if (blurThumbnail) {
        // Stessa sfocatura bitmap della riga «Continua a guardare»: funziona anche
        // su Android 7-11, dove `Modifier.blur`/`RenderEffect` non esiste.
        SpoilerBlurredImage(
          data = thumbUrl,
          contentDescription = episode.title.takeIf { it.isNotBlank() },
          contentScale = ContentScale.Crop,
          modifier = Modifier.fillMaxSize()
        )
      } else {
        AsyncImage(
          model = thumbUrl,
          contentDescription = episode.title.takeIf { it.isNotBlank() },
          contentScale = ContentScale.Crop,
          modifier = Modifier.fillMaxSize()
        )
      }
    } else {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .background(
            Brush.verticalGradient(
              colors = listOf(Color(0xFF1E293B), Color(0xFF0F172A))
            )
          )
      )
    }

    if (isSimklWatched) {
      Row(
        modifier = Modifier
          .align(Alignment.TopEnd)
          .padding(5.dp)
          .background(Color(0xFF059669), RoundedCornerShape(5.dp))
          .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
      ) {
        Icon(
          imageVector = Icons.Default.Check,
          contentDescription = stringResource(R.string.detail_simkl_watched_desc),
          tint = Color.White,
          modifier = Modifier.size(12.dp)
        )
        Text(
          text = stringResource(R.string.detail_simkl_watched_badge),
          color = Color.White,
          fontSize = 9.sp,
          fontWeight = FontWeight.Bold
        )
      }
    }

    Box(
      modifier = Modifier
        .align(Alignment.Center)
        .size(30.dp)
        .background(Color.Black.copy(alpha = 0.55f), CircleShape)
        .border(1.dp, Color.White.copy(alpha = 0.3f), CircleShape),
      contentAlignment = Alignment.Center
    ) {
      Icon(
        imageVector = Icons.Default.PlayArrow,
        contentDescription = null,
        tint = Color.White,
        modifier = Modifier.size(18.dp)
      )
    }

    if (episode.currentProgressMs > 0L) {
      LinearProgressIndicator(
        progress = { episode.progressFraction },
        modifier = Modifier
          .align(Alignment.BottomCenter)
          .fillMaxWidth()
          .height(3.dp),
        color = NovaCyan,
        trackColor = Color.Black.copy(alpha = 0.6f)
      )
    }
  }
}

/** Riga tab "Episodi"/"Produzione" con sottolineatura ciano, come la TV. */
@Composable
fun MobileDetailTabs(
  labels: List<String>,
  selectedIndex: Int,
  onSelect: (Int) -> Unit,
  modifier: Modifier = Modifier
) {
  Row(
    modifier = modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(10.dp)
  ) {
    labels.forEachIndexed { index, label ->
      val selected = index == selectedIndex
      Column(
        modifier = Modifier
          .clip(RoundedCornerShape(8.dp))
          .clickable(role = Role.Tab) { onSelect(index) }
          .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        Text(
          text = label,
          color = if (selected) NovaCyanBright else NovaTextSecondary,
          fontSize = 15.sp,
          fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(4.dp))
        Box(
          modifier = Modifier
            .width(36.dp)
            .height(3.dp)
            .background(
              if (selected) NovaCyan else Color.Transparent,
              RoundedCornerShape(2.dp)
            )
        )
      }
    }
  }
}

/** Riga "anno • durata/stagioni • età • rating", con stelle TMDB come la TV. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MobileDetailBadges(
  year: Int?,
  durationMinutes: Int?,
  seasonsCount: Int?,
  episodesCount: Int?,
  ageRating: Int?,
  rating: Float?,
  modifier: Modifier = Modifier
) {
  FlowRow(
    modifier = modifier,
    verticalArrangement = Arrangement.spacedBy(6.dp),
    horizontalArrangement = Arrangement.spacedBy(7.dp)
  ) {
    year?.takeIf { it > 0 }?.let {
      MobileQualityBadge(text = it.toString())
    }
    if (seasonsCount != null) {
      val text = if (episodesCount != null && episodesCount > 0) {
        stringResource(R.string.detail_seasons_episodes_format, seasonsCount, episodesCount)
      } else {
        stringResource(R.string.hero_seasons_count, seasonsCount)
      }
      MobileQualityBadge(text = text)
    } else if (durationMinutes != null && durationMinutes > 0) {
      val hours = durationMinutes / 60
      val minutes = durationMinutes % 60
      MobileQualityBadge(text = if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m")
    }
    ageRating?.let {
      MobileQualityBadge(text = "$it+")
    }
    rating?.takeIf { it > 0f }?.let {
      MobileQualityBadge(
        text = String.format(java.util.Locale.US, "%.1f", it),
        leadingIcon = Icons.Default.Star,
        leadingIconTint = NovaGold
      )
    }
  }
}

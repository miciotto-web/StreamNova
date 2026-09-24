package com.example.ui.screens

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.MediaItem
import com.example.ui.components.ContinueWatchingCard
import com.example.ui.components.PosterMediaCard
import com.example.ui.components.StandardMediaCard
import com.example.ui.components.TvFocusableBox
import com.example.ui.theme.NovaBackground
import com.example.ui.theme.NovaCardBg
import com.example.ui.theme.NovaCyan
import com.example.ui.theme.NovaCyanBright
import com.example.ui.theme.NovaSurface
import com.example.ui.theme.NovaSurfaceVariant
import com.example.ui.theme.NovaTextMuted
import com.example.ui.theme.NovaTextPrimary
import com.example.ui.theme.NovaTextSecondary
import com.example.ui.viewmodel.StreamNovaViewModel

@Composable
fun CategoryBrowsingScreen(
  title: String,
  subtitle: String,
  items: List<MediaItem>,
  onMediaClick: (MediaItem) -> Unit,
  modifier: Modifier = Modifier
) {
  Column(
    modifier = modifier
      .fillMaxSize()
      .background(NovaBackground)
      .padding(start = 32.dp, top = 24.dp, end = 32.dp)
  ) {
    Text(
      text = title,
      color = NovaTextPrimary,
      fontSize = 24.sp,
      fontWeight = FontWeight.Bold
    )
    Text(
      text = subtitle,
      color = NovaTextSecondary,
      fontSize = 13.sp,
      modifier = Modifier.padding(top = 4.dp, bottom = 20.dp)
    )

    LazyVerticalGrid(
      columns = GridCells.Adaptive(minSize = 155.dp),
      contentPadding = PaddingValues(bottom = 40.dp),
      horizontalArrangement = Arrangement.spacedBy(16.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
      modifier = Modifier.fillMaxSize()
    ) {
      items(items, key = { it.id }) { item ->
        PosterMediaCard(
          media = item,
          onClick = { onMediaClick(item) },
          modifier = Modifier.fillMaxWidth()
        )
      }
    }
  }
}

@Composable
fun MyContentScreen(
  allMedia: List<MediaItem>,
  onMediaClick: (MediaItem) -> Unit,
  modifier: Modifier = Modifier
) {
  val favorites = allMedia.filter { it.isFavorite }
  val continueWatching = allMedia.filter { it.currentProgressMs > 0 }

  LazyColumn(
    modifier = modifier
      .fillMaxSize()
      .background(NovaBackground)
      .padding(top = 24.dp),
    contentPadding = PaddingValues(bottom = 48.dp)
  ) {
    item {
      Text(
        text = "I miei contenuti",
        color = NovaTextPrimary,
        fontSize = 24.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 32.dp)
      )
      Text(
        text = "La tua lista personalizzata e la cronologia di visione sincronizzata",
        color = NovaTextSecondary,
        fontSize = 13.sp,
        modifier = Modifier.padding(start = 32.dp, top = 4.dp, end = 32.dp, bottom = 20.dp)
      )
    }

    if (continueWatching.isNotEmpty()) {
      item {
        CarouselHeader(title = "Continua a guardare", badge = "${continueWatching.size} in corso")
        LazyRow(
          contentPadding = PaddingValues(horizontal = 32.dp, vertical = 8.dp),
          horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
          items(continueWatching, key = { "cw_my_${it.id}" }) { item ->
            ContinueWatchingCard(
              media = item,
              onClick = { onMediaClick(item) }
            )
          }
        }
        Spacer(modifier = Modifier.height(24.dp))
      }
    }

    item {
      CarouselHeader(
        title = "La mia lista (Preferiti)",
        badge = if (favorites.isEmpty()) "Vuota" else "${favorites.size} salvati"
      )
      if (favorites.isEmpty()) {
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 32.dp),
          contentAlignment = Alignment.Center
        ) {
          Text(
            text = "Non hai ancora aggiunto film o serie alla tua lista. Premi '+ La mia lista' nella schermata di dettaglio.",
            color = NovaTextMuted,
            fontSize = 14.sp
          )
        }
      } else {
        LazyRow(
          contentPadding = PaddingValues(horizontal = 32.dp, vertical = 8.dp),
          horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
          items(favorites, key = { "fav_${it.id}" }) { item ->
            StandardMediaCard(
              media = item,
              onClick = { onMediaClick(item) }
            )
          }
        }
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
  viewModel: StreamNovaViewModel,
  onMediaClick: (MediaItem) -> Unit,
  modifier: Modifier = Modifier
) {
  val query by viewModel.searchQuery.collectAsState()
  val results by viewModel.filteredSearchResults.collectAsState()

  val suggestedQueries = listOf("Dune", "The Last of Us", "Fantascienza", "4K HDR", "Nolan", "Azione")

  Column(
    modifier = modifier
      .fillMaxSize()
      .background(NovaBackground)
      .padding(start = 32.dp, top = 24.dp, end = 32.dp)
  ) {
    Text(
      text = "Cerca su StreamNova",
      color = NovaTextPrimary,
      fontSize = 24.sp,
      fontWeight = FontWeight.Bold
    )

    Spacer(modifier = Modifier.height(16.dp))

    // Search Input Bar
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .background(NovaSurfaceVariant, RoundedCornerShape(12.dp))
        .border(1.dp, NovaCyan.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
        .padding(horizontal = 16.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Icon(
        imageVector = Icons.Default.Search,
        contentDescription = "Cerca",
        tint = NovaCyanBright,
        modifier = Modifier.size(24.dp)
      )
      Spacer(modifier = Modifier.width(12.dp))
      BasicTextField(
        value = query,
        onValueChange = { viewModel.updateSearchQuery(it) },
        textStyle = TextStyle(
          color = NovaTextPrimary,
          fontSize = 16.sp,
          fontWeight = FontWeight.Medium
        ),
        cursorBrush = SolidColor(NovaCyanBright),
        modifier = Modifier.weight(1f),
        decorationBox = { innerTextField ->
          if (query.isEmpty()) {
            Text(
              text = "Cerca per titolo, attore, regista o genere (es. Dune, Pedro Pascal, Fantascienza)...",
              color = NovaTextMuted,
              fontSize = 14.sp
            )
          }
          innerTextField()
        }
      )
      if (query.isNotEmpty()) {
        Icon(
          imageVector = Icons.Default.Close,
          contentDescription = "Cancella",
          tint = NovaTextSecondary,
          modifier = Modifier
            .size(20.dp)
            .clickable { viewModel.updateSearchQuery("") }
        )
      }
    }

    Spacer(modifier = Modifier.height(14.dp))

    // Quick suggestions chips
    FlowRow(
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      suggestedQueries.forEach { tag ->
        TvFocusableBox(
          shape = RoundedCornerShape(20.dp),
          onClick = { viewModel.updateSearchQuery(tag) }
        ) { isFocused ->
          Text(
            text = tag,
            color = if (isFocused) NovaCyanBright else NovaTextSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
              .background(
                if (isFocused) NovaCyan.copy(alpha = 0.25f) else Color(0x331E293B),
                RoundedCornerShape(20.dp)
              )
              .padding(horizontal = 12.dp, vertical = 6.dp)
          )
        }
      }
    }

    Spacer(modifier = Modifier.height(20.dp))

    Text(
      text = if (query.isBlank()) "Tutti i contenuti (${results.size})" else "Risultati trovati (${results.size})",
      color = NovaTextSecondary,
      fontSize = 13.sp,
      fontWeight = FontWeight.SemiBold
    )

    Spacer(modifier = Modifier.height(12.dp))

    LazyVerticalGrid(
      columns = GridCells.Adaptive(minSize = 155.dp),
      contentPadding = PaddingValues(bottom = 40.dp),
      horizontalArrangement = Arrangement.spacedBy(16.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
      modifier = Modifier.fillMaxSize()
    ) {
      items(results, key = { "search_${it.id}" }) { item ->
        PosterMediaCard(
          media = item,
          onClick = { onMediaClick(item) },
          modifier = Modifier.fillMaxWidth()
        )
      }
    }
  }
}

@Composable
fun SettingsScreen(
  modifier: Modifier = Modifier
) {
  var selectedQuality by remember { mutableStateOf("Ultra HD 4K HDR (Massima qualità)") }
  var selectedAudioLang by remember { mutableStateOf("Italiano (Dolby Atmos)") }
  var selectedSubtitles by remember { mutableStateOf("Disattivati") }

  LazyColumn(
    modifier = modifier
      .fillMaxSize()
      .background(NovaBackground)
      .padding(start = 32.dp, top = 24.dp, end = 32.dp),
    contentPadding = PaddingValues(bottom = 48.dp),
    verticalArrangement = Arrangement.spacedBy(20.dp)
  ) {
    item {
      Text(
        text = "Impostazioni StreamNova TV",
        color = NovaTextPrimary,
        fontSize = 24.sp,
        fontWeight = FontWeight.Bold
      )
      Text(
        text = "Configurazione audio, video e preferenze per Android TV",
        color = NovaTextSecondary,
        fontSize = 13.sp,
        modifier = Modifier.padding(top = 4.dp)
      )
    }

    // Video Section
    item {
      SettingsGroupCard(title = "Riproduzione Video", icon = Icons.Default.HighQuality) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          val qualities = listOf(
            "Ultra HD 4K HDR (Massima qualità)",
            "Full HD 1080p (Risparmio banda)",
            "Automatica (In base alla connessione)"
          )
          qualities.forEach { q ->
            SettingOptionRow(
              title = q,
              isSelected = selectedQuality == q,
              onClick = { selectedQuality = q }
            )
          }
        }
      }
    }

    // Audio & Subtitles Section
    item {
      SettingsGroupCard(title = "Audio e Lingua Predefinita", icon = Icons.Default.Language) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          val audios = listOf("Italiano (Dolby Atmos)", "Inglese (Originale)")
          audios.forEach { a ->
            SettingOptionRow(
              title = a,
              isSelected = selectedAudioLang == a,
              onClick = { selectedAudioLang = a }
            )
          }
        }
      }
    }

    // System & Device Info
    item {
      SettingsGroupCard(title = "Informazioni Dispositivo TV", icon = Icons.Default.Tv) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          InfoRow(label = "Applicazione", value = "StreamNova for Android TV")
          InfoRow(label = "Versione", value = "1.0.0 (Build 2026)")
          InfoRow(label = "Framework", value = "Jetpack Compose for TV + Media3 ExoPlayer")
          InfoRow(label = "Metadata & Asset TMDB", value = "Attivo (Locandine, Hero e Loghi Ufficiali)")
          InfoRow(label = "Cache Immagini", value = "Coil Memory & Disk Cache 4K")
          InfoRow(label = "Supporto HDR", value = "HDR10 / Dolby Vision / HLG Attivo")
          InfoRow(label = "Streaming Adattivo", value = "HLS / DASH Multi-bitrate Supportato")
        }
      }
    }
  }
}

@Composable
fun SettingsGroupCard(
  title: String,
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  content: @Composable () -> Unit
) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .background(NovaCardBg, RoundedCornerShape(12.dp))
      .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(12.dp))
      .padding(20.dp)
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.padding(bottom = 14.dp)
    ) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        tint = NovaCyanBright,
        modifier = Modifier.size(20.dp)
      )
      Spacer(modifier = Modifier.width(10.dp))
      Text(
        text = title,
        color = NovaTextPrimary,
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold
      )
    }
    content()
  }
}

@Composable
fun SettingOptionRow(
  title: String,
  isSelected: Boolean,
  onClick: () -> Unit
) {
  TvFocusableBox(
    shape = RoundedCornerShape(8.dp),
    onClick = onClick,
    modifier = Modifier.fillMaxWidth()
  ) { isFocused ->
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .background(
          if (isSelected) NovaCyan.copy(alpha = 0.15f) else Color.Transparent,
          RoundedCornerShape(8.dp)
        )
        .padding(horizontal = 14.dp, vertical = 10.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Text(
        text = title,
        color = if (isSelected || isFocused) NovaTextPrimary else NovaTextSecondary,
        fontSize = 14.sp,
        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
      )
      if (isSelected) {
        Icon(
          imageVector = Icons.Default.Check,
          contentDescription = "Selezionato",
          tint = NovaCyanBright,
          modifier = Modifier.size(18.dp)
        )
      }
    }
  }
}

@Composable
fun InfoRow(label: String, value: String) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween
  ) {
    Text(text = label, color = NovaTextMuted, fontSize = 13.sp)
    Text(text = value, color = NovaTextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
  }
}

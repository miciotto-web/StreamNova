package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.ui.components.PosterMediaCard
import com.example.ui.components.StreamingProvider
import com.example.ui.components.TvActionButton
import com.example.ui.components.TvFocusBringIntoView
import com.example.ui.components.TvFocusableBox
import com.example.ui.theme.NovaBackground
import com.example.ui.theme.NovaSurfaceVariant
import com.example.ui.theme.NovaTextMuted
import com.example.ui.theme.NovaTextSecondary

@Composable
fun ProviderScreen(
  provider: StreamingProvider,
  allMedia: List<MediaItem>,
  onBackClick: () -> Unit,
  onMediaClick: (MediaItem) -> Unit,
  modifier: Modifier = Modifier
) {
  BackHandler { onBackClick() }

  val providerMedia = remember(allMedia, provider) {
    allMedia.filter { it.provider?.equals(provider.id, ignoreCase = true) == true }
  }

  var selectedFilter by remember { mutableStateOf("Tutti") }

  val filters = listOf(
    "Tutti",
    "Film",
    "Serie TV",
    "Top 10",
    "Azione",
    "Fantascienza",
    "Dramma",
    "Commedia"
  )

  val filteredItems = remember(providerMedia, selectedFilter) {
    when (selectedFilter) {
      "Tutti" -> providerMedia
      "Film" -> providerMedia.filter { it.type == MediaType.FILM }
      "Serie TV" -> providerMedia.filter { it.type == MediaType.SERIE_TV }
      "Top 10" -> providerMedia.sortedByDescending { it.rating }.take(10)
      "Azione" -> providerMedia.filter { item ->
        item.genres.any { g ->
          g.contains("Azione", ignoreCase = true) ||
          g.contains("Action", ignoreCase = true) ||
          g.contains("Avventura", ignoreCase = true) ||
          g.contains("Adventure", ignoreCase = true)
        }
      }
      "Fantascienza" -> providerMedia.filter { item ->
        item.genres.any { g ->
          g.contains("Fantascienza", ignoreCase = true) ||
          g.contains("Sci-Fi", ignoreCase = true) ||
          g.contains("Sci Fi", ignoreCase = true) ||
          g.contains("Fantasy", ignoreCase = true)
        }
      }
      "Dramma" -> providerMedia.filter { item ->
        item.genres.any { g ->
          g.contains("Dramma", ignoreCase = true) ||
          g.contains("Drama", ignoreCase = true)
        }
      }
      "Commedia" -> providerMedia.filter { item ->
        item.genres.any { g ->
          g.contains("Commedia", ignoreCase = true) ||
          g.contains("Comedy", ignoreCase = true)
        }
      }
      else -> providerMedia.filter { it.genres.any { g -> g.contains(selectedFilter, ignoreCase = true) } }
    }
  }

  Column(
    modifier = modifier
      .fillMaxSize()
      .background(NovaBackground)
  ) {
    // Header con gradiente personalizzato del Provider
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .background(
          Brush.verticalGradient(
            colors = listOf(
              provider.primaryColor.copy(alpha = 0.4f),
              provider.secondaryColor.copy(alpha = 0.85f),
              NovaBackground
            )
          )
        )
        .padding(start = 32.dp, top = 26.dp, end = 32.dp, bottom = 12.dp)
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
          TvActionButton(
            text = "Torna Indietro",
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            isPrimary = false,
            onClick = onBackClick
          )

          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
          ) {
            if (provider.logoResId != null) {
              Image(
                painter = painterResource(id = provider.logoResId),
                contentDescription = provider.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                  .size(44.dp)
                  .clip(RoundedCornerShape(10.dp))
                  .border(1.dp, provider.accentColor.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
              )
            } else if (!provider.logoUrl.isNullOrBlank()) {
              AsyncImage(
                model = provider.logoUrl,
                contentDescription = provider.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                  .size(44.dp)
                  .clip(RoundedCornerShape(10.dp))
                  .border(1.dp, provider.accentColor.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
              )
            }

            Text(
              text = provider.name.uppercase(),
              color = Color.White,
              fontSize = 28.sp,
              fontWeight = FontWeight.Black,
              letterSpacing = 1.sp
            )
          }
        }

        Box(
          modifier = Modifier
            .background(Color(0x33000000), RoundedCornerShape(50))
            .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(50))
            .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
          Text(
            text = "${filteredItems.size} Titoli Disponibili",
            color = provider.accentColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
          )
        }
      }
    }

    // Filter Chips Pills con forma a pillola identica
    LazyRow(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 32.dp, vertical = 8.dp),
      horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
      items(filters) { filter ->
        val isSelected = selectedFilter == filter
        TvFocusableBox(
          shape = RoundedCornerShape(50),
          focusedScale = 1.05f,
          onClick = { selectedFilter = filter }
        ) { isFocused ->
          Box(
            modifier = Modifier
              .background(
                when {
                  isFocused -> provider.accentColor
                  isSelected -> provider.primaryColor.copy(alpha = 0.4f)
                  else -> NovaSurfaceVariant
                },
                RoundedCornerShape(50)
              )
              .border(
                1.dp,
                if (isSelected || isFocused) provider.accentColor else Color(0xFF1E293B),
                RoundedCornerShape(50)
              )
              .padding(horizontal = 16.dp, vertical = 8.dp)
          ) {
            Text(
              text = filter,
              color = if (isFocused) Color.Black else if (isSelected) provider.accentColor else NovaTextSecondary,
              fontSize = 13.sp,
              fontWeight = if (isSelected || isFocused) FontWeight.Bold else FontWeight.Medium
            )
          }
        }
      }
    }

    Spacer(modifier = Modifier.height(8.dp))

    // Griglia dei contenuti dedicati
    if (filteredItems.isEmpty()) {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .padding(32.dp),
        contentAlignment = Alignment.Center
      ) {
        Text(
          text = "Nessun contenuto trovato per il filtro \"$selectedFilter\"",
          color = NovaTextMuted,
          fontSize = 16.sp
        )
      }
    } else {
      TvFocusBringIntoView {
        LazyVerticalGrid(
          columns = GridCells.Adaptive(minSize = 155.dp),
          contentPadding = PaddingValues(start = 32.dp, end = 32.dp, top = 8.dp, bottom = 48.dp),
          horizontalArrangement = Arrangement.spacedBy(16.dp),
          verticalArrangement = Arrangement.spacedBy(16.dp),
          modifier = Modifier.fillMaxSize()
        ) {
          items(filteredItems, key = { it.id }) { item ->
            PosterMediaCard(
              media = item,
              onClick = { onMediaClick(item) },
              modifier = Modifier.fillMaxWidth()
            )
          }
        }
      }
    }
  }
}

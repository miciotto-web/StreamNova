package com.example.ui.screens

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.snapshotFlow
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import com.example.R
import com.example.BuildConfig
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.model.SearchTypeFilter
import com.example.data.prefs.AppSettingsRepository
import com.example.data.prefs.PlaybackSettings
import com.example.data.prefs.PreferredResolution
import com.example.data.prefs.SubtitleBackground
import com.example.data.prefs.SubtitlePosition
import com.example.data.prefs.SubtitleSize
import com.example.data.prefs.StreamingEngineMode
import com.example.ui.components.ContinueWatchingCard
import com.example.ui.components.PosterMediaCard
import com.example.ui.components.StandardMediaCard
import com.example.ui.components.TvActionButton
import com.example.ui.components.TvFocusBringIntoView
import com.example.ui.components.TvFocusableBox
import com.example.ui.components.TextInputDialog
import com.example.ui.components.TvPinDialog
import com.example.ui.theme.NovaBackground
import com.example.ui.theme.NovaCardBg
import com.example.ui.theme.NovaCardBgFocused
import com.example.ui.theme.NovaCyan
import com.example.ui.theme.NovaCyanBright
import com.example.ui.theme.NovaDivider
import com.example.ui.theme.NovaGreen
import com.example.ui.theme.NovaRed
import com.example.ui.theme.NovaSurface
import com.example.ui.theme.NovaSurfaceVariant
import com.example.ui.theme.NovaTextMuted
import com.example.ui.theme.NovaTextPrimary
import com.example.ui.theme.NovaTextSecondary
import com.example.ui.viewmodel.StreamNovaViewModel
import com.example.ui.viewmodel.TorBoxAccountState

enum class CategoryCatalogFilter(
  val id: String,
  @StringRes val labelRes: Int? = null,
  val brandName: String? = null
) {
  ALL("all", labelRes = R.string.filter_all),
  TOP_10("top_10", labelRes = R.string.filter_top_10),
  NETFLIX("netflix", brandName = "Netflix"),
  HBO_MAX("hbo_max", brandName = "HBO Max"),
  DISNEY_PLUS("disney_plus", brandName = "Disney+"),
  PRIME_VIDEO("prime_video", brandName = "Prime Video"),
  APPLE_TV("apple_tv", brandName = "Apple TV+"),
  ACTION("action", labelRes = R.string.filter_action),
  SCI_FI("sci_fi", labelRes = R.string.filter_sci_fi),
  DRAMA("drama", labelRes = R.string.filter_drama),
  COMEDY("comedy", labelRes = R.string.filter_comedy)
}

@Composable
fun CategoryBrowsingScreen(
  title: String,
  subtitle: String = "",
  items: List<MediaItem>,
  onMediaClick: (MediaItem) -> Unit,
  onLoadMore: (() -> Unit)? = null,
  modifier: Modifier = Modifier
) {
  var selectedFilter by remember { mutableStateOf(CategoryCatalogFilter.ALL) }

  val filters = CategoryCatalogFilter.values()

  val filteredItems = remember(items, selectedFilter) {
    when (selectedFilter) {
      CategoryCatalogFilter.ALL -> items
      CategoryCatalogFilter.TOP_10 -> items.sortedByDescending { it.rating }.take(10)
      CategoryCatalogFilter.NETFLIX -> items.filter { it.provider?.contains("netflix", ignoreCase = true) == true }
      CategoryCatalogFilter.HBO_MAX -> items.filter { it.provider?.contains("hbo", ignoreCase = true) == true }
      CategoryCatalogFilter.DISNEY_PLUS -> items.filter { it.provider?.contains("disney", ignoreCase = true) == true }
      CategoryCatalogFilter.PRIME_VIDEO -> items.filter { it.provider?.contains("prime", ignoreCase = true) == true }
      CategoryCatalogFilter.APPLE_TV -> items.filter { it.provider?.contains("apple", ignoreCase = true) == true }
      CategoryCatalogFilter.ACTION -> items.filter { item ->
        item.genres.any { g ->
          g.contains("Azione", ignoreCase = true) ||
          g.contains("Action", ignoreCase = true) ||
          g.contains("Avventura", ignoreCase = true) ||
          g.contains("Adventure", ignoreCase = true)
        }
      }
      CategoryCatalogFilter.SCI_FI -> items.filter { item ->
        item.genres.any { g ->
          g.contains("Fantascienza", ignoreCase = true) ||
          g.contains("Sci-Fi", ignoreCase = true) ||
          g.contains("Sci Fi", ignoreCase = true) ||
          g.contains("Fantasy", ignoreCase = true)
        }
      }
      CategoryCatalogFilter.DRAMA -> items.filter { item ->
        item.genres.any { g ->
          g.contains("Dramma", ignoreCase = true) ||
          g.contains("Drama", ignoreCase = true)
        }
      }
      CategoryCatalogFilter.COMEDY -> items.filter { item ->
        item.genres.any { g ->
          g.contains("Commedia", ignoreCase = true) ||
          g.contains("Comedy", ignoreCase = true)
        }
      }
    }
  }

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

    // Filter Chips Row
    LazyRow(
      modifier = Modifier.padding(vertical = 14.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      items(filters) { filter ->
        val isSelected = selectedFilter == filter
        val label = filter.labelRes?.let { stringResource(it) } ?: filter.brandName ?: ""
        TvFocusableBox(
          shape = RoundedCornerShape(20.dp),
          onClick = { selectedFilter = filter }
        ) { isFocused ->
          Box(
            modifier = Modifier
              .background(
                when {
                  isFocused -> NovaCyanBright
                  isSelected -> NovaCyan.copy(alpha = 0.25f)
                  else -> NovaSurfaceVariant
                },
                RoundedCornerShape(20.dp)
              )
              .border(
                1.dp,
                if (isSelected || isFocused) NovaCyanBright else Color(0xFF1E293B),
                RoundedCornerShape(20.dp)
              )
              .padding(horizontal = 14.dp, vertical = 8.dp)
          ) {
            Text(
              text = label,
              color = if (isFocused) NovaBackground else if (isSelected) NovaCyanBright else NovaTextSecondary,
              fontSize = 12.sp,
              fontWeight = if (isSelected || isFocused) FontWeight.Bold else FontWeight.Medium
            )
          }
        }
      }
    }

    TvFocusBringIntoView {
      LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 150.dp),
        contentPadding = PaddingValues(bottom = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize()
      ) {
        itemsIndexed(filteredItems, key = { _, item -> item.id }) { index, item ->
          if (index >= filteredItems.size - 6) {
            LaunchedEffect(Unit) {
              onLoadMore?.invoke()
            }
          }
          Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.TopCenter
          ) {
            PosterMediaCard(
              media = item,
              onClick = { onMediaClick(item) }
            )
          }
        }
      }
    }
  }
}

@Composable
fun FavoritesScreen(
  allMedia: List<MediaItem>,
  onMediaClick: (MediaItem) -> Unit,
  modifier: Modifier = Modifier
) {
  val favorites = allMedia.filter { it.isFavorite }

  Column(
    modifier = modifier
      .fillMaxSize()
      .background(NovaBackground)
      .padding(start = 32.dp, top = 24.dp, end = 32.dp)
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Column {
        Text(
          text = stringResource(R.string.favorites_title),
          color = NovaTextPrimary,
          fontSize = 26.sp,
          fontWeight = FontWeight.Black,
          letterSpacing = 0.5.sp
        )
        Text(
          text = stringResource(R.string.favorites_subtitle),
          color = NovaTextSecondary,
          fontSize = 13.sp,
          modifier = Modifier.padding(top = 2.dp)
        )
      }

      Box(
        modifier = Modifier
          .background(Color(0x33000000), RoundedCornerShape(50))
          .border(1.dp, NovaCyanBright.copy(alpha = 0.5f), RoundedCornerShape(50))
          .padding(horizontal = 16.dp, vertical = 6.dp)
      ) {
        Text(
          text = if (favorites.isEmpty()) stringResource(R.string.favorites_count_zero) else stringResource(R.string.favorites_count_format, favorites.size),
          color = NovaCyanBright,
          fontSize = 12.sp,
          fontWeight = FontWeight.Bold
        )
      }
    }

    Spacer(modifier = Modifier.height(20.dp))

    if (favorites.isEmpty()) {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .padding(32.dp),
        contentAlignment = Alignment.Center
      ) {
        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          Text(
            text = stringResource(R.string.favorites_empty_title),
            color = NovaTextPrimary,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
          )
          Text(
            text = stringResource(R.string.favorites_empty_desc),
            color = NovaTextMuted,
            fontSize = 14.sp
          )
        }
      }
    } else {
      TvFocusBringIntoView {
        LazyVerticalGrid(
          columns = GridCells.Adaptive(minSize = 150.dp),
          contentPadding = PaddingValues(bottom = 48.dp),
          horizontalArrangement = Arrangement.spacedBy(16.dp),
          verticalArrangement = Arrangement.spacedBy(16.dp),
          modifier = Modifier.fillMaxSize()
        ) {
          items(favorites, key = { "fav_grid_${it.id}" }) { item ->
            Box(
              modifier = Modifier.fillMaxWidth(),
              contentAlignment = Alignment.TopCenter
            ) {
              PosterMediaCard(
                media = item,
                onClick = { onMediaClick(item) }
              )
            }
          }
        }
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
  FavoritesScreen(
    allMedia = allMedia,
    onMediaClick = onMediaClick,
    modifier = modifier
  )
}

data class GlobalSearchCategory(
  val id: String,
  @StringRes val labelRes: Int,
  val gradientStart: Color,
  val gradientEnd: Color,
  val accentColor: Color,
  val keywords: List<String>,
  val movieGenreId: Int? = null,
  val tvGenreId: Int? = null
)

val GLOBAL_SEARCH_CATEGORIES = listOf(
  GlobalSearchCategory("action_adventure", R.string.search_cat_action_adventure, Color(0xFFEF4444), Color(0xFF991B1B), Color(0xFFFCA5A5), listOf("Action & Adventure", "Azione & Avventura", "Action", "Azione", "Avventura"), movieGenreId = 28, tvGenreId = 10759),
  GlobalSearchCategory("animazione", R.string.filter_animation, Color(0xFFF59E0B), Color(0xFFB45309), Color(0xFFFDE68A), listOf("Animazione", "Animation"), movieGenreId = 16, tvGenreId = 16),
  GlobalSearchCategory("avventura", R.string.search_cat_adventure, Color(0xFF10B981), Color(0xFF047857), Color(0xFFA7F3D0), listOf("Avventura", "Adventure"), movieGenreId = 12, tvGenreId = 10759),
  GlobalSearchCategory("azione", R.string.filter_action, Color(0xFFDC2626), Color(0xFF7F1D1D), Color(0xFFFECACA), listOf("Azione", "Action"), movieGenreId = 28, tvGenreId = 10759),
  GlobalSearchCategory("commedia", R.string.filter_comedy, Color(0xFFEAB308), Color(0xFFA16207), Color(0xFFFEF08A), listOf("Commedia", "Comedy"), movieGenreId = 35, tvGenreId = 35),
  GlobalSearchCategory("crime", R.string.search_cat_crime, Color(0xFF64748B), Color(0xFF334155), Color(0xFFCBD5E1), listOf("Crime", "Crimine", "Poliziesco"), movieGenreId = 80, tvGenreId = 80),
  GlobalSearchCategory("documentario", R.string.search_cat_documentary, Color(0xFF06B6D4), Color(0xFF0E7490), Color(0xFFA5F3FC), listOf("Documentario", "Documnetario", "Documentary"), movieGenreId = 99, tvGenreId = 99),
  GlobalSearchCategory("dramma", R.string.filter_drama, Color(0xFF8B5CF6), Color(0xFF5B21B6), Color(0xFFDDD6FE), listOf("Dramma", "Drama"), movieGenreId = 18, tvGenreId = 18),
  GlobalSearchCategory("famiglia", R.string.search_cat_family, Color(0xFFEC4899), Color(0xFF9D174D), Color(0xFFFBCFE8), listOf("Famiglia", "Family", "Kids"), movieGenreId = 10751, tvGenreId = 10762),
  GlobalSearchCategory("fantascienza", R.string.filter_sci_fi, Color(0xFF00E5FF), Color(0xFF007799), Color(0xFFE0F7FA), listOf("Fantascienza", "Sci-Fi", "Science Fiction"), movieGenreId = 878, tvGenreId = 10765),
  GlobalSearchCategory("fantasy", R.string.search_cat_fantasy, Color(0xFFA855F7), Color(0xFF6B21A8), Color(0xFFF3E8FF), listOf("Fantasy", "Fantastico"), movieGenreId = 14, tvGenreId = 10765),
  GlobalSearchCategory("guerra", R.string.search_cat_war, Color(0xFF78716C), Color(0xFF44403C), Color(0xFFE7E5E4), listOf("Guerra", "War", "Guerra & Politica"), movieGenreId = 10752, tvGenreId = 10768),
  GlobalSearchCategory("horror", R.string.search_cat_horror, Color(0xFFE11D48), Color(0xFF881337), Color(0xFFFECDD3), listOf("Horror"), movieGenreId = 27, tvGenreId = 27),
  GlobalSearchCategory("kids", R.string.search_cat_kids, Color(0xFFF43F5E), Color(0xFF9F1239), Color(0xFFFFE4E6), listOf("Kids", "Bambini", "Famiglia"), movieGenreId = 10751, tvGenreId = 10762),
  GlobalSearchCategory("mistero", R.string.search_cat_mystery, Color(0xFF6366F1), Color(0xFF3730A3), Color(0xFFE0E7FF), listOf("Mistero", "Mystery"), movieGenreId = 9648, tvGenreId = 9648),
  GlobalSearchCategory("musica", R.string.search_cat_music, Color(0xFF14B8A6), Color(0xFF0F766E), Color(0xFFCCFBF1), listOf("Musica", "Music", "Musicale"), movieGenreId = 10402, tvGenreId = null),
  GlobalSearchCategory("news", R.string.search_cat_news, Color(0xFF3B82F6), Color(0xFF1E40AF), Color(0xFFBFDBFE), listOf("News", "Notizie", "Attualità"), movieGenreId = null, tvGenreId = 10763),
  GlobalSearchCategory("reality", R.string.search_cat_reality, Color(0xFFFB923C), Color(0xFFC2410C), Color(0xFFFFEDD5), listOf("Reality", "Reality-TV"), movieGenreId = null, tvGenreId = 10764),
  GlobalSearchCategory("romance", R.string.search_cat_romance, Color(0xFFF472B6), Color(0xFFBE185D), Color(0xFFFCE7F3), listOf("Romance", "Romantico", "Sentimentale"), movieGenreId = 10749, tvGenreId = 18),
  GlobalSearchCategory("sci_fi_fantasy", R.string.search_cat_sci_fi_fantasy, Color(0xFF0284C7), Color(0xFF4338CA), Color(0xFFBAE6FD), listOf("Sci-Fi & Fantasy", "Fantascienza", "Fantasy"), movieGenreId = 878, tvGenreId = 10765),
  GlobalSearchCategory("storia", R.string.search_cat_history, Color(0xFFD97706), Color(0xFF78350F), Color(0xFFFDE68A), listOf("Storia", "History", "Storico", "Biografia"), movieGenreId = 36, tvGenreId = 18),
  GlobalSearchCategory("thriller", R.string.search_cat_thriller, Color(0xFFEA580C), Color(0xFF9A3412), Color(0xFFFFEDD5), listOf("Thriller", "Suspense"), movieGenreId = 53, tvGenreId = 9648),
  GlobalSearchCategory("western", R.string.search_cat_western, Color(0xFFB45309), Color(0xFF713F12), Color(0xFFFEF3C7), listOf("Western"), movieGenreId = 37, tvGenreId = 37),
  GlobalSearchCategory("televisione_film", R.string.search_cat_tv_movie, Color(0xFF38BDF8), Color(0xFF0369A1), Color(0xFFE0F2FE), listOf("Televisione Film", "Film TV", "TV Movie", "Cinema", "Film"), movieGenreId = 10770, tvGenreId = null)
)

/**
 * Barra di ricerca TV.
 * Comportamento D-pad: la barra è focusabile e mostra il testo, ma la tastiera (IME)
 * NON si apre automaticamente quando riceve il focus. La tastiera viene attivata solo
 * premendo ENTER/OK sulla barra, entrando in modalità editing.
 */
@Composable
private fun SearchInputBar(
  query: String,
  onQueryChange: (String) -> Unit,
  onSubmit: () -> Unit,
  onClear: () -> Unit,
  modifier: Modifier = Modifier
) {
  var isEditing by remember { mutableStateOf(false) }
  val fieldFocusRequester = remember { FocusRequester() }
  val focusManager = LocalFocusManager.current

  // Se l'utente preme Back durante l'editing, chiude la modalità editing tornando allo stato di riposo
  BackHandler(enabled = isEditing) {
    isEditing = false
  }

  // All'attivazione esplicita (solo con ENTER/OK) porta il focus sull'input e apre la tastiera.
  LaunchedEffect(isEditing) {
    if (isEditing) {
      withFrameNanos { }
      fieldFocusRequester.requestFocus()
    }
  }

  if (!isEditing) {
    // STATO DI RIPOSO: navigabile col D-pad senza mai aprire la tastiera.
    // La tastiera si apre SOLO premendo ENTER/tasto centrale del telecomando.
    TvFocusableBox(
      modifier = modifier.fillMaxWidth(),
      shape = RoundedCornerShape(50),
      focusedScale = 1f,
      onClick = {
        isEditing = true
      }
    ) { isFocused ->
      SearchBarRow(
        borderWidth = if (isFocused) 2.dp else 1.dp,
        borderColor = if (isFocused) NovaCyanBright else NovaCyan.copy(alpha = 0.4f),
        trailing = {
          if (query.isNotEmpty()) {
            Icon(
              imageVector = Icons.Default.Close,
              contentDescription = stringResource(R.string.action_clear),
              tint = NovaTextSecondary,
              modifier = Modifier
                .size(20.dp)
                .clickable { onClear() }
            )
          }
        }
      ) {
        Text(
          text = query.ifBlank { stringResource(R.string.search_input_placeholder) },
          color = if (query.isBlank()) NovaTextMuted else NovaTextPrimary,
          fontSize = if (query.isBlank()) 14.sp else 16.sp,
          fontWeight = FontWeight.Medium,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f)
        )
      }
    }
  } else {
    SearchBarRow(
      borderWidth = 1.5.dp,
      borderColor = NovaCyanBright,
      trailing = {
        if (query.isNotEmpty()) {
          Icon(
            imageVector = Icons.Default.Close,
            contentDescription = stringResource(R.string.action_clear),
            tint = NovaTextSecondary,
            modifier = Modifier
              .size(20.dp)
              .clickable { onClear() }
          )
        }
      }
    ) {
      BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        maxLines = 1,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(
          onSearch = {
            isEditing = false
            onSubmit()
          }
        ),
        textStyle = TextStyle(
          color = NovaTextPrimary,
          fontSize = 16.sp,
          fontWeight = FontWeight.Medium
        ),
        cursorBrush = SolidColor(NovaCyanBright),
        modifier = Modifier
          .weight(1f)
          .focusRequester(fieldFocusRequester)
          .onFocusChanged {
            if (!it.isFocused) {
              isEditing = false
            }
          }
          // SU/GIÙ escono dalla barra verso i contenuti, chiudendo l'editing.
          .onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown &&
              (event.key == Key.DirectionDown || event.key == Key.DirectionUp)
            ) {
              isEditing = false
              val direction = if (event.key == Key.DirectionDown) FocusDirection.Down else FocusDirection.Up
              focusManager.moveFocus(direction)
            } else {
              false
            }
          }
          // ENTER/OK invia la ricerca e chiude l'editing.
          .onKeyEvent { event ->
            if (event.key == Key.Enter || event.key == Key.NumPadEnter || event.key == Key.DirectionCenter) {
              if (event.type == KeyEventType.KeyUp) {
                isEditing = false
                onSubmit()
              }
              true
            } else {
              false
            }
          },
        decorationBox = { innerTextField ->
          if (query.isEmpty()) {
            Text(
              text = stringResource(R.string.search_input_placeholder),
              color = NovaTextMuted,
              fontSize = 14.sp
            )
          }
          innerTextField()
        }
      )
    }
  }
}

@Composable
private fun SearchBarRow(
  borderWidth: androidx.compose.ui.unit.Dp = 1.dp,
  borderColor: Color = NovaCyan.copy(alpha = 0.4f),
  trailing: @Composable () -> Unit = {},
  content: @Composable RowScope.() -> Unit
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .background(NovaSurfaceVariant, RoundedCornerShape(50))
      .border(borderWidth, borderColor, RoundedCornerShape(50))
      .padding(horizontal = 20.dp, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Icon(
      imageVector = Icons.Default.Search,
      contentDescription = stringResource(R.string.nav_search),
      tint = NovaCyanBright,
      modifier = Modifier.size(24.dp)
    )
    Spacer(modifier = Modifier.width(12.dp))
    content()
    trailing()
  }
}

@OptIn(ExperimentalLayoutApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun SearchScreen(
  viewModel: StreamNovaViewModel,
  onMediaClick: (MediaItem) -> Unit,
  modifier: Modifier = Modifier
) {
  val query by viewModel.searchQuery.collectAsState()
  val results by viewModel.filteredSearchResults.collectAsState()
  val allMedia by viewModel.allMedia.collectAsState()
  val searchFilter by viewModel.searchFilter.collectAsState()
  val categoryItems by viewModel.categoryItems.collectAsState()
  val isCategoryLoading by viewModel.isCategoryLoading.collectAsState()

  val savedCategoryId by viewModel.selectedSearchCategoryId.collectAsState()
  var selectedCategory by remember {
    mutableStateOf(
      savedCategoryId?.let { id ->
        GLOBAL_SEARCH_CATEGORIES.find { it.id == id }
      }
    )
  }

  var isSearchSubmitted by remember { mutableStateOf(false) }

  val searchBarFocusRequester = remember { FocusRequester() }
  val tuttiFilterFocusRequester = remember { FocusRequester() }
  val filmFilterFocusRequester = remember { FocusRequester() }
  val serieTvFilterFocusRequester = remember { FocusRequester() }
  val contentFirstItemFocusRequester = remember { FocusRequester() }
  val backButtonFocusRequester = remember { FocusRequester() }

  val categoryFocusRequesters = remember {
    List(GLOBAL_SEARCH_CATEGORIES.size) { index ->
      if (index == 0) contentFirstItemFocusRequester else FocusRequester()
    }
  }

  val activeFilterRequester = when (searchFilter) {
    SearchTypeFilter.ALL -> tuttiFilterFocusRequester
    SearchTypeFilter.FILM -> filmFilterFocusRequester
    SearchTypeFilter.SERIE_TV -> serieTvFilterFocusRequester
  }

  LaunchedEffect(savedCategoryId) {
    if (savedCategoryId != null) {
      if (selectedCategory == null || selectedCategory?.id != savedCategoryId) {
        selectedCategory = GLOBAL_SEARCH_CATEGORIES.find { it.id == savedCategoryId }
      }
      try {
        contentFirstItemFocusRequester.requestFocus()
      } catch (_: Exception) {
        try {
          activeFilterRequester.requestFocus()
        } catch (_: Exception) {}
      }
    }
  }

  LaunchedEffect(selectedCategory) {
    if (selectedCategory != null) {
      try {
        contentFirstItemFocusRequester.requestFocus()
      } catch (_: Exception) {
        try {
          activeFilterRequester.requestFocus()
        } catch (_: Exception) {}
      }
    }
  }

  // All'ingresso nella schermata Cerca porta il focus sulla barra di ricerca (a riposo, senza tastiera)
  LaunchedEffect(Unit) {
    if (savedCategoryId != null || selectedCategory != null) return@LaunchedEffect
    withFrameNanos { }
    try {
      searchBarFocusRequester.requestFocus()
    } catch (_: Exception) {
      try {
        tuttiFilterFocusRequester.requestFocus()
      } catch (_: Exception) {}
    }
  }

  // Media filtrati per la categoria selezionata
  val categoryMedia = remember(selectedCategory, allMedia) {
    if (selectedCategory == null) emptyList()
    else {
      allMedia.filter { item ->
        selectedCategory!!.keywords.any { kw ->
          item.genres.any { g -> g.contains(kw, ignoreCase = true) } ||
          item.title.contains(kw, ignoreCase = true) ||
          item.synopsis.contains(kw, ignoreCase = true)
        }
      }
    }
  }

  // Gestione pulsante Indietro del telecomando
  if (selectedCategory != null) {
    BackHandler {
      viewModel.setSelectedSearchCategoryId(null)
      selectedCategory = null
    }
  } else if (isSearchSubmitted || query.isNotBlank()) {
    BackHandler {
      isSearchSubmitted = false
      viewModel.updateSearchQuery("")
    }
  }

  Column(
    modifier = modifier
      .fillMaxSize()
      .background(NovaBackground)
      .padding(start = 32.dp, top = 24.dp, end = 32.dp)
  ) {
    // Header con Titolo e Pulsante Indietro
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Column {
        Text(
          text = stringResource(R.string.search_screen_title),
          color = NovaTextPrimary,
          fontSize = 26.sp,
          fontWeight = FontWeight.Black,
          letterSpacing = 0.5.sp
        )
        Text(
          text = stringResource(R.string.search_screen_subtitle),
          color = NovaTextSecondary,
          fontSize = 13.sp,
          modifier = Modifier.padding(top = 2.dp)
        )
      }

      if (selectedCategory != null || query.isNotBlank()) {
        TvActionButton(
          text = stringResource(R.string.search_back_to_categories),
          icon = Icons.AutoMirrored.Filled.ArrowBack,
          isPrimary = false,
          onClick = {
            viewModel.setSelectedSearchCategoryId(null)
            selectedCategory = null
            isSearchSubmitted = false
            viewModel.updateSearchQuery("")
          },
          modifier = Modifier
            .focusRequester(backButtonFocusRequester)
            .focusProperties {
              down = searchBarFocusRequester
              up = FocusRequester.Cancel
              left = FocusRequester.Cancel
            }
        )
      }
    }

    Spacer(modifier = Modifier.height(16.dp))

    // Search Input Bar (Pill Shape): la tastiera si apre solo con ENTER/OK sulla barra.
    SearchInputBar(
      query = query,
      onQueryChange = {
        viewModel.updateSearchQuery(it)
        if (it.isBlank()) {
          isSearchSubmitted = false
        }
      },
      onSubmit = {
        if (query.isNotBlank()) {
          viewModel.setSelectedSearchCategoryId(null)
          selectedCategory = null
          isSearchSubmitted = true
        }
      },
      onClear = {
        viewModel.updateSearchQuery("")
        isSearchSubmitted = false
      },
      modifier = Modifier
        .focusRequester(searchBarFocusRequester)
        .focusProperties {
          up = if (selectedCategory != null || query.isNotBlank()) backButtonFocusRequester else FocusRequester.Cancel
          down = activeFilterRequester
          left = FocusRequester.Cancel
        }
    )

    Spacer(modifier = Modifier.height(12.dp))

    // Selettore Filtro Ricerca D-Pad: ["Tutti", "Film", "Serie TV"]
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(bottom = 12.dp),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      SearchTypeFilter.values().forEach { filter ->
        val isSelected = searchFilter == filter
        val requester = when (filter) {
          SearchTypeFilter.ALL -> tuttiFilterFocusRequester
          SearchTypeFilter.FILM -> filmFilterFocusRequester
          SearchTypeFilter.SERIE_TV -> serieTvFilterFocusRequester
        }
        TvFocusableBox(
          modifier = Modifier
            .focusRequester(requester)
            .focusProperties {
              up = searchBarFocusRequester
              down = contentFirstItemFocusRequester
              when (filter) {
                SearchTypeFilter.ALL -> {
                  left = FocusRequester.Cancel
                  right = filmFilterFocusRequester
                }
                SearchTypeFilter.FILM -> {
                  left = tuttiFilterFocusRequester
                  right = serieTvFilterFocusRequester
                }
                SearchTypeFilter.SERIE_TV -> {
                  left = filmFilterFocusRequester
                  right = FocusRequester.Cancel
                }
              }
            },
          shape = RoundedCornerShape(50),
          focusedScale = 1.05f,
          onClick = { viewModel.setSearchFilter(filter) }
        ) { isFocused ->
          Row(
            modifier = Modifier
              .background(
                if (isSelected) NovaCyan else if (isFocused) NovaSurfaceVariant else Color(0x331E293B),
                RoundedCornerShape(50)
              )
              .border(
                width = if (isFocused) 1.5.dp else 1.dp,
                color = if (isFocused) NovaCyanBright else if (isSelected) NovaCyan else Color(0x33475569),
                shape = RoundedCornerShape(50)
              )
              .padding(horizontal = 18.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            Text(
              text = when (filter) {
                SearchTypeFilter.ALL -> stringResource(R.string.filter_all)
                SearchTypeFilter.FILM -> stringResource(R.string.filter_movies)
                SearchTypeFilter.SERIE_TV -> stringResource(R.string.filter_tv_series)
              },
              color = if (isSelected) Color.Black else if (isFocused) NovaCyanBright else NovaTextPrimary,
              fontSize = 13.sp,
              fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
            )
          }
        }
      }
    }

    // Vista Condizionale: Categoria Selezionata / Risultati di Ricerca Testo / Default con soli Badge Colorati
    when {
      selectedCategory != null -> {
        val cat = selectedCategory!!
        val rawCategoryMedia = if (categoryItems.isNotEmpty()) categoryItems else categoryMedia
        val displayedCategoryMedia = remember(rawCategoryMedia, searchFilter) {
          when (searchFilter) {
            SearchTypeFilter.ALL -> rawCategoryMedia
            SearchTypeFilter.FILM -> rawCategoryMedia.filter { it.type == MediaType.FILM }
            SearchTypeFilter.SERIE_TV -> rawCategoryMedia.filter { it.type == MediaType.SERIE_TV }
          }
        }
        val categoryGridState = rememberLazyGridState()

        // Paginazione infinita allo scorrimento della griglia
        LaunchedEffect(categoryGridState) {
          snapshotFlow {
            val layout = categoryGridState.layoutInfo
            val total = layout.totalItemsCount
            val last = layout.visibleItemsInfo.lastOrNull()?.index ?: 0
            total to last
          }.collect { (total, last) ->
            if (total > 0 && last >= total - 6) {
              viewModel.loadNextCategoryPage()
            }
          }
        }

        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 14.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
          ) {
            Box(
              modifier = Modifier
                .background(
                  brush = Brush.horizontalGradient(listOf(cat.gradientStart, cat.gradientEnd)),
                  shape = RoundedCornerShape(50)
                )
                .border(1.5.dp, cat.accentColor, RoundedCornerShape(50))
                .padding(horizontal = 18.dp, vertical = 7.dp)
            ) {
              Text(
                text = stringResource(cat.labelRes).uppercase(),
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 0.5.sp
              )
            }
            Text(
              text = stringResource(R.string.search_category_desc),
              color = NovaTextSecondary,
              fontSize = 13.sp
            )
          }

          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
          ) {
            if (isCategoryLoading) {
              CircularProgressIndicator(
                color = NovaCyanBright,
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp
              )
            }
            Box(
              modifier = Modifier
                .background(Color(0x33000000), RoundedCornerShape(50))
                .border(1.dp, cat.accentColor.copy(alpha = 0.5f), RoundedCornerShape(50))
                .padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
              Text(
                text = stringResource(R.string.search_titles_available, displayedCategoryMedia.size),
                color = cat.accentColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
              )
            }
          }
        }

        if (displayedCategoryMedia.isEmpty() && !isCategoryLoading) {
          Box(
            modifier = Modifier
              .fillMaxSize()
              .padding(32.dp),
            contentAlignment = Alignment.Center
          ) {
            Text(
              text = stringResource(R.string.search_no_content_for_category, stringResource(cat.labelRes)),
              color = NovaTextMuted,
              fontSize = 16.sp
            )
          }
        } else {
          TvFocusBringIntoView {
            LazyVerticalGrid(
              state = categoryGridState,
              columns = GridCells.Adaptive(minSize = 150.dp),
              contentPadding = PaddingValues(bottom = 48.dp),
              horizontalArrangement = Arrangement.spacedBy(16.dp),
              verticalArrangement = Arrangement.spacedBy(16.dp),
              modifier = Modifier.fillMaxSize()
            ) {
              itemsIndexed(displayedCategoryMedia, key = { _, item -> "cat_${cat.id}_${item.id}" }) { index, item ->
                Box(
                  modifier = Modifier
                    .fillMaxWidth()
                    .then(if (index == 0) Modifier.focusRequester(contentFirstItemFocusRequester) else Modifier)
                    .focusProperties {
                      if (index < 5) {
                        up = activeFilterRequester
                      }
                    },
                  contentAlignment = Alignment.TopCenter
                ) {
                  PosterMediaCard(
                    media = item,
                    onClick = { onMediaClick(item) }
                  )
                }
              }
            }
          }
        }
      }

      query.isNotBlank() -> {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 14.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text(
            text = stringResource(R.string.search_results_found, query, results.size),
            color = NovaCyanBright,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold
          )
        }

        if (results.isEmpty()) {
          Box(
            modifier = Modifier
              .fillMaxSize()
              .padding(32.dp),
            contentAlignment = Alignment.Center
          ) {
            Text(
              text = stringResource(R.string.search_no_results, query),
              color = NovaTextMuted,
              fontSize = 16.sp
            )
          }
        } else {
          TvFocusBringIntoView {
            LazyVerticalGrid(
              columns = GridCells.Adaptive(minSize = 150.dp),
              contentPadding = PaddingValues(bottom = 48.dp),
              horizontalArrangement = Arrangement.spacedBy(16.dp),
              verticalArrangement = Arrangement.spacedBy(16.dp),
              modifier = Modifier.fillMaxSize()
            ) {
              itemsIndexed(results, key = { _, item -> "search_${item.id}" }) { index, item ->
                Box(
                  modifier = Modifier
                    .fillMaxWidth()
                    .then(if (index == 0) Modifier.focusRequester(contentFirstItemFocusRequester) else Modifier)
                    .focusProperties {
                      if (index < 5) {
                        up = activeFilterRequester
                      }
                    },
                  contentAlignment = Alignment.TopCenter
                ) {
                  PosterMediaCard(
                    media = item,
                    onClick = { onMediaClick(item) }
                  )
                }
              }
            }
          }
        }
      }

      else -> {
        // STATO DEFAULT: Nessun poster! Mostra solo i badge colorati per la ricerca globale
        Column(modifier = Modifier.fillMaxSize()) {
          Text(
            text = stringResource(R.string.search_global_title),
            color = NovaCyanBright,
            fontSize = 13.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.sp
          )
          Text(
            text = stringResource(R.string.search_global_subtitle),
            color = NovaTextSecondary,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 2.dp, bottom = 14.dp)
          )

          TvFocusBringIntoView {
          LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 170.dp),
            contentPadding = PaddingValues(bottom = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
          ) {
            itemsIndexed(GLOBAL_SEARCH_CATEGORIES, key = { _, it -> it.id }) { index, cat ->
              val requester = if (index < categoryFocusRequesters.size) categoryFocusRequesters[index] else null
              TvFocusableBox(
                modifier = Modifier
                  .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
                  .focusProperties {
                    if (index < 4) {
                      up = activeFilterRequester
                    } else if (index - 4 < categoryFocusRequesters.size) {
                      up = categoryFocusRequesters[index - 4]
                    }
                    if (index + 4 < categoryFocusRequesters.size) {
                      down = categoryFocusRequesters[index + 4]
                    }
                  },
                shape = RoundedCornerShape(50),
                focusedScale = 1.05f,
                onClick = {
                  try {
                    activeFilterRequester.requestFocus()
                  } catch (_: Exception) {}
                  viewModel.setSelectedSearchCategoryId(cat.id)
                  selectedCategory = cat
                  isSearchSubmitted = false
                  val localMatches = allMedia.filter { item ->
                    cat.keywords.any { kw ->
                      item.genres.any { g -> g.contains(kw, ignoreCase = true) } ||
                      item.title.contains(kw, ignoreCase = true) ||
                      item.synopsis.contains(kw, ignoreCase = true)
                    }
                  }
                  viewModel.initCategory(cat.movieGenreId, cat.tvGenreId, localMatches)
                }
              ) { isFocused ->
                Box(
                  modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(
                      brush = Brush.horizontalGradient(
                        colors = listOf(
                          cat.gradientStart.copy(alpha = if (isFocused) 0.95f else 0.70f),
                          cat.gradientEnd.copy(alpha = if (isFocused) 0.95f else 0.70f)
                        )
                      ),
                      shape = RoundedCornerShape(50)
                    )
                    .border(
                      width = if (isFocused) 2.dp else 1.dp,
                      color = if (isFocused) Color.White else cat.accentColor.copy(alpha = 0.5f),
                      shape = RoundedCornerShape(50)
                    )
                    .padding(horizontal = 16.dp),
                  contentAlignment = Alignment.Center
                ) {
                  Text(
                    text = stringResource(cat.labelRes).uppercase(),
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp,
                    maxLines = 1
                  )
                }
              }
            }
          }
          }
        }
      }
    }
  }
}

@Composable
fun SettingsScreen(
  viewModel: StreamNovaViewModel? = null,
  modifier: Modifier = Modifier
) {
  val isRefreshing = viewModel?.isRefreshing?.collectAsState()?.value ?: false
  val appVersion = BuildConfig.VERSION_NAME
  val playbackSettings = viewModel?.playbackSettings?.collectAsState()?.value ?: PlaybackSettings()
  // ── Debrid / TorBox ─────────────────────────────────────────────────
  val torBoxApiKey = viewModel?.torBoxApiKey?.collectAsState()?.value
  val torBoxEnabled = viewModel?.torBoxInstantDebridEnabled?.collectAsState()?.value ?: true
  val torBoxState = viewModel?.torBoxAccountState?.collectAsState()?.value ?: TorBoxAccountState.Unknown
  val streamingEngineMode = AppSettingsRepository.streamingEngineMode.collectAsState().value ?: StreamingEngineMode.HTTP_WEB
  val autoplayEnabled = viewModel?.autoplayEnabled?.collectAsState()?.value ?: false
  val appLanguage = viewModel?.appLanguage?.collectAsState()?.value ?: "it"
  val parentalControlEnabled = viewModel?.parentalControlEnabled?.collectAsState()?.value ?: false
  val parentalControlLevel = viewModel?.parentalControlLevel?.collectAsState()?.value ?: "18"
  var showTorBoxKeyDialog by remember { mutableStateOf(false) }
  var showResolutionDialog by remember { mutableStateOf(false) }
  var showSubtitleLanguageDialog by remember { mutableStateOf(false) }
  var showSubtitleSizeDialog by remember { mutableStateOf(false) }
  var showSubtitleBackgroundDialog by remember { mutableStateOf(false) }
  var showSubtitlePositionDialog by remember { mutableStateOf(false) }
  var showAudioLanguageDialog by remember { mutableStateOf(false) }
  var showAppLanguageDialog by remember { mutableStateOf(false) }
  var showStreamingEngineModeDialog by remember { mutableStateOf(false) }
  var showParentalPinDialog by remember { mutableStateOf(false) }
  var parentalPinStep by remember { mutableStateOf<ParentalPinStep>(ParentalPinStep.None) }
  var parentalPinErrorMessage by remember { mutableStateOf<String?>(null) }
  var showParentalManageDialog by remember { mutableStateOf(false) }
  var showParentalLevelDialog by remember { mutableStateOf(false) }

  // Verifica automatica (una sola volta) della chiave salvata: all'ingresso in
  // Impostazioni l'indicatore diventa verde "Collegato" se la chiave è valida.
  LaunchedEffect(torBoxApiKey) {
    if (!torBoxApiKey.isNullOrBlank() && torBoxState is TorBoxAccountState.Unknown) {
      viewModel?.verifyTorBoxAccount()
    }
  }

  TvFocusBringIntoView {
    LazyColumn(
      modifier = modifier
        .fillMaxSize()
        .background(NovaBackground)
        .padding(start = 32.dp, top = 24.dp, end = 32.dp),
      contentPadding = PaddingValues(bottom = 48.dp),
      verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
      item {
        Text(
          text = stringResource(R.string.settings_title),
          color = NovaTextPrimary,
          fontSize = 24.sp,
          fontWeight = FontWeight.Bold
        )
        Text(
          text = stringResource(R.string.settings_subtitle),
          color = NovaTextSecondary,
          fontSize = 13.sp,
          modifier = Modifier.padding(top = 4.dp)
        )
      }

      // ── GENERALE ────────────────────────────────────────────────────────
      item {
        SettingsSection(title = stringResource(R.string.settings_sec_general)) {
          SettingsRow(
            icon = Icons.Default.Language,
            title = stringResource(R.string.settings_app_language),
            value = appLanguageLabel(appLanguage),
            onClick = { showAppLanguageDialog = true }
          )
          SettingsRow(
            icon = Icons.Default.Lock,
            title = stringResource(R.string.settings_parental_control),
            value = if (parentalControlEnabled) {
              stringResource(R.string.parental_control_on_level, "$parentalControlLevel+")
            } else {
              stringResource(R.string.parental_control_off)
            },
            onClick = {
              parentalPinErrorMessage = null
              if (!parentalControlEnabled) {
                parentalPinStep = ParentalPinStep.CreateNew
                showParentalPinDialog = true
              } else {
                parentalPinStep = ParentalPinStep.VerifyToManage
                showParentalPinDialog = true
              }
            }
          )
          SettingsRow(
            icon = Icons.Default.Refresh,
            title = stringResource(R.string.settings_check_updates),
            value = if (isRefreshing) stringResource(R.string.settings_in_progress) else null,
            onClick = { viewModel?.refreshCatalog() }
          )
          SettingsRow(
            icon = Icons.Default.Info,
            title = stringResource(R.string.settings_app_version),
            value = appVersion
          )
        }
      }

      // ── MOTORE DI RIPRODUZIONE ──────────────────────────────────────────
      item {
        SettingsSection(title = stringResource(R.string.settings_sec_playback_engine)) {
          SettingsRow(
            icon = Icons.Default.Bolt,
            title = stringResource(R.string.settings_playback_mode),
            value = when (streamingEngineMode) {
              StreamingEngineMode.DEBRID_TORBOX -> stringResource(R.string.settings_mode_debrid)
              StreamingEngineMode.HTTP_WEB -> stringResource(R.string.settings_mode_web_scraper)
            },
            onClick = { showStreamingEngineModeDialog = true }
          )
          
          if (streamingEngineMode == StreamingEngineMode.DEBRID_TORBOX) {
            // Chiave API: dialog con campo di testo (incolla da torbox.app/account)
            SettingsRow(
              icon = Icons.Default.VpnKey,
              title = stringResource(R.string.settings_torbox_api_key),
              value = torBoxApiKey?.let { maskApiKey(it) } ?: stringResource(R.string.settings_not_configured),
              valueColor = if (!torBoxApiKey.isNullOrBlank()) NovaGreen else NovaTextMuted,
              onClick = { showTorBoxKeyDialog = true }
            )

            // Stato account: verde "Collegato" se valida, altrimenti pulsante
            // "Verifica Account" (GET https://api.torbox.app/v1/api/user/me)
            SettingsRow(
              icon = Icons.Default.CloudDone,
              title = stringResource(R.string.settings_account_status),
              value = when (torBoxState) {
                is TorBoxAccountState.Checking -> stringResource(R.string.settings_status_checking)
                is TorBoxAccountState.Connected -> stringResource(R.string.settings_status_connected)
                is TorBoxAccountState.Error -> stringResource(R.string.settings_status_verify_account)
                else -> if (torBoxApiKey.isNullOrBlank()) stringResource(R.string.settings_key_not_configured) else stringResource(R.string.settings_status_verify_account)
              },
              valueColor = when (torBoxState) {
                is TorBoxAccountState.Connected -> NovaGreen
                is TorBoxAccountState.Error -> NovaRed
                else -> NovaCyanBright
              },
              onClick = { viewModel?.verifyTorBoxAccount() }
            )

            // Toggle "Usa TorBox Instant Debrid"
            SettingsRow(
              icon = Icons.Default.Bolt,
              title = stringResource(R.string.settings_use_torbox_instant),
              value = if (torBoxEnabled) stringResource(R.string.settings_value_on) else stringResource(R.string.settings_value_off),
              valueColor = if (torBoxEnabled) NovaGreen else NovaTextMuted,
              onClick = { viewModel?.setTorBoxInstantDebridEnabled(!torBoxEnabled) }
            )
          } else {
            // Spiegazione per modalità HTTP
            Text(
              text = stringResource(R.string.settings_http_desc),
              color = NovaTextSecondary,
              fontSize = 12.sp,
              modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
            )
          }
        }
      }

      // ── RIPRODUZIONE ────────────────────────────────────────────────────
      item {
        SettingsSection(title = stringResource(R.string.settings_sec_playback)) {
          SettingsRow(
            icon = Icons.Default.PlayArrow,
            title = stringResource(R.string.settings_autoplay),
            value = if (autoplayEnabled) stringResource(R.string.settings_value_on) else stringResource(R.string.settings_value_off),
            valueColor = if (autoplayEnabled) NovaGreen else NovaTextMuted,
            onClick = { viewModel?.setAutoplayEnabled(!autoplayEnabled) }
          )
          SettingsRow(
            icon = Icons.Default.HighQuality,
            title = stringResource(R.string.settings_preferred_resolution),
            value = preferredResolutionLabel(playbackSettings.preferredResolution),
            onClick = { showResolutionDialog = true }
          )
          SettingsRow(
            icon = Icons.Default.Language,
            title = stringResource(R.string.settings_preferred_audio_language),
            value = audioLanguageLabel(playbackSettings.preferredAudioLanguage),
            onClick = { showAudioLanguageDialog = true }
          )
          SettingsRow(
            icon = Icons.Default.PlayArrow,
            title = stringResource(R.string.settings_autoplay_next_episode),
            value = if (playbackSettings.autoPlayNextEpisode) stringResource(R.string.settings_value_on) else stringResource(R.string.settings_value_off),
            valueColor = if (playbackSettings.autoPlayNextEpisode) NovaGreen else NovaTextMuted,
            onClick = { viewModel?.setAutoPlayNextEpisode(!playbackSettings.autoPlayNextEpisode) }
          )
          SettingsRow(
            icon = Icons.Default.Replay,
            title = stringResource(R.string.settings_auto_resume),
            value = if (playbackSettings.autoResume) stringResource(R.string.settings_value_on) else stringResource(R.string.settings_value_off),
            valueColor = if (playbackSettings.autoResume) NovaGreen else NovaTextMuted,
            onClick = { viewModel?.setAutoResume(!playbackSettings.autoResume) }
          )
        }
      }

      // ── SOTTOTITOLI ─────────────────────────────────────────────────────
      item {
        SettingsSection(title = stringResource(R.string.settings_sec_subtitles)) {
          SettingsRow(
            icon = Icons.Default.Subtitles,
            title = stringResource(R.string.settings_auto_subtitles),
            value = if (playbackSettings.subtitlesEnabled) stringResource(R.string.settings_value_on) else stringResource(R.string.settings_value_off),
            valueColor = if (playbackSettings.subtitlesEnabled) NovaGreen else NovaTextMuted,
            onClick = { viewModel?.setSubtitlesEnabled(!playbackSettings.subtitlesEnabled) }
          )
          SettingsRow(
            icon = Icons.Default.Translate,
            title = stringResource(R.string.settings_preferred_subtitle_lang),
            value = subtitleLanguageLabel(playbackSettings.preferredSubtitleLanguage),
            onClick = { showSubtitleLanguageDialog = true }
          )
          SettingsRow(
            icon = Icons.Default.FormatSize,
            title = stringResource(R.string.settings_subtitle_size),
            value = subtitleSizeLabel(playbackSettings.subtitleSize),
            onClick = { showSubtitleSizeDialog = true }
          )
          SettingsRow(
            icon = Icons.Default.Wallpaper,
            title = stringResource(R.string.settings_subtitle_background),
            value = subtitleBackgroundLabel(playbackSettings.subtitleBackground),
            onClick = { showSubtitleBackgroundDialog = true }
          )
          SettingsRow(
            icon = Icons.Default.VerticalAlignBottom,
            title = stringResource(R.string.settings_subtitle_position),
            value = subtitlePositionLabel(playbackSettings.subtitlePosition),
            onClick = { showSubtitlePositionDialog = true }
          )
          SettingsRow(icon = Icons.Default.Palette, title = stringResource(R.string.settings_subtitle_color))
          SettingsRow(
            icon = Icons.Default.ClosedCaption,
            title = stringResource(R.string.settings_forced_subtitles),
            value = if (playbackSettings.forcedSubtitlesEnabled) stringResource(R.string.settings_value_on) else stringResource(R.string.settings_value_off),
            valueColor = if (playbackSettings.forcedSubtitlesEnabled) NovaGreen else NovaTextMuted,
            onClick = { viewModel?.setForcedSubtitlesEnabled(!playbackSettings.forcedSubtitlesEnabled) }
          )
        }
      }

      // ── AVANZATO ────────────────────────────────────────────────────────
      item {
        SettingsSection(title = stringResource(R.string.settings_sec_advanced)) {
          SettingsRow(icon = Icons.Default.BugReport, title = stringResource(R.string.settings_bug_reports))
          SettingsRow(icon = Icons.Default.PrivacyTip, title = stringResource(R.string.settings_privacy_data))
          SettingsRow(
            icon = Icons.Default.DeleteSweep,
            title = stringResource(R.string.settings_clear_cache),
            onClick = { viewModel?.clearCacheAndRefresh() }
          )
          SettingsRow(icon = Icons.Default.Restore, title = stringResource(R.string.settings_restore_defaults))
          SettingsRow(icon = Icons.Default.Gavel, title = stringResource(R.string.settings_legal_info))
        }
      }
    }
  }

  if (showTorBoxKeyDialog) {
    TextInputDialog(
      title = stringResource(R.string.settings_torbox_api_key),
      hint = stringResource(R.string.settings_torbox_key_hint),
      initialText = torBoxApiKey ?: "",
      confirmLabel = stringResource(R.string.action_save),
      onConfirm = { key ->
        showTorBoxKeyDialog = false
        viewModel?.setTorBoxApiKey(key)
        viewModel?.verifyTorBoxAccount()
      },
      onDismiss = { showTorBoxKeyDialog = false }
    )
  }

  if (showStreamingEngineModeDialog) {
    val modeOptions = listOf(
      stringResource(R.string.settings_mode_debrid),
      stringResource(R.string.settings_mode_web_scraper)
    )
    TrackSelectionDialog(
      title = stringResource(R.string.settings_playback_mode),
      options = modeOptions,
      selectedIndex = if (streamingEngineMode == StreamingEngineMode.DEBRID_TORBOX) 0 else 1,
      onSelect = { idx ->
        val newMode = if (idx == 0) StreamingEngineMode.DEBRID_TORBOX else StreamingEngineMode.HTTP_WEB
        viewModel?.setStreamingEngineMode(newMode)
        showStreamingEngineModeDialog = false
      },
      onDismiss = { showStreamingEngineModeDialog = false }
    )
  }

  if (showResolutionDialog) {
    val resOptions = PreferredResolution.values()
    val resLabels = resOptions.map { preferredResolutionLabel(it) }
    TrackSelectionDialog(
      title = stringResource(R.string.settings_preferred_resolution),
      options = resLabels,
      selectedIndex = resOptions.indexOf(playbackSettings.preferredResolution).coerceAtLeast(0),
      onSelect = { idx ->
        viewModel?.setPreferredResolution(resOptions[idx])
        showResolutionDialog = false
      },
      onDismiss = { showResolutionDialog = false }
    )
  }

  if (showSubtitleSizeDialog) {
    val sizeOptions = SubtitleSize.entries
    val sizeLabels = sizeOptions.map { subtitleSizeLabel(it) }
    TrackSelectionDialog(
      title = stringResource(R.string.settings_subtitle_size),
      options = sizeLabels,
      selectedIndex = sizeOptions.indexOf(playbackSettings.subtitleSize).coerceAtLeast(0),
      onSelect = { idx ->
        viewModel?.setSubtitleSize(sizeOptions[idx])
        showSubtitleSizeDialog = false
      },
      onDismiss = { showSubtitleSizeDialog = false }
    )
  }

  if (showSubtitleBackgroundDialog) {
    val backgroundOptions = SubtitleBackground.entries
    val backgroundLabels = backgroundOptions.map { subtitleBackgroundLabel(it) }
    TrackSelectionDialog(
      title = stringResource(R.string.settings_subtitle_background),
      options = backgroundLabels,
      selectedIndex = backgroundOptions.indexOf(playbackSettings.subtitleBackground).coerceAtLeast(0),
      onSelect = { idx ->
        viewModel?.setSubtitleBackground(backgroundOptions[idx])
        showSubtitleBackgroundDialog = false
      },
      onDismiss = { showSubtitleBackgroundDialog = false }
    )
  }

  if (showSubtitlePositionDialog) {
    val positionOptions = SubtitlePosition.entries
    val positionLabels = positionOptions.map { subtitlePositionLabel(it) }
    TrackSelectionDialog(
      title = stringResource(R.string.settings_subtitle_position),
      options = positionLabels,
      selectedIndex = positionOptions.indexOf(playbackSettings.subtitlePosition).coerceAtLeast(0),
      onSelect = { idx ->
        viewModel?.setSubtitlePosition(positionOptions[idx])
        showSubtitlePositionDialog = false
      },
      onDismiss = { showSubtitlePositionDialog = false }
    )
  }

  if (showSubtitleLanguageDialog) {
    val subOptions = SUBTITLE_LANGUAGE_CODES.map { subtitleLanguageLabel(it) }
    TrackSelectionDialog(
      title = stringResource(R.string.settings_preferred_subtitle_lang),
      options = subOptions,
      selectedIndex = SUBTITLE_LANGUAGE_CODES
        .indexOf(playbackSettings.preferredSubtitleLanguage)
        .coerceAtLeast(0),
      onSelect = { idx ->
        viewModel?.setPreferredSubtitleLanguage(SUBTITLE_LANGUAGE_CODES[idx])
        showSubtitleLanguageDialog = false
      },
      onDismiss = { showSubtitleLanguageDialog = false }
    )
  }

  if (showAudioLanguageDialog) {
    val audioOptions = AUDIO_LANGUAGE_CODES.map { audioLanguageLabel(it) }
    TrackSelectionDialog(
      title = stringResource(R.string.settings_preferred_audio_language),
      options = audioOptions,
      selectedIndex = AUDIO_LANGUAGE_CODES
        .indexOf(playbackSettings.preferredAudioLanguage)
        .coerceAtLeast(0),
      onSelect = { idx ->
        viewModel?.setPreferredAudioLanguage(AUDIO_LANGUAGE_CODES[idx])
        showAudioLanguageDialog = false
      },
      onDismiss = { showAudioLanguageDialog = false }
    )
  }

  if (showAppLanguageDialog) {
    val appOptions = APP_LANGUAGE_CODES.map { appLanguageLabel(it) }
    TrackSelectionDialog(
      title = stringResource(R.string.settings_app_language),
      options = appOptions,
      selectedIndex = APP_LANGUAGE_CODES.indexOf(appLanguage).coerceAtLeast(0),
      onSelect = { idx ->
        viewModel?.setAppLanguage(APP_LANGUAGE_CODES[idx])
        showAppLanguageDialog = false
      },
      onDismiss = { showAppLanguageDialog = false }
    )
  }

  if (showParentalPinDialog && parentalPinStep !is ParentalPinStep.None) {
    val title = stringResource(R.string.parental_dialog_enter_pin_title)
    val subtitle = when (parentalPinStep) {
      is ParentalPinStep.CreateNew, is ParentalPinStep.ChangeNewPin ->
        stringResource(R.string.parental_dialog_create_pin)
      is ParentalPinStep.ConfirmNew, is ParentalPinStep.ChangeConfirmPin ->
        stringResource(R.string.parental_dialog_confirm_pin)
      else ->
        stringResource(R.string.parental_dialog_enter_current_pin)
    }
    val mismatchMsg = stringResource(R.string.parental_dialog_pin_mismatch)
    val wrongPinMsg = stringResource(R.string.parental_dialog_wrong_pin)

    TvPinDialog(
      title = title,
      subtitle = subtitle,
      errorMessage = parentalPinErrorMessage,
      onPinSubmit = { enteredPin ->
        when (val step = parentalPinStep) {
          is ParentalPinStep.CreateNew -> {
            parentalPinErrorMessage = null
            parentalPinStep = ParentalPinStep.ConfirmNew(enteredPin)
          }
          is ParentalPinStep.ConfirmNew -> {
            if (enteredPin == step.firstPin) {
              viewModel?.setParentalControlPin(enteredPin)
              viewModel?.setParentalControlEnabled(true)
              showParentalPinDialog = false
              parentalPinStep = ParentalPinStep.None
              parentalPinErrorMessage = null
            } else {
              parentalPinErrorMessage = mismatchMsg
            }
          }
          is ParentalPinStep.VerifyToManage -> {
            val valid = viewModel?.verifyParentalControlPin(enteredPin) ?: false
            if (valid) {
              showParentalPinDialog = false
              parentalPinStep = ParentalPinStep.None
              parentalPinErrorMessage = null
              showParentalManageDialog = true
            } else {
              parentalPinErrorMessage = wrongPinMsg
            }
          }
          is ParentalPinStep.ChangeNewPin -> {
            parentalPinErrorMessage = null
            parentalPinStep = ParentalPinStep.ChangeConfirmPin(enteredPin)
          }
          is ParentalPinStep.ChangeConfirmPin -> {
            if (enteredPin == step.firstPin) {
              viewModel?.setParentalControlPin(enteredPin)
              showParentalPinDialog = false
              parentalPinStep = ParentalPinStep.None
              parentalPinErrorMessage = null
            } else {
              parentalPinErrorMessage = mismatchMsg
            }
          }
          else -> {}
        }
      },
      onDismiss = {
        showParentalPinDialog = false
        parentalPinStep = ParentalPinStep.None
        parentalPinErrorMessage = null
      }
    )
  }

  if (showParentalManageDialog) {
    val manageOptions = listOf(
      stringResource(R.string.parental_option_level, "$parentalControlLevel+"),
      stringResource(R.string.parental_option_change_pin),
      stringResource(R.string.parental_option_disable)
    )
    TrackSelectionDialog(
      title = stringResource(R.string.parental_dialog_manage_title),
      options = manageOptions,
      selectedIndex = 0,
      onSelect = { idx ->
        showParentalManageDialog = false
        when (idx) {
          0 -> showParentalLevelDialog = true
          1 -> {
            parentalPinErrorMessage = null
            parentalPinStep = ParentalPinStep.ChangeNewPin
            showParentalPinDialog = true
          }
          2 -> viewModel?.setParentalControlEnabled(false)
        }
      },
      onDismiss = { showParentalManageDialog = false }
    )
  }

  if (showParentalLevelDialog) {
    val levels = listOf("14", "16", "18")
    val levelOptions = levels.map { "$it+" }
    TrackSelectionDialog(
      title = stringResource(R.string.parental_select_level_title),
      options = levelOptions,
      selectedIndex = levels.indexOf(parentalControlLevel).coerceAtLeast(0),
      onSelect = { idx ->
        viewModel?.setParentalControlLevel(levels[idx])
        showParentalLevelDialog = false
      },
      onDismiss = { showParentalLevelDialog = false }
    )
  }
}

/** Passi del flusso di inserimento/verifica PIN per il controllo genitori. */
private sealed interface ParentalPinStep {
  object None : ParentalPinStep
  object CreateNew : ParentalPinStep
  data class ConfirmNew(val firstPin: String) : ParentalPinStep
  object VerifyToManage : ParentalPinStep
  object ChangeNewPin : ParentalPinStep
  data class ChangeConfirmPin(val firstPin: String) : ParentalPinStep
}

/** Codici lingua selezionabili per l'app e metadati TMDB. */
private val APP_LANGUAGE_CODES = listOf("it", "en", "es", "fr", "de")

@Composable
private fun appLanguageLabel(code: String): String = when (code) {
  "en" -> stringResource(R.string.lang_english)
  "es" -> stringResource(R.string.lang_spanish)
  "fr" -> stringResource(R.string.lang_french)
  "de" -> stringResource(R.string.lang_german)
  else -> stringResource(R.string.lang_italian)
}

/** Codici lingua selezionabili per l'audio; "" = "Originale / Qualsiasi" (nessun vincolo). */
private val AUDIO_LANGUAGE_CODES = listOf("it", "en", "es", "fr", "de", "")

@Composable
private fun audioLanguageLabel(code: String): String = when (code) {
  "it" -> stringResource(R.string.lang_italian)
  "en" -> stringResource(R.string.lang_english)
  "es" -> stringResource(R.string.lang_spanish)
  "fr" -> stringResource(R.string.lang_french)
  "de" -> stringResource(R.string.lang_german)
  else -> stringResource(R.string.lang_original_any)
}

/** Codici lingua selezionabili per i sottotitoli; "" = "Originale / Qualsiasi" (nessun vincolo). */
private val SUBTITLE_LANGUAGE_CODES = listOf("it", "en", "es", "fr", "de", "pt", "")

/** Maschera la chiave API per la UI: `abcd1234...wxyz` → `abcd••••••••wxyz`. */
private fun maskApiKey(key: String): String {
  val clean = key.trim()
  return if (clean.length <= 8) "••••••••" else clean.take(4) + "••••••••" + clean.takeLast(4)
}

@Composable
private fun subtitleLanguageLabel(code: String): String = when (code) {
  "it" -> stringResource(R.string.lang_sub_italian)
  "en" -> stringResource(R.string.lang_sub_english)
  "es" -> stringResource(R.string.lang_sub_spanish)
  "fr" -> stringResource(R.string.lang_sub_french)
  "de" -> stringResource(R.string.lang_sub_german)
  "pt" -> stringResource(R.string.lang_sub_portuguese)
  else -> stringResource(R.string.lang_original_any)
}

@Composable
private fun preferredResolutionLabel(res: PreferredResolution): String = when (res) {
  PreferredResolution.AUTO -> stringResource(R.string.res_auto)
  PreferredResolution.UHD_4K -> "4K Ultra HD"
  PreferredResolution.FULL_HD_1080P -> "1080p Full HD"
  PreferredResolution.HD_720P -> "720p HD"
}

@Composable
private fun subtitleSizeLabel(size: SubtitleSize): String = when (size) {
  SubtitleSize.SMALL -> stringResource(R.string.sub_size_small)
  SubtitleSize.MEDIUM -> stringResource(R.string.sub_size_medium)
  SubtitleSize.LARGE -> stringResource(R.string.sub_size_large)
  SubtitleSize.VERY_LARGE -> stringResource(R.string.sub_size_very_large)
}

@Composable
private fun subtitleBackgroundLabel(bg: SubtitleBackground): String = when (bg) {
  SubtitleBackground.NONE -> stringResource(R.string.sub_bg_none)
  SubtitleBackground.BLACK -> stringResource(R.string.sub_bg_black)
  SubtitleBackground.SEMI_TRANSPARENT -> stringResource(R.string.sub_bg_semi_transparent)
}

@Composable
private fun subtitlePositionLabel(pos: SubtitlePosition): String = when (pos) {
  SubtitlePosition.BOTTOM -> stringResource(R.string.sub_pos_bottom)
  SubtitlePosition.CENTER -> stringResource(R.string.sub_pos_center)
  SubtitlePosition.TOP -> stringResource(R.string.sub_pos_top)
}

/**
 * Sezione delle impostazioni: intestazione con accento ciano + card contenitore.
 * Le sezioni sono separate visivamente per una lettura immediata su TV.
 */
@Composable
fun SettingsSection(
  title: String,
  content: @Composable () -> Unit
) {
  Column(modifier = Modifier.fillMaxWidth()) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Box(
        modifier = Modifier
          .width(4.dp)
          .height(18.dp)
          .background(
            Brush.verticalGradient(listOf(NovaCyan, NovaCyanBright)),
            RoundedCornerShape(2.dp)
          )
      )
      Spacer(modifier = Modifier.width(10.dp))
      Text(
        text = title.uppercase(),
        color = NovaTextPrimary,
        fontSize = 15.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp
      )
    }
    Spacer(modifier = Modifier.height(12.dp))
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .background(NovaCardBg, RoundedCornerShape(14.dp))
        .border(1.dp, NovaDivider, RoundedCornerShape(14.dp))
        .padding(vertical = 6.dp),
      verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
      content()
    }
  }
}

/**
 * Riga di impostazione navigabile con D-pad.
 *
 * @param value valore reale mostrato a destra (es. versione app). Resta null per
 *        le voci ancora prive di implementazione: nessun valore inventato.
 * @param onClick azione reale. Se null la riga è solo una voce UI (FASE 1):
 *        resta focalizzabile per la navigazione ma non esegue comportamenti fittizi.
 */
@Composable
fun SettingsRow(
  icon: ImageVector,
  title: String,
  value: String? = null,
  valueColor: Color = NovaCyanBright,
  onClick: (() -> Unit)? = null
) {
  TvFocusableBox(
    shape = RoundedCornerShape(10.dp),
    focusedScale = 1.02f,
    onClick = { onClick?.invoke() },
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 8.dp)
  ) { isFocused ->
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .background(
          if (isFocused) NovaCardBgFocused else Color.Transparent,
          RoundedCornerShape(10.dp)
        )
        .padding(horizontal = 16.dp, vertical = 14.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        tint = if (isFocused) NovaCyanBright else NovaTextMuted,
        modifier = Modifier.size(20.dp)
      )
      Spacer(modifier = Modifier.width(14.dp))
      Text(
        text = title,
        color = if (isFocused) NovaTextPrimary else NovaTextSecondary,
        fontSize = 15.sp,
        fontWeight = if (isFocused) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f)
      )
      if (value != null) {
        Spacer(modifier = Modifier.width(12.dp))
        Text(
          text = value,
          color = valueColor,
          fontSize = 13.sp,
          fontWeight = FontWeight.Medium,
          maxLines = 1
        )
      }
    }
  }
}

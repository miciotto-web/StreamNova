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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Tv
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
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
import com.example.data.model.MediaItem
import com.example.ui.components.ContinueWatchingCard
import com.example.ui.components.PosterMediaCard
import com.example.ui.components.StandardMediaCard
import com.example.ui.components.TvActionButton
import com.example.ui.components.TvFocusBringIntoView
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
  onLoadMore: (() -> Unit)? = null,
  modifier: Modifier = Modifier
) {
  var selectedFilter by remember { mutableStateOf("Tutti") }

  val filters = listOf("Tutti", "Top 10", "Netflix", "HBO Max", "Disney+", "Prime Video", "Azione", "Fantascienza", "Dramma", "Commedia")

  val filteredItems = remember(items, selectedFilter) {
    when (selectedFilter) {
      "Tutti" -> items
      "Top 10" -> items.sortedByDescending { it.rating }.take(10)
      "Netflix" -> items.filter { it.provider?.equals("netflix", ignoreCase = true) == true }
      "HBO Max" -> items.filter { it.provider?.equals("hbo", ignoreCase = true) == true }
      "Disney+" -> items.filter { it.provider?.equals("disney", ignoreCase = true) == true }
      "Prime Video" -> items.filter { it.provider?.equals("prime", ignoreCase = true) == true }
      "Azione" -> items.filter { item ->
        item.genres.any { g ->
          g.contains("Azione", ignoreCase = true) ||
          g.contains("Action", ignoreCase = true) ||
          g.contains("Avventura", ignoreCase = true) ||
          g.contains("Adventure", ignoreCase = true)
        }
      }
      "Fantascienza" -> items.filter { item ->
        item.genres.any { g ->
          g.contains("Fantascienza", ignoreCase = true) ||
          g.contains("Sci-Fi", ignoreCase = true) ||
          g.contains("Sci Fi", ignoreCase = true) ||
          g.contains("Fantasy", ignoreCase = true)
        }
      }
      "Dramma" -> items.filter { item ->
        item.genres.any { g ->
          g.contains("Dramma", ignoreCase = true) ||
          g.contains("Drama", ignoreCase = true)
        }
      }
      "Commedia" -> items.filter { item ->
        item.genres.any { g ->
          g.contains("Commedia", ignoreCase = true) ||
          g.contains("Comedy", ignoreCase = true)
        }
      }
      else -> items.filter { it.genres.any { g -> g.contains(selectedFilter, ignoreCase = true) } }
    }
  }

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
      Column(modifier = Modifier.weight(1f)) {
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
          modifier = Modifier.padding(top = 4.dp)
        )
      }

      Box(
        modifier = Modifier
          .background(NovaCyan.copy(alpha = 0.15f), RoundedCornerShape(20.dp))
          .border(1.dp, NovaCyanBright.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
          .padding(horizontal = 14.dp, vertical = 6.dp)
      ) {
        Text(
          text = "${filteredItems.size} Titoli TMDB",
          color = NovaCyanBright,
          fontSize = 12.sp,
          fontWeight = FontWeight.Bold
        )
      }
    }

    // Filter Chips Row
    LazyRow(
      modifier = Modifier.padding(vertical = 14.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      items(filters) { filter ->
        val isSelected = selectedFilter == filter
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
              text = filter,
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
        columns = GridCells.Adaptive(minSize = 155.dp),
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
          text = "Preferiti",
          color = NovaTextPrimary,
          fontSize = 26.sp,
          fontWeight = FontWeight.Black,
          letterSpacing = 0.5.sp
        )
        Text(
          text = "I tuoi film e serie TV salvati nella tua lista personale",
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
          text = if (favorites.isEmpty()) "0 Titoli Salvati" else "${favorites.size} Titoli Salvati",
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
            text = "Nessun titolo salvato nei Preferiti",
            color = NovaTextPrimary,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
          )
          Text(
            text = "Tocca l'icona del cuore o 'Aggiungi ai Preferiti' nella scheda di dettaglio di qualsiasi film o serie.",
            color = NovaTextMuted,
            fontSize = 14.sp
          )
        }
      }
    } else {
      TvFocusBringIntoView {
        LazyVerticalGrid(
          columns = GridCells.Adaptive(minSize = 155.dp),
          contentPadding = PaddingValues(bottom = 48.dp),
          horizontalArrangement = Arrangement.spacedBy(16.dp),
          verticalArrangement = Arrangement.spacedBy(16.dp),
          modifier = Modifier.fillMaxSize()
        ) {
          items(favorites, key = { "fav_grid_${it.id}" }) { item ->
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
  val label: String,
  val gradientStart: Color,
  val gradientEnd: Color,
  val accentColor: Color,
  val keywords: List<String>
)

val GLOBAL_SEARCH_CATEGORIES = listOf(
  GlobalSearchCategory("action_adventure", "Action & Adventure", Color(0xFFEF4444), Color(0xFF991B1B), Color(0xFFFCA5A5), listOf("Action & Adventure", "Azione & Avventura", "Action", "Azione", "Avventura")),
  GlobalSearchCategory("animazione", "Animazione", Color(0xFFF59E0B), Color(0xFFB45309), Color(0xFFFDE68A), listOf("Animazione", "Animation", "Anime")),
  GlobalSearchCategory("avventura", "Avventura", Color(0xFF10B981), Color(0xFF047857), Color(0xFFA7F3D0), listOf("Avventura", "Adventure")),
  GlobalSearchCategory("azione", "Azione", Color(0xFFDC2626), Color(0xFF7F1D1D), Color(0xFFFECACA), listOf("Azione", "Action")),
  GlobalSearchCategory("commedia", "Commedia", Color(0xFFEAB308), Color(0xFFA16207), Color(0xFFFEF08A), listOf("Commedia", "Comedy")),
  GlobalSearchCategory("crime", "Crime", Color(0xFF64748B), Color(0xFF334155), Color(0xFFCBD5E1), listOf("Crime", "Crimine", "Poliziesco")),
  GlobalSearchCategory("documentario", "Documentario", Color(0xFF06B6D4), Color(0xFF0E7490), Color(0xFFA5F3FC), listOf("Documentario", "Documnetario", "Documentary")),
  GlobalSearchCategory("dramma", "Dramma", Color(0xFF8B5CF6), Color(0xFF5B21B6), Color(0xFFDDD6FE), listOf("Dramma", "Drama")),
  GlobalSearchCategory("famiglia", "Famiglia", Color(0xFFEC4899), Color(0xFF9D174D), Color(0xFFFBCFE8), listOf("Famiglia", "Family", "Kids")),
  GlobalSearchCategory("fantascienza", "Fantascienza", Color(0xFF00E5FF), Color(0xFF007799), Color(0xFFE0F7FA), listOf("Fantascienza", "Sci-Fi", "Science Fiction")),
  GlobalSearchCategory("fantasy", "Fantasy", Color(0xFFA855F7), Color(0xFF6B21A8), Color(0xFFF3E8FF), listOf("Fantasy", "Fantastico")),
  GlobalSearchCategory("guerra", "Guerra", Color(0xFF78716C), Color(0xFF44403C), Color(0xFFE7E5E4), listOf("Guerra", "War", "Guerra & Politica")),
  GlobalSearchCategory("horror", "Horror", Color(0xFFE11D48), Color(0xFF881337), Color(0xFFFECDD3), listOf("Horror")),
  GlobalSearchCategory("kids", "Kids", Color(0xFFF43F5E), Color(0xFF9F1239), Color(0xFFFFE4E6), listOf("Kids", "Bambini", "Famiglia")),
  GlobalSearchCategory("mistero", "Mistero", Color(0xFF6366F1), Color(0xFF3730A3), Color(0xFFE0E7FF), listOf("Mistero", "Mystery")),
  GlobalSearchCategory("musica", "Musica", Color(0xFF14B8A6), Color(0xFF0F766E), Color(0xFFCCFBF1), listOf("Musica", "Music", "Musicale")),
  GlobalSearchCategory("news", "News", Color(0xFF3B82F6), Color(0xFF1E40AF), Color(0xFFBFDBFE), listOf("News", "Notizie", "Attualità")),
  GlobalSearchCategory("reality", "Reality", Color(0xFFFB923C), Color(0xFFC2410C), Color(0xFFFFEDD5), listOf("Reality", "Reality-TV")),
  GlobalSearchCategory("romance", "Romance", Color(0xFFF472B6), Color(0xFFBE185D), Color(0xFFFCE7F3), listOf("Romance", "Romantico", "Sentimentale")),
  GlobalSearchCategory("sci_fi_fantasy", "Sci-Fi & Fantasy", Color(0xFF0284C7), Color(0xFF4338CA), Color(0xFFBAE6FD), listOf("Sci-Fi & Fantasy", "Fantascienza", "Fantasy")),
  GlobalSearchCategory("storia", "Storia", Color(0xFFD97706), Color(0xFF78350F), Color(0xFFFDE68A), listOf("Storia", "History", "Storico", "Biografia")),
  GlobalSearchCategory("thriller", "Thriller", Color(0xFFEA580C), Color(0xFF9A3412), Color(0xFFFFEDD5), listOf("Thriller", "Suspense")),
  GlobalSearchCategory("western", "Western", Color(0xFFB45309), Color(0xFF713F12), Color(0xFFFEF3C7), listOf("Western")),
  GlobalSearchCategory("televisione_film", "Televisione film", Color(0xFF38BDF8), Color(0xFF0369A1), Color(0xFFE0F2FE), listOf("Televisione Film", "Film TV", "TV Movie", "Cinema", "Film"))
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
  var wasFocused by remember { mutableStateOf(false) }
  val fieldFocusRequester = remember { FocusRequester() }
  val focusManager = LocalFocusManager.current

  // All'attivazione porta il focus sull'input e apre la tastiera.
  LaunchedEffect(isEditing) {
    if (isEditing) {
      withFrameNanos { }
      fieldFocusRequester.requestFocus()
    }
  }

  if (!isEditing) {
    // Stato di riposo: focusabile col D-pad ma senza tastiera.
    TvFocusableBox(
      modifier = modifier.fillMaxWidth(),
      shape = RoundedCornerShape(50),
      focusedScale = 1f,
      onClick = {
        wasFocused = false
        isEditing = true
      }
    ) {
      SearchBarRow(
        trailing = {
          if (query.isNotEmpty()) {
            Icon(
              imageVector = Icons.Default.Close,
              contentDescription = "Cancella",
              tint = NovaTextSecondary,
              modifier = Modifier
                .size(20.dp)
                .clickable { onClear() }
            )
          }
        }
      ) {
        Text(
          text = query.ifBlank { "Scrivi il titolo, attore o regista e premi Invio per cercare..." },
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
            contentDescription = "Cancella",
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
        keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
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
            if (it.isFocused) {
              wasFocused = true
            } else if (wasFocused) {
              // Il focus è uscito verso i contenuti: torna in stato di riposo.
              isEditing = false
            }
          }
          // SU/GIÙ escono dalla barra verso i contenuti, senza muovere il cursore.
          .onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown &&
              (event.key == Key.DirectionDown || event.key == Key.DirectionUp)
            ) {
              val direction = if (event.key == Key.DirectionDown) FocusDirection.Down else FocusDirection.Up
              focusManager.moveFocus(direction)
            } else {
              false
            }
          }
          // ENTER/OK invia la ricerca.
          .onKeyEvent { event ->
            if (event.key == Key.Enter || event.key == Key.NumPadEnter || event.key == Key.DirectionCenter) {
              if (event.type == KeyEventType.KeyUp) {
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
              text = "Scrivi il titolo, attore o regista e premi Invio per cercare...",
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
      contentDescription = "Cerca",
      tint = NovaCyanBright,
      modifier = Modifier.size(24.dp)
    )
    Spacer(modifier = Modifier.width(12.dp))
    content()
    trailing()
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
  val allMedia by viewModel.allMedia.collectAsState()

  var selectedCategory by remember { mutableStateOf<GlobalSearchCategory?>(null) }
  var isSearchSubmitted by remember { mutableStateOf(false) }

  val firstCategoryFocusRequester = remember { FocusRequester() }

  // All'ingresso il focus va su un elemento stabile (il primo badge), non sulla
  // casella di ricerca: così la tastiera non si apre automaticamente.
  LaunchedEffect(Unit) {
    withFrameNanos { }
    firstCategoryFocusRequester.requestFocus()
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
    BackHandler { selectedCategory = null }
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
          text = "Cerca su StreamNova",
          color = NovaTextPrimary,
          fontSize = 26.sp,
          fontWeight = FontWeight.Black,
          letterSpacing = 0.5.sp
        )
        Text(
          text = "Cerca per testo o seleziona una categoria per esplorare tutti i contenuti",
          color = NovaTextSecondary,
          fontSize = 13.sp,
          modifier = Modifier.padding(top = 2.dp)
        )
      }

      if (selectedCategory != null || query.isNotBlank()) {
        TvActionButton(
          text = "Torna alle Categorie",
          icon = Icons.AutoMirrored.Filled.ArrowBack,
          isPrimary = false,
          onClick = {
            selectedCategory = null
            isSearchSubmitted = false
            viewModel.updateSearchQuery("")
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
          selectedCategory = null
          isSearchSubmitted = true
        }
      },
      onClear = {
        viewModel.updateSearchQuery("")
        isSearchSubmitted = false
      }
    )

    Spacer(modifier = Modifier.height(18.dp))

    // Vista Condizionale: Categoria Selezionata / Risultati di Ricerca Testo / Default con soli Badge Colorati
    when {
      selectedCategory != null -> {
        val cat = selectedCategory!!
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
                text = cat.label.uppercase(),
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 0.5.sp
              )
            }
            Text(
              text = "Tutti i contenuti dedicati al genere",
              color = NovaTextSecondary,
              fontSize = 13.sp
            )
          }

          Box(
            modifier = Modifier
              .background(Color(0x33000000), RoundedCornerShape(50))
              .border(1.dp, cat.accentColor.copy(alpha = 0.5f), RoundedCornerShape(50))
              .padding(horizontal = 16.dp, vertical = 6.dp)
          ) {
            Text(
              text = "${categoryMedia.size} Titoli Disponibili",
              color = cat.accentColor,
              fontSize = 12.sp,
              fontWeight = FontWeight.Bold
            )
          }
        }

        if (categoryMedia.isEmpty()) {
          Box(
            modifier = Modifier
              .fillMaxSize()
              .padding(32.dp),
            contentAlignment = Alignment.Center
          ) {
            Text(
              text = "Nessun contenuto trovato per ${cat.label}",
              color = NovaTextMuted,
              fontSize = 16.sp
            )
          }
        } else {
          TvFocusBringIntoView {
            LazyVerticalGrid(
              columns = GridCells.Adaptive(minSize = 155.dp),
              contentPadding = PaddingValues(bottom = 48.dp),
              horizontalArrangement = Arrangement.spacedBy(16.dp),
              verticalArrangement = Arrangement.spacedBy(16.dp),
              modifier = Modifier.fillMaxSize()
            ) {
              items(categoryMedia, key = { "cat_${cat.id}_${it.id}" }) { item ->
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

      query.isNotBlank() -> {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 14.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text(
            text = "Risultati trovati per \"$query\" (${results.size})",
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
              text = "Nessun risultato trovato per \"$query\"",
              color = NovaTextMuted,
              fontSize = 16.sp
            )
          }
        } else {
          TvFocusBringIntoView {
            LazyVerticalGrid(
              columns = GridCells.Adaptive(minSize = 155.dp),
              contentPadding = PaddingValues(bottom = 48.dp),
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
      }

      else -> {
        // STATO DEFAULT: Nessun poster! Mostra solo i badge colorati per la ricerca globale
        Column(modifier = Modifier.fillMaxSize()) {
          Text(
            text = "RICERCA GLOBALE PER CATEGORIA",
            color = NovaCyanBright,
            fontSize = 13.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.sp
          )
          Text(
            text = "Seleziona un badge per aprire la sezione dedicata e visualizzare tutti i poster",
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
            items(GLOBAL_SEARCH_CATEGORIES, key = { it.id }) { cat ->
              TvFocusableBox(
                modifier = if (cat.id == GLOBAL_SEARCH_CATEGORIES.first().id) {
                  Modifier.focusRequester(firstCategoryFocusRequester)
                } else {
                  Modifier
                },
                shape = RoundedCornerShape(50),
                focusedScale = 1.05f,
                onClick = {
                  selectedCategory = cat
                  isSearchSubmitted = false
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
                    text = cat.label.uppercase(),
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
  var selectedQuality by remember { mutableStateOf("Ultra HD 4K HDR (Massima qualità)") }
  var selectedAudioLang by remember { mutableStateOf("Italiano (Dolby Atmos)") }
  var selectedSubtitles by remember { mutableStateOf("Disattivati") }

  val allMedia = viewModel?.allMedia?.collectAsState()?.value ?: emptyList()
  val isRefreshing = viewModel?.isRefreshing?.collectAsState()?.value ?: false
  val movieCount = allMedia.count { it.type == com.example.data.model.MediaType.FILM }
  val tvCount = allMedia.count { it.type == com.example.data.model.MediaType.SERIE_TV }

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
        text = "Configurazione audio, video e integrazione catalogo TMDB",
        color = NovaTextSecondary,
        fontSize = 13.sp,
        modifier = Modifier.padding(top = 4.dp)
      )
    }

    // TMDB Integration Card
    item {
      SettingsGroupCard(title = "Integrazione TMDB (The Movie Database)", icon = Icons.Default.Info) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          InfoRow(label = "Stato TMDB API", value = "Connesso & Sincronizzato")
          InfoRow(label = "Chiave API Attiva", value = "da92••••••••••••••••••••••••b018")
          InfoRow(label = "Cache Locale Room", value = "Attiva (Database SQLite • TTL 12h)")
          InfoRow(label = "Titoli nel Catalogo", value = "${allMedia.size} totali ($movieCount Film, $tvCount Serie TV)")
          InfoRow(label = "Provider Integrati", value = "Netflix • HBO Max • Disney+ • Prime Video")

          Spacer(modifier = Modifier.height(4.dp))

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
          ) {
            TvFocusableBox(
              shape = RoundedCornerShape(50),
              onClick = { viewModel?.refreshCatalog() },
              modifier = Modifier.weight(1f)
            ) { isFocused ->
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .background(
                    if (isFocused) Brush.horizontalGradient(listOf(NovaCyan, NovaCyanBright))
                    else Brush.horizontalGradient(listOf(NovaCyan.copy(alpha = 0.2f), NovaCyan.copy(alpha = 0.12f))),
                    RoundedCornerShape(50)
                  )
                  .border(1.dp, NovaCyanBright, RoundedCornerShape(50))
                  .padding(vertical = 11.dp, horizontal = 18.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
              ) {
                Icon(
                  imageVector = Icons.Default.Refresh,
                  contentDescription = null,
                  tint = if (isFocused) Color.Black else NovaCyanBright,
                  modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                  text = if (isRefreshing) "Sincronizzazione..." else "Aggiorna con Cache",
                  color = if (isFocused) Color.Black else NovaCyanBright,
                  fontSize = 13.sp,
                  fontWeight = FontWeight.Bold
                )
              }
            }

            TvFocusableBox(
              shape = RoundedCornerShape(50),
              onClick = { viewModel?.clearCacheAndRefresh() },
              modifier = Modifier.weight(1f)
            ) { isFocused ->
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .background(
                    if (isFocused) Color(0xFFEF4444)
                    else Color(0x22EF4444),
                    RoundedCornerShape(50)
                  )
                  .border(1.dp, if (isFocused) Color.White else Color(0x66EF4444), RoundedCornerShape(50))
                  .padding(vertical = 11.dp, horizontal = 18.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
              ) {
                Icon(
                  imageVector = Icons.Default.Close,
                  contentDescription = null,
                  tint = if (isFocused) Color.White else Color(0xFFFCA5A5),
                  modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                  text = "Svuota Cache Room",
                  color = if (isFocused) Color.White else Color(0xFFFCA5A5),
                  fontSize = 13.sp,
                  fontWeight = FontWeight.Bold
                )
              }
            }
          }
        }
      }
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
    shape = RoundedCornerShape(50),
    onClick = onClick,
    modifier = Modifier.fillMaxWidth()
  ) { isFocused ->
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .background(
          if (isSelected) NovaCyan.copy(alpha = 0.15f) else Color.Transparent,
          RoundedCornerShape(50)
        )
        .border(
          width = 1.dp,
          color = if (isFocused) NovaCyanBright else if (isSelected) NovaCyan.copy(alpha = 0.5f) else Color.Transparent,
          shape = RoundedCornerShape(50)
        )
        .padding(horizontal = 16.dp, vertical = 9.dp),
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

package com.aistudio.streamnova.mobile.ui

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import com.aistudio.streamnova.mobile.R
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.model.SearchCategoryFilter
import com.example.data.model.SearchTypeFilter

/**
 * Sottocategoria della sezione Cerca.
 *
 * Il mapping è la riproduzione Mobile del mapping TV: stessi id, nomi, ordine,
 * keyword e genre ID. La sola differenza è l'uso del package di risorse Mobile.
 *
 * [contentFilter] replica la fonte dichiarata dalla TV: default
 * [SearchCategoryFilter.GENRE] per le categorie storiche (cataloghi addon con
 * fallback TMDB), [SearchCategoryFilter.TMDB_TV_GENRE] per "Talk", servita
 * nativamente da `discover/tv` senza consultare i cataloghi degli addon.
 */
data class MobileSearchCategory(
  val id: String,
  @StringRes val labelRes: Int,
  val gradientStart: Color,
  val gradientEnd: Color,
  val accentColor: Color,
  val keywords: List<String>,
  val movieGenreId: Int? = null,
  val tvGenreId: Int? = null,
  /**
   * Fonte della categoria. Default [SearchCategoryFilter.GENRE] per le categorie
   * storiche; "Talk" usa [SearchCategoryFilter.TMDB_TV_GENRE] ed è servita
   * nativamente da TMDB tramite [tvGenreId].
   */
  val contentFilter: SearchCategoryFilter = SearchCategoryFilter.GENRE
)

/**
 * Sottocategorie effettivamente presenti nella Cerca TV.
 *
 * L'ordine è parte del comportamento della TV e viene preservato anche su Mobile.
 */
val MOBILE_SEARCH_CATEGORIES = listOf(
  MobileSearchCategory("action_adventure", R.string.search_cat_action_adventure, Color(0xFFEF4444), Color(0xFF991B1B), Color(0xFFFCA5A5), listOf("Action & Adventure", "Azione & Avventura", "Action", "Azione", "Avventura"), movieGenreId = 28, tvGenreId = 10759),
  MobileSearchCategory("animazione", R.string.filter_animation, Color(0xFFF59E0B), Color(0xFFB45309), Color(0xFFFDE68A), listOf("Animazione", "Animation"), movieGenreId = 16, tvGenreId = 16),
  MobileSearchCategory("avventura", R.string.search_cat_adventure, Color(0xFF10B981), Color(0xFF047857), Color(0xFFA7F3D0), listOf("Avventura", "Adventure"), movieGenreId = 12, tvGenreId = 10759),
  MobileSearchCategory("azione", R.string.filter_action, Color(0xFFDC2626), Color(0xFF7F1D1D), Color(0xFFFECACA), listOf("Azione", "Action"), movieGenreId = 28, tvGenreId = 10759),
  MobileSearchCategory("commedia", R.string.filter_comedy, Color(0xFFEAB308), Color(0xFFA16207), Color(0xFFFEF08A), listOf("Commedia", "Comedy"), movieGenreId = 35, tvGenreId = 35),
  MobileSearchCategory("crime", R.string.search_cat_crime, Color(0xFF64748B), Color(0xFF334155), Color(0xFFCBD5E1), listOf("Crime", "Crimine", "Poliziesco"), movieGenreId = 80, tvGenreId = 80),
  MobileSearchCategory("documentario", R.string.search_cat_documentary, Color(0xFF06B6D4), Color(0xFF0E7490), Color(0xFFA5F3FC), listOf("Documentario", "Documnetario", "Documentary"), movieGenreId = 99, tvGenreId = 99),
  MobileSearchCategory("dramma", R.string.filter_drama, Color(0xFF8B5CF6), Color(0xFF5B21B6), Color(0xFFDDD6FE), listOf("Dramma", "Drama"), movieGenreId = 18, tvGenreId = 18),
  MobileSearchCategory("famiglia", R.string.search_cat_family, Color(0xFFEC4899), Color(0xFF9D174D), Color(0xFFFBCFE8), listOf("Famiglia", "Family", "Kids"), movieGenreId = 10751, tvGenreId = 10762),
  MobileSearchCategory("fantascienza", R.string.filter_sci_fi, Color(0xFF00E5FF), Color(0xFF007799), Color(0xFFE0F7FA), listOf("Fantascienza", "Sci-Fi", "Science Fiction"), movieGenreId = 878, tvGenreId = 10765),
  MobileSearchCategory("fantasy", R.string.search_cat_fantasy, Color(0xFFA855F7), Color(0xFF6B21A8), Color(0xFFF3E8FF), listOf("Fantasy", "Fantastico"), movieGenreId = 14, tvGenreId = 10765),
  MobileSearchCategory("guerra", R.string.search_cat_war, Color(0xFF78716C), Color(0xFF44403C), Color(0xFFE7E5E4), listOf("Guerra", "War", "Guerra & Politica"), movieGenreId = 10752, tvGenreId = 10768),
  MobileSearchCategory("horror", R.string.search_cat_horror, Color(0xFFE11D48), Color(0xFF881337), Color(0xFFFECDD3), listOf("Horror"), movieGenreId = 27, tvGenreId = 27),
  MobileSearchCategory("kids", R.string.search_cat_kids, Color(0xFFF43F5E), Color(0xFF9F1239), Color(0xFFFFE4E6), listOf("Kids", "Bambini", "Famiglia"), movieGenreId = 10751, tvGenreId = 10762),
  MobileSearchCategory("mistero", R.string.search_cat_mystery, Color(0xFF6366F1), Color(0xFF3730A3), Color(0xFFE0E7FF), listOf("Mistero", "Mystery"), movieGenreId = 9648, tvGenreId = 9648),
  MobileSearchCategory("musica", R.string.search_cat_music, Color(0xFF14B8A6), Color(0xFF0F766E), Color(0xFFCCFBF1), listOf("Musica", "Music", "Musicale"), movieGenreId = 10402, tvGenreId = null),
  MobileSearchCategory("news", R.string.search_cat_news, Color(0xFF3B82F6), Color(0xFF1E40AF), Color(0xFFBFDBFE), listOf("News", "Notizie", "Attualità"), movieGenreId = null, tvGenreId = 10763),
  MobileSearchCategory("reality", R.string.search_cat_reality, Color(0xFFFB923C), Color(0xFFC2410C), Color(0xFFFFEDD5), listOf("Reality", "Reality-TV"), movieGenreId = null, tvGenreId = 10764),
  MobileSearchCategory("romance", R.string.search_cat_romance, Color(0xFFF472B6), Color(0xFFBE185D), Color(0xFFFCE7F3), listOf("Romance", "Romantico", "Sentimentale"), movieGenreId = 10749, tvGenreId = 18),
  MobileSearchCategory("sci_fi_fantasy", R.string.search_cat_sci_fi_fantasy, Color(0xFF0284C7), Color(0xFF4338CA), Color(0xFFBAE6FD), listOf("Sci-Fi & Fantasy", "Fantascienza", "Fantasy"), movieGenreId = 878, tvGenreId = 10765),
  MobileSearchCategory("storia", R.string.search_cat_history, Color(0xFFD97706), Color(0xFF78350F), Color(0xFFFDE68A), listOf("Storia", "History", "Storico", "Biografia"), movieGenreId = 36, tvGenreId = 18),
  MobileSearchCategory("thriller", R.string.search_cat_thriller, Color(0xFFEA580C), Color(0xFF9A3412), Color(0xFFFFEDD5), listOf("Thriller", "Suspense"), movieGenreId = 53, tvGenreId = 9648),
  MobileSearchCategory("western", R.string.search_cat_western, Color(0xFFB45309), Color(0xFF713F12), Color(0xFFFEF3C7), listOf("Western"), movieGenreId = 37, tvGenreId = 37),
  /**
   * "Talk" occupa il posto che era di "Televisione film" (genere TMDB 10770).
   *
   * È il genere TV ufficiale TMDB "Talk" (id 10767) e viene servita NATIVAMENTE da
   * `discover/tv?with_genres=10767`, ordinata per popolarità e paginata. Non passa
   * dai cataloghi degli addon e non richiede la modalità Debrid: funziona anche
   * senza addon installati. Nessun filtro di lingua o paese: la categoria mostra
   * talk show italiani e stranieri.
   */
  MobileSearchCategory(
    id = "talk",
    labelRes = R.string.search_cat_talk,
    gradientStart = Color(0xFF38BDF8),
    gradientEnd = Color(0xFF0369A1),
    accentColor = Color(0xFFE0F2FE),
    keywords = listOf("Talk", "Talk Show"),
    tvGenreId = 10767,
    contentFilter = SearchCategoryFilter.TMDB_TV_GENRE
  )
)
/** Nomi TMDB canonici aggiunti alle keyword per il matching dei cataloghi addon. */
internal val MOBILE_TMDB_MOVIE_GENRE_NAMES: Map<Int, String> = mapOf(
  28 to "Action",
  12 to "Adventure",
  16 to "Animation",
  35 to "Comedy",
  80 to "Crime",
  99 to "Documentary",
  18 to "Drama",
  10751 to "Family",
  14 to "Fantasy",
  36 to "History",
  27 to "Horror",
  10402 to "Music",
  9648 to "Mystery",
  10749 to "Romance",
  878 to "Science Fiction",
  10770 to "TV Movie",
  53 to "Thriller",
  10752 to "War",
  37 to "Western"
)

/** Nomi TMDB canonici per i generi delle serie TV. */
internal val MOBILE_TMDB_TV_GENRE_NAMES: Map<Int, String> = mapOf(
  10759 to "Action & Adventure",
  16 to "Animation",
  35 to "Comedy",
  80 to "Crime",
  99 to "Documentary",
  18 to "Drama",
  10751 to "Family",
  10765 to "Sci-Fi & Fantasy",
  10762 to "Kids",
  10763 to "News",
  10764 to "Reality",
  10767 to "Talk",
  10768 to "War & Politics",
  9648 to "Mystery"
)

/** Keyword effettive della categoria, identiche alla composizione usata dalla TV. */
internal fun MobileSearchCategory.effectiveKeywords(): List<String> = keywords +
  listOfNotNull(movieGenreId?.let { MOBILE_TMDB_MOVIE_GENRE_NAMES[it] }) +
  listOfNotNull(tvGenreId?.let { MOBILE_TMDB_TV_GENRE_NAMES[it] })

/** Filtro per tipo applicato ai risultati della categoria, come nella schermata TV. */
internal fun filterMobileSearchCategoryItems(
  items: List<MediaItem>,
  filter: SearchTypeFilter
): List<MediaItem> = when (filter) {
  SearchTypeFilter.ALL -> items
  SearchTypeFilter.FILM -> items.filter { it.type == MediaType.FILM }
  SearchTypeFilter.SERIE_TV -> items.filter { it.type == MediaType.SERIE_TV }
}


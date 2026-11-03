package com.aistudio.streamnova.mobile.ui

import androidx.annotation.StringRes
import com.aistudio.streamnova.mobile.R
import com.example.data.model.MediaItem

/**
 * Tipo di riga della Home mobile.
 *
 * La sequenza è quella della Home TV 1.0.4: Hero, Continua a guardare, hub
 * provider, Top 10 Film/Serie, Novità Film/Serie, For You Film/Serie,
 * Popolari Film/Serie, sezioni catalogo aggiuntive. Film e Serie restano
 * righe SEPARATE, come in TV: la versione precedente le univa in una sola
 * riga "Popolari" e una sola "Top 10", duplicando contenuti e perdendo
 * l'ordine della Home di riferimento.
 */
enum class MobileHomeSectionKind {
  HERO,
  CONTINUE_WATCHING,
  PROVIDERS,
  POSTER_ROW,
  TOP_10_ROW,
  EXTRA_ROW
}

/**
 * Riga pronta da disegnare. [titleRes]/[badgeRes] coprono le sezioni stock
 * (etichette TV localizzate); [title]/[badge] coprono le sezioni catalogo
 * il cui titolo e nome addon arrivano dai dati reali.
 */
data class MobileHomeSection(
  val key: String,
  val kind: MobileHomeSectionKind,
  val items: List<MediaItem> = emptyList(),
  @StringRes val titleRes: Int? = null,
  @StringRes val badgeRes: Int? = null,
  val title: String? = null,
  val badge: String? = null
)

/**
 * Deriva l'elenco ordinato delle righe Home dallo stato già filtrato.
 *
 * Funzione PURA e unico punto che decide ordine e presenza delle righe:
 * - nessuna riga "Film"/"Serie TV" generica: in TV quelle sono destinazioni
 *   della navigazione, non righe della Home;
 * - nessun raggruppamento Film+Serie: Top 10 e Popolari restano separati;
 * - le sezioni extra mantengono l'ordine reale restituito dalla pipeline.
 */
fun MobileCatalogUiState.homeSections(): List<MobileHomeSection> = buildList {
  if (heroItems.isNotEmpty()) {
    add(MobileHomeSection("hero", MobileHomeSectionKind.HERO, heroItems))
  }
  if (continueWatching.isNotEmpty()) {
    add(
      MobileHomeSection(
        key = "continue_watching",
        kind = MobileHomeSectionKind.CONTINUE_WATCHING,
        items = continueWatching,
        titleRes = R.string.home_continue,
        badgeRes = R.string.home_badge_in_progress
      )
    )
  }
  add(
    MobileHomeSection(
      key = "providers",
      kind = MobileHomeSectionKind.PROVIDERS,
      titleRes = R.string.home_section_streaming_platforms,
      badgeRes = R.string.home_badge_select_provider
    )
  )
  if (top10Movies.isNotEmpty()) {
    add(
      MobileHomeSection(
        key = "top_10_film",
        kind = MobileHomeSectionKind.TOP_10_ROW,
        items = top10Movies,
        titleRes = R.string.home_section_top_10_movies,
        badgeRes = R.string.home_badge_tmdb_ranking_italy
      )
    )
  }
  if (top10Series.isNotEmpty()) {
    add(
      MobileHomeSection(
        key = "top_10_serie",
        kind = MobileHomeSectionKind.TOP_10_ROW,
        items = top10Series,
        titleRes = R.string.home_section_top_10_series,
        badgeRes = R.string.home_badge_most_watched_tmdb
      )
    )
  }
  if (trendingMovies.isNotEmpty()) {
    add(
      MobileHomeSection(
        key = "trending_film",
        kind = MobileHomeSectionKind.POSTER_ROW,
        items = trendingMovies,
        titleRes = R.string.home_section_trending_movies,
        badgeRes = R.string.home_badge_trending_week
      )
    )
  }
  if (trendingSeries.isNotEmpty()) {
    add(
      MobileHomeSection(
        key = "trending_serie",
        kind = MobileHomeSectionKind.POSTER_ROW,
        items = trendingSeries,
        titleRes = R.string.home_section_trending_series,
        badgeRes = R.string.home_badge_trending_phenomena
      )
    )
  }
  if (forYouMovies.isNotEmpty()) {
    add(
      MobileHomeSection(
        key = "for_you_film",
        kind = MobileHomeSectionKind.POSTER_ROW,
        items = forYouMovies,
        titleRes = R.string.home_section_for_you_movies,
        badgeRes = R.string.home_badge_based_on_preferences
      )
    )
  }
  if (forYouSeries.isNotEmpty()) {
    add(
      MobileHomeSection(
        key = "for_you_serie",
        kind = MobileHomeSectionKind.POSTER_ROW,
        items = forYouSeries,
        titleRes = R.string.home_section_for_you_series,
        badgeRes = R.string.home_badge_suggested_for_you
      )
    )
  }
  if (popularMovies.isNotEmpty()) {
    add(
      MobileHomeSection(
        key = "popolari_film",
        kind = MobileHomeSectionKind.POSTER_ROW,
        items = popularMovies,
        titleRes = R.string.home_section_popular_movies,
        badgeRes = R.string.home_badge_most_watched_cinema
      )
    )
  }
  if (popularSeries.isNotEmpty()) {
    add(
      MobileHomeSection(
        key = "popolari_serie",
        kind = MobileHomeSectionKind.POSTER_ROW,
        items = popularSeries,
        titleRes = R.string.home_section_popular_series,
        badgeRes = R.string.home_badge_most_discussed_series
      )
    )
  }
  extraSections.forEach { section ->
    if (section.items.isNotEmpty()) {
      add(
        MobileHomeSection(
          key = "extra_${section.id}",
          kind = MobileHomeSectionKind.EXTRA_ROW,
          items = section.items,
          title = section.title,
          // Stessa attribuzione della TV: `addonName` della sezione reale.
          badge = section.addonName
        )
      )
    }
  }
}

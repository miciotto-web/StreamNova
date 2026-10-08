package com.example.ui.screens

import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchCategoryKeywordTest {

    @Test
    fun `category without keywords should return all media as initial items`() {
        // Given a category without keywords (e.g., music genre)
        val categoryWithoutKeywords = GlobalSearchCategory(
            id = "musica",
            labelRes = 1,
            gradientStart = com.example.ui.theme.NovaCyan,
            gradientEnd = com.example.ui.theme.NovaCyan,
            accentColor = com.example.ui.theme.NovaCyan,
            keywords = emptyList(),
            movieGenreId = 10402,
            tvGenreId = null
        )

        val allMedia = listOf(
            MediaItem(id = "1", title = "Movie 1", synopsis = "", videoUrl = "", genres = listOf("Action"), type = MediaType.FILM),
            MediaItem(id = "2", title = "Series 1", synopsis = "", videoUrl = "", genres = listOf("Comedy"), type = MediaType.SERIE_TV)
        )

        // When category has no keywords, all media should be passed as initial items
        val keywordMatches = if (categoryWithoutKeywords.keywords.isNotEmpty()) {
            allMedia.filter { item ->
                categoryWithoutKeywords.keywords.any { kw ->
                    item.genres.any { g -> g.contains(kw, ignoreCase = true) } ||
                    item.title.contains(kw, ignoreCase = true) ||
                    item.synopsis.contains(kw, ignoreCase = true)
                }
            }
        } else {
            allMedia
        }

        // Then all media should be included
        assertEquals(2, keywordMatches.size)
    }

    @Test
    fun `category with keywords and matches should filter correctly`() {
        // Given a category with keywords and matching media
        val actionCategory = GlobalSearchCategory(
            id = "action",
            labelRes = 2,
            gradientStart = com.example.ui.theme.NovaRed,
            gradientEnd = com.example.ui.theme.NovaRed,
            accentColor = com.example.ui.theme.NovaRed,
            keywords = listOf("Action", "Azione"),
            movieGenreId = 28,
            tvGenreId = 10759
        )

        val allMedia = listOf(
            MediaItem(id = "1", title = "Action Movie", synopsis = "", videoUrl = "", genres = listOf("Action", "Adventure"), type = MediaType.FILM),
            MediaItem(id = "2", title = "Comedy Film", synopsis = "", videoUrl = "", genres = listOf("Comedy"), type = MediaType.FILM),
            MediaItem(id = "3", title = "Action Series", synopsis = "", videoUrl = "", genres = listOf("Action"), type = MediaType.SERIE_TV)
        )

        // When filtering by keywords
        val keywordMatches = if (actionCategory.keywords.isNotEmpty()) {
            allMedia.filter { item ->
                actionCategory.keywords.any { kw ->
                    item.genres.any { g -> g.contains(kw, ignoreCase = true) } ||
                    item.title.contains(kw, ignoreCase = true) ||
                    item.synopsis.contains(kw, ignoreCase = true)
                }
            }
        } else {
            allMedia
        }

        // Then only matching items should be included
        assertEquals(2, keywordMatches.size)
        assertTrue(keywordMatches.any { it.title == "Action Movie" })
        assertTrue(keywordMatches.any { it.title == "Action Series" })
    }

    @Test
    fun `category with keywords but no matches should return empty list using old approach`() {
        // Given a category with keywords but no matching media
        val horrorCategory = GlobalSearchCategory(
            id = "horror",
            labelRes = 3,
            gradientStart = com.example.ui.theme.NovaRed,
            gradientEnd = com.example.ui.theme.NovaRed,
            accentColor = com.example.ui.theme.NovaRed,
            keywords = listOf("Horror", "Terrore"),
            movieGenreId = 27,
            tvGenreId = 27
        )

        val allMedia = listOf(
            MediaItem(id = "1", title = "Comedy Film", synopsis = "", videoUrl = "", genres = listOf("Comedy"), type = MediaType.FILM),
            MediaItem(id = "2", title = "Action Series", synopsis = "", videoUrl = "", genres = listOf("Action"), type = MediaType.SERIE_TV)
        )

        // When filtering by keywords with no matches
        val keywordMatches = if (horrorCategory.keywords.isNotEmpty()) {
            allMedia.filter { item ->
                horrorCategory.keywords.any { kw ->
                    item.genres.any { g -> g.contains(kw, ignoreCase = true) } ||
                    item.title.contains(kw, ignoreCase = true) ||
                    item.synopsis.contains(kw, ignoreCase = true)
                }
            }
        } else {
            allMedia
        }

        // Then no items should be included (old behavior that causes empty categories)
        assertEquals(0, keywordMatches.size)
    }
}

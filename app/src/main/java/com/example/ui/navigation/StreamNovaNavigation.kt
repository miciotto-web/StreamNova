package com.example.ui.navigation

import android.os.Bundle
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.example.data.model.MediaType

sealed class Screen(val route: String) {
  object Browsing : Screen("browsing")

  object Detail : Screen("detail/{mediaType}/{tmdbId}") {
    const val ARG_MEDIA_TYPE = "mediaType"
    const val ARG_TMDB_ID = "tmdbId"

    val arguments: List<NamedNavArgument> = listOf(
      navArgument(ARG_MEDIA_TYPE) { type = NavType.StringType },
      navArgument(ARG_TMDB_ID) { type = NavType.IntType }
    )

    fun createRoute(mediaType: MediaType, tmdbId: Int): String {
      val typeStr = if (mediaType == MediaType.SERIE_TV) "TV" else "MOVIE"
      return "detail/$typeStr/$tmdbId"
    }
  }

  object Provider : Screen("provider/{providerId}") {
    const val ARG_PROVIDER_ID = "providerId"
    val arguments: List<NamedNavArgument> = listOf(
      navArgument(ARG_PROVIDER_ID) { type = NavType.StringType }
    )
    fun createRoute(providerId: String): String = "provider/$providerId"
  }
}

data class DetailNavArgs(
  val tmdbId: Int,
  val mediaType: MediaType
) {
  companion object {
    fun fromBundle(bundle: Bundle?): DetailNavArgs {
      val id = bundle?.getInt(Screen.Detail.ARG_TMDB_ID) ?: 0
      val typeStr = bundle?.getString(Screen.Detail.ARG_MEDIA_TYPE) ?: "MOVIE"
      val type = if (typeStr.equals("TV", ignoreCase = true)) MediaType.SERIE_TV else MediaType.FILM
      return DetailNavArgs(id, type)
    }
  }
}

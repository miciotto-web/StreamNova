package com.example.data.stremio

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Fix poster e anno "0" dei cataloghi addon (Easy Catalog / Xperience):
 * path relativi TMDB risolti, anno estratto da `releaseInfo` / `release_date` /
 * `released`, mai uno "0" mostrato a schermo.
 */
class StremioCatalogMapperTest {

  private val originalResolver = StremioCatalogMapper.posterResolver

  @After
  fun tearDown() {
    StremioCatalogMapper.posterResolver = originalResolver
  }

  private fun meta(
    id: String = "tt0944947",
    type: String = TYPE_SERIES,
    poster: String? = null,
    posterFallback: String? = null,
    releaseInfo: Any? = null,
    releaseDate: String? = null,
    released: String? = null
  ): StremioMetaItem = StremioMetaItem(
    id = id,
    type = type,
    name = "Titolo di prova",
    poster = poster,
    posterFallback = posterFallback,
    releaseInfo = releaseInfo,
    releaseDate = releaseDate,
    released = released
  )

  @Test
  fun posterRelativoTmdbVienePortatoAdUrlAssoluto() {
    val item = StremioCatalogMapper.toMediaItem(meta(poster = "/abc123xyz.jpg"))

    assertEquals(
      "https://image.tmdb.org/t/p/w500/abc123xyz.jpg",
      StremioCatalogMapper.extractImageUrl(item.posterUrl)
    )
  }

  @Test
  fun posterConPercorsoNudoTmdbVieneRisolto() {
    assertEquals(
      "https://image.tmdb.org/t/p/w500/abc123xyz.jpg",
      StremioCatalogMapper.extractImageUrl(StremioCatalogMapper.sanitizePoster("abc123xyz.jpg"))
    )
  }

  @Test
  fun posterHttpVienePromossoAHttps() {
    assertEquals(
      "https://img.example.org/x.jpg",
      StremioCatalogMapper.sanitizePoster("http://img.example.org/x.jpg")
    )
    assertEquals(
      "https://img.example.org/x.jpg",
      StremioCatalogMapper.sanitizePoster("https://img.example.org/x.jpg")
    )
  }

  @Test
  fun posterNonValidoNonVienePassatoAllaUI() {
    assertNull(StremioCatalogMapper.sanitizePoster(null))
    assertNull(StremioCatalogMapper.sanitizePoster("   "))
    assertNull(StremioCatalogMapper.toMediaItem(meta(poster = "non un url")).posterUrl)
  }

  @Test
  fun posterFallbackDellAddonVieneUsatoQuandoIlPosterManca() {
    val item = StremioCatalogMapper.toMediaItem(
      meta(poster = null, posterFallback = "https://image.tmdb.org/t/p/w500/fallback.jpg")
    )

    assertEquals("https://image.tmdb.org/t/p/w500/fallback.jpg", item.posterUrl)
  }

  @Test
  fun posterRecuperatoDaIdTmdbQuandoIlCatalogoNonLoFornisce() {
    StremioCatalogMapper.posterResolver = { id, isTv ->
      if (id == "tmdb:603" && !isTv) "https://image.tmdb.org/t/p/w500/matrix.jpg" else null
    }

    val resolved = StremioCatalogMapper.toMediaItem(meta(id = "tmdb:603", type = TYPE_MOVIE))
    val missing = StremioCatalogMapper.toMediaItem(meta(id = "tmdb:999", type = TYPE_MOVIE))

    assertEquals("https://image.tmdb.org/t/p/w500/matrix.jpg", resolved.posterUrl)
    assertNull(missing.posterUrl)
  }

  @Test
  fun annoEstrattoDaReleaseInfoReleaseDateEReleased() {
    assertEquals(2024, StremioCatalogMapper.resolveYear(meta(releaseInfo = "2024-05-10")))
    assertEquals(2019, StremioCatalogMapper.resolveYear(meta(releaseDate = "10/05/2019")))
    assertEquals(2021, StremioCatalogMapper.resolveYear(meta(released = "2021-11-24T12:00:00Z")))
    assertEquals(1999, StremioCatalogMapper.resolveYear(meta(releaseInfo = 1999)))
    assertEquals(2020, StremioCatalogMapper.resolveYear(meta(releaseInfo = "2020")))
  }

  @Test
  fun annoAssenteOPeriodicoMaiMostratoComeZero() {
    assertEquals(0, StremioCatalogMapper.resolveYear(meta()))
    assertEquals(0, StremioCatalogMapper.resolveYear(meta(releaseInfo = "0")))
    assertEquals(0, StremioCatalogMapper.resolveYear(meta(releaseInfo = "?")))

    val item = StremioCatalogMapper.toMediaItem(meta(releaseInfo = null))
    assertEquals(0, item.year)
    assertEquals("", item.displayYear)

    val year2020 = StremioCatalogMapper.toMediaItem(meta(releaseInfo = "2020-01-01"))
    assertEquals("2020", year2020.displayYear)
  }
}

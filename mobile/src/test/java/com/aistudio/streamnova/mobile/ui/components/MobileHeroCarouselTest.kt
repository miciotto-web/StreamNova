package com.aistudio.streamnova.mobile.ui.components

import com.example.data.model.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileHeroCarouselTest {

  private fun media(id: String, logoUrl: String? = null) = MediaItem(
    id = id,
    title = id,
    synopsis = "",
    videoUrl = "",
    logoUrl = logoUrl
  )

  @Test
  fun avantiScorreConWrapAround() {
    assertEquals(1, MobileHeroCarouselNavigation.nextIndex(0, 3))
    assertEquals(2, MobileHeroCarouselNavigation.nextIndex(1, 3))
    assertEquals(0, MobileHeroCarouselNavigation.nextIndex(2, 3))
    assertEquals(0, MobileHeroCarouselNavigation.nextIndex(0, 0))
  }

  @Test
  fun indietroSiFermaSulPrimoPoster() {
    assertEquals(1, MobileHeroCarouselNavigation.previousIndex(2))
    assertEquals(0, MobileHeroCarouselNavigation.previousIndex(1))
    assertEquals(0, MobileHeroCarouselNavigation.previousIndex(0))
    assertFalse(MobileHeroCarouselNavigation.canScrollToPrevious(0, 3))
    assertTrue(MobileHeroCarouselNavigation.canScrollToPrevious(1, 3))
    assertFalse(MobileHeroCarouselNavigation.canScrollToPrevious(1, 1))
  }

  @Test
  fun logoOriginaleUsatoSoloSeValorizzato() {
    assertEquals(
      "https://image.tmdb.org/t/p/w500/logo.png",
      media("a", "https://image.tmdb.org/t/p/w500/logo.png").heroLogoUrl()
    )
    assertNull(media("b", null).heroLogoUrl())
    assertNull(media("c", "   ").heroLogoUrl())
  }
}

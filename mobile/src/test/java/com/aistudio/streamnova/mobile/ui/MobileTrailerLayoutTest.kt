package com.aistudio.streamnova.mobile.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class MobileTrailerLayoutTest {

  private val aspectRatio = 16f / 9f

  @Test
  fun inPortraitIlPlayerUsaLInteraLarghezzaDisponibile() {
    val size = trailerPlayerSize(availableWidthDp = 448f, availableHeightDp = 900f)

    assertEquals(448f, size.widthDp, 0.001f)
    assertEquals(252f, size.heightDp, 0.001f)
  }

  @Test
  fun inLandscapeIlPlayerUsaLInteraAltezzaDisponibile() {
    val size = trailerPlayerSize(availableWidthDp = 997f, availableHeightDp = 448f)

    assertEquals(448f, size.heightDp, 0.001f)
    assertEquals(448f * aspectRatio, size.widthDp, 0.01f)
  }

  @Test
  fun ilRapportoRestaSempreSediciNove() {
    val portrait = trailerPlayerSize(availableWidthDp = 448f, availableHeightDp = 900f)
    val landscape = trailerPlayerSize(availableWidthDp = 997f, availableHeightDp = 448f)

    assertEquals(aspectRatio, portrait.widthDp / portrait.heightDp, 0.001f)
    assertEquals(aspectRatio, landscape.widthDp / landscape.heightDp, 0.001f)
  }

  @Test
  fun areaGiaSediciNoveNonVieneRidimensionata() {
    val size = trailerPlayerSize(availableWidthDp = 800f, availableHeightDp = 450f)

    assertEquals(800f, size.widthDp, 0.001f)
    assertEquals(450f, size.heightDp, 0.001f)
  }

  @Test
  fun areaNonValidaRestituisceZeroSenzaCrash() {
    assertEquals(
      TrailerPlayerSize(0f, 0f),
      trailerPlayerSize(availableWidthDp = 0f, availableHeightDp = 900f)
    )
    assertEquals(
      TrailerPlayerSize(0f, 0f),
      trailerPlayerSize(availableWidthDp = 448f, availableHeightDp = -1f)
    )
  }
}

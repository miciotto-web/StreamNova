package com.example.ui.screens.player

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.stremio.StremioSubtitle
import com.example.data.stremio.StremioSubtitleAdapter
import com.example.data.stremio.StremioSubtitleBridge
import com.example.data.stremio.StremioStreamItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Test del passaggio dei sottotitoli Stremio da `StremioStreamItem` a `StreamSource`,
 * cioè il punto in cui il bridge incontra il runtime per la prima volta.
 */
@RunWith(AndroidJUnit4::class)
class StreamSourceSubtitlesTest {

  private fun stream(vararg subtitles: StremioSubtitle?) =
    StremioStreamItem(name = "1080p", title = "Movie", subtitles = subtitles.toList())

  @Test
  fun streamSenzaSottotitoliProduceListaVuota() {
    val entries = StremioSubtitleBridge.fromStream(
      StremioStreamItem(name = "1080p")
    )

    assertTrue(StremioSubtitleAdapter.toSubtitles(entries, "Addon").isEmpty())
  }

  @Test
  fun streamConSottotitoliProduceModelloInterno() {
    val entries = StremioSubtitleBridge.fromStream(
      stream(StremioSubtitle(id = "s1", url = "https://a.test/ita.srt", lang = "ita", label = "Italiano"))
    )

    val subtitles = StremioSubtitleAdapter.toSubtitles(entries, "IlCorsaroViola")

    val subtitle = subtitles.single()
    assertEquals("s1", subtitle.id)
    assertEquals("https://a.test/ita.srt", subtitle.url)
    assertEquals("ita", subtitle.lang)
    assertEquals("Italiano", subtitle.label)
    assertEquals("IlCorsaroViola", subtitle.addonName)
    assertTrue(subtitle.isStreamProvided)
  }

  @Test
  fun provenienzaEndpointNonEStreamProvided() {
    val entries = StremioSubtitleBridge.fromEndpoint(
      listOf(StremioSubtitle(id = "e1", url = "https://a.test/eng.srt", lang = "eng"))
    )

    val subtitles = StremioSubtitleAdapter.toSubtitles(entries, "Addon")

    assertTrue(!subtitles.single().isStreamProvided)
  }

  @Test
  fun ordinePreservatoDalBridge() {
    val entries = StremioSubtitleBridge.fromStream(
      stream(
        StremioSubtitle(id = "a", url = "https://a.test/a.srt", lang = "ita"),
        StremioSubtitle(id = "b", url = "https://a.test/b.srt", lang = "ita"),
        StremioSubtitle(id = "c", url = "https://a.test/c.srt", lang = "eng")
      )
    )

    assertEquals(
      listOf("a", "b", "c"),
      StremioSubtitleAdapter.toSubtitles(entries, "Addon").map { it.id }
    )
  }
}

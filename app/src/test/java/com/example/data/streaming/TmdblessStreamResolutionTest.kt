package com.example.data.streaming

import com.example.data.provider.AnimeStreamProvider
import com.example.data.streaming.providers.Cb01Provider
import com.example.data.streaming.providers.EurostreamingProvider
import com.example.data.streaming.providers.MultiEmbedProvider
import com.example.data.streaming.providers.StreamingCommunityProvider
import com.example.data.streaming.providers.SuperEmbedProvider
import com.example.data.streaming.providers.TwoEmbedProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Un titolo apribile dal solo ID IMDb (`tt...`) deve restare riproducibile anche
 * quando la risoluzione IMDb -> TMDB non produce nulla (chiave TMDB assente,
 * titolo non presente su TMDB o rete non raggiungibile).
 *
 * [StreamManager.providerRequiresTmdbId] decide quali provider sopravvivono in
 * quello stato: Stremio e TorBox interrogano la risorsa `stream` con l'ID IMDb
 * nativo e i provider italiani cercano per titolo, mentre i player basati solo su
 * `tmdbId` non hanno alcun percorso alternativo e vanno spenti invece che
 * invocati per farli fallire.
 */
class TmdblessStreamResolutionTest {

  @Test
  fun providerBasatiSoloSuTmdbIdRichiedonoTmdb() {
    val soloTmdb = listOf(
      VixSrcProvider(),
      MultiEmbedProvider(),
      TwoEmbedProvider(),
      SuperEmbedProvider()
    )
    soloTmdb.forEach { provider ->
      assertTrue(
        "${provider.javaClass.simpleName} costruisce l'URL del player solo da tmdbId",
        StreamManager.providerRequiresTmdbId(provider)
      )
    }
  }

  @Test
  fun providerPerTitoloNonRichiedonoTmdb() {
    val perTitolo = listOf(
      StreamingCommunityProvider(),
      Cb01Provider(),
      EurostreamingProvider(),
      TorBoxStreamProvider(),
      AnimeStreamProvider()
    )
    perTitolo.forEach { provider ->
      assertFalse(
        "${provider.javaClass.simpleName} cerca per titolo o per ID IMDb e deve restare attivo",
        StreamManager.providerRequiresTmdbId(provider)
      )
    }
  }

  /**
   * Un provider sconosciuto non viene mai spento: la classificazione è una lista
   * esplicita di esclusioni, quindi l'aggiunta di un nuovo provider per titolo non
   * lo disattiva silenziosamente quando manca l'ID TMDB.
   */
  @Test
  fun providerSconosciutoNonVieneEscluso() {
    val sconosciuto = object : StreamProvider {
      override suspend fun getStreams(
        tmdbId: Int,
        isTv: Boolean,
        season: Int?,
        episode: Int?,
        title: String?,
        year: Int?
      ): List<StreamSource> = emptyList()
    }
    assertFalse(StreamManager.providerRequiresTmdbId(sconosciuto))
  }
}

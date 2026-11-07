package com.example.ui.viewmodel

import com.example.data.repository.MediaRepository
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Test unitari sulla query TMDB CANONICA dei cataloghi provider usata dalle
 * sezioni Film/Serie TV (`loadCategoryProvider` → `loadProviderCatalog`).
 *
 * Storia: HBO seguiva un percorso dedicato (network ID 49|3186 per le serie,
 * watch_region=US per i film) che produceva cataloghi incompleti e divergenti
 * dalla schermata Provider. Oggi TUTTI i provider, HBO incluso, passano dalla
 * query canonica: il test ne fissa il contratto.
 */
class ProviderTmdbQueryTest {

    @Test
    fun `la query canonica di HBO e Max usa 1899 più 384 in watch_region IT`() {
        // 1899 = "HBO Max" in Italia (384 non esiste più in watch_region=IT e da
        // solo restituisce 0 risultati). 384 resta in OR per compatibilità con le
        // altre regioni.
        assertEquals(
            MediaRepository.ProviderTmdbQuery(providerIds = "1899|384", watchRegion = "IT"),
            MediaRepository.providerTmdbQuery(384)
        )
        assertEquals(
            MediaRepository.ProviderTmdbQuery(providerIds = "1899|384", watchRegion = "IT"),
            MediaRepository.providerTmdbQuery(1899)
        )
    }

    @Test
    fun `la query canonica di NOW TV usa il provider 39 in watch_region IT`() {
        assertEquals(
            MediaRepository.ProviderTmdbQuery(providerIds = "39", watchRegion = "IT"),
            MediaRepository.providerTmdbQuery(39)
        )
    }

    @Test
    fun `gli altri provider usano il proprio ID in watch_region IT`() {
        assertEquals(
            MediaRepository.ProviderTmdbQuery(providerIds = "8", watchRegion = "IT"),
            MediaRepository.providerTmdbQuery(8)
        )
        assertEquals(
            MediaRepository.ProviderTmdbQuery(providerIds = "531", watchRegion = "IT"),
            MediaRepository.providerTmdbQuery(531)
        )
    }

    @Test
    fun `il tag provider di HBO e NOW TV coincide con providerId dei filtri di sezione`() {
        // Il filtro di sezione (CategoryCatalogFilter) passa providerId="hbo" /
        // "nowtv": è lo stesso tag che `providerTagFor` attribuisce ai titoli del
        // catalogo, quindi la griglia trova i contenuti scaricati.
        assertEquals("hbo", MediaRepository.providerTagFor(384))
        assertEquals("hbo", MediaRepository.providerTagFor(1899))
        assertEquals("nowtv", MediaRepository.providerTagFor(39))
    }
}

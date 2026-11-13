package com.example.ui.screens

import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Contratto dei badge di ordinamento delle schede Film / Serie TV.
 *
 * Il riordino è client-side: la lista filtrata dal badge provider deve essere restituita
 * nell'ordine scelto senza toccare la sorgente del catalogo. I test coprono le tre modalità,
 * i criteri di spareggio e i valori mancanti (anno/voto = 0), che non devono mescolarsi ai
 * valori reali.
 */
class CategorySortOrderTest {

  private fun item(
    id: String,
    year: Int,
    rating: Float = 8f,
    releaseDate: String? = null,
    title: String = id
  ): MediaItem = MediaItem(
    id = id,
    title = title,
    synopsis = "",
    videoUrl = "",
    type = MediaType.FILM,
    year = year,
    releaseDate = releaseDate,
    rating = rating
  )

  private fun ids(items: List<MediaItem>): List<String> = items.map { it.id }

  @Test
  fun popolariPreservaEsattamenteLOrdineNativoDelCatalogo() {
    val items = listOf(item("a", 2001), item("b", 2024), item("c", 2015))

    val sorted = sortCategoryItems(items, CategorySortOrder.POPOLARI)

    assertSame("«Popolari» non deve ricostruire la lista", items, sorted)
    assertEquals(listOf("a", "b", "c"), ids(sorted))
  }

  @Test
  fun recentiOrdinaPerAnnoDecrescente() {
    val items = listOf(item("vecchio", 2001), item("nuovo", 2024), item("medio", 2015))

    val sorted = sortCategoryItems(items, CategorySortOrder.RECENTI)

    assertEquals(listOf("nuovo", "medio", "vecchio"), ids(sorted))
  }

  @Test
  fun recentiUsaLaDataCompletaDentroLoStessoAnno() {
    val items = listOf(
      item("gennaio", 2024, releaseDate = "2024-01-10"),
      item("giugno", 2024, releaseDate = "2024-06-01"),
      item("non_datato", 2024)
    )

    val sorted = sortCategoryItems(items, CategorySortOrder.RECENTI)

    assertEquals(listOf("giugno", "gennaio", "non_datato"), ids(sorted))
  }

  @Test
  fun recentiMetteInCodaGliAnniSconosciuti() {
    val items = listOf(item("sconosciuto", 0, releaseDate = null), item("noto", 1999))

    val sorted = sortCategoryItems(items, CategorySortOrder.RECENTI)

    assertEquals(listOf("noto", "sconosciuto"), ids(sorted))
  }

  @Test
  fun perValutazioneOrdinaPerVotoDecrescente() {
    val items = listOf(
      item("basso", 2020, rating = 6.1f),
      item("alto", 1990, rating = 9.4f),
      item("medio", 2024, rating = 7.8f)
    )

    val sorted = sortCategoryItems(items, CategorySortOrder.PER_VALUTAZIONE)

    assertEquals(listOf("alto", "medio", "basso"), ids(sorted))
  }

  @Test
  fun perValutazioneUsaAnnoEPoiTitoloComeSpareggio() {
    val items = listOf(
      item("vecchio", 2001, rating = 8f, title = "Vecchio"),
      item("recente", 2024, rating = 8f, title = "Recente"),
      item("alfa", 2024, rating = 8f, title = "Alfa")
    )

    val sorted = sortCategoryItems(items, CategorySortOrder.PER_VALUTAZIONE)

    assertEquals(listOf("alfa", "recente", "vecchio"), ids(sorted))
  }

  @Test
  fun perValutazioneMetteInCodaIVotiSconosciuti() {
    val items = listOf(
      item("senza_voto", 2024, rating = 0f),
      item("con_voto", 1990, rating = 7f)
    )

    val sorted = sortCategoryItems(items, CategorySortOrder.PER_VALUTAZIONE)

    assertEquals(listOf("con_voto", "senza_voto"), ids(sorted))
  }

  @Test
  fun aParitaDiChiaveRestaLOrdineDiArrivo() {
    val items = listOf(item("primo", 2024), item("secondo", 2024), item("terzo", 2024))

    assertEquals(
      listOf("primo", "secondo", "terzo"),
      ids(sortCategoryItems(items, CategorySortOrder.RECENTI))
    )
    assertEquals(
      listOf("primo", "secondo", "terzo"),
      ids(sortCategoryItems(items, CategorySortOrder.PER_VALUTAZIONE))
    )
  }

  @Test
  fun leDueModalitaNonRestituisconoLaListaOriginale() {
    val items = listOf(item("a", 2001), item("b", 2024))

    val recenti = sortCategoryItems(items, CategorySortOrder.RECENTI)
    val valutazione = sortCategoryItems(items, CategorySortOrder.PER_VALUTAZIONE)

    assertEquals("«Recenti» deve produrre una nuova lista", false, recenti === items)
    assertEquals("«Per Valutazione» deve produrre una nuova lista", false, valutazione === items)
  }
}

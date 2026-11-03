package com.aistudio.streamnova.mobile.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileNavigationPolicyTest {

  @Test
  fun lOrdineDelleVociIncludeImpostazioniSubitoDopoCerca() {
    assertEquals(
      listOf(
        MobileTopDestination.HOME,
        MobileTopDestination.MOVIES,
        MobileTopDestination.SERIES,
        MobileTopDestination.FAVORITES,
        MobileTopDestination.SEARCH,
        MobileTopDestination.SETTINGS,
        MobileTopDestination.ADDONS
      ),
      mobileTopDestinations
    )
    val searchIndex = mobileTopDestinations.indexOf(MobileTopDestination.SEARCH)
    assertEquals(
      MobileTopDestination.SETTINGS,
      mobileTopDestinations[searchIndex + 1]
    )
  }

  @Test
  fun laBarraInferioreUnicaValeInPortraitELandscape() {
    assertEquals(
      MobileNavigationPlacement.BOTTOM_BAR,
      mobileNavigationPlacement(isLandscape = false)
    )
    assertEquals(
      MobileNavigationPlacement.BOTTOM_BAR,
      mobileNavigationPlacement(isLandscape = true)
    )
  }

  @Test
  fun leRotteTopLevelSonoUnivoche() {
    val routes = mobileTopDestinations.map { it.route }
    assertEquals(routes.size, routes.distinct().size)
  }

  @Test
  fun laRottaProviderUsaLEsameIDDellaHomeTv() {
    assertEquals("provider/netflix", MobileRoutes.provider("netflix"))
    assertEquals("provider/paramount", MobileRoutes.provider("paramount"))
  }

  @Test
  fun addonCompareNellaBarraSoloInModalitaDebrid() {
    assertEquals("addons", MobileRoutes.ADDONS)
    val withAddons = visibleTopDestinations(showAddons = true)
    val withoutAddons = visibleTopDestinations(showAddons = false)
    assertEquals(7, withAddons.size)
    assertEquals(6, withoutAddons.size)
    assertTrue(withAddons.contains(MobileTopDestination.ADDONS))
    assertFalse(withoutAddons.contains(MobileTopDestination.ADDONS))
    // Impostazioni resta immediatamente dopo Cerca in entrambe le configurazioni.
    assertEquals(
      MobileTopDestination.SETTINGS,
      withAddons[withAddons.indexOf(MobileTopDestination.SEARCH) + 1]
    )
    assertEquals(
      MobileTopDestination.SETTINGS,
      withoutAddons[withoutAddons.indexOf(MobileTopDestination.SEARCH) + 1]
    )
  }

  @Test
  fun laRottaDettaglioCodificaLIdEPreservaLArgomentoDelPlayer() {
    assertEquals("detail/tmdb_m_1", MobileRoutes.detail("tmdb_m_1"))
    assertEquals(
      "player/tmdb_tv_1?season=2&episode=3&autoplay=false&manual=false",
      MobileRoutes.player("tmdb_tv_1", season = 2, episode = 3)
    )
    assertEquals(
      "player/tmdb_m_1?season=-1&episode=-1&autoplay=false&manual=false",
      MobileRoutes.player("tmdb_m_1")
    )
    // Avanzamento automatico: il nuovo episodio parte senza source picker.
    assertEquals(
      "player/tmdb_tv_1?season=1&episode=2&autoplay=true&manual=false",
      MobileRoutes.player("tmdb_tv_1", season = 1, episode = 2, autoStart = true)
    )
  }

  @Test
  fun laRottaPlayerPortaIlFlagDiSelezioneManuale() {
    // «Seleziona fonte»: stessa rotta del player, con il flag che tiene aperto
    // il selettore delle sorgenti senza avviare la riproduzione.
    assertEquals(
      "player/tmdb_m_1?season=-1&episode=-1&autoplay=false&manual=true",
      MobileRoutes.player("tmdb_m_1", manual = true)
    )
    assertEquals(
      "player/tmdb_tv_1?season=2&episode=3&autoplay=false&manual=true",
      MobileRoutes.player("tmdb_tv_1", season = 2, episode = 3, manual = true)
    )
  }
}

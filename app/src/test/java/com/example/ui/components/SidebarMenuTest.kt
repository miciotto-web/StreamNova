package com.example.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.R
import com.example.data.prefs.StreamingEngineMode
import com.example.ui.viewmodel.SidebarSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifica che il menu laterale esponga la voce **Addon** (con icona dedicata)
 * accanto alle altre sezioni, comprese le Impostazioni, e che la nasconda
 * quando la modalità di riproduzione non è Debrid TorBox.
 */
@RunWith(AndroidJUnit4::class)
class SidebarMenuTest {

  @Test
  fun menuLateraleContieneVoceAddon() {
    val items = sidebarMenuItems()

    val addon = items.firstOrNull { it.section == SidebarSection.ADDON }
    assertNotNull("Voce Addon mancante nel menu laterale", addon)
    assertEquals(R.string.nav_addons, addon!!.labelRes)
    assertEquals(Icons.Default.Extension, addon.icon)

    val idxAddon = items.indexOfFirst { it.section == SidebarSection.ADDON }
    val idxSettings = items.indexOfFirst { it.section == SidebarSection.IMPOSTAZIONI }
    assertTrue("Addon deve precedere Impostazioni", idxAddon in 0 until idxSettings)
  }

  @Test
  fun menuLateraleMantieneSezioniBase() {
    val sections = sidebarMenuItems().map { it.section }
    assertTrue(sections.contains(SidebarSection.HOME))
    assertTrue(sections.contains(SidebarSection.CERCA))
    assertTrue(sections.contains(SidebarSection.IMPOSTAZIONI))
    assertEquals(sections.size, sections.distinct().size)
  }

  /**
   * Modalità Debrid TorBox: la voce Addon è presente, quindi il menu visibile coincide
   * con quello completo (nessuna voce nascosta in questa modalità).
   */
  @Test
  fun voceAddonVisibileInModalitaDebridTorBox() {
    val items = visibleSidebarMenuItems(StreamingEngineMode.DEBRID_TORBOX.supportsAddonsSection)

    assertTrue(
      "In modalità Debrid TorBox la voce Addon deve essere visibile",
      items.any { it.section == SidebarSection.ADDON }
    )
    assertEquals(sidebarMenuItems(), items)
  }

  /**
   * Modalità Web Streaming (HTTP): la voce Addon sparisce completamente dal menu, mentre
   * tutte le altre sezioni restano e mantengono l'ordine originale (nessun buco, nessun
   * indice spostato: la lista è semplicemente più corta).
   */
  @Test
  fun voceAddonNascostaInModalitaHttpWeb() {
    val items = visibleSidebarMenuItems(StreamingEngineMode.HTTP_WEB.supportsAddonsSection)

    assertTrue(
      "In modalità Web Streaming la voce Addon non deve comparire",
      items.none { it.section == SidebarSection.ADDON }
    )
    assertEquals(sidebarMenuItems().size - 1, items.size)
    assertEquals(
      sidebarMenuItems().filterNot { it.section == SidebarSection.ADDON },
      items
    )

    // "Impostazioni" resta l'ultima voce: nascondere Addon non deve alterare l'ordine.
    assertEquals(SidebarSection.IMPOSTAZIONI, items.last().section)
  }

  /**
   * La regola di visibilità vive sull'enum della modalità di riproduzione: solo
   * DEBRID_TORBOX abilita l'area Addon, la modalità di default HTTP_WEB la esclude.
   */
  @Test
  fun soloDebridTorBoxAbilitaAreaAddon() {
    assertTrue(StreamingEngineMode.DEBRID_TORBOX.supportsAddonsSection)
    assertFalse(StreamingEngineMode.HTTP_WEB.supportsAddonsSection)
  }
}

package com.example.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.R
import com.example.ui.viewmodel.SidebarSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifica che il menu laterale esponga la voce **Addon** (con icona dedicata)
 * accanto alle altre sezioni, comprese le Impostazioni.
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
}

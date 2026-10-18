package com.example.ui.screens

import androidx.compose.ui.graphics.Color
import com.example.ui.theme.NovaCyan
import com.example.ui.theme.NovaSurface
import com.example.ui.theme.NovaSurfaceVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regressione della "banda blu scura" nella voce evidenziata dei dialog Audio e
 * Sottotitoli (e degli altri menu che usano le stesse righe).
 *
 * Il difetto: ogni riga disegnava un PROPRIO sfondo [NovaSurfaceVariant] (navy scuro)
 * e un PROPRIO bordo ciano INTERNI al ring di focus di `TvFocusableBox`: i due bordi
 * concentrici racchiudevano un anello scuro che sporca la selezione.
 *
 * L'aspetto atteso dopo la correzione mantiene il focus immediatamente riconoscibile
 * su TV (ring esterno + fill ciano CHIARO + testo ciano) e slate per stato:
 * - selezionata e a fuoco: fill ciano 0.2, NESSUN bordo interno (niente doppio ring);
 * - solo a fuoco: fill ciano chiaro, NESSUN bordo interno;
 * - solo selezionata: fill ciano 0.2 + bordo ciano (chip attivo);
 * - neutra: tutto trasparente.
 */
class TrackOptionRowAppearanceTest {

  @Test
  fun voceSolaAFuocoNessunAnelloScuroInterno() {
    val appearance = trackOptionRowAppearance(isSelected = false, isFocused = true)
    assertEquals(NovaCyan.copy(alpha = 0.15f), appearance.background)
    assertEquals(Color.Transparent, appearance.borderColor)
    // La banda colpevole era esplicitamente il fill navy scuro a fuoco:
    assertNotEquals(NovaSurfaceVariant, appearance.background)
  }

  @Test
  fun voceSelezionataEdAFuocoUnSoloRingDiFocus() {
    val appearance = trackOptionRowAppearance(isSelected = true, isFocused = true)
    assertEquals(NovaCyan.copy(alpha = 0.2f), appearance.background)
    assertEquals(Color.Transparent, appearance.borderColor) // il ring è quello di TvFocusableBox
  }

  @Test
  fun voceSelezionataNonAFuocoMantieneIlChipAttivo() {
    val appearance = trackOptionRowAppearance(isSelected = true, isFocused = false)
    assertEquals(NovaCyan.copy(alpha = 0.2f), appearance.background)
    assertEquals(NovaCyan, appearance.borderColor)
  }

  @Test
  fun voceNeutraCompletamenteTrasparente() {
    val appearance = trackOptionRowAppearance(isSelected = false, isFocused = false)
    assertEquals(Color.Transparent, appearance.background)
    assertEquals(Color.Transparent, appearance.borderColor)
  }

  @Test
  fun leQuattroCombinazioniSonoDistinte() {
    val appearances = listOf(
      trackOptionRowAppearance(false, false),
      trackOptionRowAppearance(false, true),
      trackOptionRowAppearance(true, false),
      trackOptionRowAppearance(true, true)
    )
    assertEquals(
      appearances.distinct().size,
      appearances.size
    )
  }

  // ── Container di testa (es. interruttore "Sottotitoli Forzati") ─────────────

  @Test
  fun containerDiTestaAFuocoFillChiaroSenzaBordoInterno() {
    assertEquals(NovaCyan.copy(alpha = 0.15f), trackHeaderRowFillColor(isFocused = true))
    assertEquals(Color.Transparent, trackHeaderRowBorderColor(isFocused = true))
  }

  @Test
  fun containerDiTestaARiposoRestaInvariato() {
    assertEquals(NovaSurface.copy(alpha = 0.6f), trackHeaderRowFillColor(isFocused = false))
    assertEquals(Color.White.copy(alpha = 0.12f), trackHeaderRowBorderColor(isFocused = false))
  }
}

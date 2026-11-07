package com.example.data.prefs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Modello condiviso dell'aspetto sottotitoli: 5 dimensioni, 4 sfondi, 4 opacità.
 *
 * I test bloccano i contratti che il player e le Impostazioni generali usano insieme:
 * - i nomi persistiti su DataStore restano stabili (nessuna preferenza persa);
 * - il colore di sfondo riceve l'alpha dell'opacità scelta, ma "Nessuno" resta trasparente;
 * - il testo resta leggibile su ogni sfondo (bianco su scuri, nero su bianco);
 * - la migrazione dal vecchio "Nero semi-trasparente" conserva l'aspetto precedente.
 */
class SubtitleAppearanceSettingsTest {

  @Test
  fun cinqueLivelliDiDimensioneInOrdineCrescente() {
    val sizes = SubtitleSize.entries
    assertEquals(5, sizes.size)
    assertEquals(
      listOf("EXTRA_SMALL", "SMALL", "MEDIUM", "LARGE", "VERY_LARGE"),
      sizes.map { it.name }
    )
    // L'ordine dell'enum è l'ordine mostrato nel menu: le frazioni devono crescere.
    val fractions = sizes.map { it.textSizeFraction }
    assertEquals(fractions.sorted(), fractions)
    assertEquals(0.0533f, SubtitleSize.MEDIUM.textSizeFraction, 0.0001f)
  }

  @Test
  fun iNomiStoriciDelleDimensioniRestanoStabili() {
    // Sono chiavi DataStore per nome: rinominarli perderebbe la scelta dell'utente.
    assertEquals(0.038f, SubtitleSize.SMALL.textSizeFraction, 0.0001f)
    assertEquals(0.075f, SubtitleSize.LARGE.textSizeFraction, 0.0001f)
    assertEquals(0.10f, SubtitleSize.VERY_LARGE.textSizeFraction, 0.0001f)
  }

  @Test
  fun quattroTipologieDiSfondo() {
    assertEquals(
      listOf("NONE", "BLACK", "WHITE", "BLUE"),
      SubtitleBackground.entries.map { it.name }
    )
  }

  @Test
  fun quattroLivelliDiOpacitaConAlphaArrotondato() {
    assertEquals(4, SubtitleOpacity.entries.size)
    assertEquals(listOf(100, 75, 50, 25), SubtitleOpacity.entries.map { it.percent })
    assertEquals(255, SubtitleOpacity.FULL.alpha)
    assertEquals(191, SubtitleOpacity.SEVENTY_FIVE.alpha)
    assertEquals(128, SubtitleOpacity.FIFTY.alpha)
    assertEquals(64, SubtitleOpacity.TWENTY_FIVE.alpha)
  }

  @Test
  fun nessunoSfondoRestaTrasparenteAQualunqueOpacita() {
    SubtitleOpacity.entries.forEach { opacity ->
      assertEquals(0, SubtitleBackground.NONE.withOpacity(opacity))
    }
  }

  @Test
  fun lopacitaVieneApplicataAlColoreDelloSfondo() {
    assertEquals(0xFF000000.toInt(), SubtitleBackground.BLACK.withOpacity(SubtitleOpacity.FULL))
    assertEquals(
      0xBF000000.toInt(),
      SubtitleBackground.BLACK.withOpacity(SubtitleOpacity.SEVENTY_FIVE)
    )
    // 50% = 0x80: identico al vecchio "Nero semi-trasparente".
    assertEquals(
      0x80000000.toInt(),
      SubtitleBackground.BLACK.withOpacity(SubtitleOpacity.FIFTY)
    )
    assertEquals(
      0x40000000,
      SubtitleBackground.BLACK.withOpacity(SubtitleOpacity.TWENTY_FIVE)
    )
    assertEquals(
      0x40FFFFFF.toInt(),
      SubtitleBackground.WHITE.withOpacity(SubtitleOpacity.TWENTY_FIVE)
    )
    assertEquals(
      0xBF0D47A1.toInt(),
      SubtitleBackground.BLUE.withOpacity(SubtitleOpacity.SEVENTY_FIVE)
    )
  }

  @Test
  fun ilTestoRestaLeggibileSuOgniSfondo() {
    assertEquals(0xFFFFFFFF.toInt(), SubtitleBackground.NONE.textColor)
    assertEquals(0xFFFFFFFF.toInt(), SubtitleBackground.BLACK.textColor)
    assertEquals(0xFF000000.toInt(), SubtitleBackground.WHITE.textColor)
    assertEquals(0xFFFFFFFF.toInt(), SubtitleBackground.BLUE.textColor)
  }

  @Test
  fun migrazioneDalVecchioNeroSemiTrasparente() {
    assertEquals(
      SubtitleBackground.BLACK to SubtitleOpacity.FIFTY,
      resolveSubtitleAppearance(storedBackground = "SEMI_TRANSPARENT", storedOpacity = null)
    )
    // Prima installazione: stesso aspetto del vecchio default (nero traslucido).
    assertEquals(
      SubtitleBackground.BLACK to SubtitleOpacity.FIFTY,
      resolveSubtitleAppearance(storedBackground = null, storedOpacity = null)
    )
    // Scelte esplicite del vecchio modello: restavano opache, quindi 100%.
    assertEquals(
      SubtitleBackground.BLACK to SubtitleOpacity.FULL,
      resolveSubtitleAppearance(storedBackground = "BLACK", storedOpacity = null)
    )
    assertEquals(
      SubtitleBackground.NONE to SubtitleOpacity.FULL,
      resolveSubtitleAppearance(storedBackground = "NONE", storedOpacity = null)
    )
  }

  @Test
  fun lopacitaSalvataHaPrecedenzaSullaMigrazione() {
    assertEquals(
      SubtitleBackground.BLACK to SubtitleOpacity.TWENTY_FIVE,
      resolveSubtitleAppearance(storedBackground = "BLACK", storedOpacity = "TWENTY_FIVE")
    )
    assertEquals(
      SubtitleBackground.BLACK to SubtitleOpacity.SEVENTY_FIVE,
      resolveSubtitleAppearance(
        storedBackground = "SEMI_TRANSPARENT",
        storedOpacity = "SEVENTY_FIVE"
      )
    )
    assertEquals(
      SubtitleBackground.WHITE to SubtitleOpacity.SEVENTY_FIVE,
      resolveSubtitleAppearance(storedBackground = "WHITE", storedOpacity = "SEVENTY_FIVE")
    )
  }

  @Test
  fun unOpacitaSconosciutaRicadeSullaMigrazione() {
    assertEquals(
      SubtitleBackground.BLACK to SubtitleOpacity.FIFTY,
      resolveSubtitleAppearance(storedBackground = "SEMI_TRANSPARENT", storedOpacity = "IGNOTA")
    )
  }

  @Test
  fun iDefaultConservanoLaspettoStorico() {
    val settings = PlaybackSettings()
    assertEquals(SubtitleSize.MEDIUM, settings.subtitleSize)
    assertEquals(SubtitleBackground.BLACK, settings.subtitleBackground)
    assertEquals(SubtitleOpacity.FIFTY, settings.subtitleOpacity)
    // Il default non coincide con "Nessuno": il vecchio default era un riquadro visibile.
    assertNotEquals(
      SubtitleBackground.NONE.withOpacity(settings.subtitleOpacity),
      settings.subtitleBackground.withOpacity(settings.subtitleOpacity)
    )
    assertTrue(settings.subtitleOpacity.alpha in 1..254)
  }
}

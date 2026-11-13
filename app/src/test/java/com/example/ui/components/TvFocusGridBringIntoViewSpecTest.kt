package com.example.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regressione del "sobbalzo" verticale della griglia Film / Serie TV a ogni LEFT/RIGHT.
 *
 * La griglia ha un viewport di poche righe (la testata con titolo, badge e filtri sta fuori):
 * con lo spec "a margini" (64dp sopra + 48dp sotto) i margini non sono soddisfacibili e a ogni
 * cambio di focus il contenuto scatta di ~8dp e rientra. Lo spec per griglie invece centra la
 * riga in ingresso e poi dichiara "già visibile" la riga: gli spostamenti orizzontali
 * restituiscono 0.
 *
 * Misure reali del TV 1080p (densità 2.0): viewport 738 px (369 dp), card 562 px (281 dp),
 * ingrandimento del focus 1.06 (3% per lato, 17 px).
 */
@OptIn(ExperimentalFoundationApi::class)
class TvFocusGridBringIntoViewSpecTest {

  private val viewport = 738f
  private val cardHeight = 562f
  private val focusScaleHalfGrowth = cardHeight * 0.03f

  private fun centeredOffset() = (viewport - cardHeight) / 2f - focusScaleHalfGrowth

  private fun assertDistance(expected: Float, actual: Float, message: String = "") {
    assertEquals(message, expected, actual, 0.01f)
  }

  @Test
  fun rigaGiaInteramenteVisibileNonMuoveIlContenuto() {
    val spec = TvFocusGridBringIntoViewSpec()

    // Prima riga all'inizio della lista.
    assertDistance(0f, spec.calculateScrollDistance(0f, cardHeight, viewport))
    // Riga centrata: il rect della card a fuoco è spostato in alto del 3% (scala 1.06).
    assertDistance(0f, spec.calculateScrollDistance(centeredOffset(), cardHeight, viewport))
  }

  @Test
  fun rigaInIngressoVieneCentrata() {
    val spec = TvFocusGridBringIntoViewSpec()

    val delta = spec.calculateScrollDistance(viewport - cardHeight + 418f, cardHeight, viewport)
    assertDistance(viewport - cardHeight + 418f - centeredOffset(), delta)
  }

  @Test
  fun dopoIlCentraggioLoSpostamentoOrizzontaleNonMuoveLaRiga() {
    val spec = TvFocusGridBringIntoViewSpec()

    // Ingresso nella riga: parte il centraggio.
    var offset = viewport - cardHeight + 418f
    offset -= spec.calculateScrollDistance(offset, cardHeight, viewport)

    // Convergenza dell'animazione al centro.
    assertDistance(0f, spec.calculateScrollDistance(offset, cardHeight, viewport))

    // Card vicina della STESSA riga: il bring-into-view arriva con la scala ancora 1.0,
    // quindi il suo rect parte 3% più in basso rispetto a quello della card a fuoco.
    assertDistance(
      0f,
      spec.calculateScrollDistance(offset + focusScaleHalfGrowth, cardHeight, viewport),
      "un LEFT/RIGHT non deve spostare la riga in verticale"
    )

    // Stessa riga con la scala del focus già applicata (rientro dal Dettaglio o refocus).
    assertDistance(0f, spec.calculateScrollDistance(offset, cardHeight, viewport))
  }

  @Test
  fun ilCentraggioSiChiudeEntroLaTolleranza() {
    val spec = TvFocusGridBringIntoViewSpec()

    // Avvia il centraggio.
    spec.calculateScrollDistance(viewport - cardHeight + 418f, cardHeight, viewport)
    // Quasi centrata: la richiesta si considera soddisfatta e il centraggio termina.
    assertDistance(0f, spec.calculateScrollDistance(centeredOffset() + 0.5f, cardHeight, viewport))
    // Da qui in poi vale la sola visibilità: nessuno scroll.
    assertDistance(0f, spec.calculateScrollDistance(centeredOffset() + 0.5f, cardHeight, viewport))
  }

  @Test
  fun unaRigaPiuAltaDelViewportRestaAncorataInAlto() {
    val spec = TvFocusGridBringIntoViewSpec()

    assertDistance(100f, spec.calculateScrollDistance(100f, viewport + 60f, viewport))
    assertDistance(-50f, spec.calculateScrollDistance(-50f, viewport + 60f, viewport))
  }
}

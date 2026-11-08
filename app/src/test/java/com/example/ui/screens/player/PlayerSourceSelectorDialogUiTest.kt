package com.example.ui.screens.player

import android.content.Context
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ApplicationProvider
import com.example.R
import com.example.data.streaming.StreamSource
import com.example.ui.components.DebridSourceCardPresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * Selettore sorgenti interno al player: verifica da UI (Robolectric + Compose).
 *
 * Contratti verificati:
 *  - la finestra mostra la STESSA card dettagliata della lista pre-playback
 *    (metadati e mapper condivisi: `DebridSourceCard` +
 *    `DebridSourceCardPresentation`);
 *  - i metadati assenti non producono badge o valori inventati;
 *  - la sorgente in riproduzione è evidenziata una sola volta, anche quando più
 *    sorgenti hanno lo stesso addon e la stessa risoluzione;
 *  - l'elenco è utilizzabile col TOUCH (mobile): il click su una card seleziona
 *    quella sorgente;
 *  - l'elenco è navigabile col D-PAD (Android TV): le card sono focalizzabili e
 *    su/giù spostano il focus fra le voci;
 *  - con molte sorgenti la lista scorre e le card non vengono tagliate.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w640dp-h900dp-tvdpi")
class PlayerSourceSelectorDialogUiTest {

  @get:Rule
  val rule = createComposeRule()

  /** Sorgente con metadati completi: 4K, WEB-DL, HEVC, italiano/inglese, dimensione reale. */
  private val richSource = StreamSource(
    streamUrl = null,
    quality = "4K",
    declaredQuality = "4K",
    serverName = "IlCorsaroViola 🧲 2160p",
    isItalian = true,
    addonName = "IlCorsaroViola",
    instantTag = "2160p TB Instant",
    releaseTitle = "Dune.Part.Two.2024.2160p.WEB-DL.DDP5.1.ITA-ENG.mkv",
    details = "🔊 DDP5.1 | 👤 382 | 💾 23.80 GB",
    infoHash = "HASH_PLAYING",
    fileIdx = 0,
    codec = "HEVC",
    releaseType = "WEB-DL",
    addonInstallId = "https://icv.stremio-italia.eu/"
  )

  /** Stesso addon e stessa risoluzione della sorgente in riproduzione, torrent diverso. */
  private val sameAddonSource = StreamSource(
    streamUrl = null,
    quality = "1080p",
    declaredQuality = "1080p",
    serverName = "IlCorsaroViola 🧲 1080p",
    isItalian = true,
    addonName = "IlCorsaroViola",
    instantTag = "1080p TB Instant",
    releaseTitle = "Dune.Part.Two.2024.1080p.WEB-DL.ITA-ENG.mkv",
    details = "🔊 DDP5.1 | 💾 1.98 GB",
    infoHash = "HASH_OTHER",
    fileIdx = 0,
    codec = "H264",
    releaseType = "WEB-DL",
    addonInstallId = "https://icv.stremio-italia.eu/"
  )

  /** Sorgente senza metadati tecnici: solo provider e risoluzione dichiarata. */
  private val bareSource = StreamSource(
    streamUrl = null,
    quality = "720p",
    declaredQuality = "720p",
    serverName = "Server Semplice"
  )

  private fun currentBadge(): String =
    ApplicationProvider.getApplicationContext<Context>()
      .getString(R.string.player_source_selector_current)

  @Test
  fun rowsShowTheSameDetailedPresentationAsThePrePlaybackList() {
    val entries = PlayerSourceSelector.buildEntries(listOf(richSource, sameAddonSource), richSource)
    rule.setContent {
      PlayerSourceSelectorDialog(entries = entries, onSelect = {}, onDismiss = {})
    }
    rule.waitForIdle()

    // Gli stessi valori calcolati dal mapper condiviso con la card pre-playback.
    rule.onNodeWithText(DebridSourceCardPresentation.fileName(richSource)).assertExists()
    rule.onNodeWithText(DebridSourceCardPresentation.sizeLabel(richSource)!!).assertExists()
    rule.onNodeWithText(DebridSourceCardPresentation.resolutionLabel(richSource)).assertExists()
    // Entrambe le sorgenti dichiarano DDP5.1: il formato audio compare in ognuna.
    rule.onAllNodesWithText(DebridSourceCardPresentation.audioFormatLabel(richSource)!!)[0]
      .assertExists()
    // "WEB-DL" compare due volte nella card: riga tecnica e logo della fila badge.
    rule.onAllNodesWithText(DebridSourceCardPresentation.releaseTypeLabel(richSource)!!)[0]
      .assertExists()
    // Identità dell'addon e peer/cache (stessi dati della card pre-playback).
    rule.onAllNodesWithText("IlCorsaroViola").assertCountEquals(2)
    rule.onNodeWithText(DebridSourceCardPresentation.peersLabel(richSource)!!).assertExists()

    // La seconda sorgente, stesso addon ma risoluzione diversa, è presente con la
    // propria riga tecnica: la finestra non mostra solo "provider · risoluzione".
    rule.onNodeWithText(DebridSourceCardPresentation.fileName(sameAddonSource)).assertExists()
    rule.onNodeWithText("1080p").assertExists()
  }

  @Test
  fun theCurrentSourceIsMarkedOnceEvenWithSameAddonAndResolution() {
    // Due sorgenti indistinguibili per provider e risoluzione: solo il torrent in
    // riproduzione deve portare il badge "In riproduzione".
    val entries = PlayerSourceSelector.buildEntries(
      listOf(richSource, richSource.copy(infoHash = "HASH_SIBLING"), sameAddonSource),
      richSource
    )
    rule.setContent {
      PlayerSourceSelectorDialog(entries = entries, onSelect = {}, onDismiss = {})
    }
    rule.waitForIdle()

    rule.onAllNodesWithText(currentBadge()).assertCountEquals(1)
  }

  @Test
  fun missingMetadataDoesNotProduceInventedValuesOrEmptyBadges() {
    val entries = PlayerSourceSelector.buildEntries(listOf(bareSource), bareSource)
    rule.setContent {
      PlayerSourceSelectorDialog(entries = entries, onSelect = {}, onDismiss = {})
    }
    rule.waitForIdle()

    // Nessun badge tecnico inventato: la sorgente non dichiara HDR, HEVC, 4K o
    // una dimensione, quindi i relativi marchi non devono comparire.
    assertTrue(DebridSourceCardPresentation.hdrBadges(bareSource).isEmpty())
    assertFalse(DebridSourceCardPresentation.isUltraHd(bareSource))
    assertFalse(DebridSourceCardPresentation.isHevc(bareSource))
    assertEquals(null, DebridSourceCardPresentation.sizeLabel(bareSource))
    assertEquals(null, DebridSourceCardPresentation.peersLabel(bareSource))

    rule.onAllNodesWithText("HDR10").assertCountEquals(0)
    rule.onAllNodesWithText("HDR10+").assertCountEquals(0)
    rule.onAllNodesWithText("HEVC").assertCountEquals(0)
    rule.onAllNodesWithText("ULTRA HD").assertCountEquals(0)
    rule.onAllNodesWithText("0 B").assertCountEquals(0)
    // La sorgente è quella in riproduzione: il badge compare esattamente una volta.
    rule.onAllNodesWithText(currentBadge()).assertCountEquals(1)
  }

  @Test
  fun clickingACardSelectsThatSource() {
    var selected: StreamSource? = null
    val entries = PlayerSourceSelector.buildEntries(listOf(richSource, sameAddonSource), richSource)
    rule.setContent {
      PlayerSourceSelectorDialog(
        entries = entries,
        onSelect = { selected = it },
        onDismiss = {}
      )
    }
    rule.waitForIdle()

    rule.onNodeWithTag(playerSourceCardTestTag(1)).performClick()
    rule.waitForIdle()

    assertNotNull("Il click su una card deve selezionare la sorgente", selected)
    assertEquals(sameAddonSource, selected)
  }

  @Test
  fun theCurrentSourceReceivesTheInitialFocus() {
    val entries = PlayerSourceSelector.buildEntries(
      listOf(richSource, sameAddonSource),
      sameAddonSource
    )
    rule.setContent {
      PlayerSourceSelectorDialog(entries = entries, onSelect = {}, onDismiss = {})
    }
    rule.waitForIdle()

    rule.onNodeWithTag(playerSourceCardTestTag(1)).assertIsFocused()
    rule.onNodeWithTag(playerSourceCardTestTag(0)).assertIsNotFocused()
  }

  @Test
  fun cardsAreFocusableAndDpadMovesTheFocus() {
    val entries = PlayerSourceSelector.buildEntries(listOf(richSource, sameAddonSource), richSource)
    rule.setContent {
      PlayerSourceSelectorDialog(entries = entries, onSelect = {}, onDismiss = {})
    }
    rule.waitForIdle()

    // Le card devono essere focalizzabili (D-pad su Android TV).
    rule.onNodeWithTag(playerSourceCardTestTag(0)).requestFocus()
    rule.waitForIdle()
    rule.onNodeWithTag(playerSourceCardTestTag(0)).assertIsFocused()
    rule.onNodeWithTag(playerSourceCardTestTag(1)).assertIsNotFocused()

    // D-pad: giù sposta il focus sulla card successiva.
    rule.onNodeWithTag(playerSourceCardTestTag(0))
      .performKeyInput { pressKey(Key.DirectionDown) }
    rule.waitForIdle()
    rule.onNodeWithTag(playerSourceCardTestTag(1)).assertIsFocused()

    // D-pad: su risale alla card precedente.
    rule.onNodeWithTag(playerSourceCardTestTag(1))
      .performKeyInput { pressKey(Key.DirectionUp) }
    rule.waitForIdle()
    rule.onNodeWithTag(playerSourceCardTestTag(0)).assertIsFocused()
  }

  @Test
  fun pressingOkOnACardSelectsThatSource() {
    var selected: StreamSource? = null
    val entries = PlayerSourceSelector.buildEntries(listOf(richSource, sameAddonSource), richSource)
    rule.setContent {
      PlayerSourceSelectorDialog(
        entries = entries,
        onSelect = { selected = it },
        onDismiss = {}
      )
    }
    rule.waitForIdle()

    // Conferma da telecomando (OK/DPAD_CENTER) sulla card focalizzata.
    rule.onNodeWithTag(playerSourceCardTestTag(1)).requestFocus()
    rule.waitForIdle()
    rule.onNodeWithTag(playerSourceCardTestTag(1))
      .performKeyInput { pressKey(Key.DirectionCenter) }
    rule.waitForIdle()

    assertNotNull("OK su una card deve selezionare la sorgente", selected)
    assertEquals(sameAddonSource, selected)
  }

  @Test
  fun backDismissesTheWindowWithoutSelectingASource() {
    var dismissed = false
    var selected: StreamSource? = null
    val entries = PlayerSourceSelector.buildEntries(listOf(richSource, sameAddonSource), richSource)
    rule.setContent {
      PlayerSourceSelectorDialog(
        entries = entries,
        onSelect = { selected = it },
        onDismiss = { dismissed = true }
      )
    }
    rule.waitForIdle()

    // BACK è gestito dalla finestra modale (Dialog): chiude senza avviare alcuna
    // sorgente. Comportamento invariato rispetto al selettore precedente.
    rule.runOnUiThread { ShadowDialog.getLatestDialog().onBackPressed() }
    rule.waitForIdle()

    assertTrue("BACK deve chiudere la finestra", dismissed)
    assertEquals(null, selected)
  }

  @Test
  fun manySourcesAreScrollableAndTheCardsAreNotClipped() {
    // 40 sorgenti dettagliate: la lista deve scorrere fino all'ultima card
    // (altezza disponibile della finestra, nessun taglio).
    val many = (0 until 40).map { index ->
      StreamSource(
        streamUrl = null,
        quality = "1080p",
        declaredQuality = "1080p",
        serverName = "IlCorsaroViola 🧲 1080p",
        addonName = "IlCorsaroViola",
        instantTag = "1080p TB Instant",
        releaseTitle = "Dune.Part.Two.2024.1080p.WEB-DL.ITA-ENG.$index.mkv",
        details = "🔊 DDP5.1 | 👤 ${100 + index} | 💾 1.98 GB",
        infoHash = "HASH_$index",
        fileIdx = 0,
        codec = "H264",
        releaseType = "WEB-DL"
      )
    }
    val entries = PlayerSourceSelector.buildEntries(many, many.first())
    rule.setContent {
      PlayerSourceSelectorDialog(entries = entries, onSelect = {}, onDismiss = {})
    }
    rule.waitForIdle()

    val lastIndex = many.lastIndex
    rule.onNodeWithTag(PLAYER_SOURCE_LIST_TEST_TAG)
      .performScrollToNode(hasTestTag(playerSourceCardTestTag(lastIndex)))
    rule.waitForIdle()

    rule.onNodeWithTag(playerSourceCardTestTag(lastIndex)).assertExists()
    rule.onNodeWithTag(playerSourceCardTestTag(lastIndex)).assertIsDisplayed()

    // D-pad anche fuori dal viewport: UP dall'ultima card raggiunge la precedente
    // (la lista continua a scorrere e mantenere il focus).
    rule.onNodeWithTag(playerSourceCardTestTag(lastIndex)).requestFocus()
    rule.waitForIdle()
    rule.onNodeWithTag(playerSourceCardTestTag(lastIndex))
      .performKeyInput { pressKey(Key.DirectionUp) }
    rule.waitForIdle()
    rule.onNodeWithTag(playerSourceCardTestTag(lastIndex - 1)).assertIsFocused()
  }
}

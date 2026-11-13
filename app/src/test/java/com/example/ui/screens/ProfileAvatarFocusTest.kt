package com.example.ui.screens

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import com.example.data.profiles.ProfileAvatar
import com.example.data.profiles.ProfileEmojiCategory
import com.example.data.profiles.ProfileRules
import com.example.data.profiles.UserProfile
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Navigazione D-pad del selettore avatar ([ProfileAvatarScreen]).
 *
 * Difetto coperto (crash osservato in emulatore): ogni chip categoria impostava
 * `focusProperties { down = requesterDellaPrimaEmojiDellaPropriaCategoria }`. Quando la
 * categoria del chip non era quella selezionata la sua riga emoji non esiste nella
 * composizione, quindi il requester era orfano e il DOWN terminava con
 * `IllegalStateException: FocusRequester is not initialized`. Lo stesso poteva accadere
 * anche sulla categoria selezionata dopo uno scroll orizzontale, quando la LazyRow
 * smonta le prime emoji.
 *
 * Il test preme i tasti come il telecomando: prima del fix il DOWN da un chip non
 * selezionato lancia l'eccezione durante il focus search.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h720dp-tvdpi")
class ProfileAvatarFocusTest {

  @get:Rule
  val rule = createComposeRule()

  private val profile = UserProfile(
    id = "profile-1",
    name = "Fabius",
    avatar = ProfileAvatar.VIOLET,
    avatarEmoji = "👽"
  )

  private fun render() {
    rule.setContent {
      ProfileAvatarScreen(
        profile = profile,
        onSave = { _, _ -> },
        onBack = {}
      )
    }
    rule.waitForIdle()
  }

  private fun focusableWithText(text: String): SemanticsMatcher =
    isFocusable() and hasAnyDescendant(hasText(text))

  private fun focusedTexts(): List<String> =
    rule.onAllNodes(isFocused(), useUnmergedTree = true)
      .fetchSemanticsNodes()
      .flatMap { node -> node.textsInSubtree() }

  /** Testi del nodo e dei suoi discendenti: il nodo focusabile non porta il testo in config. */
  private fun androidx.compose.ui.semantics.SemanticsNode.textsInSubtree(): List<String> =
    config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty() +
      children.flatMap { it.textsInSubtree() }

  private fun waitUntilFocused(matcher: SemanticsMatcher, timeoutMillis: Long = 5_000) {
    rule.waitUntil(timeoutMillis) {
      try {
        rule.onNode(matcher).assertIsFocused()
        true
      } catch (_: AssertionError) {
        false
      } catch (_: IllegalStateException) {
        false
      }
    }
  }

  @OptIn(ExperimentalTestApi::class)
  private fun pressDpad(key: Key) {
    rule.onNode(isFocused()).performKeyInput { pressKey(key) }
    rule.waitForIdle()
  }

  @Test
  fun downDaUnaCategoriaNonSelezionataEntraNellaRigaEmojiSenzaCrash() {
    render()

    // Focus iniziale sull'emoji del profilo (👽, categoria Sci-Fi).
    waitUntilFocused(focusableWithText("👽"))

    pressDpad(Key.DirectionUp)
    waitUntilFocused(focusableWithText("Sci-Fi & Anime"))

    // Il chip della categoria NON selezionata è il caso che faceva crashare il focus search.
    pressDpad(Key.DirectionLeft)
    waitUntilFocused(focusableWithText("Commedia") or focusableWithText("Comedy"))

    // DOWN deve atterrare nella riga emoji visibile senza eccezioni.
    pressDpad(Key.DirectionDown)
    rule.waitForIdle()

    val focused = focusedTexts()
    assertTrue(
      "DOWN dal chip non selezionato non ha portato il focus nella riga emoji: $focused",
      focused.any { it == "Aa" } ||
        focused.any { it in ProfileRules.emojisFor(ProfileEmojiCategory.SCIFI) }
    )
  }

  @Test
  fun downDallaCategoriaVisibileFunzionaAncheDopoLoScrollDellaRiga() {
    render()
    waitUntilFocused(focusableWithText("👽"))

    // Scorre la riga fino in fondo: le prime emoji (compresa "Aa") vengono smontate.
    repeat(ProfileRules.emojisFor(ProfileEmojiCategory.SCIFI).size) {
      pressDpad(Key.DirectionRight)
    }
    rule.onAllNodes(focusableWithText("Aa")).assertCountEquals(0)

    pressDpad(Key.DirectionUp)
    val chipLabels = listOf(
      "Cinema",
      "Movies & TV",
      "Thriller",
      "Commedia",
      "Comedy",
      "Sci-Fi & Anime"
    )
    assertTrue(
      "UP dalla riga emoji scrollata non ha portato il focus sui chip categoria: ${focusedTexts()}",
      focusedTexts().any { it in chipLabels }
    )

    // DOWN deve atterrare su un'emoji ancora montata, non su un requester orfano.
    pressDpad(Key.DirectionDown)
    rule.waitForIdle()

    val focused = focusedTexts()
    assertTrue(
      "DOWN dopo lo scroll non ha portato il focus su un'emoji: $focused",
      focused.any { it in ProfileRules.emojisFor(ProfileEmojiCategory.SCIFI) }
    )
  }
}

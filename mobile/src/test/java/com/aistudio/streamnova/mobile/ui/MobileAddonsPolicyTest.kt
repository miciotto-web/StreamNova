package com.aistudio.streamnova.mobile.ui

import com.example.data.prefs.StreamingEngineMode
import com.example.data.stremio.InstalledAddon
import com.example.data.stremio.StremioCatalogDefinition
import com.example.data.stremio.StremioManifest
import com.example.data.stremio.provider.BindingConfidence
import com.example.data.stremio.provider.BindingSource
import com.example.data.stremio.provider.CatalogKey
import com.example.data.stremio.provider.ProviderBinding
import com.example.data.stremio.provider.ProviderBindingProposalStatus
import java.io.IOException
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test della policy pura della schermata Add-on mobile.
 *
 * Le eccezioni usate sono quelle REALI del repository condiviso (messaggi di
 * `StremioAddonRepository.fetchManifest`/`normalizeUrl`, verificati anche dai
 * test core): la classificazione non deve dipendere dal testo grezzo mostrato
 * all'utente, che viene localizzato in 5 lingue dalla UI.
 */
class MobileAddonsPolicyTest {

  // ── Classificazione errori ────────────────────────────────────────────────────

  @Test
  fun lUrlNonValidoEUnErroreDiInput() {
    assertEquals(
      MobileAddonError.INVALID_URL,
      classifyAddonError(IllegalArgumentException("Inserisci l'URL dell'addon"))
    )
    assertEquals(
      MobileAddonError.INVALID_URL,
      classifyAddonError(IllegalArgumentException("URL non valido: foo"))
    )
  }

  @Test
  fun unHttpNonRiuscitoEUnErroreHttp() {
    assertEquals(
      MobileAddonError.HTTP,
      classifyAddonError(
        IOException("Manifest non trovato (HTTP 404) per http://127.0.0.1/manifest.json")
      )
    )
    assertEquals(
      MobileAddonError.HTTP,
      classifyAddonError(
        IOException("Manifest non trovato (HTTP 500) per http://127.0.0.1/manifest.json")
      )
    )
  }

  @Test
  fun unManifestMalformatoOIncompletoEUnErroreManifest() {
    val messages = listOf(
      "Manifest vuoto per http://127.0.0.1/manifest.json",
      "Manifest JSON non valido per http://127.0.0.1/manifest.json: Unexpected token",
      "Manifest privo di campo 'id'",
      "Manifest privo di campo 'name'"
    )
    messages.forEach { message ->
      assertEquals(
        "Atteso MANIFEST_INVALID per: $message",
        MobileAddonError.MANIFEST_INVALID,
        classifyAddonError(IOException(message))
      )
    }
  }

  @Test
  fun unTimeoutDiReteEUnErroreTimeout() {
    assertEquals(
      MobileAddonError.TIMEOUT,
      classifyAddonError(SocketTimeoutException("connect timed out"))
    )
  }

  @Test
  fun unErroreDiConnessioneGenericoEUnErroreDiRete() {
    assertEquals(
      MobileAddonError.NETWORK,
      classifyAddonError(IOException("Failed to connect to /127.0.0.1:8080"))
    )
    assertEquals(
      MobileAddonError.NETWORK,
      classifyAddonError(IOException("Unable to resolve host \"addon.example\""))
    )
  }

  @Test
  fun unErroreSconosciutoNonFingeUnCasoNoto() {
    assertEquals(
      MobileAddonError.UNKNOWN,
      classifyAddonError(IllegalStateException("boom"))
    )
  }

  // ── Gate modalità motore ──────────────────────────────────────────────────────

  @Test
  fun laVoceAddonEsisteSoloInModalitaDebrid() {
    assertTrue(addonsEntryAvailable(StreamingEngineMode.DEBRID_TORBOX))
    assertFalse(addonsEntryAvailable(StreamingEngineMode.HTTP_WEB))
  }

  // ── Etichette card ────────────────────────────────────────────────────────────

  @Test
  fun laVersioneMostrataESoloQuellaDichiarataDalManifest() {
    assertEquals("v1.2.3", addonVersionLabel("1.2.3"))
    assertEquals("v2.0", addonVersionLabel("  2.0 "))
    assertNull(addonVersionLabel(null))
    assertNull(addonVersionLabel("   "))
  }

  @Test
  fun iTokenDiagnosticiRiflettonoGliExtraDichiarati() {
    val full = StremioCatalogDefinition(
      type = "movie",
      id = "top",
      extra = listOf("search", "genre", "skip")
    )
    assertEquals(listOf("search", "genre", "skip"), catalogCapabilityTokens(full))

    val searchOnly = StremioCatalogDefinition(
      type = "movie",
      id = "searchable",
      extra = listOf("search")
    )
    assertEquals(listOf("search"), catalogCapabilityTokens(searchOnly))

    val plain = StremioCatalogDefinition(type = "movie", id = "plain")
    assertTrue(catalogCapabilityTokens(plain).isEmpty())
  }

  // ── Proposte binding (pipeline TV) ────────────────────────────────────────────

  private fun disneyAddon() = InstalledAddon(
    baseUrl = "http://addon.example",
    manifest = StremioManifest(
      id = "addon.disney",
      name = "The Walt Disney Company",
      catalogs = listOf(
        StremioCatalogDefinition(
          type = "movie",
          id = "streaming_disney_movies",
          name = "Disney+ Movies"
        )
      )
    )
  )

  private val disneyKey = CatalogKey("addon.disney", "movie", "streaming_disney_movies")

  @Test
  fun leProposteUsanoLEuristicaMaNonCreanobinding() {
    val proposals = addonBindingProposals(disneyAddon()) { null }

    assertTrue("Il proposer deve suggerire Disney+", proposals.any { it.providerId == "disney" })
    assertTrue(
      "Senza binding dichiarati ogni proposta resta PROPOSED",
      proposals.all { it.status == ProviderBindingProposalStatus.PROPOSED }
    )
  }

  @Test
  fun unBindingUtenteEsistenteRendeLaPropostaAssociata() {
    val userBinding = ProviderBinding(
      key = disneyKey,
      providerId = "disney",
      source = BindingSource.USER,
      confidence = BindingConfidence.CONFIRMED
    )

    val proposals = addonBindingProposals(disneyAddon()) { key ->
      if (key.stableId == userBinding.key.stableId) userBinding else null
    }

    val disney = proposals.first { it.providerId == "disney" }
    assertEquals(ProviderBindingProposalStatus.USER, disney.status)
    assertTrue("Un binding utente non è ri-confermabile", !disney.isConfirmable)
    assertTrue("Un binding utente è rimovibile", disney.isRemovable)
  }
}

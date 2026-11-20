package com.example.data.premiumize

import com.example.data.prefs.AppSettingsRepository
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Verifica del repository Premiumize (servizio Debrid accostato a TorBox):
 *  - `cache/check` con magnet derivati dagli infoHash (esito booleano per item);
 *  - sblocco cached-only (transfer/create → item/details → stream_link) con
 *    selezione del file dell'EPISODIO richiesto in un torrent di stagione;
 *  - cache dei link distinta per episodio;
 *  - verifica account con chiave valida/non valida.
 */
class PremiumizeRepositoryTest {

  private companion object {
    const val CACHED_HASH = "0123456789abcdef0123456789abcdef01234567"
    const val OTHER_HASH = "fedcba9876543210fedcba9876543210fedcba98"
    const val TRANSFER_ID = "transfer-77"
    const val EP2_URL = "https://stream.premiumize.me/dl/Show.S01E02.1080p.ITA-ENG.mkv"
  }

  private class FakePremiumizeApi(
    private val files: List<PremiumizeItemFileDto>
  ) : PremiumizeApi {
    val magnetsChecked = mutableListOf<String>()
    val magnetsCreated = mutableListOf<String>()
    var invalidKey = false
    /** Verifica che l'interceptor di autenticazione abbia appeso `apikey`. */
    var apiKeysSeen = mutableListOf<String>()

    private fun ensureApikey(apikey: String?) {
      apiKeysSeen += apikey ?: ""
    }

    override suspend fun accountInfo(): PremiumizeAccountInfoDto {
      ensureApikey(null)
      return if (invalidKey) {
        PremiumizeAccountInfoDto(status = "error", message = "Invalid API key")
      } else {
        PremiumizeAccountInfoDto(status = "success", customerId = "98765", premiumUntil = 4_000_000_000L)
      }
    }

    override suspend fun cacheCheck(items: List<String>): PremiumizeCacheCheckDto {
      ensureApikey(null)
      magnetsChecked += items
      return PremiumizeCacheCheckDto(
        status = "success",
        response = items.map { it.contains(CACHED_HASH) }
      )
    }

    override suspend fun transferCreate(src: String): PremiumizeTransferCreateDto {
      ensureApikey(null)
      magnetsCreated += src
      return PremiumizeTransferCreateDto(status = "success", id = TRANSFER_ID)
    }

    override suspend fun itemDetails(id: String): PremiumizeItemDetailsDto {
      ensureApikey(null)
      return PremiumizeItemDetailsDto(status = "success", id = id, name = "Show.S01.1080p", files = files)
    }
  }

  private fun file(name: String, size: Long) = PremiumizeItemFileDto(
    id = name,
    name = name,
    size = size,
    mime = "video/x-matroska",
    streamLink = "https://stream.premiumize.me/dl/${name}"
  )

  private fun seasonPack(): List<PremiumizeItemFileDto> = listOf(
    file("Show.S01E01.1080p.ITA-ENG.mkv", 4_000_000_000L),
    file("Show.S01E02.1080p.ITA-ENG.mkv", 3_000_000_000L),
    file("Show.S01E03.1080p.ITA-ENG.mkv", 2_000_000_000L)
  )

  private var api: FakePremiumizeApi? = null

  private fun installFake(files: List<PremiumizeItemFileDto>): FakePremiumizeApi {
    val fake = FakePremiumizeApi(files)
    PremiumizeApiClient.setApiForTesting(fake)
    api = fake
    return fake
  }

  @Before
  fun setUp() {
    AppSettingsRepository.setPremiumizeInstantDebridEnabled(true)
    AppSettingsRepository.setPremiumizeApiKey("fake-premiumize-key")
    PremiumizeRepository.clearCache()
  }

  @After
  fun tearDown() {
    PremiumizeApiClient.setApiForTesting(null)
    PremiumizeRepository.clearCache()
    AppSettingsRepository.setPremiumizeApiKey(null)
  }

  @Test
  fun `checkCached converte gli hash in magnet e marca solo gli hash cached`() = runTest {
    installFake(seasonPack())

    val entries = PremiumizeRepository.checkCached(listOf(CACHED_HASH, OTHER_HASH))

    assertEquals(
      "Gli hash vengono interrogati come magnet (formato Premiumize)",
      listOf("magnet:?xt=urn:btih:$CACHED_HASH", "magnet:?xt=urn:btih:$OTHER_HASH"),
      api!!.magnetsChecked
    )
    assertEquals(true, entries[CACHED_HASH]?.cached)
    assertNull("Hash non in cache deve restare assente (mai dichiarato non cached)", entries[OTHER_HASH])
  }

  @Test
  fun `sblocco episodio seleziona il file SxxExx e restituisce lo stream_link`() = runTest {
    installFake(seasonPack())

    val url = PremiumizeRepository.resolveInfoHash(CACHED_HASH, fileIdx = null, season = 1, episode = 2)

    assertEquals(EP2_URL, url)
    // Il transfer parte SOLO per hash confermati in cache.
    assertEquals(listOf("magnet:?xt=urn:btih:$CACHED_HASH"), api!!.magnetsCreated)
  }

  @Test
  fun `episodi diversi dello stesso torrent non condividono il link`() = runTest {
    installFake(seasonPack())

    val ep1 = PremiumizeRepository.resolveInfoHash(CACHED_HASH, fileIdx = null, season = 1, episode = 1)
    val ep2 = PremiumizeRepository.resolveInfoHash(CACHED_HASH, fileIdx = null, season = 1, episode = 2)

    assertEquals("https://stream.premiumize.me/dl/Show.S01E01.1080p.ITA-ENG.mkv", ep1)
    assertEquals(EP2_URL, ep2)
    assertNotEquals(ep1, ep2)
  }

  @Test
  fun `il link gia risolto viene riusato solo per lo stesso episodio`() = runTest {
    installFake(seasonPack())

    PremiumizeRepository.resolveInfoHash(CACHED_HASH, fileIdx = null, season = 1, episode = 2)
    PremiumizeRepository.resolveInfoHash(CACHED_HASH, fileIdx = null, season = 1, episode = 2)

    assertEquals("Una sola sequenza di sblocco per lo stesso episodio", 1, api!!.magnetsCreated.size)
  }

  @Test
  fun `hash non in cache non crea alcun transfer`() = runTest {
    installFake(seasonPack())

    assertNull(PremiumizeRepository.resolveInfoHash(OTHER_HASH, fileIdx = null, season = 1, episode = 1))
    assertTrue(api!!.magnetsCreated.isEmpty())
  }

  @Test
  fun `chiave di cache dei link distinta per episodio e stabile per film`() {
    assertNotEquals(
      PremiumizeRepository.linkCacheKey(CACHED_HASH, 1, 1),
      PremiumizeRepository.linkCacheKey(CACHED_HASH, 1, 2)
    )
    assertEquals(CACHED_HASH, PremiumizeRepository.linkCacheKey(CACHED_HASH.uppercase(), null, null))
  }

  @Test
  fun `chiave non valida produce AccountCheck Invalid`() = runTest {
    installFake(seasonPack()).invalidKey = true

    val check = PremiumizeRepository.verifyAccount()
    assertTrue(check is PremiumizeRepository.AccountCheck.Invalid)
  }

  @Test
  fun `chiave valida produce AccountCheck Valid con customer id`() = runTest {
    installFake(seasonPack())

    val check = PremiumizeRepository.verifyAccount()
    assertTrue(check is PremiumizeRepository.AccountCheck.Valid)
    assertEquals("98765", (check as PremiumizeRepository.AccountCheck.Valid).account.customerId)
  }

  @Test
  fun `servizio non configurato non interroga l'API`() = runTest {
    installFake(seasonPack())
    AppSettingsRepository.setPremiumizeApiKey(null)

    assertNull(PremiumizeRepository.resolveInfoHash(CACHED_HASH, fileIdx = null, season = 1, episode = 1))
    assertTrue(api!!.magnetsCreated.isEmpty())
  }
}

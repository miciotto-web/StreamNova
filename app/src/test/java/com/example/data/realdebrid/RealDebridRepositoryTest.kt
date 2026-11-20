package com.example.data.realdebrid

import com.example.data.prefs.AppSettingsRepository
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

/**
 * Verifica del repository Real-Debrid (servizio Debrid accostato a TorBox):
 *  - parse di `instantAvailability` (rd non vuoto → cached);
 *  - sblocco cached-only (addMagnet → selectFiles → info → unrestrict/link)
 *    con selezione del file dell'EPISODIO richiesto in un torrent di stagione;
 *  - cache dei link distinta per episodio (mai il link di S01E01 per S01E02);
 *  - verifica account con chiave non valida (HTTP 401).
 */
class RealDebridRepositoryTest {

  private companion object {
    const val CACHED_HASH = "0123456789abcdef0123456789abcdef01234567"
    const val OTHER_HASH = "fedcba9876543210fedcba9876543210fedcba98"
    const val TORRENT_ID = "77"
    const val CDN_URL = "https://01.real-debrid.com/d/ABCDEF123456"
  }

  /** File del torrent di stagione nel formato `instantAvailability`/`torrents/info`. */
  private class FakeRealDebridApi(
    private val seasonPack: List<RealDebridFileDto>
  ) : RealDebridApi {
    val selectedFileIds = mutableListOf<String>()
    val restrictedLinks = mutableListOf<String>()
    var invalidKey = false

    override suspend fun user(): Response<RealDebridUserDto> =
      if (invalidKey) {
        Response.error(401, "{}".toResponseBody(null))
      } else {
        Response.success(RealDebridUserDto(username = "tester", premium = 1))
      }

    override suspend fun instantAvailability(hash: String):
      Map<String, RealDebridInstantAvailabilityDto> =
      if (hash.trim().lowercase() == CACHED_HASH) {
        mapOf(CACHED_HASH to RealDebridInstantAvailabilityDto(rd = listOf(RealDebridVariantDto(files = seasonPack))))
      } else {
        emptyMap()
      }

    override suspend fun addMagnet(magnet: String): RealDebridAddMagnetDto =
      RealDebridAddMagnetDto(id = TORRENT_ID, uri = magnet)

    override suspend fun torrentInfo(id: String): RealDebridTorrentInfoDto =
      RealDebridTorrentInfoDto(
        id = id,
        hash = CACHED_HASH,
        name = "Show.S01.1080p",
        files = seasonPack,
        // I link compaiono SOLO dopo la selezione dei file (comportamento reale).
        links = if (selectedFileIds.isEmpty()) emptyList() else listOf("https://real-debrid.com/d/RESTRICTED")
      )

    override suspend fun selectFiles(id: String, fileIds: String): Response<Unit> {
      selectedFileIds += fileIds
      return Response.success(Unit)
    }

    override suspend fun unrestrictLink(link: String): RealDebridUnrestrictDto {
      restrictedLinks += link
      return RealDebridUnrestrictDto(download = CDN_URL)
    }
  }

  private fun file(id: Int, path: String, size: Long) =
    RealDebridFileDto(id = id, path = path, size = size)

  private fun seasonPack(): List<RealDebridFileDto> = listOf(
    file(101, "/Show.S01E01.1080p.ITA-ENG.mkv", 4_000_000_000L),
    file(102, "/Show.S01E02.1080p.ITA-ENG.mkv", 3_000_000_000L),
    file(103, "/Show.S01E03.1080p.ITA-ENG.mkv", 2_000_000_000L)
  )

  private var api: FakeRealDebridApi? = null

  private fun installFake(files: List<RealDebridFileDto>): FakeRealDebridApi {
    val fake = FakeRealDebridApi(files)
    RealDebridApiClient.setApiForTesting(fake)
    api = fake
    return fake
  }

  @Before
  fun setUp() {
    AppSettingsRepository.setRealDebridInstantDebridEnabled(true)
    AppSettingsRepository.setRealDebridApiKey("fake-realdebrid-key")
    RealDebridRepository.clearCache()
  }

  @After
  fun tearDown() {
    RealDebridApiClient.setApiForTesting(null)
    RealDebridRepository.clearCache()
    AppSettingsRepository.setRealDebridApiKey(null)
  }

  @Test
  fun `checkCached marca cached solo gli hash disponibili istantaneamente`() = runTest {
    installFake(seasonPack())

    val entries = RealDebridRepository.checkCached(listOf(CACHED_HASH, OTHER_HASH))

    assertEquals(true, entries[CACHED_HASH]?.cached)
    // Semantica TorBox: l'hash verificato ma NON cached resta presente con
    // cached=false (mai classificato cached, mai inventato).
    assertEquals(false, entries[OTHER_HASH]?.cached)
    // I file della prima variante rd descrivono il torrent.
    assertEquals(3, entries[CACHED_HASH]?.files?.size)
  }

  @Test
  fun `sblocco episodio seleziona il file SxxExx e restituisce il link unrestrict`() = runTest {
    val fake = installFake(seasonPack())

    val url = RealDebridRepository.resolveInfoHash(CACHED_HASH, fileIdx = null, season = 1, episode = 2)

    assertEquals(CDN_URL, url)
    // Il file selezionato è quello dell'episodio richiesto, non il più grande.
    assertEquals(listOf("102"), fake.selectedFileIds)
    assertEquals(listOf("https://real-debrid.com/d/RESTRICTED"), fake.restrictedLinks)
  }

  @Test
  fun `episodi diversi dello stesso torrent non condividono il link`() = runTest {
    val fake = installFake(seasonPack())

    val ep1 = RealDebridRepository.resolveInfoHash(CACHED_HASH, fileIdx = null, season = 1, episode = 1)
    val ep2 = RealDebridRepository.resolveInfoHash(CACHED_HASH, fileIdx = null, season = 1, episode = 2)

    assertEquals(CDN_URL, ep1)
    assertEquals(CDN_URL, ep2)
    assertEquals(listOf("101", "102"), fake.selectedFileIds)
  }

  @Test
  fun `il link gia risolto viene riusato solo per lo stesso episodio`() = runTest {
    val fake = installFake(seasonPack())

    RealDebridRepository.resolveInfoHash(CACHED_HASH, fileIdx = null, season = 1, episode = 2)
    RealDebridRepository.resolveInfoHash(CACHED_HASH, fileIdx = null, season = 1, episode = 2)

    assertEquals("Una sola sequenza di sblocco per lo stesso episodio", listOf("102"), fake.selectedFileIds)
  }

  @Test
  fun `hash non in cache non produce alcun sblocco`() = runTest {
    val fake = installFake(seasonPack())

    assertNull(RealDebridRepository.resolveInfoHash(OTHER_HASH, fileIdx = null, season = 1, episode = 1))
    assertTrue(fake.selectedFileIds.isEmpty())
  }

  @Test
  fun `chiave di cache dei link distinta per episodio e stabile per film`() {
    assertNotEquals(
      RealDebridRepository.linkCacheKey(CACHED_HASH, 1, 1),
      RealDebridRepository.linkCacheKey(CACHED_HASH, 1, 2)
    )
    assertEquals(CACHED_HASH, RealDebridRepository.linkCacheKey(CACHED_HASH.uppercase(), null, null))
  }

  @Test
  fun `chiave non valida produce AccountCheck Invalid`() = runTest {
    val fake = installFake(seasonPack())
    fake.invalidKey = true

    val check = RealDebridRepository.verifyAccount()
    assertTrue(check is RealDebridRepository.AccountCheck.Invalid)
    assertTrue((check as RealDebridRepository.AccountCheck.Invalid).message.contains("401"))
  }

  @Test
  fun `servizio non configurato non interroga l'API`() = runTest {
    val fake = installFake(seasonPack())
    AppSettingsRepository.setRealDebridApiKey(null)

    assertNull(RealDebridRepository.resolveInfoHash(CACHED_HASH, fileIdx = null, season = 1, episode = 1))
    assertTrue(fake.selectedFileIds.isEmpty())
  }
}

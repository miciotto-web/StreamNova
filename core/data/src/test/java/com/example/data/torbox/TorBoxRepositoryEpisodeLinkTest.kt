package com.example.data.torbox

import com.example.data.prefs.AppSettingsRepository
import kotlinx.coroutines.test.runTest
import okhttp3.RequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import retrofit2.Response

/**
 * Regressione Fase 2 (Debrid, prossimo episodio): lo sblocco di un infoHash deve
 * riguardare **il file dell'episodio richiesto**.
 *
 * In un torrent di stagione lo stesso `infoHash` contiene tutti gli episodi, quindi:
 *  - non è lecito riusare il link sbloccato per S01E01 quando si chiede S01E02
 *    (la cache dei link era indicizzata solo per hash, TTL 15 minuti);
 *  - non è lecito scegliere il file video più grande (o il primo file) del pack
 *    quando stagione/episodio sono noti.
 */
class TorBoxRepositoryEpisodeLinkTest {

  private companion object {
    const val HASH = "0123456789abcdef0123456789abcdef01234567"
    const val TORRENT_ID = 77
  }

  private class FakeTorBoxApi(
    private val files: List<TorboxTorrentFileDto>
  ) : TorBoxApi {
    /** file_id richiesti a `requestdl`, nell'ordine in cui sono stati chiesti. */
    val requestedFileIds = mutableListOf<Int>()

    override suspend fun getUserMe(): TorBoxUserResponse =
      TorBoxUserResponse(success = true, data = TorBoxUserDto(email = "test@example.org"))

    override suspend fun checkCached(hash: String, format: String): TorBoxCheckCachedResponse {
      val entries = hash.split(',')
        .map { it.trim().lowercase() }
        .filter { it.isNotBlank() }
        .associateWith { normalized ->
          mapOf("id" to TORRENT_ID, "hash" to normalized, "name" to "Show.S01.1080p")
        }
      return TorBoxCheckCachedResponse(success = true, data = entries)
    }

    override suspend fun createTorrent(
      authorization: String,
      magnet: RequestBody,
      addOnlyIfCached: RequestBody,
      allowZip: RequestBody
    ): Response<TorboxEnvelopeDto<TorboxCreateTorrentDataDto>> =
      Response.success(
        TorboxEnvelopeDto(
          success = true,
          data = TorboxCreateTorrentDataDto(torrentId = TORRENT_ID, hash = HASH)
        )
      )

    override suspend fun getTorrent(
      authorization: String,
      id: Int,
      bypassCache: Boolean
    ): Response<TorboxEnvelopeDto<TorboxTorrentDataDto>> =
      Response.success(
        TorboxEnvelopeDto(
          success = true,
          data = TorboxTorrentDataDto(id = id, hash = HASH, name = "Show.S01.1080p", files = files)
        )
      )

    override suspend fun requestDownloadLink(
      authorization: String,
      token: String,
      torrentId: Int,
      fileId: Int?,
      zipLink: Boolean,
      redirect: Boolean,
      appendName: Boolean
    ): Response<TorboxEnvelopeDto<String>> {
      fileId?.let { requestedFileIds += it }
      return Response.success(
        TorboxEnvelopeDto(success = true, data = "https://cdn.torbox.app/dl/file-$fileId")
      )
    }

    override suspend fun getMyList(
      authorization: String,
      hash: String,
      limit: Int,
      offset: Int
    ): Response<TorboxEnvelopeDto<List<TorboxTorrentDataDto>>> =
      Response.success(
        TorboxEnvelopeDto(
          success = true,
          data = listOf(TorboxTorrentDataDto(id = TORRENT_ID, hash = HASH, files = files))
        )
      )
  }

  private fun file(id: Int, name: String, size: Long) =
    TorboxTorrentFileDto(id = id, name = name, shortName = name, size = size)

  private fun seasonPack(): List<TorboxTorrentFileDto> = listOf(
    file(101, "Show.S01E01.1080p.mkv", 4_000_000_000L),
    file(102, "Show.S01E02.1080p.mkv", 3_000_000_000L),
    file(103, "Show.S01E03.1080p.mkv", 2_000_000_000L)
  )

  private fun installFake(files: List<TorboxTorrentFileDto>): FakeTorBoxApi {
    val api = FakeTorBoxApi(files)
    TorBoxApiClient.setApiForTesting(api)
    return api
  }

  @Before
  fun setUp() {
    AppSettingsRepository.setTorBoxInstantDebridEnabled(true)
    AppSettingsRepository.setTorBoxApiKey("fake-torbox-key")
    TorBoxRepository.clearCache()
  }

  @After
  fun tearDown() {
    TorBoxApiClient.setApiForTesting(null)
    TorBoxRepository.clearCache()
    AppSettingsRepository.setTorBoxApiKey(null)
  }

  @Test
  fun `episodi diversi dello stesso torrent non condividono il link`() = runTest {
    val api = installFake(seasonPack())

    val ep1 = TorBoxRepository.resolveInfoHash(HASH, fileIdx = null, season = 1, episode = 1)
    val ep2 = TorBoxRepository.resolveInfoHash(HASH, fileIdx = null, season = 1, episode = 2)

    assertEquals("https://cdn.torbox.app/dl/file-101", ep1)
    assertEquals("https://cdn.torbox.app/dl/file-102", ep2)
    assertNotEquals("Il link di S01E01 non deve essere riusato per S01E02", ep1, ep2)
    assertEquals(listOf(101, 102), api.requestedFileIds)
  }

  @Test
  fun `il link di un episodio gia risolto viene riusato solo per lo stesso episodio`() = runTest {
    val api = installFake(seasonPack())

    val first = TorBoxRepository.resolveInfoHash(HASH, fileIdx = null, season = 1, episode = 2)
    val second = TorBoxRepository.resolveInfoHash(HASH, fileIdx = null, season = 1, episode = 2)

    assertEquals(first, second)
    assertEquals("Una sola richiesta di sblocco per lo stesso episodio", listOf(102), api.requestedFileIds)
  }

  @Test
  fun `torrent con un solo file video usato anche senza pattern SxxExx nel nome`() = runTest {
    installFake(listOf(file(201, "Show.1080p.WEB-DL.mkv", 3_500_000_000L)))

    val url = TorBoxRepository.resolveInfoHash(HASH, fileIdx = null, season = 1, episode = 2)
    assertEquals("https://cdn.torbox.app/dl/file-201", url)
  }

  @Test
  fun `episodio assente dal pack non produce un link di un altro episodio`() = runTest {
    installFake(listOf(file(101, "Show.S01E01.1080p.mkv", 4_000_000_000L)))

    // S01E02 non esiste nel torrent: meglio nessun link (nuova risoluzione) che
    // avviare l'episodio sbagliato.
    assertNull(TorBoxRepository.resolveInfoHash(HASH, fileIdx = null, season = 1, episode = 2))
  }

  @Test
  fun `chiave di cache dei link distinta per episodio e stabile per film`() {
    assertNotEquals(
      TorBoxRepository.linkCacheKey(HASH, 1, 1),
      TorBoxRepository.linkCacheKey(HASH, 1, 2)
    )
    assertNotEquals(
      TorBoxRepository.linkCacheKey(HASH, 1, 2),
      TorBoxRepository.linkCacheKey(HASH, 2, 2)
    )
    // Senza episodio (film o richiesta senza metadati) resta l'hash normalizzato.
    assertEquals(HASH, TorBoxRepository.linkCacheKey(HASH.uppercase(), null, null))
  }
}

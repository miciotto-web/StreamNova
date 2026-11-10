package com.example.data.torbox

import com.example.data.prefs.AppSettingsRepository
import kotlinx.coroutines.test.runTest
import okhttp3.RequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import retrofit2.Response

/**
 * Regressione selezione risoluzione Debrid: la card scelta dall'utente deve
 * riprodurre il file a risoluzione più alta dell'episodio/film richiesto.
 *
 * Regole verificate:
 *  - con stagione/episodio noti il match SxxExx è autorevole e, a parità di
 *    episodio, vince il file più grande (1080p > 720p); il fileIdx dichiarato
 *    dall'addon NON viene usato (l'indice del metadata torrent non coincide con
 *    l'ordinamento file di TorBox: fileIdx=6 per S2E1 puntava al file 2x07);
 *  - senza episodio (film) il fileIdx è il segnale migliore della card scelta
 *    (es. variante 1080p di un torrent multi-file) e viene onorato se valido;
 *  - due card dello stesso torrent con fileIdx diversi NON condividono il link
 *    in cache (la chiave include il fileIdx).
 */
class TorBoxRepositoryFileIdxTest {

  private companion object {
    const val HASH = "0123456789abcdef0123456789abcdef01234567"
    const val TORRENT_ID = 91
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
          mapOf("id" to TORRENT_ID, "hash" to normalized, "name" to "Release.Multi")
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
          data = TorboxTorrentDataDto(id = id, hash = HASH, name = "Release.Multi", files = files)
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

  /** Torrent multi-qualità: il PRIMO file è 720p, il SECONDO è 1080p. */
  private fun multiQualityMovie(): List<TorboxTorrentFileDto> = listOf(
    file(201, "Movie.2024.720p.WEB-DL.mkv", 2_000_000_000L),
    file(202, "Movie.2024.1080p.WEB-DL.mkv", 4_500_000_000L)
  )

  /** Pack stagione con lo stesso episodio in due qualità. */
  private fun multiQualityEpisode(): List<TorboxTorrentFileDto> = listOf(
    file(301, "Show.S01E01.720p.WEB-DL.mkv", 1_500_000_000L),
    file(302, "Show.S01E01.1080p.WEB-DL.mkv", 4_000_000_000L),
    file(303, "Show.S01E02.1080p.WEB-DL.mkv", 3_800_000_000L)
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
  fun `fileIdx dell'addon seleziona il file 1080p anche se il primo file è 720p`() = runTest {
    val api = installFake(multiQualityMovie())

    // La card 1080p dell'addon dichiara fileIdx=1 (secondo file del torrent).
    val url = TorBoxRepository.resolveInfoHash(HASH, fileIdx = 1)

    assertEquals("https://cdn.torbox.app/dl/file-202", url)
    assertEquals(listOf(202), api.requestedFileIds)
  }

  @Test
  fun `card 720p e card 1080p dello stesso torrent non condividono il link`() = runTest {
    val api = installFake(multiQualityMovie())

    val url720 = TorBoxRepository.resolveInfoHash(HASH, fileIdx = 0)
    val url1080 = TorBoxRepository.resolveInfoHash(HASH, fileIdx = 1)

    assertEquals("https://cdn.torbox.app/dl/file-201", url720)
    assertEquals("https://cdn.torbox.app/dl/file-202", url1080)
    assertEquals("Due sblocchi distinti, uno per card", listOf(201, 202), api.requestedFileIds)
  }

  @Test
  fun `fileIdx fuori dal torrent ricade sul selettore (file più grande)`() = runTest {
    installFake(multiQualityMovie())

    val url = TorBoxRepository.resolveInfoHash(HASH, fileIdx = 99)

    assertEquals("https://cdn.torbox.app/dl/file-202", url)
  }

  @Test
  fun `fileIdx che punta a un non-video ricade sul selettore video`() = runTest {
    installFake(
      listOf(
        file(401, "cover.jpg", 500_000L),
        file(402, "Movie.2024.1080p.WEB-DL.mkv", 4_500_000_000L)
      )
    )

    val url = TorBoxRepository.resolveInfoHash(HASH, fileIdx = 0)

    assertEquals("https://cdn.torbox.app/dl/file-402", url)
  }

  @Test
  fun `stesso episodio in due qualità senza fileIdx sblocca il file più grande`() = runTest {
    installFake(multiQualityEpisode())

    val url = TorBoxRepository.resolveInfoHash(HASH, fileIdx = null, season = 1, episode = 1)

    assertEquals("1080p deve vincere sul 720p a parità di episodio", "https://cdn.torbox.app/dl/file-302", url)
  }

  @Test
  fun `con stagione e episodio il match SxxExx vince sul fileIdx dichiarato`() = runTest {
    // Caso osservato sul campo: Torrentio dichiara fileIdx=6 per l'episodio 2x01
    // di un pack, ma l'ordinamento file di TorBox mette 2x07 all'indice 6. Con
    // stagione/episodio noti il match SxxExx deve vincere (mai l'indice).
    val pack = (1..12).map { n ->
      file(300 + n, "Show.S02E%02d.1080p.WEB-DL.mkv".format(n), (5_000_000_000L - n * 100_000_000L))
    }
    installFake(pack)

    val url = TorBoxRepository.resolveInfoHash(HASH, fileIdx = 6, season = 2, episode = 1)

    assertEquals("https://cdn.torbox.app/dl/file-301", url)
  }

  @Test
  fun `con stagione e episodio e più qualità per episodio sblocca il file più grande`() = runTest {
    // Pack con lo stesso episodio in due qualità e fileIdx che punta al 720p:
    // il match SxxExx sceglie il più grande (1080p), ignorando l'indice.
    installFake(
      listOf(
        file(401, "Show.S01E01.720p.WEB-DL.mkv", 1_500_000_000L),
        file(402, "Show.S01E01.1080p.WEB-DL.mkv", 4_000_000_000L)
      )
    )

    val url = TorBoxRepository.resolveInfoHash(HASH, fileIdx = 0, season = 1, episode = 1)

    assertEquals("https://cdn.torbox.app/dl/file-402", url)
  }

  @Test
  fun `chiave di cache distinta per fileIdx e stabile senza`() {
    // Due card (720p/1080p) dello stesso torrent: chiavi diverse.
    assertNotEquals(
      TorBoxRepository.linkCacheKey(HASH, null, null, 0),
      TorBoxRepository.linkCacheKey(HASH, null, null, 1)
    )
    // Stesso fileIdx: stessa chiave (riuso del link).
    assertEquals(
      TorBoxRepository.linkCacheKey(HASH, null, null, 1),
      TorBoxRepository.linkCacheKey(HASH.uppercase(), null, null, 1)
    )
    // Senza fileIdx la chiave resta quella storica (hash puro).
    assertEquals(HASH, TorBoxRepository.linkCacheKey(HASH, null, null))
    // Con episodio la chiave combina episodio e fileIdx.
    assertEquals("$HASH:s1e2:f3", TorBoxRepository.linkCacheKey(HASH, 1, 2, 3))
    assertEquals("$HASH:s1e2", TorBoxRepository.linkCacheKey(HASH, 1, 2))
  }
}

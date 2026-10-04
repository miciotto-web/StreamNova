package com.example.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.api.TmdbMovieDto
import com.example.data.api.TmdbPaginatedResponse
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class RoomCacheIntegrationTest {

  private lateinit var database: StreamNovaDatabase
  private lateinit var responseCacheDao: TmdbResponseCacheDao
  private lateinit var cachedMediaDao: CachedMediaDao

  @Before
  fun setup() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    database = Room.inMemoryDatabaseBuilder(context, StreamNovaDatabase::class.java)
      .allowMainThreadQueries()
      .build()
    responseCacheDao = database.tmdbResponseCacheDao()
    cachedMediaDao = database.cachedMediaDao()
  }

  @After
  fun tearDown() {
    database.close()
  }

  @Test
  fun testTmdbResponseCacheInsertAndRetrieve() = runBlocking {
    val key = "trending_movies_test"
    val json = """{"page":1,"results":[{"id":123,"title":"Test Film","original_title":"Test","overview":"Overview","backdrop_path":"/b.jpg","poster_path":"/p.jpg","release_date":"2024-01-01","vote_average":8.5,"genre_ids":[28]}],"total_pages":1,"total_results":1}"""

    val entry = TmdbResponseCacheEntity(
      endpointKey = key,
      jsonResponse = json,
      cachedAt = System.currentTimeMillis(),
      ttlMillis = 10000L
    )

    responseCacheDao.insertCache(entry)

    val retrieved = responseCacheDao.getCache(key)
    assertNotNull(retrieved)
    assertEquals(key, retrieved?.endpointKey)
    assertFalse(retrieved!!.isExpired())

    val deserialized = MediaCacheMapper.deserializeMovieResponse(retrieved.jsonResponse)
    assertNotNull(deserialized)
    assertEquals(1, deserialized?.results?.size)
    assertEquals(123, deserialized?.results?.first()?.id)
    assertEquals("Test Film", deserialized?.results?.first()?.title)
  }

  @Test
  fun testTmdbResponseCacheExpiration() = runBlocking {
    val key = "expired_key"
    val oldTime = System.currentTimeMillis() - 20000L
    val entry = TmdbResponseCacheEntity(
      endpointKey = key,
      jsonResponse = "{}",
      cachedAt = oldTime,
      ttlMillis = 5000L
    )
    responseCacheDao.insertCache(entry)

    val retrieved = responseCacheDao.getCache(key)
    assertNotNull(retrieved)
    assertTrue(retrieved!!.isExpired())
  }

  @Test
  fun testCachedMediaDaoOperations() = runBlocking {
    val entity = CachedMediaItemEntity(
      id = "test_item_1",
      tmdbId = 999,
      title = "Inception",
      originalTitle = "Inception",
      synopsis = "Un ladro entra nei sogni.",
      videoUrl = "https://example.com/v.mp4",
      resolution = "UHD_4K",
      qualityTags = "4K,HDR",
      backdropUrl = "https://example.com/b.jpg",
      posterUrl = "https://example.com/p.jpg",
      logoUrl = null,
      type = "FILM",
      year = 2010,
      durationMinutes = 148,
      seasonsCount = null,
      rating = 8.8f,
      genres = "Fantascienza,Azione",
      director = "Christopher Nolan",
      cast = "Leonardo DiCaprio,Joseph Gordon-Levitt",
      provider = "netflix",
      isTrending = true,
      isTop10 = true,
      isFavorite = false,
      currentProgressMs = 0L,
      totalDurationMs = 148 * 60 * 1000L
    )

    cachedMediaDao.insertOrUpdate(entity)
    assertEquals(1, cachedMediaDao.count())

    val fetched = cachedMediaDao.getMediaById("test_item_1")
    assertNotNull(fetched)
    assertEquals("Inception", fetched?.title)
    assertFalse(fetched!!.isFavorite)

    // Update favorite state
    cachedMediaDao.updateFavorite("test_item_1", true)
    val updatedFav = cachedMediaDao.getMediaById("test_item_1")
    assertTrue(updatedFav!!.isFavorite)

    // Update progress
    cachedMediaDao.updateProgress("test_item_1", 45000L)
    val updatedProg = cachedMediaDao.getMediaById("test_item_1")
    assertEquals(45000L, updatedProg!!.currentProgressMs)
  }
}

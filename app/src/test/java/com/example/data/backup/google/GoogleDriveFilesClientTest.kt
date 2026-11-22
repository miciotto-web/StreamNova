package com.example.data.backup.google

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Contratto del client REST di Google Drive v3 (files) contro un server HTTP reale. */
class GoogleDriveFilesClientTest {

  private lateinit var server: MockWebServer
  private lateinit var client: GoogleDriveFilesClient

  @Before
  fun setUp() {
    server = MockWebServer()
    server.start()
    client = GoogleDriveFilesClient(
      filesEndpointUrl = server.url("/drive/v3/files").toString(),
      uploadEndpointUrl = server.url("/upload/drive/v3/files").toString()
    )
  }

  @After
  fun tearDown() {
    server.shutdown()
  }

  @Test
  fun `findBackupsFolder returns the folder id when present`() = runTest {
    server.enqueue(
      MockResponse().setBody(
        """{"files": [{"id": "FOLDER1", "name": "StreamNova Backups"}]}"""
      )
    )
    val result = client.findBackupsFolder("at")
    assertEquals("FOLDER1", result.getOrThrow())

    val recorded = server.takeRequest()
    assertTrue(recorded.requestUrl!!.queryParameter("q")!!.contains("StreamNova Backups"))
    assertEquals("Bearer at", recorded.getHeader("Authorization"))
  }

  @Test
  fun `findBackupsFolder returns null when the folder does not exist`() = runTest {
    server.enqueue(MockResponse().setBody("""{"files": []}"""))
    val result = client.findBackupsFolder("at")
    assertNull(result.getOrThrow())
  }

  @Test
  fun `createBackupsFolder posts the folder metadata and returns the id`() = runTest {
    server.enqueue(
      MockResponse().setBody("""{"id": "NEWFOLDER", "name": "StreamNova Backups"}""")
    )
    val result = client.createBackupsFolder("at")
    assertEquals("NEWFOLDER", result.getOrThrow())
    val recorded = server.takeRequest()
    assertEquals("POST", recorded.method)
    val body = recorded.body.readUtf8()
    assertTrue(body.contains("StreamNova Backups"))
    assertTrue(body.contains("application/vnd.google-apps.folder"))
  }

  @Test
  fun `uploadBackup sends multipart with metadata parents and content`() = runTest {
    server.enqueue(
      MockResponse().setBody(
        """{"id": "FILE1", "name": "StreamNova_Backup_2026-11-22_18-00-00.json"}"""
      )
    )
    val result = client.uploadBackup(
      accessToken = "at",
      folderId = "FOLDER1",
      fileName = "StreamNova_Backup_2026-11-22_18-00-00.json",
      json = """{"formatVersion":1}"""
    )
    assertTrue(result.isSuccess)
    assertEquals("FILE1", result.getOrThrow().id)

    val recorded = server.takeRequest()
    assertEquals("POST", recorded.method)
    assertEquals("multipart", recorded.requestUrl!!.queryParameter("uploadType"))
    assertTrue(recorded.getHeader("Content-Type")!!.contains("multipart/related"))
    val body = recorded.body.readUtf8()
    assertTrue(body.contains("StreamNova_Backup_2026-11-22_18-00-00.json"))
    assertTrue(body.contains("FOLDER1"))
    assertTrue(body.contains("""{"formatVersion":1}"""))
  }

  @Test
  fun `uploadBackup maps HTTP errors to classified messages`() = runTest {
    server.enqueue(
      MockResponse().setResponseCode(403).setBody(
        """{"error": {"code": 403, "message": "storageQuotaExceeded"}}"""
      )
    )
    val result = client.uploadBackup("at", "FOLDER1", "f.json", "{}")
    assertTrue(result.isFailure)
    assertTrue(result.exceptionOrNull()!!.message!!.contains("Spazio Google Drive"))
  }

  @Test
  fun `listBackups parses entries with modified time and size`() = runTest {
    server.enqueue(
      MockResponse().setBody(
        """
        {
          "files": [
            {"id": "A", "name": "a.json", "modifiedTime": "2026-11-22T17:59:59.000Z", "size": "1234"},
            {"id": "B", "name": "b.json", "modifiedTime": "2026-11-21T10:00:00.000Z", "size": "456"}
          ]
        }
        """.trimIndent()
      )
    )
    val files = client.listBackups("at", "FOLDER1").getOrThrow()
    assertEquals(2, files.size)
    assertEquals("A", files[0].id)
    assertEquals(1234L, files[0].sizeBytes)
    assertTrue(files[0].modifiedTimeMs > files[1].modifiedTimeMs)
  }

  @Test
  fun `downloadBackup returns the raw JSON`() = runTest {
    server.enqueue(
      MockResponse().setBody("""{"formatVersion": 1, "payload": {"addons": []}}""")
    )
    val json = client.downloadBackup("at", "FILE1").getOrThrow()
    assertTrue(json.contains("formatVersion"))
    val recorded = server.takeRequest()
    assertEquals("media", recorded.requestUrl!!.queryParameter("alt"))
  }

  @Test
  fun `findFileByName matches an existing file`() = runTest {
    server.enqueue(
      MockResponse().setBody(
        """{"files": [{"id": "DUP", "name": "d.json", "size": "10"}]}"""
      )
    )
    val file = client.findFileByName("at", "FOLDER1", "d.json").getOrThrow()
    assertEquals("DUP", file?.id)
  }

  @Test
  fun `errors carry user-readable messages without tokens`() = runTest {
    server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
    val result = client.listBackups("SECRETBEARER", "FOLDER1")
    val message = result.exceptionOrNull()!!.message!!
    assertTrue(message.contains("Autorizzazione"))
    // Il token NON compare mai nei messaggi di errore.
    assertTrue(!message.contains("SECRETBEARER"))
  }
}

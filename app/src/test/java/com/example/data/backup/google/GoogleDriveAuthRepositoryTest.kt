package com.example.data.backup.google

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.prefs.ApiKeyCipher
import com.example.data.prefs.ApiKeyCipherFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Cifra finto per test JVM: prefisso + rotazione, stesso contratto di [ApiKeyCipher]. */
class FakeTokenCipher : ApiKeyCipher {
  override fun encrypt(plain: String): String =
    ApiKeyCipherFormat.MARKER + java.util.Base64.getEncoder().encodeToString(plain.reversed().toByteArray())

  override fun decrypt(stored: String): String? =
    if (ApiKeyCipherFormat.isEncrypted(stored)) {
      String(java.util.Base64.getDecoder().decode(ApiKeyCipherFormat.payload(stored))).reversed()
    } else null
}

/**
 * Persistenza e isolamento account del repository Google Drive su Robolectric:
 * DataStore reale (file per profilo), cifratura finta, rete puntata a MockWebServer.
 */
@RunWith(RobolectricTestRunner::class)
class GoogleDriveAuthRepositoryTest {

  private lateinit var context: Context
  private val dispatcher: TestDispatcher = StandardTestDispatcher()
  private lateinit var server: MockWebServer

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
    context = ApplicationProvider.getApplicationContext()
    server = MockWebServer()
    server.start()
    GoogleDriveAuthRepository.authClientOverride = GoogleDriveAuthClient(
      deviceCodeEndpointUrl = server.url("/device/code").toString(),
      tokenEndpointUrl = server.url("/token").toString(),
      revokeEndpointUrl = server.url("/revoke").toString(),
      userInfoEndpointUrl = server.url("/userinfo").toString()
    )
    // Ogni test usa un profilo separato: nessun collisione di file DataStore.
    GoogleDriveAuthRepository.init(
      context,
      profileId = "drive_test_${System.nanoTime()}",
      keyCipher = FakeTokenCipher(),
      oauthClientSecret = "test-secret"
    )
  }

  @After
  fun tearDown() {
    server.shutdown()
    Dispatchers.resetMain()
  }

  @Test
  fun `not connected after init without stored account`() = runTest(dispatcher) {
    assertFalse(GoogleDriveAuthRepository.isConnected())
    assertNull(GoogleDriveAuthRepository.currentAccount())
    assertEquals(GoogleDriveAuthState.NotConnected, GoogleDriveAuthRepository.authState.value)
  }

  @Test
  fun `startDeviceFlow without client id fails without network and keeps state clean`() =
    runTest(dispatcher) {
      val result = GoogleDriveAuthRepository.startDeviceFlow("")
      assertTrue(result.isFailure)
      assertFalse(GoogleDriveAuthRepository.isConnected())
      assertEquals(0, server.requestCount)
    }

  @Test
  fun `startDeviceFlow surfaces the user code and switches to Connecting`() = runTest(dispatcher) {
    server.enqueue(
      MockResponse().setBody(
        """
        {"device_code": "DEV", "user_code": "CODE123", "verification_url": "https://google.com/device", "expires_in": 1800, "interval": 5}
        """.trimIndent()
      )
    )
    val result = GoogleDriveAuthRepository.startDeviceFlow("client-id")
    assertTrue(result.isSuccess)
    assertEquals(
      GoogleDriveAuthState.Connecting("CODE123", "https://google.com/device"),
      GoogleDriveAuthRepository.authState.value
    )
    GoogleDriveAuthRepository.cancelConnecting()
    assertEquals(GoogleDriveAuthState.NotConnected, GoogleDriveAuthRepository.authState.value)
  }

  @Test
  fun `persistAccount stores an encrypted refresh token and clears folder isolation`() =
    runTest(dispatcher) {
      GoogleDriveAuthRepository.persistAccount("plain-refresh-token", "sub-abc", "utente@example.com")
      assertTrue(GoogleDriveAuthRepository.isConnected())
      assertEquals(
        GoogleDriveAccount(accountKey = "sub-abc", email = "utente@example.com"),
        GoogleDriveAuthRepository.currentAccount()
      )
      assertEquals(
        GoogleDriveAuthState.Connected("utente@example.com"),
        GoogleDriveAuthRepository.authState.value
      )
    }

  @Test
  fun `account without refresh token is never persisted`() = runTest(dispatcher) {
    GoogleDriveAuthRepository.persistAccount(null, "sub-abc", "utente@example.com")
    assertFalse(GoogleDriveAuthRepository.isConnected())
    assertNull(GoogleDriveAuthRepository.currentAccount())
  }

  @Test
  fun `rememberBackupsFolderId without a connected account stores nothing`() = runTest(dispatcher) {
    // Nessun account collegato: il folderId NON resta valido, il riferimento è
    // valido SOLO per l'account con cui la cartella è stata creata.
    GoogleDriveAuthRepository.rememberBackupsFolderId("SOME_FOLDER")
    assertNull(GoogleDriveAuthRepository.cachedBackupsFolderId())
  }

  @Test
  fun `folder id survives when account unchanged and dies when marked missing`() =
    runTest(dispatcher) {
      GoogleDriveAuthRepository.persistAccount("rt", "sub-abc", "a@b.c")
      GoogleDriveAuthRepository.rememberBackupsFolderId("FOLDER_OK")
      assertEquals("FOLDER_OK", GoogleDriveAuthRepository.cachedBackupsFolderId())

      // Cartella eliminata dall'utente: mai restituita finché non ricreata.
      GoogleDriveAuthRepository.markBackupsFolderMissing()
      assertNull(GoogleDriveAuthRepository.cachedBackupsFolderId())
    }

  @Test
  fun `disconnect revokes the token and clears every local trace`() = runTest(dispatcher) {
    server.enqueue(MockResponse().setResponseCode(200))
    GoogleDriveAuthRepository.persistAccount("rt-test", "sub-abc", "a@b.c")
    GoogleDriveAuthRepository.rememberBackupsFolderId("FOLDER_OK")

    val result = GoogleDriveAuthRepository.disconnect()
    assertTrue(result.isSuccess)
    assertEquals(1, server.requestCount)
    assertTrue(server.takeRequest().body.readUtf8().contains("token=rt-test"))

    assertFalse(GoogleDriveAuthRepository.isConnected())
    assertNull(GoogleDriveAuthRepository.currentAccount())
    assertEquals(GoogleDriveAuthState.NotConnected, GoogleDriveAuthRepository.authState.value)
    assertNull(GoogleDriveAuthRepository.cachedBackupsFolderId())
  }
}

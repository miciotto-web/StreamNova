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

/**
 * Contratto del client OAuth Google (Device Authorization Grant) contro un server
 * HTTP reale (MockWebServer): nessuna dipendenza da Google Play Services o browser.
 */
class GoogleDriveAuthClientTest {

  private lateinit var server: MockWebServer
  private lateinit var client: GoogleDriveAuthClient

  @Before
  fun setUp() {
    server = MockWebServer()
    server.start()
    client = GoogleDriveAuthClient(
      deviceCodeEndpointUrl = server.url("/device/code").toString(),
      tokenEndpointUrl = server.url("/token").toString(),
      revokeEndpointUrl = server.url("/revoke").toString(),
      userInfoEndpointUrl = server.url("/userinfo").toString()
    )
  }

  @After
  fun tearDown() {
    server.shutdown()
  }

  @Test
  fun `startDeviceFlow returns user code and verification url`() = runTest {
    server.enqueue(
      MockResponse().setBody(
        """
        {
          "device_code": "DEV123",
          "user_code": "ABCD-EFGH",
          "verification_url": "https://google.com/device",
          "expires_in": 1800,
          "interval": 5
        }
        """.trimIndent()
      )
    )
    val result = client.startDeviceFlow("test-client-id")
    assertTrue(result.isSuccess)
    val grant = result.getOrThrow()
    assertEquals("DEV123", grant.deviceCode)
    assertEquals("ABCD-EFGH", grant.userCode)
    assertEquals("https://google.com/device", grant.verificationUrlResolved)

    val recorded = server.takeRequest()
    val body = recorded.body.readUtf8()
    assertTrue(body.contains("client_id=test-client-id"))
    assertTrue(body.contains("scope="))
    assertTrue(body.contains("drive.file"))
  }

  @Test
  fun `startDeviceFlow fails with blank client id without touching the network`() = runTest {
    val result = client.startDeviceFlow("")
    assertTrue(result.isFailure)
    assertEquals(0, server.requestCount)
  }

  @Test
  fun `pollTokenOnce maps authorization_pending to Pending`() = runTest {
    server.enqueue(
      MockResponse().setResponseCode(400).setBody(
        """{"error": "authorization_pending", "error_description": "waiting"}"""
      )
    )
    val poll = client.pollTokenOnce("id", "test-secret", "dev")
    assertTrue(poll is GoogleTokenPoll.Pending)
    assertTrue(server.takeRequest().body.readUtf8().contains("client_secret=test-secret"))
  }

  @Test
  fun `pollTokenOnce maps slow_down to SlowDown`() = runTest {
    server.enqueue(
      MockResponse().setResponseCode(400).setBody(
        """{"error": "slow_down", "error_description": "slow down"}"""
      )
    )
    val poll = client.pollTokenOnce("id", "test-secret", "dev")
    assertTrue(poll is GoogleTokenPoll.SlowDown)
  }

  @Test
  fun `pollTokenOnce returns tokens on success with refresh token`() = runTest {
    server.enqueue(
      MockResponse().setBody(
        """
        {
          "access_token": "at",
          "expires_in": 3599,
          "refresh_token": "rt",
          "scope": "https://www.googleapis.com/auth/drive.file",
          "token_type": "Bearer"
        }
        """.trimIndent()
      )
    )
    val poll = client.pollTokenOnce("id", "test-secret", "dev")
    val success = poll as GoogleTokenPoll.Success
    assertEquals("at", success.token.accessToken)
    assertEquals("rt", success.token.refreshToken)
  }

  @Test
  fun `pollTokenOnce maps access_denied to Denied`() = runTest {
    server.enqueue(
      MockResponse().setResponseCode(400).setBody(
        """{"error": "access_denied", "error_description": "user denied"}"""
      )
    )
    val poll = client.pollTokenOnce("id", "test-secret", "dev")
    val denied = poll as GoogleTokenPoll.Denied
    assertEquals("access_denied", denied.reason)
  }

  @Test
  fun `pollTokenOnce maps network failure to Transient`() = runTest {
    server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_START))
    val poll = client.pollTokenOnce("id", "test-secret", "dev")
    assertTrue(poll is GoogleTokenPoll.Transient)
  }

  @Test
  fun `refreshAccessToken succeeds and requests grant_type refresh_token`() = runTest {
    server.enqueue(
      MockResponse().setBody(
        """
        {"access_token": "new-at", "expires_in": 3599, "token_type": "Bearer"}
        """.trimIndent()
      )
    )
    val result = client.refreshAccessToken("id", "test-secret", "stored-rt")
    assertTrue(result.isSuccess)
    assertEquals("new-at", result.getOrThrow().accessToken)
    val body = server.takeRequest().body.readUtf8()
    assertTrue(body.contains("grant_type=refresh_token"))
    assertTrue(body.contains("refresh_token=stored-rt"))
    assertTrue(body.contains("client_secret=test-secret"))
  }

  @Test
  fun `refreshAccessToken rejected maps to TOKEN_REJECTED`() = runTest {
    server.enqueue(
      MockResponse().setResponseCode(400).setBody(
        """{"error": "invalid_grant", "error_description": "Token has been expired or revoked."}"""
      )
    )
    val result = client.refreshAccessToken("id", "test-secret", "revoked-rt")
    assertTrue(result.isFailure)
    val error = result.exceptionOrNull() as GoogleAuthException
    assertEquals(GoogleAuthException.Code.TOKEN_REJECTED, error.code)
  }

  @Test
  fun `revokeToken succeeds on 200`() = runTest {
    server.enqueue(MockResponse().setResponseCode(200))
    val result = client.revokeToken("rt")
    assertTrue(result.isSuccess)
    val body = server.takeRequest().body.readUtf8()
    assertTrue(body.contains("token=rt"))
  }

  @Test
  fun `fetchEmail parses the userinfo response`() = runTest {
    server.enqueue(
      MockResponse().setBody("""{"email": "utente@example.com", "name": "Utente"}""")
    )
    val result = client.fetchEmail("at")
    assertEquals("utente@example.com", result.getOrThrow())
  }

  @Test
  fun `id token decoder extracts email and sub`() {
    // header.payload.signature con payload {"sub":"11123","email":"utente@example.com"}
    val payload = java.util.Base64.getUrlEncoder()
      .encodeToString("""{"sub":"11123","email":"utente@example.com"}""".toByteArray())
    val idToken = "eyJhbGciOiJub25lIn0.$payload.sig"
    assertEquals("utente@example.com", GoogleIdTokenDecoder.extractEmail(idToken))
    assertEquals("11123", GoogleIdTokenDecoder.extractSub(idToken))
  }

  @Test
  fun `id token decoder returns null for garbage`() {
    assertNull(GoogleIdTokenDecoder.extractEmail("not-a-token"))
    assertNull(GoogleIdTokenDecoder.extractEmail(null))
    assertNull(GoogleIdTokenDecoder.extractSub(""))
  }
}

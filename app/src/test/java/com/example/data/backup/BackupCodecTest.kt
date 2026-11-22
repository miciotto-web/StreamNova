package com.example.data.backup

import com.example.data.sync.SyncAddonPayload
import com.example.data.sync.SyncAppSettingsPayload
import com.example.data.sync.SyncConfigPayload
import com.example.data.sync.SyncSimklAccountPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contratto del formato di backup e della redazione delle credenziali. */
class BackupCodecTest {

  private fun appSettings(
    torBoxKey: String? = "tb-secret",
    openSubsKey: String? = "os-secret",
    simklId: String? = "client-secret"
  ) = SyncAppSettingsPayload(
    torBoxApiKey = torBoxKey,
    openSubtitlesApiKey = openSubsKey,
    simklClientId = simklId,
    torBoxInstantDebridEnabled = true,
    streamingEngineMode = "HTTP_WEB",
    autoplayEnabled = false,
    appLanguage = "it",
    parentalControlEnabled = false,
    parentalControlPinHash = "",
    parentalControlLevel = "18",
    hideUnreleasedContent = false,
    userProviderBindingsJson = "[]",
    cwShowRow = true,
    cwLayout = "CARD",
    cwEpisodeThumbnails = false,
    cwBlurUnwatched = false,
    cwNextFromFurthest = false,
    cwShowUnaired = true,
    cwSort = "DEFAULT"
  )

  private fun envelope(
    payload: SyncConfigPayload = SyncConfigPayload(
      appSettings = appSettings(),
      addons = listOf(SyncAddonPayload("https://addon.example/manifest.json", "Addon", true))
    )
  ) = BackupEnvelope(
    formatVersion = BACKUP_FORMAT_VERSION,
    appVersionName = "1.2.1",
    appVersionCode = 1201,
    exportedAtEpochMs = 1_700_000_000_000L,
    deviceName = "TV",
    payload = payload
  )

  @Test
  fun `roundtrip preserves settings and addons`() {
    val decoded = BackupCodec.decode(BackupCodec.encode(envelope()))
    assertTrue(decoded is BackupDecodeResult.Success)
    val success = decoded as BackupDecodeResult.Success
    assertEquals("1.2.1", success.envelope.appVersionName)
    assertEquals("tb-secret", success.envelope.payload.appSettings?.torBoxApiKey)
    assertEquals(1, success.envelope.payload.addons.size)
    assertTrue(success.envelope.hasContent)
  }

  @Test
  fun `rejects future format versions`() {
    val json = BackupCodec.encode(envelope().copy(formatVersion = BACKUP_FORMAT_VERSION + 1))
    val decoded = BackupCodec.decode(json)
    assertTrue(decoded is BackupDecodeResult.Failure)
    assertEquals(BackupError.UNSUPPORTED_VERSION, (decoded as BackupDecodeResult.Failure).error)
  }

  @Test
  fun `rejects invalid and empty files`() {
    assertEquals(
      BackupError.EMPTY,
      (BackupCodec.decode("") as BackupDecodeResult.Failure).error
    )
    assertEquals(
      BackupError.INVALID_JSON,
      (BackupCodec.decode("{rotto") as BackupDecodeResult.Failure).error
    )
    assertEquals(
      BackupError.EMPTY_PAYLOAD,
      (BackupCodec.decode(BackupCodec.encode(BackupEnvelope())) as BackupDecodeResult.Failure).error
    )
  }

  @Test
  fun `redaction removes every secret but keeps settings`() {
    val payload = SyncConfigPayload(
      appSettings = appSettings(),
      addons = listOf(SyncAddonPayload("https://addon.example/manifest.json", "Addon", true)),
      simklAccount = SyncSimklAccountPayload(
        accessToken = "access-secret",
        refreshToken = "refresh-secret",
        accountId = 1L,
        username = "utente",
        accountType = "vip",
        tokenExpiresAt = "2030-01-01",
        tokenExpiresAtEpochMs = 1L,
        scope = "all",
        flow = "QR",
        sessionExpired = false
      )
    )
    assertTrue(BackupSanitizer.containsSecrets(payload))
    val redacted = BackupSanitizer.redactSecrets(payload)
    assertFalse(BackupSanitizer.containsSecrets(redacted))
    assertNull(redacted.appSettings?.torBoxApiKey)
    assertNull(redacted.appSettings?.openSubtitlesApiKey)
    assertNull(redacted.appSettings?.simklClientId)
    assertNull(redacted.simklAccount?.accessToken)
    assertNull(redacted.simklAccount?.refreshToken)
    // Le impostazioni non segrete restano intatte.
    assertEquals("it", redacted.appSettings?.appLanguage)
    assertEquals(1, redacted.addons.size)
    assertEquals("utente", redacted.simklAccount?.username)
  }

  @Test
  fun `export without credentials never serializes secrets`() {
    val redacted = BackupSanitizer.redactSecrets(
      SyncConfigPayload(appSettings = appSettings())
    )
    val encoded = BackupCodec.encode(envelope(payload = redacted).copy(includesCredentials = false))
    assertFalse(encoded.contains("tb-secret"))
    assertFalse(encoded.contains("os-secret"))
    assertFalse(encoded.contains("client-secret"))
  }
}

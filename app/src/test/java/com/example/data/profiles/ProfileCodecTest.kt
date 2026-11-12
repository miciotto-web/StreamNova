package com.example.data.profiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Round-trip del catalogo profili: il JSON salvato su DataStore deve restituire
 * esattamente profili, colori e profilo attivo. È il formato da cui dipende il
 * bootstrap dell'app, quindi va protetto anche prima del test su emulatore.
 */
class ProfileCodecTest {

  @Test
  fun `il catalogo viene codificato e riletto senza perdite`() {
    val state = ProfileStoreState(
      profiles = listOf(
        StoredProfile(
          id = "default",
          name = "Profilo principale",
          avatar = ProfileAvatar.CYAN.name,
          isPrimary = true,
          createdAtEpochMs = 0L,
          hasCustomName = false,
          avatarEmoji = "🎬"
        ),
        StoredProfile(
          id = "p123abc",
          name = "Bambini",
          avatar = ProfileAvatar.AMBER.name,
          isPrimary = false,
          createdAtEpochMs = 1_700_000_000_000L,
          hasCustomName = true,
          avatarEmoji = "🍿"
        )
      ),
      activeProfileId = "p123abc"
    )

    val json = ProfileCodec.encode(state)
    val decoded = ProfileCodec.decode(json)

    assertNotNull(decoded)
    assertEquals(state, decoded)
  }

  @Test
  fun `un JSON assente o corrotto non fa fallire l'avvio`() {
    assertNull(ProfileCodec.decode(null))
    assertNull(ProfileCodec.decode(""))
    assertNull(ProfileCodec.decode("{non-json"))
  }
}

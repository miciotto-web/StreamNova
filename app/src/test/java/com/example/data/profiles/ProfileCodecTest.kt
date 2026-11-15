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
          avatarEmoji = "🍿",
          avatarImage = ProfileImageAvatar.SULLEY.storage
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

  @Test
  fun `toStored include l'avatar-immagine come valore di storage`() {
    val profile = UserProfile(
      id = "p1",
      name = "Anna",
      avatar = ProfileAvatar.ROSE,
      avatarEmoji = null,
      avatarImage = ProfileImageAvatar.TIANA
    )

    val stored = profile.toStored()

    assertNull(stored.avatarEmoji)
    assertEquals("tiana", stored.avatarImage)
  }

  @Test
  fun `un avatar-immagine ignoto o assente degrada a null senza errori`() {
    assertNull(ProfileImageAvatar.fromStorage(null))
    assertNull(ProfileImageAvatar.fromStorage(""))
    // Valore manomesso sul disco: non deve risolversi in una voce arbitraria.
    assertNull(ProfileImageAvatar.fromStorage("non-esiste"))
    assertEquals(ProfileImageAvatar.SPIDERMAN, ProfileImageAvatar.fromStorage("spiderman"))
    assertEquals(ProfileImageAvatar.BEAR, ProfileImageAvatar.fromStorage("bear"))
  }

  @Test
  fun `ogni avatar-immagine appartiene a una categoria del selettore`() {
    ProfileImageAvatar.entries.forEach { image ->
      assertEquals(image.category, ProfileImageCategory.entries.firstOrNull { it == image.category })
    }
    // Ogni categoria ha almeno un avatar: nessuna scheda vuota nel selettore.
    ProfileImageCategory.entries.forEach { category ->
      assertNotNull(ProfileImageAvatar.entries.firstOrNull { it.category == category })
    }
  }
}

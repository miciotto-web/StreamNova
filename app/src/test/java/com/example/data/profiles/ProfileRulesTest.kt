package com.example.data.profiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test delle regole pure dei profili: validazione dei nomi, palette, nomi su disco.
 * Non richiedono Android e proteggono i vincoli che la UI e il bootstrap dell'app
 * condividono con il repository.
 */
class ProfileRulesTest {

  private fun profile(
    id: String,
    name: String,
    avatar: ProfileAvatar = ProfileAvatar.CYAN,
    primary: Boolean = false,
    emoji: String? = null
  ) = UserProfile(
    id = id,
    name = name,
    avatar = avatar,
    isPrimary = primary,
    createdAtEpochMs = 0L,
    avatarEmoji = emoji
  )

  @Test
  fun `il nome viene normalizzato togliendo spazi esterni e doppi spazi`() {
    assertEquals("Anna Maria", ProfileRules.normalizeName("  Anna   Maria "))
    assertEquals("Bambini", ProfileRules.normalizeName("Bambini"))
  }

  @Test
  fun `un nome vuoto non e valido`() {
    assertEquals(
      ProfileNameError.EMPTY,
      ProfileRules.nameError("   ", listOf(profile("p1", "Anna")))
    )
  }

  @Test
  fun `un nome oltre il limite non e valido`() {
    val tooLong = "a".repeat(ProfileRules.MAX_NAME_LENGTH + 1)
    assertEquals(
      ProfileNameError.TOO_LONG,
      ProfileRules.nameError(tooLong, emptyList())
    )
  }

  @Test
  fun `il duplicato e riconosciuto senza distinzione tra maiuscole e minuscole`() {
    val existing = listOf(profile("p1", "Anna"))
    assertEquals(
      ProfileNameError.DUPLICATE,
      ProfileRules.nameError("anna", existing)
    )
    assertEquals(
      ProfileNameError.DUPLICATE,
      ProfileRules.nameError("  ANNA  ", existing)
    )
  }

  @Test
  fun `la rinomina ignora il profilo stesso`() {
    val existing = listOf(profile("p1", "Anna"), profile("p2", "Marco"))
    assertNull(ProfileRules.nameError("Anna", existing, excludeId = "p1"))
    assertEquals(
      ProfileNameError.DUPLICATE,
      ProfileRules.nameError("Marco", existing, excludeId = "p1")
    )
  }

  @Test
  fun `la palette propone il primo colore libero e poi ricomincia`() {
    val twoProfiles = listOf(
      profile("p1", "Anna", ProfileAvatar.CYAN),
      profile("p2", "Marco", ProfileAvatar.VIOLET)
    )
    assertEquals(ProfileAvatar.AMBER, ProfileRules.nextAvatar(twoProfiles))

    val allColors = ProfileAvatar.entries.mapIndexed { index, avatar ->
      profile("p$index", "Profilo $index", avatar)
    }
    assertTrue(ProfileRules.nextAvatar(allColors) in ProfileAvatar.entries)
  }

  @Test
  fun `il profilo principale mantiene il nome storico del database`() {
    assertEquals(
      "streamnova_database.db",
      ProfileStorage.databaseName(ProfileStorage.PRIMARY_PROFILE_ID)
    )
    assertEquals(
      "streamnova_database_transformers.db",
      ProfileStorage.databaseName("transformers")
    )
  }

  @Test
  fun `ogni profilo aggiuntivo ha un nome storage distinto e sicuro`() {
    val name = ProfileStorage.databaseName("p1234abcd")
    assertEquals("streamnova_database_p1234abcd.db", name)
    assertEquals(
      "simkl_preferences_p1234abcd",
      ProfileStorage.dataStoreName(ProfileStorage.SIMKL_STORE_BASE, "p1234abcd")
    )
    assertEquals(
      "persistent_subtitles",
      ProfileStorage.dataStoreName(
        ProfileStorage.SUBTITLE_STORE_BASE,
        ProfileStorage.PRIMARY_PROFILE_ID
      )
    )
  }

  @Test
  fun `un id manomesso non puo uscire dalla directory dell'app`() {
    val segment = ProfileStorage.fileSegment("../../evil")
    assertEquals("evil", segment)
    assertEquals(
      "streamnova_database_profile.db",
      ProfileStorage.databaseName("///")
    )
  }

  @Test
  fun `l'emoji suggerita e la prima non usata della palette cinema`() {
    assertEquals("🎬", ProfileRules.nextEmoji(emptyList()))
    assertEquals(
      "🍿",
      ProfileRules.nextEmoji(listOf(profile("p1", "Anna", emoji = "🎬")))
    )
    assertEquals(
      "🎥",
      ProfileRules.nextEmoji(
        listOf(
          profile("p1", "Anna", emoji = "🎬"),
          profile("p2", "Marco", emoji = "🍿")
        )
      )
    )
  }

  @Test
  fun `le categorie emoji non contengono duplicati e coprono i temi richiesti`() {
    ProfileEmojiCategory.entries.forEach { category ->
      val emojis = ProfileRules.emojisFor(category)
      assertTrue("$category non deve essere vuota", emojis.isNotEmpty())
      assertEquals("$category non deve avere duplicati", emojis.size, emojis.distinct().size)
    }
    assertTrue("🍿 nella categoria Cinema", "🍿" in ProfileRules.emojisFor(ProfileEmojiCategory.CINEMA))
    assertTrue("🔪 nella categoria Thriller", "🔪" in ProfileRules.emojisFor(ProfileEmojiCategory.THRILLER))
    assertTrue("😂 nella categoria Commedia", "😂" in ProfileRules.emojisFor(ProfileEmojiCategory.COMEDY))
    assertTrue("🚀 nella categoria Sci-Fi", "🚀" in ProfileRules.emojisFor(ProfileEmojiCategory.SCIFI))
  }

  @Test
  fun `la categoria viene risolta dall'emoji e la musica e esclusa`() {
    assertEquals(ProfileEmojiCategory.CINEMA, ProfileRules.categoryFor("🍿"))
    assertEquals(ProfileEmojiCategory.THRILLER, ProfileRules.categoryFor("🔪"))
    assertEquals(ProfileEmojiCategory.COMEDY, ProfileRules.categoryFor("🤡"))
    assertEquals(ProfileEmojiCategory.SCIFI, ProfileRules.categoryFor("🛸"))
    assertNull(ProfileRules.categoryFor("🎧"))
    assertNull(ProfileRules.categoryFor("🍿🎟️🎬🥤"))
  }

  @Test
  fun `le emoji del selettore sono compatte per lo storage`() {
    ProfileEmojiCategory.entries
      .flatMap { ProfileRules.emojisFor(it) }
      .forEach { emoji ->
        assertTrue("emoji troppo lunga per il campo avatar: $emoji", emoji.length <= 8)
      }
  }

  @Test
  fun `l'iniziale dell'avatar e la prima lettera del nome`() {
    assertEquals("A", profile("p1", "anna").initial)
    assertEquals("?", profile("p1", "   ").initial)
  }
}

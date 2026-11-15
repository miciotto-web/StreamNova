package com.example.data.profiles

/**
 * Colore dell'avatar di un profilo.
 *
 * Il valore persistito è il nome dell'enum, quindi aggiungere nuovi colori in coda è
 * compatibile con i profili già salvati. La mappatura verso i colori Compose vive nella
 * UI (`ProfilesScreen`): il livello dati resta indipendente dal tema.
 */
enum class ProfileAvatar {
  CYAN,
  VIOLET,
  AMBER,
  GREEN,
  ROSE,
  BLUE;

  companion object {
    fun fromStorage(value: String?): ProfileAvatar =
      entries.firstOrNull { it.name == value } ?: CYAN
  }
}

/**
 * Un profilo StreamNova.
 *
 * @param id identificatore stabile usato per i nomi su disco e per il profilo attivo.
 * @param name nome mostrato ("Anna", "Bambini", …).
 * @param avatar colore scelto per l'avatar.
 * @param isPrimary true per il profilo creato al primo avvio: conserva i dati storici
 *   dell'app e non può essere eliminato.
 * @param createdAtEpochMs data di creazione (ordinamento stabile in gestione).
 * @param hasCustomName false finché l'utente non rinomina il profilo principale: la UI
 *   mostra in quel caso il nome localizzato nella lingua corrente dell'app, invece del
 *   nome tecnico salvato alla creazione.
 * @param avatarEmoji emoji scelta come avatar (a tema cinema). null = iniziale del nome.
 * @param avatarImage avatar-immagine dei personaggi (es. supereroi, Simpson). Se presente
 *   ha la precedenza sull'emoji; i due campi sono mutuamente esclusivi per costruzione
 *   della UI (scegliere l'uno deseleziona l'altro).
 */
data class UserProfile(
  val id: String,
  val name: String,
  val avatar: ProfileAvatar = ProfileAvatar.CYAN,
  val isPrimary: Boolean = false,
  val createdAtEpochMs: Long = 0L,
  val hasCustomName: Boolean = true,
  val avatarEmoji: String? = null,
  val avatarImage: ProfileImageAvatar? = null
) {
  /** Iniziale mostrata nel cerchio-avatar quando non è stata scelta un'emoji. */
  val initial: String
    get() = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
}

/** Motivo per cui un nome profilo non è accettabile. */
enum class ProfileNameError {
  EMPTY,
  TOO_LONG,
  DUPLICATE
}

/** Categorie tematiche del selettore emoji dell'avatar. */
enum class ProfileEmojiCategory {
  CINEMA,
  THRILLER,
  COMEDY,
  SCIFI
}

/** Categorie di avatar-immagine nel selettore (personaggi raggruppati per collezione). */
enum class ProfileImageCategory {
  CINEMA_FANTASY,
  SIMPSONS,
  DISNEY,
  CARTOON_CLASSIC,
  BLACK_MIRROR
}

/**
 * Avatar-immagine predefiniti forniti con l'app (personaggi disegnati in stile cerchio).
 *
 * Il valore persistito è [storage], validato in lettura contro le voci note: un catalogo
 * manomesso degrada senza errori all'avatar precedente (emoji o iniziale). La mappatura
 * verso le risorse drawable vive nella UI (`ProfileAvatarBadge`), così il livello dati
 * resta indipendente da Android.
 */
enum class ProfileImageAvatar(val storage: String, val category: ProfileImageCategory) {
  SPIDERMAN("spiderman", ProfileImageCategory.CINEMA_FANTASY),
  NAVI("navi", ProfileImageCategory.CINEMA_FANTASY),
  TIANA("tiana", ProfileImageCategory.CINEMA_FANTASY),
  HULK("hulk", ProfileImageCategory.CINEMA_FANTASY),
  BLACK_PANTHER("black_panther", ProfileImageCategory.CINEMA_FANTASY),
  SULLEY("sulley", ProfileImageCategory.CINEMA_FANTASY),
  BB8("bb8", ProfileImageCategory.CINEMA_FANTASY),
  MANDALORIAN("mandalorian", ProfileImageCategory.CINEMA_FANTASY),
  HOMER("homer", ProfileImageCategory.SIMPSONS),
  MARGE("marge", ProfileImageCategory.SIMPSONS),
  BART("bart", ProfileImageCategory.SIMPSONS),
  LISA("lisa", ProfileImageCategory.SIMPSONS),
  MAGGIE("maggie", ProfileImageCategory.SIMPSONS),
  KRUSTY("krusty", ProfileImageCategory.SIMPSONS),
  RALPH("ralph", ProfileImageCategory.SIMPSONS),
  HIBBERT("hibbert", ProfileImageCategory.SIMPSONS),
  ELSA("elsa", ProfileImageCategory.DISNEY),
  ANNA("anna", ProfileImageCategory.DISNEY),
  OLAF("olaf", ProfileImageCategory.DISNEY),
  YZMA("yzma", ProfileImageCategory.DISNEY),
  RALPH_SPACCATUTTO("ralph_spaccatutto", ProfileImageCategory.DISNEY),
  MICKEY("mickey", ProfileImageCategory.DISNEY),
  DAISY("daisy", ProfileImageCategory.DISNEY),
  BEAST("beast", ProfileImageCategory.DISNEY),
  BUGS_BUNNY("bugs_bunny", ProfileImageCategory.CARTOON_CLASSIC),
  SCOOBY_DOO("scooby_doo", ProfileImageCategory.CARTOON_CLASSIC),
  TAZ("taz", ProfileImageCategory.CARTOON_CLASSIC),
  MARVIN("marvin", ProfileImageCategory.CARTOON_CLASSIC),
  DAFFY("daffy", ProfileImageCategory.CARTOON_CLASSIC),
  FRED_FLINTSTONE("fred_flintstone", ProfileImageCategory.CARTOON_CLASSIC),
  ROSARIO("rosario", ProfileImageCategory.CARTOON_CLASSIC),
  TADDEO("taddeo", ProfileImageCategory.CARTOON_CLASSIC),
  PUPPY("puppy", ProfileImageCategory.BLACK_MIRROR),
  PIXEL_WOLF("pixel_wolf", ProfileImageCategory.BLACK_MIRROR),
  COOKIE("cookie", ProfileImageCategory.BLACK_MIRROR),
  CROSS("cross", ProfileImageCategory.BLACK_MIRROR),
  DOLL("doll", ProfileImageCategory.BLACK_MIRROR),
  MONKEY("monkey", ProfileImageCategory.BLACK_MIRROR),
  BEAR("bear", ProfileImageCategory.BLACK_MIRROR);

  companion object {
    /** Valore persistito → voce nota; null o ignoto = nessun avatar-immagine. */
    fun fromStorage(value: String?): ProfileImageAvatar? =
      entries.firstOrNull { it.storage == value }
  }
}

/**
 * Regole pure sui profili: nessuna dipendenza da Android, così sono verificabili nei
 * test JVM e riusate identiche da creazione e rinomina.
 */
object ProfileRules {

  /** Limite del nome: sta comodo nella card del picker e nella riga delle Impostazioni. */
  const val MAX_NAME_LENGTH = 24

  /** Numero massimo di profili per dispositivo. */
  const val MAX_PROFILES = 8

  /**
   * Normalizza il nome digitato: spazi esterni rimossi e sequenze di spazi interni
   * ridotte a uno solo, così "  Anna   Maria " e "Anna Maria" sono lo stesso nome.
   */
  fun normalizeName(raw: String): String =
    raw.trim().replace(Regex("\\s+"), " ")

  /**
   * Valida [raw] contro i profili esistenti.
   *
   * @param excludeId profilo da ignorare nel controllo duplicati (rinomina).
   * @return null se il nome è valido, altrimenti il primo errore.
   */
  fun nameError(
    raw: String,
    profiles: List<UserProfile>,
    excludeId: String? = null
  ): ProfileNameError? {
    val name = normalizeName(raw)
    return when {
      name.isEmpty() -> ProfileNameError.EMPTY
      name.length > MAX_NAME_LENGTH -> ProfileNameError.TOO_LONG
      profiles.any { it.id != excludeId && it.name.equals(name, ignoreCase = true) } ->
        ProfileNameError.DUPLICATE
      else -> null
    }
  }

  /**
   * Colore suggerito per un nuovo profilo: il primo della palette non ancora usato,
   * altrimenti si ricomincia ciclicamente. Il colore resta poi modificabile.
   */
  fun nextAvatar(profiles: List<UserProfile>): ProfileAvatar {
    val used = profiles.map { it.avatar }.toSet()
    return ProfileAvatar.entries.firstOrNull { it !in used }
      ?: ProfileAvatar.entries[profiles.size % ProfileAvatar.entries.size]
  }

  /**
   * Emoji "storiche" a tema cinema: sono la palette SUGGERITA alla creazione di un
   * nuovo profilo e restano riconoscibili a distanza sul televisore.
   *
   * Il selettore completo, organizzato per categoria, è in [emojisFor].
   */
  val cinemaEmojis: List<String> = listOf(
    "🎬", "🍿", "🎥", "🎭", "🎟️", "🌟", "🚀", "🦸",
    "🧙", "🐉", "👻", "🤖", "🐶", "🐱", "👽", "🦖"
  )

  /**
   * Palette completa del selettore, divisa per tema.
   *
   * Le emoji restano le stesse per ogni profilo (cambiano colore e nome), così la
   * scelta è immediata anche con il telecomando. Ogni lista è deduplicata preservando
   * l'ordine di presentazione; le categorie possono condividere alcune emoji (es. 🍿
   * in Cinema e Commedia), perché il tema è il criterio di ricerca.
   */
  private val emojisByCategory: Map<ProfileEmojiCategory, List<String>> = mapOf(
    ProfileEmojiCategory.CINEMA to listOf(
      "🍿", "🎟️", "🎬", "🥤", "🎞️", "📽️", "🖤", "✨",
      "🛋️", "🍕", "📺", "🎭", "🧠", "🔥", "🎥", "🌟",
      "🦸", "🐶", "🐱", "🦖"
    ),
    ProfileEmojiCategory.THRILLER to listOf(
      "🔪", "🩸", "👻", "🖤", "🕵️‍♂️", "🔍", "🕵️‍♀️", "🦇",
      "🧠", "⚡", "👁️‍🗨️", "🕸️", "🕷️", "🌙", "🧪"
    ),
    ProfileEmojiCategory.COMEDY to listOf(
      "🍿", "🤪", "🤭", "🍷", "💅", "😂", "☕️", "🛋️",
      "🍕", "🥴", "🥳", "🤡", "✨", "🫠"
    ),
    ProfileEmojiCategory.SCIFI to listOf(
      "🌌", "🚀", "👽", "🛸", "⚡", "🧙‍♂️", "🐉", "🔮",
      "⚔️", "🛡️", "🪐", "✨", "🤖", "🔴", "🔵", "🌀", "🦸"
    )
  )

  /** Emoji della categoria [category], nell'ordine di presentazione. */
  fun emojisFor(category: ProfileEmojiCategory): List<String> =
    emojisByCategory[category].orEmpty()

  /**
   * Categoria che contiene [emoji], o null se l'emoji non appartiene al set corrente
   * (es. avatar salvato da una versione precedente o profilo senza emoji).
   */
  fun categoryFor(emoji: String?): ProfileEmojiCategory? {
    if (emoji.isNullOrBlank()) return null
    return ProfileEmojiCategory.entries.firstOrNull { category ->
      emoji in emojisFor(category)
    }
  }

  /** Emoji del profilo principale creato automaticamente. */
  const val DEFAULT_PRIMARY_EMOJI = "🎬"

  /**
   * Emoji suggerita per un nuovo profilo: la prima non ancora usata, altrimenti si
   * ricomincia ciclicamente. Evita che due profili nascano identici.
   */
  fun nextEmoji(profiles: List<UserProfile>): String {
    val used = profiles.mapNotNull { it.avatarEmoji }.toSet()
    return cinemaEmojis.firstOrNull { it !in used }
      ?: cinemaEmojis[profiles.size % cinemaEmojis.size]
  }
}

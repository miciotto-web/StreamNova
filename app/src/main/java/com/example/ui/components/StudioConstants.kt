package com.example.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.example.R

/**
 * Definizione dichiarativa di uno STUDIO di produzione dell'hub «Studios» della Home.
 *
 * Ogni scheda porta il logo ufficiale ORIGINALE del brand (drawable locale, colori reali)
 * e gli ID TMDB delle case di produzione che la compongono: `with_companies` TMDB accetta
 * più id separati da pipe (logica OR), quindi il catalogo della scheda copre tutti i
 * marchi del gruppo (es. Marvel = Marvel Studios + Marvel Entertainment + Marvel Television).
 *
 * I colori non sono decorazione libera: definiscono il gradiente di brand della card
 * dell'hub e dell'header della scheda dedicata, come da riferimento grafico Apple TV.
 */
data class StudioDefinition(
  /** Identità stabile per navigazione e ripristino focus (es. "marvel"). */
  val id: String,
  val name: String,
  /** ID TMDB delle production companies (pipe-separated) per il discover `with_companies`. */
  val tmdbCompanyIds: String,
  /** Logo ufficiale ORIGINALE del brand (drawable locale, nessun filtro colore). */
  @param:DrawableRes val logoResId: Int,
  val primaryColor: Color,
  val secondaryColor: Color,
  val accentColor: Color
) {
  /** Gradiente verticale di brand condiviso da card hub e header scheda. */
  val headerBrush: Brush
    get() = Brush.verticalGradient(listOf(primaryColor, secondaryColor))
}

object StudioConstants {

  val MARVEL = StudioDefinition(
    id = "marvel",
    name = "Marvel",
    tmdbCompanyIds = "420|7505|38679",
    logoResId = R.drawable.studio_logo_marvel,
    primaryColor = Color(0xFFB01317),
    secondaryColor = Color(0xFF2A0507),
    accentColor = Color(0xFFFF6B6B)
  )

  val DC = StudioDefinition(
    id = "dc",
    name = "DC",
    tmdbCompanyIds = "429|9993",
    logoResId = R.drawable.studio_logo_dc,
    primaryColor = Color(0xFF1157D9),
    secondaryColor = Color(0xFF071E4D),
    accentColor = Color(0xFF6EA8FF)
  )

  val A24 = StudioDefinition(
    id = "a24",
    name = "A24",
    tmdbCompanyIds = "41077",
    logoResId = R.drawable.studio_logo_a24,
    primaryColor = Color(0xFF1C1C20),
    secondaryColor = Color(0xFF050506),
    accentColor = Color(0xFFBBBBCC)
  )

  val PIXAR = StudioDefinition(
    id = "pixar",
    name = "Pixar",
    tmdbCompanyIds = "3",
    logoResId = R.drawable.studio_logo_pixar,
    primaryColor = Color(0xFF123358),
    secondaryColor = Color(0xFF060B14),
    accentColor = Color(0xFF43C6F0)
  )

  val DISNEY = StudioDefinition(
    id = "disney",
    name = "Disney+",
    tmdbCompanyIds = "2|3475",
    logoResId = R.drawable.studio_logo_disney,
    primaryColor = Color(0xFF0BA3C9),
    secondaryColor = Color(0xFF05355C),
    accentColor = Color(0xFF39D6FF)
  )

  val DREAMWORKS = StudioDefinition(
    id = "dreamworks",
    name = "DreamWorks",
    tmdbCompanyIds = "521|42141",
    logoResId = R.drawable.studio_logo_dreamworks,
    primaryColor = Color(0xFF1749A2),
    secondaryColor = Color(0xFF0A2154),
    accentColor = Color(0xFF7FB2FF)
  )

  val ILLUMINATION = StudioDefinition(
    id = "illumination",
    name = "Illumination",
    tmdbCompanyIds = "6704",
    logoResId = R.drawable.studio_logo_illumination,
    primaryColor = Color(0xFF0A2B66),
    secondaryColor = Color(0xFF050F26),
    accentColor = Color(0xFF9CCBFF)
  )

  val LEGENDARY = StudioDefinition(
    id = "legendary",
    name = "Legendary",
    tmdbCompanyIds = "923",
    logoResId = R.drawable.studio_logo_legendary,
    primaryColor = Color(0xFF0C2E5C),
    secondaryColor = Color(0xFF060F22),
    accentColor = Color(0xFFC9D8EC)
  )

  val HBO_MAX = StudioDefinition(
    id = "hbomax",
    name = "HBO Max",
    tmdbCompanyIds = "3268|158691",
    logoResId = R.drawable.studio_logo_hbomax,
    primaryColor = Color(0xFF5B2EBF),
    secondaryColor = Color(0xFF2A1157),
    accentColor = Color(0xFFB57AFF)
  )

  val SONY = StudioDefinition(
    id = "sony",
    name = "Sony Pictures",
    tmdbCompanyIds = "34|5|2251",
    logoResId = R.drawable.studio_logo_sony,
    primaryColor = Color(0xFF18202E),
    secondaryColor = Color(0xFF0A0D14),
    accentColor = Color(0xFFD9B24C)
  )

  val PARAMOUNT = StudioDefinition(
    id = "paramount",
    name = "Paramount+",
    tmdbCompanyIds = "4|314668",
    logoResId = R.drawable.studio_logo_paramount,
    primaryColor = Color(0xFF6FA8E4),
    secondaryColor = Color(0xFF2F6FB4),
    accentColor = Color(0xFFDDEFFF)
  )

  val TWENTIETH_CENTURY_FOX = StudioDefinition(
    id = "twentieth_century_fox",
    name = "20th Century Fox",
    tmdbCompanyIds = "25|127928",
    logoResId = R.drawable.studio_logo_fox,
    primaryColor = Color(0xFF12305F),
    secondaryColor = Color(0xFF071428),
    accentColor = Color(0xFFD9A441)
  )

  val LIONSGATE = StudioDefinition(
    id = "lionsgate",
    name = "Lionsgate",
    tmdbCompanyIds = "1632",
    logoResId = R.drawable.studio_logo_lionsgate,
    primaryColor = Color(0xFF2B67B0),
    secondaryColor = Color(0xFF123B75),
    accentColor = Color(0xFFCCE1FF)
  )

  val NEW_LINE = StudioDefinition(
    id = "newline",
    name = "New Line Cinema",
    tmdbCompanyIds = "12",
    logoResId = R.drawable.studio_logo_newline,
    primaryColor = Color(0xFF161618),
    secondaryColor = Color(0xFF070707),
    accentColor = Color(0xFFCFCFCF)
  )

  val STUDIO_GHIBLI = StudioDefinition(
    id = "ghibli",
    name = "Studio Ghibli",
    tmdbCompanyIds = "10342",
    logoResId = R.drawable.studio_logo_ghibli,
    primaryColor = Color(0xFF35597E),
    secondaryColor = Color(0xFF182C42),
    accentColor = Color(0xFFBFD4E8)
  )

  val LUCASFILM = StudioDefinition(
    id = "lucasfilm",
    name = "Lucasfilm",
    tmdbCompanyIds = "1",
    logoResId = R.drawable.studio_logo_lucasfilm,
    primaryColor = Color(0xFF1A1A1C),
    secondaryColor = Color(0xFF080808),
    accentColor = Color(0xFFDDDDDD)
  )

  val WARNER = StudioDefinition(
    id = "warner",
    name = "Warner Bros.",
    tmdbCompanyIds = "174|2785",
    logoResId = R.drawable.studio_logo_warner,
    primaryColor = Color(0xFF3E7CC2),
    secondaryColor = Color(0xFF123B75),
    accentColor = Color(0xFF9CC6F5)
  )

  val BLUMHOUSE = StudioDefinition(
    id = "blumhouse",
    name = "Blumhouse",
    tmdbCompanyIds = "3172|68884",
    logoResId = R.drawable.studio_logo_blumhouse,
    primaryColor = Color(0xFF12301C),
    secondaryColor = Color(0xFF070F0A),
    accentColor = Color(0xFF57D98B)
  )

  val UNIVERSAL = StudioDefinition(
    id = "universal",
    name = "Universal",
    tmdbCompanyIds = "33",
    logoResId = R.drawable.studio_logo_universal,
    primaryColor = Color(0xFF122457),
    secondaryColor = Color(0xFF080D20),
    accentColor = Color(0xFF7EA6F0)
  )

  /**
   * Ordine dell'hub «Studios» della Home: identità statica, l'indice è stabile tra le
   * sessioni e il ripristino focus per identità (id) non dipende da dati remoti.
   */
  val ALL = listOf(
    MARVEL,
    DC,
    A24,
    PIXAR,
    DISNEY,
    DREAMWORKS,
    ILLUMINATION,
    LEGENDARY,
    HBO_MAX,
    SONY,
    PARAMOUNT,
    TWENTIETH_CENTURY_FOX,
    LIONSGATE,
    NEW_LINE,
    STUDIO_GHIBLI,
    LUCASFILM,
    WARNER,
    BLUMHOUSE,
    UNIVERSAL
  )

  fun byId(id: String?): StudioDefinition? = ALL.firstOrNull { it.id == id }
}

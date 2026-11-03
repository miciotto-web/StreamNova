package com.aistudio.streamnova.mobile.ui

import androidx.annotation.StringRes
import com.aistudio.streamnova.mobile.R

/** Rotte dell'app mobile. Gli argomenti numerici usano -1 come "non specificato". */
object MobileRoutes {
  const val HOME = "home"
  const val MOVIES = "movies"
  const val SERIES = "series"
  const val SEARCH = "search"
  const val FAVORITES = "favorites"
  const val SETTINGS = "settings"
  const val ADDONS = "addons"
  const val DETAIL = "detail/{mediaId}"
  const val PLAYER =
    "player/{mediaId}?season={season}&episode={episode}&autoplay={autoplay}&manual={manual}"
  const val PROVIDER = "provider/{providerId}"

  fun detail(mediaId: String): String = "detail/${encodePathSegment(mediaId)}"

  /**
   * Rotta del player.
   *
   * @param manual `true` quando la schermata viene aperta dalla SELEZIONE
   *   MANUALE delle sorgenti (pulsante «Seleziona fonte» della scheda
   *   Dettaglio): il selettore delle sorgenti viene mostrato comunque e
   *   nessuna riproduzione parte senza la conferma dell'utente.
   */
  fun player(
    mediaId: String,
    season: Int? = null,
    episode: Int? = null,
    autoStart: Boolean = false,
    manual: Boolean = false
  ): String =
    "player/${encodePathSegment(mediaId)}?season=${season ?: -1}&episode=${episode ?: -1}" +
      "&autoplay=$autoStart&manual=$manual"

  fun provider(providerId: String): String = "provider/${encodePathSegment(providerId)}"

  /**
   * Percent-encoding di un singolo segmento di path, con la stessa resa di
   * `Uri.encode` ma senza dipendenza Android: la funzione resta verificabile
   * nei test JVM puri.
   */
  private fun encodePathSegment(value: String): String =
    java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}

/**
 * Destinazioni top-level della navigazione Mobile.
 *
 * Ordine Fase 8.4: Home, Film, Serie TV, Preferiti, Cerca, Impostazioni, Addon.
 * Impostazioni è immediatamente dopo Cerca come richiesto; Addon chiude la riga
 * ed è presente solo in modalità Debrid (`StreamingEngineMode.supportsAddonsSection`,
 * stessa regola della sidebar TV). Tutte le voci vivono nella barra inferiore
 * scorrevole: nessun pulsante flottante o seconda fila.
 */
enum class MobileTopDestination(
  val route: String,
  @StringRes val labelRes: Int
) {
  HOME(MobileRoutes.HOME, R.string.nav_home),
  MOVIES(MobileRoutes.MOVIES, R.string.nav_movies),
  SERIES(MobileRoutes.SERIES, R.string.nav_tv_series),
  FAVORITES(MobileRoutes.FAVORITES, R.string.nav_favorites),
  SEARCH(MobileRoutes.SEARCH, R.string.nav_search),
  SETTINGS(MobileRoutes.SETTINGS, R.string.nav_settings),
  ADDONS(MobileRoutes.ADDONS, R.string.nav_addons)
}

/** Ordine stabile delle voci, verificato dai test di regressione. */
val mobileTopDestinations: List<MobileTopDestination> = MobileTopDestination.entries.toList()

/**
 * Voci effettivamente mostrate nella barra: Addon esiste solo quando il motore
 * Debrid è attivo, come la sezione Add-on della TV.
 */
fun visibleTopDestinations(showAddons: Boolean): List<MobileTopDestination> =
  if (showAddons) {
    mobileTopDestinations
  } else {
    mobileTopDestinations.filterNot { it == MobileTopDestination.ADDONS }
  }

/** Posizione della navigazione top-level in base all'orientamento. */
enum class MobileNavigationPlacement {
  BOTTOM_BAR,
  RIGHT_RAIL
}

/**
 * Fase 8.4: la navigazione Mobile usa un'unica barra inferiore scorrevole in
 * portrait, landscape e tablet. Il rail laterale delle fasi precedenti è stato
 * rimosso perché le voci richieste (incluse Impostazioni e Addon) devono stare
 * su una sola riga orizzontale nella parte inferiore dello schermo.
 *
 * Il parametro resta per compatibilità con i test e con le chiamate esistenti;
 * il valore restituito è sempre [MobileNavigationPlacement.BOTTOM_BAR].
 */
@Suppress("UNUSED_PARAMETER")
fun mobileNavigationPlacement(isLandscape: Boolean): MobileNavigationPlacement =
  MobileNavigationPlacement.BOTTOM_BAR

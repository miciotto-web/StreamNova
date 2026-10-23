package com.example.data.streaming.extractors

import java.util.concurrent.CopyOnWriteArrayList
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * Cookie store in memoria condiviso dagli estrattori hoster.
 *
 * Serve ai flussi in cui la pagina padre rilascia un cookie di sessione che
 * l'iframe/player successivo deve presentare (altrimenti risponde 403 o con un
 * gate anti-bot). OkHttp applica il [CookieJar] anche alle richieste derivate da
 * un redirect, quindi la sessione resta continua fra pagina e player.
 *
 * Il matching non è "per host" ma delegato a [Cookie.matches]: dominio (con
 * host-only), percorso, secure e scadenza vengono rispettati come in un browser.
 * I cookie scaduti non vengono mai restituiti e vengono rimossi alla prima
 * occasione utile, senza richiedere una scansione periodica.
 */
internal class InMemoryCookieJar(
  private val now: () -> Long = System::currentTimeMillis
) : CookieJar {

  private val cookies = CopyOnWriteArrayList<Cookie>()

  override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
    val wallClock = now()
    this.cookies.removeAll { it.expiresAt <= wallClock }
    cookies.forEach { incoming ->
      // Sostituisce il cookie con la stessa identità (nome/dominio/percorso/
      // host-only/secure): è il comportamento di un browser, non un accumulo.
      this.cookies.removeAll { it.hasSameIdentityAs(incoming) }
      if (incoming.expiresAt > wallClock) this.cookies += incoming
    }
  }

  override fun loadForRequest(url: HttpUrl): List<Cookie> {
    val wallClock = now()
    val expired = cookies.filter { it.expiresAt <= wallClock }
    if (expired.isNotEmpty()) cookies.removeAll(expired.toSet())
    return cookies.filter { it.matches(url) }
  }

  /** Elimina tutti i cookie (usato dai test per isolare i casi). */
  fun clear() {
    cookies.clear()
  }

  private fun Cookie.hasSameIdentityAs(other: Cookie): Boolean =
    name == other.name &&
      domain == other.domain &&
      path == other.path &&
      secure == other.secure &&
      hostOnly == other.hostOnly
}

package com.example.data.network

import java.io.IOException
import java.net.URL
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Diagnostica dei fallimenti TLS durante l'installazione/refresh dei manifest addon.
 *
 * L'errore `SSLHandshakeException: Chain validation failed` di Conscrypt non
 * distingue le cause: catena non fidata dal sistema, ma anche **orologio del
 * dispositivo errato** (il caso più frequente su Android TV/emulatori, dove lo
 * snapshot ripristina una data vecchia o il sync NTP fallisce). Un certificato
 * ruotato dal server (es. rotazione Cloudflare ~90 giorni) risulta "not yet
 * valid" o "expired" su un orologio indietro/avanti e produce esattamente quel
 * messaggio, mentre gli altri host continuano a funzionare (i loro certificati
 * sono stati emessi molto prima del drift).
 *
 * [translateTlsFailure] intercetta il fallimento e, con una connessione di
 * **solo diagnostica** (trust-all che NON rende fidati i dati: serve unicamente
 * a leggere la catena presentata dal server), confronta la finestra di validità
 * del certificato foglia con l'orologio del dispositivo e produce un messaggio
 * azionabile per l'utente. Se la causa non è TLS o non è rilevabile, il messaggio
 * originale resta invariato.
 */
object TlsDiagnostics {

  private const val DIAG_CONNECT_TIMEOUT_S = 10L
  private const val DIAG_READ_TIMEOUT_S = 10L

  /** true se [failure] (o una delle cause) è un handshake TLS fallito. */
  fun isTlsHandshakeFailure(failure: Throwable): Boolean {
    // La catena delle cause può contenere cicli (anche a due elementi): il set di
    // visitati (identità, Throwable non ridefinisce equals) garantisce la
    // terminazione anche in quel caso.
    val seen = HashSet<Throwable>()
    var current: Throwable? = failure
    while (current != null && seen.add(current)) {
      if (current is SSLHandshakeException) return true
      current = current.cause?.takeIf { it !== current }
    }
    return false
  }

  /**
   * Trasforma un fallimento di rete in un errore con messaggio diagnostico quando
   * la causa è TLS. Restituisce [failure] INVARIATO se non è un fallimento TLS o
   * se la diagnostica non raccoglie abbastanza informazioni; altrimenti una
   * [IOException] con il messaggio azionabile (l'eccezione originale come cause).
   * L'eccezione restituita va sempre rilanciata dal chiamante.
   */
  fun translateTlsFailure(failure: Throwable, url: String): Throwable {
    if (!isTlsHandshakeFailure(failure)) return failure
    val message = describeFailure(url, failure) ?: return failure
    return IOException(message, failure)
  }

  /**
   * Analizza la catena reale del server per spiegare il fallimento TLS.
   * Restituisce `null` se la catena non è leggibile (nessuna diagnosi possibile).
   */
  fun describeFailure(url: String, failure: Throwable): String? {
    val host = runCatching { URL(url).host }.getOrNull()?.takeIf { it.isNotBlank() }
      ?: return null
    val leaf = fetchLeafCertificate(url) ?: return null
    return clockMismatchMessage(
      deviceNowMillis = System.currentTimeMillis(),
      notBefore = leaf.notBefore,
      notAfter = leaf.notAfter,
      host = host
    ) ?: run {
      // Orologio coerente con la finestra di validità: il fallimento è di fiducia.
      val issuer = leaf.issuerX500Principal.name
        .substringAfter("CN=", "")
        .substringBefore(",")
        .takeIf { it.isNotBlank() } ?: leaf.issuerX500Principal.name
      "Il certificato di $host (emittente: $issuer) non è considerato fidato da " +
        "questo dispositivo, ma l'orologio risulta corretto: la catena del server " +
        "potrebbe usare una CA non presente nel sistema Android. " +
        "Aggiorna il sistema o segnala il problema allo sviluppatore dell'addon."
    }
  }

  /**
   * Messaggio di mismatch orologio/certificato, o `null` se l'orologio cade
   * dentro la finestra di validità (funzione pura, testabile).
   */
  fun clockMismatchMessage(
    deviceNowMillis: Long,
    notBefore: Date,
    notAfter: Date,
    host: String
  ): String? = when {
    deviceNowMillis < notBefore.time ->
      "L'orologio del dispositivo è indietro: data attuale ${formatUtc(deviceNowMillis)}, " +
        "il certificato di $host è valido solo dal ${formatUtc(notBefore.time)}. " +
        "Imposta data e ora corrette del dispositivo e riprova."
    deviceNowMillis > notAfter.time ->
      "L'orologio del dispositivo è avanti: data attuale ${formatUtc(deviceNowMillis)}, " +
        "il certificato di $host risulta scaduto il ${formatUtc(notAfter.time)} " +
        "(orario del server). Controlla data e ora del dispositivo."
    else -> null
  }

  /** Formatta un istante in UTC (confronto univoco con le date del certificato). */
  private fun formatUtc(millis: Long): String =
    SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ITALIAN).apply {
      timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date(millis)) + " UTC"

  /**
   * Connessione di diagnostica: recupera il certificato foglia del server con un
   * trust manager che accetta tutto. ATTENZIONE: il client qui creato non è usato
   * per nessun dato applicativo — solo per LEGGERE la catena e spiegare il fallimento.
   */
  private fun fetchLeafCertificate(url: String): X509Certificate? {
    val capturing = CapturingTrustManager()
    val client = runCatching {
      val sslContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<TrustManager>(capturing), SecureRandom())
      }
      OkHttpClient.Builder()
        .sslSocketFactory(sslContext.socketFactory, capturing)
        .hostnameVerifier(HostnameVerifier { _, _ -> true })
        .connectTimeout(DIAG_CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(DIAG_READ_TIMEOUT_S, TimeUnit.SECONDS)
        .build()
    }.getOrNull() ?: return null

    return runCatching {
      val request = Request.Builder().url(url).head().build()
      client.newCall(request).execute().use { response ->
        response.body?.bytes()
      }
      capturing.captured?.firstOrNull()
    }.getOrNull()
  }

  /** Trust manager che registra la catena ricevuta senza validarla (solo diagnostica). */
  private class CapturingTrustManager : X509TrustManager {
    @Volatile
    var captured: Array<X509Certificate>? = null
      private set

    override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) = Unit

    override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {
      captured = chain
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
  }
}

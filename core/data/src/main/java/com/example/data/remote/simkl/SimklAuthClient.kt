package com.example.data.remote.simkl

import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonEncodingException
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.EOFException
import kotlinx.coroutines.delay
import retrofit2.Response

/**
 * Flusso di autorizzazione utilizzabile con il `client_id` configurato.
 *
 * Un'app registrata su SIMKL è **V1 o V2, mai entrambe** (api.simkl.org):
 * un client V1 viene rifiutato dagli endpoint oauth2 con `401 invalid_client`, un client V2
 * viene rifiutato da `/oauth/pin` con `400 unauthorized_client`. Il flusso effettivo
 * viene quindi rilevato una volta e memorizzato (vedi [SimklAuthClient.startLogin]).
 */
enum class SimklAuthFlow { V2_DEVICE, V1_PIN }

/**
 * Prompt mostrato all'utente per autorizzare l'app da un altro dispositivo.
 *
 * [qrPayload] è il contenuto del QR, e dipende da cosa il protocollo ufficiale mette a
 * disposizione:
 *  - AUTH V2 con `verification_uri_complete` → `https://simkl.com/pin?user_code=XXXX-YYYY`:
 *    il codice è già inserito nella pagina, sul telefono basta approvare ([qrPrefillsCode]);
 *  - AUTH V2 senza quel campo, e AUTH V1 PIN → il solo `https://simkl.com/pin`: il codice
 *    va digitato a mano (V1: PIN di 5 caratteri, 15 minuti di validità, polling ogni 5 s).
 *
 * [deviceCode] è la credenziale di polling: non va mai mostrata a schermo né registrata
 * nei log (vedi api.simkl.org, Device authorization V2).
 */
data class SimklLoginPrompt(
  val flow: SimklAuthFlow,
  val userCode: String,
  val verificationUri: String,
  val qrPayload: String,
  val deviceCode: String,
  val expiresAtEpochMs: Long,
  val intervalSeconds: Long
) {
  val isV2: Boolean get() = flow == SimklAuthFlow.V2_DEVICE

  /**
   * Vero **solo** se il QR contiene davvero il codice già inserito, cioè quando SIMKL ha
   * restituito `verification_uri_complete` (solo AUTH V2). La UI decide con questa
   * proprietà — non con il flusso — se dire all'utente "il codice è già inserito" o
   * "inserisci questo codice": se il campo completo manca, il QR è il solo
   * `https://simkl.com/pin` e va digitato il codice.
   */
  val qrPrefillsCode: Boolean get() = qrPayload != verificationUri

  fun secondsLeft(nowMs: Long): Long = ((expiresAtEpochMs - nowMs) / 1000L).coerceAtLeast(0L)
}

/** Token ottenuti al termine del login (o con un refresh). */
data class SimklTokenBundle(
  val flow: SimklAuthFlow,
  val accessToken: String,
  val refreshToken: String? = null,
  val scope: String? = null,
  val expiresAtEpochMs: Long? = null
) {
  /**
   * `media:write` è necessario per scrobble e scritture; i token V1 sono sempre full-access.
   * L'assenza di `media:write` in V2 produce un token che funziona in lettura e prende 403
   * alla prima scrittura: il repository lo segnala esplicitamente all'utente.
   */
  val canWrite: Boolean
    get() = flow == SimklAuthFlow.V1_PIN || scope?.contains("media:write") == true
}

open class SimklAuthException(message: String, cause: Throwable? = null) :
  IllegalStateException(message, cause)

/** Codice scaduto/consumato: serve un nuovo prompt. */
class SimklLoginExpiredException(message: String) : SimklAuthException(message)

/** Base: il collegamento SIMKL non è utilizzabile per come è configurato. */
open class SimklClientConfigException(message: String) : SimklAuthException(message)

/**
 * Nessun Client ID utilizzabile è incluso nella build. È una configurazione dello
 * sviluppatore, non un errore dell'utente né dell'installazione: l'utente non ha nulla da
 * aggiornare, inserire o correggere (vedi `.env.example` e `simkl_client_id_report.md`).
 */
class SimklClientIdMissingException(message: String) : SimklClientConfigException(message)

/**
 * Il server ha rifiutato il client su **entrambi** i flussi: Client ID errato, revocato o non
 * abilitato per OAuth. Non è un problema di versione dell'app, e il messaggio a schermo non
 * deve suggerire un aggiornamento: è un rifiuto lato SIMKL, che può anche essere temporaneo.
 */
class SimklClientRejectedException(message: String) : SimklClientConfigException(message)

/**
 * Risposta 2xx ma inutilizzabile: corpo assente, non JSON, o JSON senza i campi del
 * protocollo. Distinta sia dall'errore di rete sia dalla configurazione: il collegamento al
 * server è avvenuto, è la risposta che non si può usare (né per mostrare un QR né per
 * decidere che il client è stato rifiutato).
 */
class SimklInvalidResponseException(message: String, cause: Throwable? = null) :
  SimklAuthException(message, cause)

/**
 * Il refresh è fallito. [error] è il codice RFC 6749 ricevuto (`invalid_grant`,
 * `invalid_client`, …); `null` se la risposta non era un envelope d'errore.
 * Solo [needsReauthorization] indica un grant morto: gli errori di rete NON passano da qui.
 */
class SimklRefreshException(val error: String?, message: String) : SimklAuthException(message) {
  val needsReauthorization: Boolean
    get() = error == "invalid_grant" || error == "invalid_client" || error == "expired_token"
}

/**
 * Implementazione dei due flussi device ufficiali SIMKL.
 *
 * Nessuna dipendenza da Android: si testa con un `SimklApi` finto o con MockWebServer.
 */
class SimklAuthClient(
  private val api: SimklApi,
  private val now: () -> Long = System::currentTimeMillis,
  private val sleeper: suspend (Long) -> Unit = { delay(it) },
  private val moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
) {
  private val errorAdapter = moshi.adapter(SimklOAuthError::class.java)

  /**
   * Avvia il login: tenta il flusso V2 (migliore, QR già compilato) e, se il client_id
   * non è abilitato per OAuth 2.0, ripiega sul PIN V1. Se il flusso memorizzato è V1
   * si prova prima quello, così il caso comune non paga una richiesta inutile.
   *
   * Una richiesta accettata ma con una risposta inutilizzabile **non** fa ripiegare
   * sull'altro flusso: è lo stesso server che risponde in un modo che non conosciamo, e
   * indovinare con l'altro protocollo nasconderebbe il guasto ([SimklInvalidResponseException]).
   */
  suspend fun startLogin(clientId: String, rememberedFlow: SimklAuthFlow? = null): SimklLoginPrompt {
    val order = if (rememberedFlow == SimklAuthFlow.V1_PIN) {
      listOf(SimklAuthFlow.V1_PIN, SimklAuthFlow.V2_DEVICE)
    } else {
      listOf(SimklAuthFlow.V2_DEVICE, SimklAuthFlow.V1_PIN)
    }
    var v2Error: String? = null
    var v1Error: String? = null
    for (flow in order) {
      when (flow) {
        SimklAuthFlow.V2_DEVICE -> {
          val response = oauthCall { api.requestDeviceAuthorization(clientId, SIMKL_SCOPE_READ_WRITE) }
          val body = response.body()
          if (response.isSuccessful && body != null) {
            return SimklLoginPrompt(
              flow = SimklAuthFlow.V2_DEVICE,
              userCode = requireCode(body.userCode, "user_code"),
              verificationUri = requireCode(body.verificationUri, "verification_uri"),
              // Il codice già inserito rende il QR sufficiente: nessuna digitazione.
              qrPayload = body.verificationUriComplete?.takeIf { it.isNotBlank() } ?: body.verificationUri,
              deviceCode = requireCode(body.deviceCode, "device_code"),
              expiresAtEpochMs = now() + body.expiresIn.coerceAtLeast(1L) * 1000L,
              intervalSeconds = body.interval.coerceAtLeast(1L)
            )
          }
          requireBody(response, body, "codice device")
          v2Error = describeError(response)
        }

        SimklAuthFlow.V1_PIN -> {
          val response = oauthCall { api.requestPin(clientId) }
          val pin = response.body()
          if (response.isSuccessful && pin != null) {
            return SimklLoginPrompt(
              flow = SimklAuthFlow.V1_PIN,
              userCode = requireCode(pin.userCode, "user_code"),
              verificationUri = requireCode(pin.verificationUri, "verification_uri"),
              qrPayload = pin.verificationUri,
              deviceCode = requireCode(pin.deviceCode, "device_code"),
              expiresAtEpochMs = now() + pin.expiresIn.coerceAtLeast(1L) * 1000L,
              intervalSeconds = pin.interval.coerceAtLeast(5L)
            )
          }
          requireBody(response, body = pin, what = "codice PIN")
          v1Error = describeError(response)
        }
      }
    }
    throw SimklClientRejectedException(
      "SIMKL: il client_id configurato non è utilizzabile. " +
        "Device flow V2: ${v2Error ?: "non tentato"}. PIN V1: ${v1Error ?: "non tentato"}. " +
        "Verifica il Client ID nelle impostazioni sviluppatore SIMKL."
    )
  }

  /**
   * Chiamata di protocollo con classificazione dei guasti di *forma*: una 2xx non
   * interpretabile diventa [SimklInvalidResponseException]. Gli errori di rete restano
   * `IOException` (non sono responsabilità di SIMKL) e gli stati non-2xx restano una
   * [Response] da interpretare con il corpo d'errore RFC 6749.
   *
   * `EOFException` rientra tra i guasti di forma perché è quello che Moshi solleva su un
   * corpo vuoto: il caso normale di un 200 senza contenuto. Una connessione davvero caduta
   * arriva qui come `ConnectException`, `SocketException` o `SocketTimeoutException`, che
   * restano errori di rete.
   */
  private suspend fun <T> oauthCall(block: suspend () -> Response<T>): Response<T> = try {
    block()
  } catch (malformed: JsonEncodingException) {
    throw SimklInvalidResponseException("Risposta SIMKL non interpretabile.", malformed)
  } catch (malformed: JsonDataException) {
    throw SimklInvalidResponseException("Risposta SIMKL senza i campi attesi.", malformed)
  } catch (empty: EOFException) {
    throw SimklInvalidResponseException("Risposta SIMKL priva di contenuto.", empty)
  }

  /** 2xx senza corpo: non è un rifiuto del client, è una risposta che non si può usare. */
  private fun <T> requireBody(response: Response<T>, body: T?, what: String) {
    if (response.isSuccessful && body == null) {
      throw SimklInvalidResponseException("Risposta SIMKL priva del $what (HTTP ${response.code()}).")
    }
  }

  /** Campo del protocollo vuoto dentro una 2xx: stessa classificazione del corpo assente. */
  private fun requireCode(value: String?, field: String): String =
    value?.takeIf { it.isNotBlank() }
      ?: throw SimklInvalidResponseException("Risposta SIMKL senza il campo $field.")

  /** Attende l'autorizzazione dell'utente, rispettando `interval` e la scadenza del codice. */
  suspend fun awaitLogin(clientId: String, prompt: SimklLoginPrompt): SimklTokenBundle =
    when (prompt.flow) {
      SimklAuthFlow.V2_DEVICE -> awaitDeviceAuthorization(clientId, prompt)
      SimklAuthFlow.V1_PIN -> awaitPinAuthorization(clientId, prompt)
    }

  /** RFC 8628: `POST /oauth2/token` con `grant_type=device_code`. */
  private suspend fun awaitDeviceAuthorization(clientId: String, prompt: SimklLoginPrompt): SimklTokenBundle {
    var intervalMs = prompt.intervalSeconds.coerceAtLeast(1L) * 1000L
    while (true) {
      if (now() >= prompt.expiresAtEpochMs) {
        throw SimklLoginExpiredException("Codice SIMKL scaduto. Richiedine uno nuovo.")
      }
      sleeper(intervalMs)
      if (now() > prompt.expiresAtEpochMs) {
        throw SimklLoginExpiredException("Codice SIMKL scaduto. Richiedine uno nuovo.")
      }
      val response = oauthCall { api.exchangeDeviceCode(clientId = clientId, deviceCode = prompt.deviceCode) }
      if (response.isSuccessful) {
        val body = response.body()
        val token = body?.accessToken
        if (!token.isNullOrBlank()) {
          return SimklTokenBundle(
            flow = SimklAuthFlow.V2_DEVICE,
            accessToken = token,
            refreshToken = body.refreshToken,
            scope = body.scope,
            expiresAtEpochMs = body.expiresIn?.takeIf { it > 0L }?.let { now() + it * 1000L }
          )
        }
        throw SimklInvalidResponseException("Risposta token SIMKL priva di access_token.")
      }
      // Il body d'errore si legge una sola volta: si parsa qui e si decide sul codice.
      val error = parseError(response.errorBody()?.string())
      when (error?.error) {
        // L'utente non ha ancora approvato: si continua a interrogare allo stesso intervallo.
        "authorization_pending" -> Unit
        // Il server chiede di rallentare: +5 s e si attende (ritentare subito resta rate-limited).
        "slow_down" -> intervalMs += 5_000L
        "expired_token" -> throw SimklLoginExpiredException("Codice SIMKL scaduto. Richiedine uno nuovo.")
        else -> throw SimklAuthException(
          error?.errorDescription ?: "Autorizzazione SIMKL non riuscita (${response.code()})."
        )
      }
    }
  }

  /** PIN V1: `GET /oauth/pin/{user_code}` con le due forme di risposta documentate. */
  private suspend fun awaitPinAuthorization(clientId: String, prompt: SimklLoginPrompt): SimklTokenBundle {
    val intervalMs = prompt.intervalSeconds.coerceAtLeast(5L) * 1000L
    while (true) {
      if (now() >= prompt.expiresAtEpochMs) {
        throw SimklLoginExpiredException("Codice SIMKL scaduto. Richiedine uno nuovo.")
      }
      sleeper(intervalMs)
      if (now() > prompt.expiresAtEpochMs) {
        throw SimklLoginExpiredException("Codice SIMKL scaduto. Richiedine uno nuovo.")
      }
      val response = oauthCall { api.pollPin(prompt.userCode, clientId) }
      val body = response.body()
      val token = body?.accessToken
      if (!token.isNullOrBlank()) {
        return SimklTokenBundle(
          flow = SimklAuthFlow.V1_PIN,
          accessToken = token,
          refreshToken = null,
          scope = null,
          expiresAtEpochMs = null
        )
      }
      // Codice consumato/scaduto: SIMKL risponde con la forma di creazione di un NUOVO PIN
      // (`device_code` presente o `user_code` diverso). Si smette di interrogare.
      if (body?.deviceCode != null ||
        (!body?.userCode.isNullOrBlank() && body.userCode != prompt.userCode)
      ) {
        throw SimklLoginExpiredException("Codice SIMKL non più valido. Richiedine uno nuovo.")
      }
      if (body?.result.equals("KO", ignoreCase = true)) {
        if (body?.message?.contains("pending", ignoreCase = true) == true) continue
        throw SimklAuthException(body?.message ?: "Autorizzazione SIMKL rifiutata.")
      }
      if (!response.isSuccessful) {
        throw SimklAuthException("Autorizzazione SIMKL non riuscita (${response.code()}).")
      }
    }
  }

  /**
   * Refresh del grant V2. Il refresh token è **non rotante**: se la risposta non ne
   * riporta uno nuovo vale ancora quello inviato.
   */
  suspend fun refresh(clientId: String, refreshToken: String): SimklTokenBundle {
    // Una risposta illeggibile è un guasto transitorio, non un grant morto: resta
    // `needsReauthorization = false`, così il repository riprova più tardi invece di
    // chiedere all'utente di ricollegare l'account.
    val response = try {
      oauthCall { api.refreshAccessToken(clientId = clientId, refreshToken = refreshToken) }
    } catch (invalid: SimklInvalidResponseException) {
      throw SimklRefreshException(null, invalid.message ?: "Risposta di refresh SIMKL non interpretabile.")
    }
    if (!response.isSuccessful) {
      val error = parseError(response.errorBody()?.string())
      throw SimklRefreshException(
        error?.error,
        error?.errorDescription ?: "Refresh SIMKL non riuscito (${response.code()})."
      )
    }
    val body = response.body()
    val token = body?.accessToken?.takeIf { it.isNotBlank() }
      ?: throw SimklRefreshException(null, "Risposta di refresh SIMKL priva di access_token.")
    return SimklTokenBundle(
      flow = SimklAuthFlow.V2_DEVICE,
      accessToken = token,
      refreshToken = body.refreshToken?.takeIf { it.isNotBlank() } ?: refreshToken,
      scope = body.scope,
      expiresAtEpochMs = body.expiresIn?.takeIf { it > 0L }?.let { now() + it * 1000L }
    )
  }

  /**
   * Revoca best-effort del grant V2 (RFC 7009). `POST /oauth2/revoke` risponde **sempre**
   * 200, anche per token inesistenti: il valore di ritorno non è una prova di revoca.
   */
  suspend fun revoke(clientId: String, token: String): Boolean =
    runCatching { api.revokeToken(clientId, token).isSuccessful }.getOrDefault(false)

  internal fun parseError(body: String?): SimklOAuthError? {
    if (body.isNullOrBlank()) return null
    return runCatching { errorAdapter.fromJson(body) }.getOrNull()
  }

  private fun describeError(response: Response<*>): String {
    val error = parseError(runCatching { response.errorBody()?.string() }.getOrNull())
    val detail = error?.error ?: error?.errorDescription
    return if (detail.isNullOrBlank()) "HTTP ${response.code()}" else "HTTP ${response.code()} ($detail)"
  }
}

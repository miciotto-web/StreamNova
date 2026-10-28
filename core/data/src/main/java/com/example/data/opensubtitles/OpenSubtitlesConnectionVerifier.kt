package com.example.data.opensubtitles

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Tipologia di fallimento della verifica della connessione a OpenSubtitles. */
enum class OpenSubtitlesVerifyError {
  /** Il servizio rifiuta la chiave (HTTP 401/403/406): chiave mancante o non valida. */
  INVALID_KEY,

  /** Il servizio ha risposto con un errore HTTP non imputabile alla chiave. */
  HTTP,

  /** Errore di rete (.timeout, DNS offline, …): la chiave non è stata né confermata né rifiutata. */
  NETWORK
}

/** Esito della verifica della connessione a OpenSubtitles. */
sealed interface OpenSubtitlesConnectionCheck {
  /** HTTP 200: la chiave è accettata dal servizio ufficiale. */
  object Valid : OpenSubtitlesConnectionCheck

  /** Nessuna chiave configurata: nessuna richiesta inviata. */
  object NotConfigured : OpenSubtitlesConnectionCheck

  /** Verifica fallita: [error] classifica il motivo senza esporre la chiave. */
  data class Failed(val error: OpenSubtitlesVerifyError, val httpCode: Int? = null) :
    OpenSubtitlesConnectionCheck
}

/**
 * Verifica che la chiave API OpenSubtitles salvata sia accettata dal servizio ufficiale.
 *
 * Riutilizza l'endpoint di ricerca già implementato (`GET /api/v1/subtitles`) con una
 * query minima: HTTP 200 = chiave valida, HTTP 401/403/406 = chiave rifiutata
 * (406 è la risposta di OpenSubtitles a una richiesta senza Api-Key o malformata),
 * qualsiasi altro HTTP = problema lato servizio, eccezione = problema di rete.
 * Nessun nuovo endpoint viene inventato e il corpo della risposta viene scartato.
 */
open class OpenSubtitlesConnectionVerifier(
  private val api: OpenSubtitlesApi = OpenSubtitlesApiClient.api,
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

  /**
   * @param currentApiKey chiave corrente in memoria (quella che userebbe il client).
   */
  open suspend fun check(currentApiKey: String?): OpenSubtitlesConnectionCheck =
    withContext(ioDispatcher) {
      val key = currentApiKey?.trim().orEmpty()
      if (key.isEmpty()) return@withContext OpenSubtitlesConnectionCheck.NotConfigured
      try {
        val response = api.searchSubtitles(query = VERIFICATION_QUERY, languages = "en")
        when {
          response.isSuccessful -> OpenSubtitlesConnectionCheck.Valid
          response.code() in REJECTED_KEY_CODES ->
            OpenSubtitlesConnectionCheck.Failed(OpenSubtitlesVerifyError.INVALID_KEY, response.code())
          else ->
            OpenSubtitlesConnectionCheck.Failed(OpenSubtitlesVerifyError.HTTP, response.code())
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        OpenSubtitlesConnectionCheck.Failed(OpenSubtitlesVerifyError.NETWORK)
      }
    }

  companion object {
    /** Query minima: il corpo della risposta non viene letto, conta solo lo status. */
    private const val VERIFICATION_QUERY = "test"

    /** Codici con cui OpenSubtitles rifiuta una chiave assente o invalida. */
    private val REJECTED_KEY_CODES = setOf(401, 403, 406)
  }
}

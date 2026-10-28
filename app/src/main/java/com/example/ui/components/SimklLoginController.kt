package com.example.ui.components

import android.util.Log
import com.example.data.remote.simkl.SimklAccountState
import com.example.data.remote.simkl.SimklAuthException
import com.example.data.remote.simkl.SimklClientConfigException
import com.example.data.remote.simkl.SimklClientIdMissingException
import com.example.data.remote.simkl.SimklClientRejectedException
import com.example.data.remote.simkl.SimklInvalidResponseException
import com.example.data.remote.simkl.SimklLoginExpiredException
import com.example.data.remote.simkl.SimklLoginPrompt
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Messaggio mostrato mentre il codice temporaneo viene richiesto a SIMKL. */
internal const val SIMKL_REQUESTING_CODE_MESSAGE = "Richiesta del codice di accesso…"

/** Attesa dell'approvazione sul telefono (testo del riferimento di interfaccia). */
internal const val SIMKL_WAITING_APPROVAL_MESSAGE = "In attesa dell'approvazione di Simkl…"

/**
 * Nessuna configurazione applicativa utilizzabile: il Client ID è una configurazione
 * dello sviluppatore (vedi `.env.example` / `local.properties`) che la build deve
 * contenere. All'utente non si chiede nulla di tecnico e non si dice **"aggiorna
 * l'app"**: la versione installata non è il problema, e un aggiornamento senza quella
 * configurazione riporterebbe lo stesso messaggio.
 */
internal const val SIMKL_NOT_AVAILABLE_MESSAGE =
  "Il collegamento a SIMKL non è disponibile in questa build di StreamNova."

/**
 * SIMKL ha rifiutato il collegamento per entrambi i flussi. Non è la versione dell'app
 * e non è nemmeno detto che sia definitivo (un 5xx del server passa di qui): si può
 * riprovare.
 */
internal const val SIMKL_CLIENT_REJECTED_MESSAGE =
  "SIMKL non ha accettato la richiesta di accesso. Riprova tra qualche minuto."

/** Errore di rete: la richiesta non è nemmeno arrivata a SIMKL. */
internal const val SIMKL_NETWORK_ERROR_MESSAGE =
  "Connessione a SIMKL non riuscita. Controlla la rete e riprova."

/** Risposta 2xx senza i dati del protocollo: né rete né configurazione. */
internal const val SIMKL_INVALID_RESPONSE_MESSAGE =
  "SIMKL ha risposto in modo inatteso. Riprova tra qualche minuto."

/** Guasto non classificato: non lo si dichiara "errore di rete" senza esserne certi. */
internal const val SIMKL_GENERIC_ERROR_MESSAGE =
  "Collegamento a SIMKL non riuscito. Riprova."

/**
 * Stato del pannello di login SIMKL. [visible] è indipendente da [prompt]: durante la
 * richiesta di un nuovo codice (`Nuovo codice`) il pannello resta aperto con il codice
 * precedente già scaduto, invece di sparire e ricomparire.
 */
data class SimklLoginUiState(
  val visible: Boolean = false,
  val prompt: SimklLoginPrompt? = null,
  val waiting: Boolean = false,
  val statusMessage: String? = null,
  val isError: Boolean = false,
  val canRetry: Boolean = false,
  /** Valorizzato solo al successo: il pannello si chiude e la UI annuncia l'esito. */
  val account: SimklAccountState? = null
)

/**
 * Ciclo di vita del login SIMKL (device flow V2 con ripiego PIN V1).
 *
 * Regole che questo oggetto garantisce, e che i test verificano:
 *  - **un solo ciclo di polling per volta**: [start] annulla il ciclo precedente, quindi
 *    un secondo tocco o "Nuovo codice" non affianca due polling;
 *  - il polling **finisce** per approvazione, annullamento ([cancel]) o scadenza del
 *    codice, e non viene mai riavviato da solo;
 *  - dopo un errore il pannello resta aperto con l'offerta di un nuovo codice;
 *  - la sincronizzazione post-login passa da [onAuthorized] e **un suo errore non
 *    trasforma un login riuscito in un login fallito**.
 *
 * Non registra mai nei log il codice utente, il device code o i token: dei fallimenti
 * finiscono in Logcat solo la classe dell'eccezione e il messaggio.
 */
class SimklLoginController(
  private val requestPrompt: suspend () -> SimklLoginPrompt,
  private val awaitAuthorization: suspend (SimklLoginPrompt) -> SimklAccountState,
  private val onAuthorized: suspend (SimklAccountState) -> Unit = {},
  private val scope: CoroutineScope
) {
  private val _state = MutableStateFlow(SimklLoginUiState())
  val state: StateFlow<SimklLoginUiState> = _state.asStateFlow()

  private var job: Job? = null

  /** Apre il pannello e chiede un codice nuovo. Usato sia dal primo tocco sia da "Nuovo codice". */
  fun start() {
    job?.cancel()
    _state.value = SimklLoginUiState(
      visible = true,
      waiting = true,
      statusMessage = SIMKL_REQUESTING_CODE_MESSAGE
    )
    job = scope.launch {
      val prompt = try {
        requestPrompt()
      } catch (cancellation: CancellationException) {
        throw cancellation
      } catch (error: Throwable) {
        // Nessun codice richiesto (client_id assente o non abilitato, rete giù): il
        // pannello resta aperto e spiega il motivo invece di restare muto.
        fail(null, error)
        return@launch
      }
      _state.value = SimklLoginUiState(visible = true, prompt = prompt, waiting = true)
      val account = try {
        awaitAuthorization(prompt)
      } catch (cancellation: CancellationException) {
        throw cancellation
      } catch (error: Throwable) {
        // Il codice resta a schermo: è ancora quello che l'utente sta autorizzando,
        // e se è scaduto il pannello offre "Nuovo codice".
        fail(prompt, error)
        return@launch
      }
      _state.value = SimklLoginUiState(account = account)
      runCatching { onAuthorized(account) }.onFailure { error ->
        Log.w(
          TAG,
          "Sincronizzazione SIMKL dopo il login non riuscita: ${error.javaClass.simpleName}"
        )
      }
    }
  }

  /** Richiede un nuovo codice senza chiudere il pannello. */
  fun retry() = start()

  /** Chiude il pannello e ferma il polling (pulsante Annulla, BACK, uscita dalla schermata). */
  fun cancel() {
    job?.cancel()
    job = null
    _state.value = SimklLoginUiState()
  }

  /** Consuma l'esito del login riuscito, così l'avviso non si ripete a ogni ricomposizione. */
  fun clearResult() {
    if (_state.value.account != null) _state.value = SimklLoginUiState()
  }

  private fun fail(prompt: SimklLoginPrompt?, error: Throwable) {
    Log.w(TAG, "Login SIMKL non riuscito: ${error.javaClass.simpleName}: ${error.message}")
    _state.value = SimklLoginUiState(
      visible = true,
      prompt = prompt,
      waiting = false,
      isError = true,
      canRetry = true,
      statusMessage = simklLoginErrorMessage(error)
    )
  }

  private companion object {
    const val TAG = "SimklLogin"
  }
}

/**
 * Traduce l'eccezione nel messaggio per l'utente, senza gergo di configurazione e senza
 * attribuire alla versione dell'app guasti che non le appartengono. I casi sono tenuti
 * distinti perché richiedono azioni diverse: la configurazione di build manca (non si
 * risolve dall'app), SIMKL ha rifiutato il client (si può riprovare), la rete è caduta
 * (si controlla la connessione), la risposta era inutilizzabile, il codice è scaduto.
 */
internal fun simklLoginErrorMessage(error: Throwable): String = when (error) {
  is SimklClientIdMissingException -> SIMKL_NOT_AVAILABLE_MESSAGE
  is SimklClientRejectedException -> SIMKL_CLIENT_REJECTED_MESSAGE
  is SimklInvalidResponseException -> SIMKL_INVALID_RESPONSE_MESSAGE
  is SimklLoginExpiredException ->
    error.message ?: "Il codice è scaduto. Richiedine uno nuovo."
  // Ripiego per la configurazione non utilizzabile in generale: mai il testo tecnico.
  is SimklClientConfigException -> SIMKL_NOT_AVAILABLE_MESSAGE
  // Errori di protocollo già scritti per l'utente (autorizzazione rifiutata, refresh…).
  is SimklAuthException -> error.message ?: SIMKL_NOT_AVAILABLE_MESSAGE
  is IOException -> SIMKL_NETWORK_ERROR_MESSAGE
  else -> SIMKL_GENERIC_ERROR_MESSAGE
}

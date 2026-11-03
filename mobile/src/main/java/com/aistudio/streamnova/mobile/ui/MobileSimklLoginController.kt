package com.aistudio.streamnova.mobile.ui

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

/** Motivo tipizzato di un login SIMKL fallito; il testo vive nelle risorse localizzate. */
enum class MobileSimklLoginError {
  NOT_AVAILABLE,
  CLIENT_REJECTED,
  NETWORK,
  INVALID_RESPONSE,
  EXPIRED,
  AUTH,
  GENERIC
}

/**
 * Stato del pannello di login SIMKL mobile.
 *
 * [requesting] distingue la richiesta del codice dall'attesa dell'approvazione:
 * la UI mostra due messaggi diversi, come la TV.
 */
data class MobileSimklLoginUiState(
  val visible: Boolean = false,
  val prompt: SimklLoginPrompt? = null,
  val waiting: Boolean = false,
  val requesting: Boolean = false,
  val error: MobileSimklLoginError? = null,
  val canRetry: Boolean = false,
  /** Valorizzato solo al successo: la schermata lo consuma e chiude il pannello. */
  val account: SimklAccountState? = null
)

/**
 * Ciclo di vita del login SIMKL sul client condiviso.
 *
 * Stesse garanzie del controller TV:
 *  - un solo polling per volta;
 *  - il ciclo termina per approvazione, annullamento o errore e non riparte da solo;
 *  - dopo un errore il pannello resta aperto e offre un nuovo codice;
 *  - la sincronizzazione post-login non può trasformare un login riuscito in un errore.
 *
 * Il controller non conosce stringhe: espone [MobileSimklLoginError], così ogni
 * lingua dell'app può mostrare il proprio messaggio.
 */
class MobileSimklLoginController(
  private val requestPrompt: suspend () -> SimklLoginPrompt,
  private val awaitAuthorization: suspend (SimklLoginPrompt) -> SimklAccountState,
  private val onAuthorized: suspend (SimklAccountState) -> Unit = {},
  private val scope: CoroutineScope
) {
  private val _state = MutableStateFlow(MobileSimklLoginUiState())
  val state: StateFlow<MobileSimklLoginUiState> = _state.asStateFlow()

  private var job: Job? = null

  fun start() {
    job?.cancel()
    _state.value = MobileSimklLoginUiState(visible = true, waiting = true, requesting = true)
    job = scope.launch {
      val prompt = try {
        requestPrompt()
      } catch (cancellation: CancellationException) {
        throw cancellation
      } catch (error: Throwable) {
        fail(null, error)
        return@launch
      }
      _state.value = MobileSimklLoginUiState(
        visible = true,
        prompt = prompt,
        waiting = true,
        requesting = false
      )
      val account = try {
        awaitAuthorization(prompt)
      } catch (cancellation: CancellationException) {
        throw cancellation
      } catch (error: Throwable) {
        fail(prompt, error)
        return@launch
      }
      _state.value = MobileSimklLoginUiState(account = account)
      runCatching { onAuthorized(account) }
    }
  }

  fun retry() = start()

  fun cancel() {
    job?.cancel()
    job = null
    _state.value = MobileSimklLoginUiState()
  }

  fun clearResult() {
    if (_state.value.account != null) _state.value = MobileSimklLoginUiState()
  }

  private fun fail(prompt: SimklLoginPrompt?, error: Throwable) {
    _state.value = MobileSimklLoginUiState(
      visible = true,
      prompt = prompt,
      waiting = false,
      requesting = false,
      error = simklLoginErrorOf(error),
      canRetry = true
    )
  }
}

/** Traduce l'eccezione del client SIMKL in un motivo localizzabile. */
internal fun simklLoginErrorOf(error: Throwable): MobileSimklLoginError = when (error) {
  is SimklClientIdMissingException -> MobileSimklLoginError.NOT_AVAILABLE
  is SimklClientRejectedException -> MobileSimklLoginError.CLIENT_REJECTED
  is SimklInvalidResponseException -> MobileSimklLoginError.INVALID_RESPONSE
  is SimklLoginExpiredException -> MobileSimklLoginError.EXPIRED
  is SimklClientConfigException -> MobileSimklLoginError.NOT_AVAILABLE
  is SimklAuthException -> MobileSimklLoginError.AUTH
  is IOException -> MobileSimklLoginError.NETWORK
  else -> MobileSimklLoginError.GENERIC
}

package com.aistudio.streamnova.mobile.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.prefs.AppSettingsRepository
import com.example.data.repository.StremioCatalogRepository
import com.example.data.stremio.InstalledAddon
import com.example.data.stremio.StremioAddonRepository
import com.example.data.stremio.provider.CatalogKey
import com.example.data.stremio.provider.ProviderBinding
import com.example.data.stremio.provider.ProviderBindingConfirmation
import com.example.data.stremio.provider.ProviderBindingProposalView
import com.example.data.stremio.provider.UserProviderBindingStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Sorgente di gestione addon del ViewModel.
 *
 * L'implementazione di default è il repository condiviso della TV; i test JVM
 * iniettano un fake, così la logica del ViewModel è verificabile senza rete,
 * senza DataStore e senza Android.
 */
interface MobileAddonGateway {

  /** Installazioni correnti, flusso reattivo del repository condiviso. */
  val addons: StateFlow<List<InstalledAddon>>

  /** Binding utente persistiti (JSON, stessa chiave della TV). */
  val userBindingsJson: StateFlow<String>

  /** Installa/aggiorna dal rawUrl: fetch e validazione del manifest. */
  suspend fun install(rawUrl: String): InstalledAddon

  /** Rimuove l'installazione indicata da [installId] (baseUrl normalizzato). */
  fun remove(installId: String)

  /** Abilita/disabilita l'installazione indicata da [installId]. */
  fun setEnabled(installId: String, enabled: Boolean)

  /** Binding dichiarato per la chiave, con precedenza USER → REGISTRY. */
  fun existingBinding(key: CatalogKey): ProviderBinding?

  /** Sostituisce i binding utente persistiti. */
  suspend fun persistUserBindings(bindings: List<ProviderBinding>)
}

/** Implementazione reale: gli stessi object condivisi con la TV. */
object DefaultMobileAddonGateway : MobileAddonGateway {

  override val addons: StateFlow<List<InstalledAddon>>
    get() = StremioAddonRepository.addons

  override val userBindingsJson: StateFlow<String>
    get() = AppSettingsRepository.userProviderBindingsJson

  override suspend fun install(rawUrl: String): InstalledAddon =
    StremioAddonRepository.installAddon(rawUrl)

  override fun remove(installId: String) {
    StremioAddonRepository.removeAddon(installId)
  }

  override fun setEnabled(installId: String, enabled: Boolean) {
    StremioAddonRepository.setAddonEnabled(installId, enabled)
  }

  override fun existingBinding(key: CatalogKey): ProviderBinding? =
    StremioCatalogRepository.resolver().resolve(key)

  override suspend fun persistUserBindings(bindings: List<ProviderBinding>) {
    UserProviderBindingStore.persist(bindings)
  }
}

/** Stato completo della schermata Add-on. */
data class MobileAddonsUiState(
  val addons: List<InstalledAddon> = emptyList(),
  val userBindings: List<ProviderBinding> = emptyList(),
  val isInstalling: Boolean = false,
  /** installId dell'addon in aggiornamento, `null` se nessuno. */
  val updatingInstallId: String? = null,
  val feedback: MobileAddonFeedback? = null
)

/**
 * ViewModel della schermata Add-on mobile.
 *
 * Tutte le azioni passano dal [MobileAddonGateway] (default: repository
 * condiviso). Come la TV, ogni operazione reale produce un esito tipizzato:
 * nessuno stato di successo viene simulato e gli errori restano quelli del
 * repository, classificati per la localizzazione.
 *
 * La chiave di gestione è sempre [InstalledAddon.installId] (baseUrl completo di
 * configurazione), mai il manifest id: due configurazioni dello stesso addon
 * restano indipendenti.
 */
class MobileAddonsViewModel(
  private val gateway: MobileAddonGateway = DefaultMobileAddonGateway
) : ViewModel() {

  private val _isInstalling = MutableStateFlow(false)
  private val _updatingInstallId = MutableStateFlow<String?>(null)
  private val _feedback = MutableStateFlow<MobileAddonFeedback?>(null)

  private val userBindings: StateFlow<List<ProviderBinding>> = gateway.userBindingsJson
    .map { UserProviderBindingStore.decode(it) }
    .stateIn(
      scope = viewModelScope,
      started = SharingStarted.Eagerly,
      initialValue = UserProviderBindingStore.decode(gateway.userBindingsJson.value)
    )

  val state: StateFlow<MobileAddonsUiState> = combine(
    gateway.addons,
    userBindings,
    _isInstalling,
    _updatingInstallId,
    _feedback
  ) { addons, bindings, installing, updating, feedback ->
    MobileAddonsUiState(
      addons = addons,
      userBindings = bindings,
      isInstalling = installing,
      updatingInstallId = updating,
      feedback = feedback
    )
  }.stateIn(
    scope = viewModelScope,
    started = SharingStarted.WhileSubscribed(5_000),
    initialValue = MobileAddonsUiState()
  )

  /**
   * Installa un addon dall'URL incollato. Guard anti-doppia installazione come
   * la TV; un URL vuoto non produce alcuna chiamata di rete.
   */
  fun install(rawUrl: String) {
    if (_isInstalling.value) return
    if (rawUrl.isBlank()) {
      _feedback.value = MobileAddonFeedback.Failed(MobileAddonError.INVALID_URL)
      return
    }
    _isInstalling.value = true
    viewModelScope.launch {
      try {
        val addon = gateway.install(rawUrl)
        _feedback.value = MobileAddonFeedback.Installed(addon.manifest.displayTitle)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        _feedback.value = MobileAddonFeedback.Failed(classifyAddonError(e))
      } finally {
        _isInstalling.value = false
      }
    }
  }

  /**
   * Aggiorna il manifest dell'installazione riusando lo stesso baseUrl salvato
   * (capacità reale del repository: sostituzione per `baseUrl`). Se il fetch
   * fallisce, l'installazione esistente resta invariata e viene mostrato l'errore.
   */
  fun update(addon: InstalledAddon) {
    if (_updatingInstallId.value != null) return
    _updatingInstallId.value = addon.installId
    viewModelScope.launch {
      try {
        val refreshed = gateway.install(addon.baseUrl)
        _feedback.value = MobileAddonFeedback.Updated(refreshed.manifest.displayTitle)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        _feedback.value = MobileAddonFeedback.Failed(classifyAddonError(e))
      } finally {
        _updatingInstallId.value = null
      }
    }
  }

  /** Rimuove l'installazione (la conferma è a carico della UI touch). */
  fun remove(addon: InstalledAddon) {
    gateway.remove(addon.installId)
    _feedback.value = MobileAddonFeedback.Removed
  }

  /** Abilita/disabilita l'installazione senza messaggi (come la TV). */
  fun setEnabled(addon: InstalledAddon, enabled: Boolean) {
    gateway.setEnabled(addon.installId, enabled)
  }

  /** Proposte di associazione per la card, ricalcolate dai binding correnti. */
  fun bindingProposals(addon: InstalledAddon): List<ProviderBindingProposalView> =
    addonBindingProposals(addon, gateway::existingBinding)

  /** Conferma la proposta: scrive solo il binding UTENTE, come la TV. */
  fun confirmBinding(key: CatalogKey, providerId: String) {
    val result = ProviderBindingConfirmation.confirm(
      userBindings = userBindings.value,
      key = key,
      providerId = providerId,
      alreadyDeclared = gateway::existingBinding
    )
    if (result.changed) {
      viewModelScope.launch { gateway.persistUserBindings(result.bindings) }
    }
  }

  /** Rimuove il binding UTENTE indicato; la registry non viene toccata. */
  fun removeBinding(key: CatalogKey, providerId: String) {
    val result = ProviderBindingConfirmation.remove(
      userBindings = userBindings.value,
      key = key,
      providerId = providerId
    )
    if (result.changed) {
      viewModelScope.launch { gateway.persistUserBindings(result.bindings) }
    }
  }

  /** Chiude il messaggio di esito (auto-dismiss della UI). */
  fun clearFeedback() {
    _feedback.value = null
  }
}

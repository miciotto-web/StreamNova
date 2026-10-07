package com.example.data.stremio.provider

import com.example.data.stremio.StremioManifest

/**
 * Stato di una proposta rispetto ai binding già dichiarati per la sua chiave.
 *
 * La UI usa questo stato per decidere cosa mostrare: [PROPOSED] ammette la conferma,
 * [USER] e [REGISTRY] mostrano "Associato" e non permettono duplicati.
 */
enum class ProviderBindingProposalStatus {
  /** Nessun binding per la chiave: l'associazione può essere confermata. */
  PROPOSED,

  /** Binding già dichiarato dall'utente: rimovibile, mai sovrascrivibile. */
  USER,

  /** Binding dichiarato dai dati di registry: la UI non lo tocca. */
  REGISTRY
}

/**
 * Proposta euristica pronta per la UI: cosa suggerisce il proposer e a che punto è
 * l'associazione reale.
 *
 * @param proposal suggerimento del proposer: resta una proposta, non un binding.
 * @param status stato rispetto ai binding dichiarati.
 * @param boundProviderId provider già associato alla chiave, `null` se nessuno.
 * @param catalogTitle nome del catalogo dichiarato dal manifest.
 */
data class ProviderBindingProposalView(
  val proposal: ProviderBindingProposal,
  val status: ProviderBindingProposalStatus,
  val boundProviderId: String? = null,
  val catalogTitle: String = proposal.key.normalizedCatalogId
) {
  val key: CatalogKey get() = proposal.key

  val providerId: String get() = proposal.normalizedProviderId

  /** true se la chiave è già associata a questo stesso provider: non duplicabile. */
  val isAlreadyBoundToSameProvider: Boolean
    get() = status != ProviderBindingProposalStatus.PROPOSED && boundProviderId == providerId

  /** true se l'utente può confermare l'associazione proposta. */
  val isConfirmable: Boolean
    get() = !isAlreadyBoundToSameProvider && status != ProviderBindingProposalStatus.USER

  /** true se esiste un binding UTENTE rimovibile su questa chiave. */
  val isRemovable: Boolean
    get() = status == ProviderBindingProposalStatus.USER
}

/** Esito di una modifica alla lista dei binding utente. */
enum class BindingEditOutcome {
  /** Binding creato: la lista restituita va persistita. */
  ADDED,

  /** Il binding identico esisteva già: nessuna scrittura. */
  ALREADY_PRESENT,

  /** Binding utente rimosso: la lista restituita va persistita. */
  REMOVED,

  /** Nessun binding utente da rimuovere: nessuna scrittura. */
  NOT_PRESENT
}

/**
 * Esito di una modifica: la nuova lista dei binding UTENTE e l'esito.
 * La lista è restituita invece che modificata in place perché il chiamante decide
 * quando scriverla.
 */
data class BindingEditResult(
  val bindings: List<ProviderBinding>,
  val outcome: BindingEditOutcome
) {
  /** true se la lista va effettivamente persistita. */
  val changed: Boolean
    get() = outcome == BindingEditOutcome.ADDED || outcome == BindingEditOutcome.REMOVED
}

/**
 * Logica pura di CONFERMA delle proposte euristiche.
 *
 * SEPARAZIONE RIGIDA
 * - [proposalsFor] usa SOLO [HeuristicProviderCatalogProposer] e non scrive nulla:
 *   una proposta resta sempre [ProviderBindingProposal].
 * - [confirm] e [remove] operano su una lista di binding UTENTE e restituiscono la
 *   nuova lista: la persistenza resta al chiamante
 *   ([UserProviderBindingStore.persist]).
 *
 * Nulla qui deduce un binding: un catalogo senza proposte resta semplicemente un
 * catalogo utilizzabile, senza binding e senza vincoli.
 */
object ProviderBindingConfirmation {

  /**
   * Trasforma le proposte del proposer in righe pronte per la UI, arricchite dallo stato
   * reale della chiave.
   *
   * @param manifest manifest dell'addon da cui ricavare i titoli dei cataloghi.
   * @param providers provider dell'app, come vista dati con i loro alias dichiarativi.
   * @param existingBinding binding già applicabile per una chiave, `null` se assente.
   *   Va alimentata con il resolver, così la UI concorda con la precedenza
   *   USER > REGISTRY già usata dal caricamento dei cataloghi.
   * @param proposer proposer da usare: di default quello euristico, mai applicato.
   */
  fun proposalsFor(
    manifest: StremioManifest,
    providers: List<ProviderAliasProvider>,
    existingBinding: (CatalogKey) -> ProviderBinding?,
    proposer: ProviderBindingProposer = HeuristicProviderCatalogProposer()
  ): List<ProviderBindingProposalView> =
    proposer.propose(manifest, providers).map { proposal ->
      val bound = existingBinding(proposal.key)
      ProviderBindingProposalView(
        proposal = proposal,
        status = when (bound?.source) {
          BindingSource.USER -> ProviderBindingProposalStatus.USER
          BindingSource.REGISTRY -> ProviderBindingProposalStatus.REGISTRY
          null -> ProviderBindingProposalStatus.PROPOSED
        },
        boundProviderId = bound?.normalizedProviderId,
        catalogTitle = manifest.findCatalog(proposal.key.stremioType, proposal.key.catalogId)
          ?.displayTitle
          ?: proposal.key.normalizedCatalogId
      )
    }

  /**
   * Conferma esplicita dell'utente: crea il binding
   * `USER` / [BindingConfidence.CONFIRMED] per chiave + provider.
   *
   * @param alreadyDeclared binding dichiarato che gia' si applica alla chiave (di
   *   solito quello risolto dal resolver). Se dichiara lo STESSO provider, la conferma
   *   non duplica nulla: il binding di registry resta l'unico in essere.
   *
   * Idempotente sul quadruplo `addonManifestId + type + catalogId + providerId`:
   * confermare due volte la stessa associazione restituisce
   * [BindingEditOutcome.ALREADY_PRESENT] e la lista invariata, quindi non può nascere
   * un duplicato.
   */
  fun confirm(
    userBindings: List<ProviderBinding>,
    key: CatalogKey,
    providerId: String,
    alreadyDeclared: (CatalogKey) -> ProviderBinding? = { null }
  ): BindingEditResult {
    val normalizedProvider = providerId.trim().lowercase()
    require(normalizedProvider.isNotEmpty()) { "providerId non può essere vuoto" }
    if (findUserBinding(userBindings, key, normalizedProvider) != null) {
      return BindingEditResult(userBindings, BindingEditOutcome.ALREADY_PRESENT)
    }
    // Chiave gia' dichiarata in registry per lo stesso provider: confermare non
    // aggiunge nulla, altrimenti il resolver restituirebbe comunque il REGISTRY.
    if (alreadyDeclared(key)?.normalizedProviderId == normalizedProvider) {
      return BindingEditResult(userBindings, BindingEditOutcome.ALREADY_PRESENT)
    }
    val binding = ProviderBinding(
      key = key,
      providerId = normalizedProvider,
      source = BindingSource.USER,
      confidence = BindingConfidence.CONFIRMED
    )
    return BindingEditResult(userBindings + binding, BindingEditOutcome.ADDED)
  }

  /**
   * Rimuove SOLO il binding UTENTE indicato. La registry non è mai toccata: se la
   * chiave ha anche un binding di registry, torna semplicemente a valere quello.
   */
  fun remove(
    userBindings: List<ProviderBinding>,
    key: CatalogKey,
    providerId: String
  ): BindingEditResult {
    val normalizedProvider = providerId.trim().lowercase()
    val remaining = userBindings.filterNot {
      it.key.stableId == key.stableId && it.normalizedProviderId == normalizedProvider
    }
    return if (remaining.size == userBindings.size) {
      BindingEditResult(userBindings, BindingEditOutcome.NOT_PRESENT)
    } else {
      BindingEditResult(remaining, BindingEditOutcome.REMOVED)
    }
  }

  /** Binding utente per chiave + provider, `null` se assente. */
  fun findUserBinding(
    userBindings: List<ProviderBinding>,
    key: CatalogKey,
    providerId: String
  ): ProviderBinding? {
    val normalizedProvider = providerId.trim().lowercase()
    return userBindings.firstOrNull {
      it.key.stableId == key.stableId && it.normalizedProviderId == normalizedProvider
    }
  }
}

package com.aistudio.streamnova.mobile.ui

import androidx.annotation.StringRes
import com.aistudio.streamnova.mobile.R
import com.example.data.prefs.StreamingEngineMode
import com.example.data.stremio.InstalledAddon
import com.example.data.stremio.StremioCatalogDefinition
import com.example.data.stremio.provider.CatalogKey
import com.example.data.stremio.provider.DefaultProviderCatalogResolver
import com.example.data.stremio.provider.ProviderBinding
import com.example.data.stremio.provider.ProviderBindingConfirmation
import com.example.data.stremio.provider.ProviderBindingProposalView
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.TimeoutCancellationException

/**
 * Famiglie di errore del flusso addon.
 *
 * Il repository condiviso lancia eccezioni con messaggi stabili (verificati dai
 * test core `StremioAddonRepositoryUrlTest`/`StremioTorBoxTest`): la
 * classificazione evita di mostrare messaggi non localizzati e distingue i casi
 * che l'utente può capire (URL errato, HTTP, manifest invalido, timeout, rete).
 */
enum class MobileAddonError {
  INVALID_URL,
  HTTP,
  MANIFEST_INVALID,
  TIMEOUT,
  NETWORK,
  UNKNOWN
}

/** Messaggio localizzato della famiglia di errore (5 lingue). */
@StringRes
fun MobileAddonError.messageRes(): Int = when (this) {
  MobileAddonError.INVALID_URL -> R.string.addons_error_invalid_url
  MobileAddonError.HTTP -> R.string.addons_error_http
  MobileAddonError.MANIFEST_INVALID -> R.string.addons_error_manifest
  MobileAddonError.TIMEOUT -> R.string.addons_error_timeout
  MobileAddonError.NETWORK -> R.string.addons_error_network
  MobileAddonError.UNKNOWN -> R.string.addons_error_unknown
}

/**
 * Esito di un'operazione di gestione addon, tipizzato: la UI lo traduce nelle
 * 5 lingue invece di mostrare il messaggio grezzo dell'eccezione.
 */
sealed interface MobileAddonFeedback {
  /** Installazione riuscita; [addonName] è il nome reale del manifest. */
  data class Installed(val addonName: String) : MobileAddonFeedback

  /** Aggiornamento riuscito (re-fetch del manifest dallo stesso URL). */
  data class Updated(val addonName: String) : MobileAddonFeedback

  /** Rimozione eseguita. */
  data object Removed : MobileAddonFeedback

  /** Operazione fallita; l'errore reale è classificato in [error]. */
  data class Failed(val error: MobileAddonError) : MobileAddonFeedback
}

/**
 * La voce Add-on esiste solo in modalità Debrid: stessa regola TV
 * (`StreamingEngineMode.supportsAddonsSection`), riusata da Home, route e
 * rientro automatico quando la modalità cambia.
 */
fun addonsEntryAvailable(mode: StreamingEngineMode): Boolean = mode.supportsAddonsSection

/**
 * Classifica l'eccezione del repository addon in una famiglia localizzabile.
 *
 * Ordine deliberato:
 *  1. [IllegalArgumentException] → URL non valido (normalizzazione o campo vuoto);
 *  2. [SocketTimeoutException] / [TimeoutCancellationException] → timeout;
 *  3. [IOException] con "HTTP" nel messaggio → errore HTTP del manifest;
 *  4. [IOException] con "Manifest" nel messaggio → manifest vuoto/JSON invalido/
 *     privo di id o name (messaggi reali di `StremioAddonRepository.fetchManifest`);
 *  5. qualsiasi altra [IOException] (DNS, connessione, TLS) → rete;
 *  6. altro → errore sconosciuto.
 *
 * Le cancellazioni di coroutine non vanno classificate: il chiamante le
 * ripropaga.
 */
fun classifyAddonError(throwable: Throwable): MobileAddonError = when {
  throwable is IllegalArgumentException -> MobileAddonError.INVALID_URL
  throwable is SocketTimeoutException -> MobileAddonError.TIMEOUT
  throwable is TimeoutCancellationException -> MobileAddonError.TIMEOUT
  throwable is IOException -> when {
    throwable.message?.contains("HTTP") == true -> MobileAddonError.HTTP
    throwable.message?.contains("Manifest") == true -> MobileAddonError.MANIFEST_INVALID
    else -> MobileAddonError.NETWORK
  }
  else -> MobileAddonError.UNKNOWN
}

/**
 * Etichetta versione della card ("v1.2.3"), o `null` se il manifest non la
 * dichiara: nessuna versione inventata.
 */
fun addonVersionLabel(version: String?): String? =
  version?.trim()?.takeIf { it.isNotBlank() }?.let { "v$it" }

/**
 * Token extra dichiarati dal catalogo (search/genre/skip) per la diagnostica.
 * Sono nomi di protocollo, non testo utente: restano in inglese come in TV.
 */
fun catalogCapabilityTokens(catalog: StremioCatalogDefinition): List<String> = buildList {
  if (catalog.supportsSearch()) add("search")
  if (catalog.supportsGenre()) add("genre")
  if (catalog.supportsSkip()) add("skip")
}

/**
 * Proposte di associazione catalogo → provider per un addon, arricchite dallo
 * stato reale di ogni chiave (USER / REGISTRY / nessuna).
 *
 * Stessa pipeline della TV: proposer euristico (`defaultAliasProviders`) e
 * resolver come unico oracolo degli "associati". Le proposte non scrivono nulla.
 */
fun addonBindingProposals(
  addon: InstalledAddon,
  existingBinding: (CatalogKey) -> ProviderBinding?
): List<ProviderBindingProposalView> =
  ProviderBindingConfirmation.proposalsFor(
    manifest = addon.manifest,
    providers = DefaultProviderCatalogResolver.defaultAliasProviders(),
    existingBinding = existingBinding
  )

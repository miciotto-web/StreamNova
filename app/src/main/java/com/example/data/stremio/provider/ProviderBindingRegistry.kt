package com.example.data.stremio.provider

import android.content.Context
import android.util.Log
import com.example.R
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

/**
 * Registry dichiarativa delle associazioni catalogo → provider.
 *
 * È un file di DATI (`app/src/main/res/raw/stremio_provider_bindings.json`), non codice:
 * aggiungere il supporto a un nuovo addon non richiede modifiche Kotlin né una build.
 *
 * FORMATO
 * ```json
 * {
 *   "version": 1,
 *   "bindings": [
 *     {
 *       "addonManifestId": "com.esempio.addon",
 *       "type": "movie",
 *       "catalogId": "disney_movies",
 *       "providerId": "disney"
 *     }
 *   ]
 * }
 * ```
 *
 * La chiave è SEMPRE `addonManifestId` + `type` + `catalogId`: mai il solo `catalogId`,
 * che è ambiguo tra addon diversi. `type` distingue `movie` da `series`.
 *
 * La chiave è sempre [BindingSource.REGISTRY]: un dato di registry vale meno di una
 * scelta utente, ma resta comunque un dato dichiarato (non un risultato euristico).
 */
data class ProviderBindingRegistryData(
  val version: Int,
  val bindings: List<ProviderBinding> = emptyList()
) : ProviderBindingSource {

  /** Indicizzato per chiave normalizzata: i lookup sono case-insensitive. */
  private val byStableId: Map<String, ProviderBinding> = bindings.associateBy { it.key.stableId }

  override fun find(key: CatalogKey): ProviderBinding? = byStableId[key.stableId]

  override fun all(): List<ProviderBinding> = byStableId.values.toList()

  companion object {
    val EMPTY = ProviderBindingRegistryData(version = ProviderBindingRegistry.CURRENT_VERSION)
  }
}

/** Lettore e parser della registry. */
object ProviderBindingRegistry {

  private const val TAG = "ProviderBindingRegistry"

  /** Versione attesa del formato file. */
  const val CURRENT_VERSION = 1

  private val moshi = Moshi.Builder()
    .add(KotlinJsonAdapterFactory())
    .build()

  private val listType = Types.newParameterizedType(List::class.java, RegistryEntry::class.java)

  private val fileAdapter = moshi.adapter(RegistryFile::class.java)
  private val entriesAdapter = moshi.adapter<List<RegistryEntry>>(listType)

  /** Registry vuota: fallback sicuro quando il file non esiste o non è leggibile. */
  fun empty(): ProviderBindingRegistryData = ProviderBindingRegistryData.EMPTY

  /**
   * Carica la registry da `res/raw`.
   * Un file assente o illeggibile non è un errore: restituisce una registry vuota,
   * così l'app continua a funzionare senza associazioni dichiarate.
   */
  fun load(context: Context): ProviderBindingRegistryData {
    val json = try {
      context.resources.openRawResource(R.raw.stremio_provider_bindings)
        .bufferedReader().use { it.readText() }
    } catch (e: Exception) {
      Log.w(TAG, "Registry non disponibile, uso registry vuota: ${e.message}")
      return empty()
    }
    return parse(json)
  }

  /**
   * Parse del JSON della registry. Righe non conformi vengono scartate senza far
   * fallire il caricamento: una voce incompleta non deve rendere inutilizzabile
   * tutto il file.
   */
  fun parse(json: String?): ProviderBindingRegistryData {
    val clean = json?.trim().orEmpty()
    if (clean.isEmpty() || clean == "[]") return empty()

    // Formato canonico: { "version": 1, "bindings": [ ... ] }.
    val file = try {
      fileAdapter.fromJson(clean)
    } catch (e: Exception) {
      Log.w(TAG, "JSON registry non valido, uso registry vuota: ${e.message}")
      null
    }

    val version = file?.version ?: CURRENT_VERSION
    if (version != CURRENT_VERSION) {
      Log.w(TAG, "Versione registry non supportata: $version (attesa $CURRENT_VERSION)")
      return empty()
    }

    // Compatibilità: se `bindings` manca, prova a leggere una lista piatta.
    val entries = file?.bindings?.takeIf { it.isNotEmpty() } ?: try {
      entriesAdapter.fromJson(clean).orEmpty()
    } catch (e: Exception) {
      Log.w(TAG, "Voci registry non leggibili, uso registry vuota: ${e.message}")
      emptyList()
    }

    val bindings = entries.mapNotNull { it.toBinding() }
    Log.i(TAG, "Registry v$version caricata: ${bindings.size} binding su ${entries.size} voci")
    return ProviderBindingRegistryData(version = version, bindings = bindings)
  }

  /** Converte una voce JSON in binding, scartando quelle incomplete o incoerenti. */
  private fun RegistryEntry.toBinding(): ProviderBinding? {
    val key = CatalogKey.of(addonManifestId, type, catalogId) ?: return null
    val provider = providerId?.trim().orEmpty()
    if (provider.isEmpty()) return null
    return ProviderBinding(
      key = key,
      providerId = provider,
      source = BindingSource.REGISTRY,
      confidence = BindingConfidence.CONFIRMED
    )
  }

  private data class RegistryFile(
    val version: Int? = null,
    val bindings: List<RegistryEntry>? = null
  )

  private data class RegistryEntry(
    val addonManifestId: String? = null,
    val type: String? = null,
    val catalogId: String? = null,
    val providerId: String? = null
  )
}

package com.example.data.stremio.provider

import android.util.Log
import com.example.data.prefs.AppSettingsRepository
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

/**
 * Store persistente delle associazioni dichiarate dall'utente
 * (precedenza massima in [DefaultProviderCatalogResolver]).
 *
 * Persistenza: DataStore Preferences, stessa chiave e stesso pattern di
 * `installed_addons_json` in `AppSettingsRepository`. Il contenuto è una lista JSON
 * di binding `{addonManifestId, type, catalogId, providerId}`.
 *
 * In questa fase non esiste la UI di editing: il store espone già encode/decode e
 * [persist], così l'interfaccia potrà essere aggiunta senza toccare il formato.
 */
object UserProviderBindingStore {

  private const val TAG = "UserProviderBindings"

  /** Versione attesa del formato persistito. */
  const val CURRENT_VERSION = 1

  private val moshi = Moshi.Builder()
    .add(KotlinJsonAdapterFactory())
    .build()

  private val listType = Types.newParameterizedType(List::class.java, StoredBinding::class.java)

  private val fileAdapter = moshi.adapter(StoredFile::class.java)
  private val entriesAdapter = moshi.adapter<List<StoredBinding>>(listType)

  /**
   * Sorgente sincrona che legge lo snapshot corrente da DataStore.
   * Il JSON viene ri-parsato solo quando cambia, così [ProviderBindingSource.find]
   * resta economico anche se chiamato spesso.
   */
  fun source(): ProviderBindingSource = DataStoreUserBindingSource()

  /** Serializza i binding per la persistenza. */
  fun encode(bindings: List<ProviderBinding>): String {
    val stored = bindings
      .distinctBy { it.key.stableId }
      .map {
        StoredBinding(
          addonManifestId = it.key.addonManifestId.trim(),
          type = it.key.stremioType,
          catalogId = it.key.catalogId.trim(),
          providerId = it.providerId.trim()
        )
      }
    return fileAdapter.toJson(StoredFile(version = CURRENT_VERSION, bindings = stored))
  }

  /**
   * Deserializza i binding persistiti. JSON assente, vuoto o non valido → lista vuota:
   * un backup corrotto non deve impedire l'avvio dell'app.
   */
  fun decode(json: String?): List<ProviderBinding> {
    val clean = json?.trim().orEmpty()
    if (clean.isEmpty() || clean == "[]") return emptyList()

    val file = try {
      fileAdapter.fromJson(clean)
    } catch (e: Exception) {
      Log.w(TAG, "JSON binding utente non valido: ${e.message}")
      null
    }
    if (file != null && file.version != CURRENT_VERSION) {
      Log.w(TAG, "Versione binding utente non supportata: ${file.version}")
      return emptyList()
    }

    val entries = file?.bindings?.takeIf { it.isNotEmpty() } ?: try {
      entriesAdapter.fromJson(clean).orEmpty()
    } catch (e: Exception) {
      Log.w(TAG, "Voci binding utente non leggibili: ${e.message}")
      emptyList()
    }

    return entries.mapNotNull { entry ->
      val key = CatalogKey.of(entry.addonManifestId, entry.type, entry.catalogId) ?: return@mapNotNull null
      val providerId = entry.providerId?.trim().orEmpty()
      if (providerId.isEmpty()) return@mapNotNull null
      ProviderBinding(
        key = key,
        providerId = providerId,
        source = BindingSource.USER,
        confidence = BindingConfidence.CONFIRMED
      )
    }
  }

  /** Scrive i binding su DataStore, sostituendo lo snapshot precedente. */
  suspend fun persist(bindings: List<ProviderBinding>) {
    AppSettingsRepository.setUserProviderBindingsJson(encode(bindings))
  }

  /** Rimuove tutti i binding utente. */
  suspend fun clear() {
    AppSettingsRepository.setUserProviderBindingsJson("[]")
  }

  /**
   * Sorgente che riflette lo stato di DataStore.
   * Cache per stringa grezza: il re-parse avviene solo a seguito di una scrittura.
   */
  private class DataStoreUserBindingSource : ProviderBindingSource {
    @Volatile private var cachedJson: String? = null
    @Volatile private var cached: Map<String, ProviderBinding> = emptyMap()

    override fun find(key: CatalogKey): ProviderBinding? = snapshot()[key.stableId]

    override fun all(): List<ProviderBinding> = snapshot().values.toList()

    private fun snapshot(): Map<String, ProviderBinding> {
      val json = AppSettingsRepository.userProviderBindingsJson.value
      if (json != cachedJson) {
        cached = decode(json).associateBy { it.key.stableId }
        cachedJson = json
      }
      return cached
    }
  }

  private data class StoredFile(
    val version: Int? = null,
    val bindings: List<StoredBinding>? = null
  )

  private data class StoredBinding(
    val addonManifestId: String? = null,
    val type: String? = null,
    val catalogId: String? = null,
    val providerId: String? = null
  )
}

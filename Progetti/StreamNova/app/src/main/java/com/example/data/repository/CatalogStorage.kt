package com.example.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.data.model.MediaItem
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

/**
 * Persistenza del catalogo (JSON via Moshi, già presente nel progetto).
 *
 * - Lettura/scrittura SINCRONE (SharedPreferences): il catalogo persistito puo' essere
 *   usato come valore iniziale di _mediaList PRIMA della prima composizione della Home,
 *   eliminando il flicker "11 preloaded -> attesa -> catalogo reale".
 * - Nessuna dipendenza aggiuntiva.
 * - In caso di assenza o errore di deserializzazione restituisce null: il chiamante
 *   cade su getInitialMedia() senza crashare.
 */
class CatalogStorage(context: Context) {

  private val prefs: SharedPreferences =
    context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

  private val moshi: Moshi = Moshi.Builder()
    .add(KotlinJsonAdapterFactory())
    .build()

  private val adapter = moshi.adapter<List<MediaItem>>(
    Types.newParameterizedType(List::class.java, MediaItem::class.java)
  )

  /** Restituisce il catalogo persistito, oppure null se assente/corrotto/non valido. */
  fun loadCatalog(): List<MediaItem>? {
    return try {
      val json = prefs.getString(KEY_CATALOG, null) ?: return null
      val items = adapter.fromJson(json)
      if (!items.isNullOrEmpty()) items else null
    } catch (e: Exception) {
      Log.e("CatalogStorage", "Errore lettura catalogo persistito: ${e.message}", e)
      null
    }
  }

  /** Salva il catalogo. Non lancia mai eccezioni verso il chiamante. */
  fun saveCatalog(items: List<MediaItem>) {
    try {
      val json = adapter.toJson(items)
      prefs.edit().putString(KEY_CATALOG, json).apply()
    } catch (e: Exception) {
      Log.e("CatalogStorage", "Errore scrittura catalogo persistito: ${e.message}", e)
    }
  }

  private companion object {
    const val FILE_NAME = "streamnova_catalog"
    const val KEY_CATALOG = "catalog_json"
  }
}
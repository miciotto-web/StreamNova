package com.example.data.image

import android.content.Context
import java.io.File

/**
 * Configurazione UNICA della cache immagini di Coil usata dall'app.
 *
 * Esiste per un solo motivo verificabile: la disk cache di Coil vive in una directory
 * sotto `cacheDir` e tiene aperto il proprio journal. Chi cancella a mano quella
 * directory (per esempio "Svuota cache" nelle Impostazioni) lascia l'`ImageLoader`
 * senza una directory valida: le scritture successive falliscono in silenzio e le
 * immagini vengono riscaricate dalla rete fino al riavvio del processo.
 *
 * Centralizzare il nome della directory permette a [com.example.StreamNovaApplication]
 * di crearla e a chi pulisce la cache di RISPETTARLA (la svuota tramite l'API di Coil,
 * senza cancellare la cartella).
 */
object ImageCache {

  /**
   * Nome della directory della disk cache, sotto `Context.cacheDir`.
   *
   * È volutamente lo stesso nome usato come default da Coil 2.7.0
   * (`SingletonDiskCache.DIRECTORY = "image_cache"`): così le immagini già in cache
   * sulle installazioni esistenti continuano a essere valide e non restano file
   * orfani nella vecchia directory.
   */
  const val DIRECTORY_NAME = "image_cache"

  /** Directory della disk cache immagini per [context]. */
  fun directory(context: Context): File = File(context.cacheDir, DIRECTORY_NAME)
}

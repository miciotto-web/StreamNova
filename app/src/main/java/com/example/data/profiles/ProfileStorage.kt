package com.example.data.profiles

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Nomi su disco degli archivi che appartengono a un SINGOLO profilo.
 *
 * Un profilo StreamNova è un namespace di dati utente: il database Room (preferiti,
 * progressi, cronologia, «Continua a guardare», episodi visti) e i DataStore che
 * contengono lo stato personale (account SIMKL, selezioni sottotitoli). Le preferenze
 * del televisore (chiavi API, addon, lingua, controllo genitori, Sync) restano invece
 * condivise e NON passano da qui.
 *
 * Il profilo PRINCIPALE usa i nomi storici dell'app: l'aggiornamento a una versione con
 * i profili non rinomina né ricrea alcun file, quindi la libreria esistente dell'utente
 * resta intatta. Ogni profilo aggiuntivo riceve un suffisso stabile ricavato dal proprio
 * id (`p<uuid>`), così i file non possono collidere tra loro.
 *
 * Il segmento usato nei nomi file è sempre sanificato: un id corrotto o manomesso non
 * può uscire dalla directory dell'app (niente `../`).
 */
object ProfileStorage {

  /** Id del profilo creato automaticamente al primo avvio (o all'aggiornamento). */
  const val PRIMARY_PROFILE_ID = "default"

  /** Nome del database Room storico: conservato dal profilo principale. */
  const val LEGACY_DATABASE_NAME = "streamnova_database.db"

  /** DataStore dell'account SIMKL (nome storico del profilo principale). */
  const val SIMKL_STORE_BASE = "simkl_preferences"

  /** DataStore delle selezioni sottotitoli (nome storico del profilo principale). */
  const val SUBTITLE_STORE_BASE = "persistent_subtitles"

  private const val TAG = "ProfileStorage"

  /** Nome del database Room per [profileId]. */
  fun databaseName(profileId: String): String =
    if (profileId == PRIMARY_PROFILE_ID) {
      LEGACY_DATABASE_NAME
    } else {
      "streamnova_database_${fileSegment(profileId)}.db"
    }

  /**
   * Nome del DataStore con base [baseName] per [profileId].
   *
   * Il percorso restituito coincide con quello usato storicamente da
   * `preferencesDataStore(name = baseName)` per il profilo principale.
   */
  fun dataStoreName(baseName: String, profileId: String): String =
    if (profileId == PRIMARY_PROFILE_ID) {
      baseName
    } else {
      "${baseName}_${fileSegment(profileId)}"
    }

  /** File `.preferences_pb` del DataStore [baseName] per [profileId]. */
  fun dataStoreFile(context: Context, baseName: String, profileId: String): File =
    File(
      File(context.applicationContext.filesDir, "datastore"),
      "${dataStoreName(baseName, profileId)}.preferences_pb"
    )

  /**
   * Elimina i dati su disco di un profilo NON principale.
   *
   * Viene chiamata solo dopo che il profilo è stato rimosso dal catalogo e solo per un
   * profilo che non è quello attivo: il database non è quindi aperto da Room e i
   * DataStore non sono in uso. Le eliminazioni sono best-effort: un file già assente o
   * non cancellabile non deve impedire la rimozione del profilo dalla UI.
   */
  fun deleteProfileData(context: Context, profileId: String) {
    if (profileId == PRIMARY_PROFILE_ID) return
    val appContext = context.applicationContext

    val databaseFile = appContext.getDatabasePath(databaseName(profileId))
    deleteQuietly(databaseFile)
    // File accessori di SQLite: senza questi una futura riapertura potrebbe ereditare
    // uno stato incoerente del vecchio profilo.
    deleteQuietly(File(databaseFile.path + "-wal"))
    deleteQuietly(File(databaseFile.path + "-shm"))
    deleteQuietly(File(databaseFile.path + "-journal"))

    deleteQuietly(dataStoreFile(appContext, SIMKL_STORE_BASE, profileId))
    deleteQuietly(dataStoreFile(appContext, SUBTITLE_STORE_BASE, profileId))
  }

  private fun deleteQuietly(file: File) {
    runCatching {
      if (file.exists() && !file.delete()) {
        Log.w(TAG, "Impossibile eliminare ${file.name}")
      }
    }.onFailure { Log.w(TAG, "Errore eliminando ${file.name}: ${it.message}") }
  }

  /**
   * Segmento sicuro per il filesystem. Gli id generati dall'app sono già `[a-z0-9]`,
   * ma la sanificazione difende dai dati corrotti persistiti su disco.
   */
  internal fun fileSegment(profileId: String): String =
    profileId.lowercase()
      .filter { it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' }
      .ifBlank { "profile" }
}

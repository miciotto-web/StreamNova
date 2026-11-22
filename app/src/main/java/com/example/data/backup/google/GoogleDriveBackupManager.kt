package com.example.data.backup.google

import android.util.Log
import com.example.data.backup.BackupEnvelope
import com.example.data.backup.BackupError
import com.example.data.backup.BackupManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "GoogleDriveBackup"

/** Un backup disponibile nel Drive personale dell'utente (cartella `StreamNova Backups`). */
data class DriveBackupEntry(
  val fileId: String,
  val fileName: String,
  val modifiedTimeMs: Long,
  val sizeBytes: Long
)

/** Stato di avanzamento dell'upload su Drive (per la UI). */
sealed interface DriveExportProgress {
  data class PreparingFolder(val accountEmail: String?) : DriveExportProgress
  data class Uploading(val fileName: String) : DriveExportProgress
  data class Done(val entry: DriveBackupEntry, val totalBytes: Long) : DriveExportProgress
}

/** Errore classificato dell'operazione Drive (messaggio già pensato per l'utente). */
class DriveBackupException(message: String) : Exception(message)

/**
 * Orchestrazione dei backup personali su Google Drive per l'account collegato.
 *
 * Architettura (decisione documentata nel report finale):
 * - **Drive personale di ogni utente**: nessun Drive dello sviluppatore, nessun
 *   backend, nessun account centralizzato. I file appartengono all'account Google
 *   collegato dall'utente.
 * - **Cartella normale visibile** `StreamNova Backups` nella root del Drive
 *   dell'utente (NON `appDataFolder`: l'utente deve poter vedere, scaricare ed
 *   eliminare i propri backup dall'interfaccia Google Drive). Con lo scope
 *   `drive.file` l'app vede solo i file/cartelle che ha creato lei: la cartella è
 *   sempre creata da StreamNova alla prima esportazione.
 * - **Riuso senza duplicati**: il folderId viene memorizzato PER ACCOUNT; se l'account
 *   cambia il riferimento non è riutilizzato. Se la cartella è stata eliminata
 *   dall'utente viene ricreata alla prossima esportazione.
 * - **Convergenza con l'import locale**: il JSON scaricato entra nella STESSA
 *   pipeline di validazione ([BackupCodec]) e ripristino ([BackupManager.restore])
 *   dell'import da file.
 */
object GoogleDriveBackupManager {

  private const val MAX_UPLOAD_ATTEMPTS = 3

  /**
   * Individua o crea la cartella dedicata `StreamNova Backups` per l'account corrente.
   *
   * Ordine di risoluzione:
   * 1. folderId in cache valido per l'account corrente → riutilizzato;
   * 2. `files.list` sul Drive (l'app trova SOLO le cartelle create da se stessa) → riutilizzato;
   * 3. assente → creata.
   *
   * Se la cartella memorizzata è stata eliminata dall'utente, il prossimo passo
   * fallisce con 404 e il riferimento viene marcato come missing, ricadendo sui
   * passi 2-3 alla prossima operazione.
   */
  suspend fun ensureBackupsFolder(clientId: String): Result<String> {
    GoogleDriveAuthRepository.cachedBackupsFolderId()?.let { return Result.success(it) }
    val token = GoogleDriveAuthRepository.getValidAccessToken(clientId).fold(
      onSuccess = { it },
      onFailure = { return Result.failure(it) }
    )
    val files = GoogleDriveFilesClient()
    val found = files.findBackupsFolder(token).fold(
      onSuccess = { it },
      onFailure = { e -> return Result.failure(DriveBackupException(describe(e))) }
    )
    if (found != null) {
      GoogleDriveAuthRepository.rememberBackupsFolderId(found)
      Log.i(TAG, "Cartella 'StreamNova Backups' trovata sul Drive dell'utente")
      return Result.success(found)
    }
    val created = files.createBackupsFolder(token).fold(
      onSuccess = { it },
      onFailure = { e -> return Result.failure(DriveBackupException(describe(e))) }
    )
    GoogleDriveAuthRepository.rememberBackupsFolderId(created)
    Log.i(TAG, "Cartella 'StreamNova Backups' creata sul Drive dell'utente")
    return Result.success(created)
  }

  /**
   * Genera il backup (stessa sorgente dell'export locale), VALIDA il JSON e lo
   * carica su Drive con retry controllati.
   *
   * Anti-duplicato al retry: un upload multipart non è resumabile, quindi un errore
   * di rete a metà risposta può lasciare il file creato sul server. Prima di ogni
   * ritentativo si verifica se un file con lo STESSO nome esiste già nella cartella:
   * se c'è, l'operazione termina come successo (l'upload precedente era completo).
   *
   * @return la voce Drive del backup caricato.
   */
  suspend fun exportToDrive(
    clientId: String,
    fileName: String,
    categories: Set<com.example.data.sync.SyncCategory> = BackupManager.DEFAULT_CATEGORIES,
    includeCredentials: Boolean = false,
    appVersionName: String = "",
    appVersionCode: Int = 0,
    onProgress: suspend (DriveExportProgress) -> Unit = {}
  ): Result<DriveBackupEntry> = withContext(Dispatchers.IO) {
    val account = GoogleDriveAuthRepository.currentAccount()
    onProgress(DriveExportProgress.PreparingFolder(account?.email))
    val envelope = BackupManager.generateEnvelope(
      context = contextForGeneration(),
      categories = categories,
      includeCredentials = includeCredentials,
      appVersionName = appVersionName,
      appVersionCode = appVersionCode
    ).fold(onSuccess = { it }, onFailure = { return@withContext Result.failure(it) })
    // Validazione del JSON generato PRIMA di toccare la rete: un backup che il
    // proprio validatore rifiuterebbe non viene mai caricato.
    val json = BackupManager.encodeValidated(envelope).fold(
      onSuccess = { it },
      onFailure = { e -> return@withContext Result.failure(DriveBackupException(e.message ?: "JSON non valido")) }
    )
    val sizeBytes = json.toByteArray(Charsets.UTF_8).size.toLong()

    onProgress(DriveExportProgress.Uploading(fileName))
    val token = GoogleDriveAuthRepository.getValidAccessToken(clientId).fold(
      onSuccess = { it },
      onFailure = { return@withContext Result.failure(it) }
    )
    val files = GoogleDriveFilesClient()

    var folderId = ensureBackupsFolder(clientId).fold(
      onSuccess = { it },
      onFailure = { return@withContext Result.failure(it) }
    )
    var lastError: Throwable? = null
    repeat(MAX_UPLOAD_ATTEMPTS) { attempt ->
      val result = files.uploadBackup(token, folderId, fileName, json)
      result.fold(
        onSuccess = { uploaded ->
          val entry = DriveBackupEntry(uploaded.id, uploaded.name, uploaded.modifiedTimeMs, uploaded.sizeBytes)
          onProgress(DriveExportProgress.Done(entry, sizeBytes))
          return@withContext Result.success(entry)
        },
        onFailure = { e ->
          lastError = e
          Log.w(TAG, "Upload tentativo ${attempt + 1} fallito: ${describe(e)}")
          when {
            // Cartella eliminata dall'utente dopo l'ultimo uso: ricreata al volo e
            // il tentativo corrente si rifà con il nuovo folderId.
            isNotFound(e) -> {
              GoogleDriveAuthRepository.markBackupsFolderMissing()
              folderId = ensureBackupsFolder(clientId).fold(
                onSuccess = { it },
                onFailure = { return@withContext Result.failure(it) }
              )
            }
            // Il retry serve SOLO per errori di rete/temporanei: errori definitivi
            // (autorizzazione, quota) non si ritentano.
            !isRetryable(e) -> return@withContext Result.failure(DriveBackupException(describe(e)))
            else -> {
              // Prevenzione duplicati: se l'upload precedente aveva in realtà completato
              // lato server, il nome esiste già e il file è il risultato atteso.
              val duplicate = files.findFileByName(token, folderId, fileName).getOrNull()
              if (duplicate != null) {
                Log.i(TAG, "Il file esiste già sul Drive dopo l'errore: upload precedente completato")
                val entry = DriveBackupEntry(duplicate.id, duplicate.name, duplicate.modifiedTimeMs, duplicate.sizeBytes)
                onProgress(DriveExportProgress.Done(entry, sizeBytes))
                return@withContext Result.success(entry)
              }
            }
          }
        }
      )
    }
    Result.failure(DriveBackupException(describe(lastError ?: Exception("Upload non riuscito"))))
  }

  /**
   * Lista dei backup JSON nella cartella `StreamNova Backups`, dal più recente.
   * Cartella assente/eliminata → lista vuota con successo (la UI mostra lo stato
   * vuoto; NESSUNA cartella viene creata durante l'import).
   */
  suspend fun listDriveBackups(clientId: String): Result<List<DriveBackupEntry>> = withContext(Dispatchers.IO) {
    val token = GoogleDriveAuthRepository.getValidAccessToken(clientId).fold(
      onSuccess = { it },
      onFailure = { return@withContext Result.failure(it) }
    )
    val files = GoogleDriveFilesClient()
    val folderId = when (val cached = GoogleDriveAuthRepository.cachedBackupsFolderId()) {
      null -> files.findBackupsFolder(token).fold(
        onSuccess = { it },
        onFailure = { e -> return@withContext Result.failure(DriveBackupException(describe(e))) }
      ) ?: return@withContext Result.success(emptyList()) // cartella mai creata: lista vuota
      else -> cached
    }
    val listed = files.listBackups(token, folderId).fold(
      onSuccess = { it },
      onFailure = { e ->
        // Cartella eliminata dall'utente: niente crash, niente creazione, lista vuota.
        if (isNotFound(e)) {
          GoogleDriveAuthRepository.markBackupsFolderMissing()
          return@withContext Result.success(emptyList())
        }
        return@withContext Result.failure(DriveBackupException(describe(e)))
      }
    )
    Result.success(
      listed.map { DriveBackupEntry(it.id, it.name, it.modifiedTimeMs, it.sizeBytes) }
    )
  }

  /**
   * Scarica un backup dal Drive e lo VALIDA con la pipeline comune
   * ([BackupCodec] + versione). Nessun restore parte senza validazione.
   */
  suspend fun downloadBackup(
    clientId: String,
    entry: DriveBackupEntry
  ): Result<BackupEnvelope> = withContext(Dispatchers.IO) {
    val token = GoogleDriveAuthRepository.getValidAccessToken(clientId).fold(
      onSuccess = { it },
      onFailure = { return@withContext Result.failure(it) }
    )
    val json = GoogleDriveFilesClient().downloadBackup(token, entry.fileId).fold(
      onSuccess = { it },
      onFailure = { e -> return@withContext Result.failure(DriveBackupException(describe(e))) }
    )
    BackupManager.previewJson(json).fold(
      onSuccess = { Result.success(it) },
      onFailure = { e -> Result.failure(DriveBackupException(e.message ?: BackupError.INVALID_JSON.message)) }
    )
  }

  /**
   * Disconnessione dell'account Google: revoca del token + cancellazione dati locali.
   * L'utente mantiene il controllo dei file: restano sul SUO Drive.
   */
  suspend fun disconnect(): Result<Unit> = GoogleDriveAuthRepository.disconnect()

  // ── helpers ────────────────────────────────────────────────────────────────

  /**
   * Il contesto per [BackupManager.generateEnvelope]: il gestore gira come oggetto
   * di dominio e usa il contesto applicativo iniettato dall'Application.
   */
  @Volatile internal var generationContext: android.content.Context? = null
  private fun contextForGeneration(): android.content.Context =
    generationContext ?: throw IllegalStateException("GoogleDriveBackupManager non inizializzato")

  private fun isRetryable(e: Throwable): Boolean {
    val message = e.message ?: return false
    return e is java.io.IOException &&
      !message.contains("Autorizzazione", ignoreCase = true) &&
      !message.contains("Spazio Google Drive", ignoreCase = true) &&
      !message.contains("quota", ignoreCase = true)
  }

  private fun isNotFound(e: Throwable): Boolean =
    e.message?.contains("non più presente", ignoreCase = true) == true ||
      e.message?.contains("404", ignoreCase = true) == true

  /** Messaggio utente da un'eccezione: MAI dettagli di token o contenuto del backup. */
  fun describe(e: Throwable): String = when (e) {
    is GoogleAuthException -> e.message
    is DriveBackupException -> e.message ?: "Operazione Google Drive non riuscita"
    is java.io.IOException -> e.message ?: "Errore di rete verso Google Drive"
    else -> "Operazione Google Drive non riuscita"
  }

  /** Inizializzazione dal contesto applicativo (chiamata dall'Application). */
  fun init(context: android.content.Context) {
    generationContext = context.applicationContext
  }
}

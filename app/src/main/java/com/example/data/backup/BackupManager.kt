package com.example.data.backup

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import com.example.data.sync.SYNC_SCHEMA_VERSION
import com.example.data.sync.SyncApplyResult
import com.example.data.sync.SyncCategory
import com.example.data.sync.SyncConfigApplier
import com.example.data.sync.SyncConfigBuilder
import com.example.data.sync.SyncConfigPayload
import com.example.data.sync.SyncSimklAccountPayload
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "BackupManager"

/**
 * Versione del FORMATO del file di backup.
 *
 * Distinta da [SYNC_SCHEMA_VERSION] (schema del payload): il file aggiunge
 * un'intestazione (versione app, data, credenziali incluse). Un backup con
 * formato SUPERIORE a quello supportato viene rifiutato con un messaggio
 * esplicito; uno inferiore è accettato e i campi mancanti valgono i default.
 */
const val BACKUP_FORMAT_VERSION = 1

/** Estensione/MIME del file di backup (JSON leggibile, nessun formato proprietario). */
const val BACKUP_MIME_TYPE = "application/json"
const val BACKUP_FILE_EXTENSION = "streamnova.json"

/**
 * Contenuto completo di un file di backup StreamNova.
 *
 * @param includesCredentials `true` quando il file contiene chiavi API/SIMKL: la
 *        UI lo dichiara esplicitamente e il default dell'export è `false`.
 * @param payload configurazione serializzata (stessi modelli della sincronizzazione
 *        device-to-device: un solo formato da mantenere).
 */
@JsonClass(generateAdapter = true)
data class BackupEnvelope(
  val formatVersion: Int = BACKUP_FORMAT_VERSION,
  val appVersionName: String = "",
  val appVersionCode: Int = 0,
  val exportedAtEpochMs: Long = 0L,
  val deviceName: String = "",
  val includesCredentials: Boolean = false,
  val payload: SyncConfigPayload = SyncConfigPayload()
) {
  /** `true` se il file contiene almeno una sezione oltre all'intestazione. */
  val hasContent: Boolean
    get() = payload.appSettings != null ||
      payload.playbackSettings != null ||
      payload.addons.isNotEmpty() ||
      payload.simklAccount != null ||
      payload.mediaItems.isNotEmpty() ||
      payload.episodeProgress.isNotEmpty()
}

/** Esito del parsing di un file di backup. */
sealed interface BackupDecodeResult {
  data class Success(val envelope: BackupEnvelope) : BackupDecodeResult
  data class Failure(val error: BackupError, val detail: String? = null) : BackupDecodeResult
}

/** Motivo di rifiuto di un backup (messaggi utente in [BackupError.message]). */
enum class BackupError(val message: String) {
  EMPTY("Il file di backup è vuoto"),
  INVALID_JSON("Il file non è un backup StreamNova valido"),
  UNSUPPORTED_VERSION("Il backup è stato creato da una versione più recente di StreamNova"),
  EMPTY_PAYLOAD("Il backup non contiene impostazioni, addon o libreria"),
  READ_FAILED("Impossibile leggere il file selezionato"),
  WRITE_FAILED("Impossibile scrivere il file di backup"),
  APPLY_FAILED("Impossibile applicare il backup")
}

/** Codec del file di backup (JSON indentato, leggibile e verificabile). */
object BackupCodec {

  private val moshi = Moshi.Builder().build()
  private val adapter = moshi.adapter(BackupEnvelope::class.java)
  private val prettyAdapter = adapter.indent("  ")

  fun encode(envelope: BackupEnvelope, pretty: Boolean = true): String =
    (if (pretty) prettyAdapter else adapter).toJson(envelope)

  /**
   * Parsing tollerante ma NON permissivo: un JSON valido con versione futura o
   * payload vuoto viene rifiutato con un motivo classificato, mai con un crash.
   */
  fun decode(json: String?): BackupDecodeResult {
    if (json.isNullOrBlank()) return BackupDecodeResult.Failure(BackupError.EMPTY)
    val envelope = try {
      adapter.fromJson(json)
    } catch (e: Exception) {
      return BackupDecodeResult.Failure(BackupError.INVALID_JSON, e.message)
    } ?: return BackupDecodeResult.Failure(BackupError.INVALID_JSON)

    if (envelope.formatVersion > BACKUP_FORMAT_VERSION) {
      return BackupDecodeResult.Failure(BackupError.UNSUPPORTED_VERSION)
    }
    if (!envelope.hasContent) {
      return BackupDecodeResult.Failure(BackupError.EMPTY_PAYLOAD)
    }
    return BackupDecodeResult.Success(envelope)
  }
}

/**
 * Rimozione delle credenziali dal payload quando l'utente non ha scelto di
 * includerle. Le chiavi rimosse sono SOLO i segreti: impostazioni, addon,
 * libreria e preferenze restano intatti.
 */
object BackupSanitizer {

  /**
   * Restituisce una copia del payload con chiavi API e token SIMKL azzerati.
   * `includesCredentials` dell'envelope deve riflettere questa operazione.
   */
  fun redactSecrets(payload: SyncConfigPayload): SyncConfigPayload = payload.copy(
    appSettings = payload.appSettings?.copy(
      torBoxApiKey = null,
      openSubtitlesApiKey = null,
      simklClientId = null
    ),
    simklAccount = payload.simklAccount?.let { account ->
      SyncSimklAccountPayload(
        accessToken = null,
        refreshToken = null,
        accountId = account.accountId,
        username = account.username,
        accountType = account.accountType,
        tokenExpiresAt = null,
        tokenExpiresAtEpochMs = null,
        scope = null,
        flow = account.flow,
        sessionExpired = true
      )
    }
  )

  /** `true` se il payload contiene ancora un segreto (verifica di sicurezza). */
  fun containsSecrets(payload: SyncConfigPayload): Boolean =
    payload.appSettings?.let {
      !it.torBoxApiKey.isNullOrBlank() ||
        !it.openSubtitlesApiKey.isNullOrBlank() ||
        !it.simklClientId.isNullOrBlank()
    } == true ||
      payload.simklAccount?.let {
        !it.accessToken.isNullOrBlank() || !it.refreshToken.isNullOrBlank()
      } == true
}

/** Riepilogo dell'export mostrato all'utente. */
data class BackupExportResult(
  val uri: Uri,
  val sizeBytes: Long,
  val includesCredentials: Boolean,
  val categories: Set<SyncCategory>
) {
  fun describe(): String = buildString {
    append("Backup salvato (${sizeBytes / 1024} KB)")
    if (includesCredentials) append(" con credenziali") else append(" senza credenziali")
    append(": ")
    append(
      when {
        categories.contains(SyncCategory.SIMKL) && categories.contains(SyncCategory.LIBRARY) ->
          "impostazioni, addon, libreria e account SIMKL"
        categories.contains(SyncCategory.LIBRARY) -> "impostazioni, addon e libreria"
        else -> "impostazioni e addon"
      }
    )
  }
}

/**
 * Backup & Restore su file, riusando la pipeline di sincronizzazione.
 *
 * - **Export**: [SyncConfigBuilder] legge lo stato corrente; se l'utente non ha
 *   scelto di includere le credenziali, [BackupSanitizer] le rimuove PRIMA della
 *   serializzazione (non finiscono mai su disco).
 * - **Import**: [BackupCodec] valida il file; [SyncConfigApplier] applica le
 *   categorie PRESENTI nel backup. La libreria è fusa senza cancellazioni locali.
 *
 * Tutte le operazioni sono su IO e non toccano la UI thread.
 */
object BackupManager {

  /** Categorie esportabili, tutte selezionate di default. */
  val DEFAULT_CATEGORIES: Set<SyncCategory> = setOf(
    SyncCategory.SETTINGS,
    SyncCategory.ADDONS,
    SyncCategory.LIBRARY,
    SyncCategory.SIMKL
  )

  /**
   * Nome file suggerito per un nuovo backup, conforme alle specifiche:
   * `StreamNova_Backup_YYYY-MM-DD_HH-mm-ss.json` (unico: ogni esportazione crea
   * un backup distinto, nessuna sovrascrittura automatica).
   */
  fun suggestedBackupFileName(atMs: Long = System.currentTimeMillis()): String {
    val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date(atMs))
    return "StreamNova_Backup_$stamp.json"
  }

  /**
   * Genera l'envelope del backup (lettura stato corrente + sanitizzazione segreti)
   * SENZA scrivere nulla: è la sorgente comune di tutte le destinazioni di export
   * (SAF, file in-app, Google Drive), così il JSON generato è identico ovunque.
   */
  suspend fun generateEnvelope(
    context: Context,
    categories: Set<SyncCategory> = DEFAULT_CATEGORIES,
    includeCredentials: Boolean = false,
    appVersionName: String = "",
    appVersionCode: Int = 0
  ): Result<BackupEnvelope> = withContext(Dispatchers.IO) {
    try {
      val rawPayload = SyncConfigBuilder.build(
        context = context,
        deviceName = Build.MODEL ?: "StreamNova",
        categories = categories
      )
      val payload = if (includeCredentials) rawPayload else BackupSanitizer.redactSecrets(rawPayload)
      Result.success(
        BackupEnvelope(
          formatVersion = BACKUP_FORMAT_VERSION,
          appVersionName = appVersionName,
          appVersionCode = appVersionCode,
          exportedAtEpochMs = System.currentTimeMillis(),
          deviceName = Build.MODEL ?: "StreamNova",
          includesCredentials = includeCredentials && BackupSanitizer.containsSecrets(rawPayload),
          payload = payload
        )
      )
    } catch (e: Exception) {
      Log.w(TAG, "Generazione backup fallita: ${e.message}")
      Result.failure(e)
    }
  }

  /**
   * Serializza l'envelope e VALIDA il JSON prodotto (round-trip con [BackupCodec.decode]):
   * un export che produce un file il proprio validatore rifiuterebbe non parte mai.
   */
  fun encodeValidated(envelope: BackupEnvelope): Result<String> {
    val json = BackupCodec.encode(envelope)
    return when (val roundTrip = BackupCodec.decode(json)) {
      is BackupDecodeResult.Success -> Result.success(json)
      is BackupDecodeResult.Failure ->
        Result.failure(IllegalStateException(roundTrip.error.message))
    }
  }

  suspend fun exportTo(
    context: Context,
    uri: Uri,
    categories: Set<SyncCategory> = DEFAULT_CATEGORIES,
    includeCredentials: Boolean = false,
    appVersionName: String = "",
    appVersionCode: Int = 0
  ): Result<BackupExportResult> = withContext(Dispatchers.IO) {
    val envelope = generateEnvelope(
      context = context,
      categories = categories,
      includeCredentials = includeCredentials,
      appVersionName = appVersionName,
      appVersionCode = appVersionCode
    ).fold(onSuccess = { it }, onFailure = { return@withContext Result.failure(it) })
    val json = encodeValidated(envelope).fold(
      onSuccess = { it },
      onFailure = { return@withContext Result.failure(it) }
    )
    try {
      val bytes = json.toByteArray(Charsets.UTF_8)
      val resolver = context.contentResolver
      resolver.openOutputStream(uri, "wt")?.use { stream ->
        stream.write(bytes)
        stream.flush()
      } ?: return@withContext Result.failure(
        IllegalStateException(BackupError.WRITE_FAILED.message)
      )
      Log.i(TAG, "Backup esportato (SAF): ${bytes.size} byte, categorie=$categories")
      Result.success(
        BackupExportResult(
          uri = uri,
          sizeBytes = bytes.size.toLong(),
          includesCredentials = envelope.includesCredentials,
          categories = categories
        )
      )
    } catch (e: Exception) {
      Log.w(TAG, "Export fallito: ${e.message}")
      Result.failure(e)
    }
  }

  /**
   * Export su FILE locale (cartella in-app, usata quando SAF non è disponibile,
   * es. Fire TV senza DocumentsUI). Scrittura ATOMICA: file temporaneo `.tmp`
   * nella stessa cartella + rename, così un file incompleto non può mai essere
   * presentato come backup valido.
   */
  suspend fun exportToFile(
    context: Context,
    target: File,
    categories: Set<SyncCategory> = DEFAULT_CATEGORIES,
    includeCredentials: Boolean = false,
    appVersionName: String = "",
    appVersionCode: Int = 0
  ): Result<BackupExportResult> = withContext(Dispatchers.IO) {
    val envelope = generateEnvelope(
      context = context,
      categories = categories,
      includeCredentials = includeCredentials,
      appVersionName = appVersionName,
      appVersionCode = appVersionCode
    ).fold(onSuccess = { it }, onFailure = { return@withContext Result.failure(it) })
    val json = encodeValidated(envelope).fold(
      onSuccess = { it },
      onFailure = { return@withContext Result.failure(it) }
    )
    try {
      target.parentFile?.mkdirs()
      val tmp = File(target.parentFile, target.name + ".tmp")
      tmp.writeText(json, Charsets.UTF_8)
      if (!tmp.renameTo(target)) {
        // Fallback di portabilità: alcune filesystem non garantiscono il rename
        // sovrascrivendo un file esistente.
        target.delete()
        if (!tmp.renameTo(target)) {
          tmp.delete()
          return@withContext Result.failure(
            IllegalStateException(BackupError.WRITE_FAILED.message)
          )
        }
      }
      val bytes = json.toByteArray(Charsets.UTF_8)
      Log.i(TAG, "Backup esportato (file locale): ${target.name}, ${bytes.size} byte")
      Result.success(
        BackupExportResult(
          uri = Uri.fromFile(target),
          sizeBytes = bytes.size.toLong(),
          includesCredentials = envelope.includesCredentials,
          categories = categories
        )
      )
    } catch (e: Exception) {
      Log.w(TAG, "Export su file fallito: ${e.message}")
      Result.failure(e)
    }
  }

  /** Valida un JSON già in memoria (import da Google Drive). */
  suspend fun previewJson(json: String?): Result<BackupEnvelope> = withContext(Dispatchers.IO) {
    when (val decoded = BackupCodec.decode(json)) {
      is BackupDecodeResult.Success -> Result.success(decoded.envelope)
      is BackupDecodeResult.Failure ->
        Result.failure(IllegalArgumentException(decoded.error.message))
    }
  }

  /**
   * Legge e valida un file di backup locale senza applicarlo (anteprima).
   */
  suspend fun previewFile(file: File): Result<BackupEnvelope> = withContext(Dispatchers.IO) {
    try {
      val json = file.readText(Charsets.UTF_8)
      previewJson(json)
    } catch (e: Exception) {
      Log.w(TAG, "Lettura backup locale fallita: ${e.message}")
      Result.failure(IllegalStateException(BackupError.READ_FAILED.message, e))
    }
  }

  /**
   * Legge e valida un backup senza applicarlo (per la schermata di anteprima).
   */
  suspend fun preview(
    context: Context,
    uri: Uri
  ): Result<BackupEnvelope> = withContext(Dispatchers.IO) {
    try {
      val json = context.contentResolver.openInputStream(uri)?.use { stream ->
        stream.readBytes().toString(Charsets.UTF_8)
      } ?: return@withContext Result.failure(
        IllegalStateException(BackupError.READ_FAILED.message)
      )
      previewJson(json)
    } catch (e: Exception) {
      Log.w(TAG, "Lettura backup fallita: ${e.message}")
      Result.failure(IllegalStateException(BackupError.READ_FAILED.message, e))
    }
  }

  /**
   * Applica un backup letto in precedenza.
   *
   * Le categorie vengono derivate SOLO dalle sezioni presenti nel file: un
   * backup parziale (es. solo addon) non tocca le altre aree.
   */
  suspend fun restore(
    context: Context,
    envelope: BackupEnvelope
  ): Result<SyncApplyResult> = withContext(Dispatchers.IO) {
    try {
      if (envelope.formatVersion > BACKUP_FORMAT_VERSION) {
        return@withContext Result.failure(
          IllegalArgumentException(BackupError.UNSUPPORTED_VERSION.message)
        )
      }
      val payload = envelope.payload
      val categories = buildSet {
        if (payload.appSettings != null || payload.playbackSettings != null) {
          add(SyncCategory.SETTINGS)
        }
        if (payload.addons.isNotEmpty()) add(SyncCategory.ADDONS)
        if (payload.simklAccount != null) add(SyncCategory.SIMKL)
        if (payload.mediaItems.isNotEmpty() || payload.episodeProgress.isNotEmpty()) {
          add(SyncCategory.LIBRARY)
        }
      }
      if (categories.isEmpty()) {
        return@withContext Result.failure(
          IllegalArgumentException(BackupError.EMPTY_PAYLOAD.message)
        )
      }
      val result = SyncConfigApplier.apply(context, payload, categories)
      Log.i(TAG, "Backup ripristinato: ${result.describe()}")
      Result.success(result)
    } catch (e: Exception) {
      Log.w(TAG, "Ripristino fallito: ${e.message}")
      Result.failure(IllegalStateException(BackupError.APPLY_FAILED.message, e))
    }
  }

  /** Versione dello schema di sincronizzazione supportata (per diagnostica). */
  val supportedSyncSchemaVersion: Int get() = SYNC_SCHEMA_VERSION
}

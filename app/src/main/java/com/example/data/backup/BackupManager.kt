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

  suspend fun exportTo(
    context: Context,
    uri: Uri,
    categories: Set<SyncCategory> = DEFAULT_CATEGORIES,
    includeCredentials: Boolean = false,
    appVersionName: String = "",
    appVersionCode: Int = 0
  ): Result<BackupExportResult> = withContext(Dispatchers.IO) {
    try {
      val rawPayload = SyncConfigBuilder.build(
        context = context,
        deviceName = Build.MODEL ?: "StreamNova",
        categories = categories
      )
      val payload = if (includeCredentials) rawPayload else BackupSanitizer.redactSecrets(rawPayload)
      val envelope = BackupEnvelope(
        formatVersion = BACKUP_FORMAT_VERSION,
        appVersionName = appVersionName,
        appVersionCode = appVersionCode,
        exportedAtEpochMs = System.currentTimeMillis(),
        deviceName = Build.MODEL ?: "StreamNova",
        includesCredentials = includeCredentials && BackupSanitizer.containsSecrets(rawPayload),
        payload = payload
      )
      val json = BackupCodec.encode(envelope)
      val resolver = context.contentResolver
      val bytes = json.toByteArray(Charsets.UTF_8)
      resolver.openOutputStream(uri, "wt")?.use { stream ->
        stream.write(bytes)
        stream.flush()
      } ?: return@withContext Result.failure(
        IllegalStateException(BackupError.WRITE_FAILED.message)
      )
      Log.i(TAG, "Backup esportato: ${bytes.size} byte, categorie=$categories")
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
      when (val decoded = BackupCodec.decode(json)) {
        is BackupDecodeResult.Success -> Result.success(decoded.envelope)
        is BackupDecodeResult.Failure ->
          Result.failure(IllegalArgumentException(decoded.error.message))
      }
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

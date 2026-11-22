package com.example.data.backup.google

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Un file di backup presente nella cartella Drive `StreamNova Backups`. */
data class DriveBackupFile(
  val id: String,
  val name: String,
  val modifiedTimeMs: Long,
  val sizeBytes: Long
)

/** Risposta di `files.list` (solo i campi richiesti). */
@JsonClass(generateAdapter = true)
internal data class DriveFileListResponse(
  val files: List<DriveFileItem> = emptyList()
)

@JsonClass(generateAdapter = true)
internal data class DriveFileItem(
  val id: String? = null,
  val name: String? = null,
  @Json(name = "modifiedTime") val modifiedTime: String? = null,
  val size: String? = null
)

/** Risposta di `files.create` / upload (metadata minimi). */
@JsonClass(generateAdapter = true)
internal data class DriveFileMetadataResponse(
  val id: String? = null,
  val name: String? = null,
  @Json(name = "mimeType") val mimeType: String? = null
)

/** Metadati JSON inviati con l'upload multipart. */
@JsonClass(generateAdapter = true)
internal data class DriveUploadMetadata(
  val name: String,
  val parents: List<String>,
  @Json(name = "mimeType") val mimeType: String
)

/**
 * Client REST di Google Drive v3 (endpoint `files`) limitato a ciò che serve ai
 * backup: individuare/creare la cartella `StreamNova Backups`, upload, lista e
 * download dei file JSON.
 *
 * Autenticazione: header `Authorization: Bearer <access token>` ottenuto dal
 * [GoogleDriveAuthClient] (Device Authorization Grant). Con lo scope `drive.file`
 * l'app vede SOLO i file/cartelle che ha creato lei stessa: nessun altro contenuto
 * del Drive dell'utente è raggiungibile.
 *
 * Niente token nei log; i messaggi di errore riportano solo esito HTTP e motivo
 * sintetico. Gli endpoint sono parametri di costruzione per i test.
 */
class GoogleDriveFilesClient(
  private val http: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    // Upload/download di backup piccoli (decine di KB): timeout di lettura generosi
    // per connessioni TV lente, non per file grandi.
    .readTimeout(60, TimeUnit.SECONDS)
    .build(),
  private val filesEndpointUrl: String = "https://www.googleapis.com/drive/v3/files",
  private val uploadEndpointUrl: String = "https://www.googleapis.com/upload/drive/v3/files"
) {

  private val moshi = Moshi.Builder().build()
  private val listAdapter = moshi.adapter(DriveFileListResponse::class.java)
  private val metadataAdapter = moshi.adapter(DriveFileMetadataResponse::class.java)
  private val uploadMetadataAdapter = moshi.adapter(DriveUploadMetadata::class.java)

  companion object {
    const val FOLDER_NAME = "StreamNova Backups"
    const val FOLDER_MIME = "application/vnd.google-apps.folder"
    const val JSON_MIME = "application/json"
    private val RFC3339_BY_UTC: ThreadSafeParser = ThreadSafeParser()
  }

  /** SimpleDateFormat non è thread-safe: parse RFC3339 (`2026-11-22T17:59:59.000Z`). */
  private class ThreadSafeParser {
    fun parse(value: String?): Long {
      if (value.isNullOrBlank()) return 0L
      return try {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
        fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
        var ms = fmt.parse(value)?.time ?: 0L
        // Frazione e offset sono ridondanti per i tempi di Drive (sempre UTC "Z"):
        // il parse della parte principale basta ai fini dell'ordinamento.
        if (ms == 0L) 0L else ms
      } catch (_: Exception) {
        0L
      }
    }
  }

  private fun authRequest(url: okhttp3.HttpUrl, accessToken: String, method: String = "GET", body: RequestBody? = null): Request =
    Request.Builder()
      .url(url)
      .header("Authorization", "Bearer $accessToken")
      .method(method, body)
      .build()

  private fun baseUrl(): okhttp3.HttpUrl =
    filesEndpointUrl.toHttpUrlOrNull()
      ?: throw IllegalStateException("Endpoint Drive non valido")

  /**
   * Individua la cartella dedicata ai backup creata da StreamNova.
   * Con `drive.file` l'app vede solo le cartelle create da se stessa: se il
   * risultato è `null` la cartella non esiste ancora (o è stata eliminata
   * dall'utente) e va ricreata.
   */
  suspend fun findBackupsFolder(accessToken: String): Result<String?> = withContext(Dispatchers.IO) {
    val url = baseUrl().newBuilder()
      .addQueryParameter(
        "q",
        "name = '$FOLDER_NAME' and mimeType = '$FOLDER_MIME' and trashed = false"
      )
      .addQueryParameter("fields", "files(id,name)")
      .addQueryParameter("pageSize", "5")
      .build()
    try {
      http.newCall(authRequest(url, accessToken)).execute().use { response ->
        val text = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
          return@use Result.failure(IOException("files.list rifiutata (HTTP ${response.code})"))
        }
        val parsed = listAdapter.fromJson(text) ?: DriveFileListResponse()
        val first = parsed.files.firstOrNull { !it.id.isNullOrBlank() }
        Result.success(first?.id)
      }
    } catch (e: Exception) {
      Result.failure(e)
    }
  }

  /** Crea la cartella dedicata ai backup. Ritorna il folderId. */
  suspend fun createBackupsFolder(accessToken: String): Result<String> = withContext(Dispatchers.IO) {
    val url = baseUrl().newBuilder()
      .addQueryParameter("fields", "id,name")
      .build()
    val metadataJson = uploadMetadataAdapter.toJson(
      DriveUploadMetadata(name = FOLDER_NAME, parents = emptyList(), mimeType = FOLDER_MIME)
    )
    val metadata = metadataJson.toRequestBody(JSON_MIME.toMediaTypeOrNull())
    val request = authRequest(url, accessToken, method = "POST", body = metadata)
    try {
      http.newCall(request).execute().use { response ->
        val text = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
          return@use Result.failure(IOException("Creazione cartella rifiutata (HTTP ${response.code})"))
        }
        val parsed = metadataAdapter.fromJson(text)
        if (parsed?.id.isNullOrBlank()) {
          Result.failure(IOException("Creazione cartella senza id"))
        } else {
          Result.success(parsed!!.id!!)
        }
      }
    } catch (e: Exception) {
      Result.failure(e)
    }
  }

  /**
   * Upload multipart (`uploadType=multipart`) di un backup JSON nella cartella.
   * Il file viene creato CON nome esplicito: nessuna sovrascrittura automatica.
   */
  suspend fun uploadBackup(
    accessToken: String,
    folderId: String,
    fileName: String,
    json: String
  ): Result<DriveBackupFile> = withContext(Dispatchers.IO) {
    val url = uploadEndpointUrl.toHttpUrlOrNull()
      ?.newBuilder()
      ?.addQueryParameter("uploadType", "multipart")
      ?.addQueryParameter("fields", "id,name,modifiedTime,size")
      ?.build()
      ?: return@withContext Result.failure(IllegalStateException("Endpoint upload non valido"))

    val metadataJson = uploadMetadataAdapter.toJson(
      DriveUploadMetadata(
        name = fileName,
        parents = listOf(folderId),
        mimeType = JSON_MIME
      )
    )
    val jsonType = JSON_MIME.toMediaTypeOrNull() ?: return@withContext Result.failure(
      IllegalStateException("MIME non valido")
    )
    val metadataPart = MultipartBody.Part.createFormData(
      "metadata", null, metadataJson.toRequestBody(jsonType)
    )
    val contentPart = MultipartBody.Part.createFormData(
      "file", fileName, json.toByteArray(Charsets.UTF_8).toRequestBody(jsonType)
    )
    val body = MultipartBody.Builder()
      .setType("multipart/related".toMediaTypeOrNull()!!)
      .addPart(metadataPart)
      .addPart(contentPart)
      .build()

    val request = authRequest(url, accessToken, method = "POST", body = body)
    try {
      http.newCall(request).execute().use { response ->
        val text = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
          return@use Result.failure(driveError(response.code, text))
        }
        val parsed = metadataAdapter.fromJson(text)
        if (parsed?.id.isNullOrBlank()) {
          Result.failure(IOException("Upload confermato senza id"))
        } else {
          Result.success(
            DriveBackupFile(
              id = parsed!!.id!!,
              name = parsed.name ?: fileName,
              modifiedTimeMs = System.currentTimeMillis(),
              sizeBytes = json.toByteArray(Charsets.UTF_8).size.toLong()
            )
          )
        }
      }
    } catch (e: Exception) {
      Result.failure(e)
    }
  }

  /** Lista dei backup JSON nella cartella, dal più recente. */
  suspend fun listBackups(accessToken: String, folderId: String): Result<List<DriveBackupFile>> =
    withContext(Dispatchers.IO) {
      val url = baseUrl().newBuilder()
        .addQueryParameter(
          "q",
          "'$folderId' in parents and trashed = false and mimeType = '$JSON_MIME'"
        )
        .addQueryParameter("orderBy", "modifiedTime desc")
        .addQueryParameter("fields", "files(id,name,modifiedTime,size)")
        .addQueryParameter("pageSize", "100")
        .build()
      try {
        http.newCall(authRequest(url, accessToken)).execute().use { response ->
          val text = response.body?.string().orEmpty()
          if (!response.isSuccessful) {
            return@use Result.failure(driveError(response.code, text))
          }
          val parsed = listAdapter.fromJson(text) ?: DriveFileListResponse()
          val files = parsed.files
            .filter { !it.id.isNullOrBlank() && !it.name.isNullOrBlank() }
            .map {
              DriveBackupFile(
                id = it.id!!,
                name = it.name!!,
                modifiedTimeMs = RFC3339_BY_UTC.parse(it.modifiedTime),
                sizeBytes = it.size?.toLongOrNull() ?: 0L
              )
            }
          Result.success(files)
        }
      } catch (e: Exception) {
        Result.failure(e)
      }
    }

  /** Scarica il contenuto JSON di un backup (già validato dalla pipeline comune). */
  suspend fun downloadBackup(accessToken: String, fileId: String): Result<String> =
    withContext(Dispatchers.IO) {
      val url = baseUrl().newBuilder()
        .addPathSegment(fileId)
        .addQueryParameter("alt", "media")
        .build()
      try {
        http.newCall(authRequest(url, accessToken)).execute().use { response ->
          if (!response.isSuccessful) {
            return@use Result.failure(IOException("Download rifiutato (HTTP ${response.code})"))
          }
          Result.success(response.body?.string().orEmpty())
        }
      } catch (e: Exception) {
        Result.failure(e)
      }
    }

  /** Cerca un backup CON NOME ESATTO nella cartella: usato per prevenire duplicati al retry. */
  suspend fun findFileByName(
    accessToken: String,
    folderId: String,
    fileName: String
  ): Result<DriveBackupFile?> = withContext(Dispatchers.IO) {
    val url = baseUrl().newBuilder()
      .addQueryParameter(
        "q",
        "'$folderId' in parents and name = '$fileName' and trashed = false"
      )
      .addQueryParameter("fields", "files(id,name,modifiedTime,size)")
      .addQueryParameter("pageSize", "1")
      .build()
    try {
      http.newCall(authRequest(url, accessToken)).execute().use { response ->
        val text = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
          return@use Result.failure(IOException("files.list rifiutata (HTTP ${response.code})"))
        }
        val parsed = listAdapter.fromJson(text) ?: DriveFileListResponse()
        val first = parsed.files.firstOrNull { !it.id.isNullOrBlank() }
        Result.success(
          first?.let {
            DriveBackupFile(it.id!!, it.name ?: fileName, RFC3339_BY_UTC.parse(it.modifiedTime), it.size?.toLongOrNull() ?: 0L)
          }
        )
      }
    } catch (e: Exception) {
      Result.failure(e)
    }
  }

  /** Traduce un esito HTTP Drive in errore classificato (quota/offline/rete). */
  private fun driveError(httpCode: Int, body: String): Exception {
    val hint = when (httpCode) {
      401, 403 -> "Autorizzazione scaduta o revocata"
      404 -> "File o cartella non più presente"
      429 -> "Troppi tentativi in poco tempo"
      500, 502, 503 -> "Servizio Google temporaneamente non disponibile"
      else -> "Errore Google Drive (HTTP $httpCode)"
    }
    if (httpCode == 403 && body.contains("storageQuotaExceeded", ignoreCase = true)) {
      return IOException("Spazio Google Drive esaurito")
    }
    return IOException(hint)
  }
}

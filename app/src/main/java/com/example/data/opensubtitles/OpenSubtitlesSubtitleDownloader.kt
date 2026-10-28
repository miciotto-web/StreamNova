package com.example.data.opensubtitles

import com.example.domain.model.Subtitle
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

/**
 * Tipologia di fallimento del download di un sottotitolo OpenSubtitles. La UI la converte
 * in un messaggio comprensibile e in un'azione di retry.
 */
enum class OpenSubtitlesDownloadError {
  /** Il risultato selezionato non ha né file_id valido né URL diretto utilizzabile. */
  NO_DOWNLOAD_LINK,

  /** Il servizio OpenSubtitles ha rifiutato la richiesta di link o il download (HTTP). */
  HTTP,

  /** Errore di rete generico durante il download del file. */
  NETWORK,

  /** Il download ha superato i timeout configurati. */
  TIMEOUT,

  /** Il file scaricato è vuoto. */
  EMPTY_FILE,

  /** Il file scaricato non è un sottotitolo valido (HTML, archivio senza tracce, ecc.). */
  INVALID_FILE
}

/** Eccezione tipizzata per il download dei sottotitoli OpenSubtitles. */
internal class OpenSubtitlesDownloadException(
  val error: OpenSubtitlesDownloadError,
  message: String,
  cause: Throwable? = null
) : RuntimeException(message, cause)

/**
 * Codec puro (testabile senza Android) per decodificare il payload scaricato da
 * OpenSubtitles: la risposta del POST /download NON è il file SRT, è solo un contenitore
 * con l'URL temporaneo; e il file scaricato dall'URL temporaneo può essere un archivio
 * ZIP o GZIP contenente il sottotitolo reale, non necessariamente un SubRip diretto.
 */
internal object OpenSubtitlesPayloadCodec {

  /** Limite difensivo a payload (e voci d'archivio): i sottotitoli reali restano piccoli. */
  internal const val DEFAULT_MAX_PAYLOAD_BYTES: Int = 10 * 1024 * 1024

  /** Estensioni dei file sottotitolo accettate dentro gli archivi e riconosciute nel nome. */
  internal val SUBTITLE_FILE_EXTENSIONS: List<String> =
    listOf("srt", "vtt", "webvtt", "ass", "ssa", "ttml", "dfxp", "sub")

  internal fun isZip(bytes: ByteArray): Boolean =
    bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() &&
      (bytes[2] == 0x03.toByte() || bytes[2] == 0x05.toByte() || bytes[2] == 0x07.toByte())

  internal fun isGzip(bytes: ByteArray): Boolean =
    bytes.size >= 2 && bytes[0] == 0x1F.toByte() && bytes[1] == 0x8B.toByte()

  /** Rileva pagine HTML scaricate per errore (pagina web OpenSubtitles, error page, ecc.). */
  internal fun looksLikeHtml(bytes: ByteArray): Boolean {
    val head = bytes.take(2048).toByteArray()
      .toString(Charsets.UTF_8)
      .lowercase()
      .trimStart('\uFEFF', ' ', '\n', '\r', '\t')
    return head.startsWith("<!doctype html") || head.startsWith("<html")
  }

  /**
   * Estrae la prima voce d'archivio che sia un file di sottotitolo riconoscibile.
   *
   * Sicurezza (zip-slip): le voci con percorsi assoluti o `..` vengono ignorate, così come
   * __MACOSX e directory; la dimensione decompressa è limitata a [maxEntryBytes].
   * Voci senza estensione nota (o con estensione non-sottotitolo, es. "readme.nfo") NON
   * vengono considerate: un archivio senza candidati validi restituisce null e il chiamante
   * fallisce con INVALID_FILE.
   */
  internal fun extractFirstSubtitleFromZip(
    zipBytes: ByteArray,
    maxEntryBytes: Int = DEFAULT_MAX_PAYLOAD_BYTES
  ): ByteArray? {
    val entries = LinkedHashMap<String, ByteArray>()
    ZipInputStream(zipBytes.inputStream().buffered(), Charsets.UTF_8).use { zis ->
      var entry: ZipEntry? = zis.nextEntry
      while (entry != null) {
        val name = entry.name ?: ""
        if (isSafeSubtitleEntryName(name) && hasSubtitleExtension(name)) {
          val content = readBounded(zis, maxEntryBytes) ?: return null
          if (content.isNotEmpty()) entries[name.substringAfterLast('/').lowercase()] = content
        }
        entry = zis.nextEntry
      }
    }
    return entries.values.firstOrNull()
  }

  private fun hasSubtitleExtension(name: String): Boolean {
    val ext = name.substringAfterLast('.', "").lowercase()
    return ext in SUBTITLE_FILE_EXTENSIONS
  }

  /** true per nomi d'archivio sicuri e candidati sottotitolo: no percorsi, no metadati. */
  internal fun isSafeSubtitleEntryName(rawName: String): Boolean {
    val name = rawName.trim()
    if (name.isEmpty() || name.endsWith('/')) return false
    val plain = name.substringBefore('\\')
    if (plain.startsWith('/') || plain.startsWith("..") || plain.contains("/../")) return false
    if (plain == "__MACOSX" || plain.startsWith("__MACOSX/") || plain.contains("/.")) return false
    return true
  }

  internal fun gunzip(bytes: ByteArray, maxBytes: Int = DEFAULT_MAX_PAYLOAD_BYTES): ByteArray =
    GZIPInputStream(bytes.inputStream().buffered(), Math.min(maxBytes, 1 shl 16)).use { gz ->
      readBounded(gz, maxBytes)
        ?: throw OpenSubtitlesDownloadException(
          OpenSubtitlesDownloadError.INVALID_FILE,
          "Sottotitolo compresso oltre il limite di $maxBytes byte"
        )
    }

  /** Legge al massimo [maxBytes] byte: null indica payload oltre il limite difensivo. */
  private fun readBounded(stream: java.io.InputStream, maxBytes: Int): ByteArray? {
    val output = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024).coerceAtLeast(1))
    val buffer = ByteArray(8 * 1024)
    var total = 0
    while (true) {
      val read = stream.read(buffer)
      if (read < 0) break
      total += read
      if (total > maxBytes) return null
      output.write(buffer, 0, read)
    }
    return output.toByteArray()
  }

  /**
   * Deduce l'estensione del file locale: prima dal contenuto (WEBVTT / ASS / TTML / SRT),
   * poi dal nome origine se dichiara un'estensione nota, infile SRT come fallback
   * (OpenSubtitles converte i file in SubRip su richiesta).
   */
  internal fun inferSubtitleExtension(payload: ByteArray, sourceName: String?): String {
    val head = payload.take(4096).toByteArray().toString(Charsets.UTF_8)
      .replace("\uFEFF", "").trimStart()
    if (head.startsWith("WEBVTT", ignoreCase = true)) return ".vtt"
    if (head.startsWith("[Script Info]", ignoreCase = true) ||
      head.contains("[V4+ Styles]", ignoreCase = true) ||
      head.contains("[V4 Styles]", ignoreCase = true)
    ) return ".ass"
    if (head.startsWith("<?xml", ignoreCase = true) &&
      (head.contains("<tt ", ignoreCase = true) || head.contains("ttml", ignoreCase = true))
    ) return ".ttml"
    if (
      Regex("""\d{1,2}:\d{2}:\d{2}[,.]\d{1,3}\s*-->\s*\d{1,2}:\d{2}:\d{2}[,.]\d{1,3}""")
        .containsMatchIn(head)
    ) {
      return ".srt"
    }

    val fromName = sourceName?.trim()?.substringAfterLast('/')
      ?.substringAfterLast('\\')?.lowercase()
    if (fromName != null) {
      val ext = fromName.substringAfterLast('.', "")
      if (ext in SUBTITLE_FILE_EXTENSIONS) return ".$ext"
    }
    return ".srt"
  }

  /** Nome file locale sicuro: solo alfanumerici, punto, '-' e '_'. */
  internal fun sanitizeFileBaseName(raw: String): String {
    val clean = raw.trim().ifEmpty { "subtitle" }
      .map { ch -> if (ch.isLetterOrDigit() || ch == '-' || ch == '_' || ch == '.') ch else '_' }
      .joinToString("")
      .take(64)
    return clean.ifEmpty { "subtitle" }
  }
}

/**
 * Scarica su file locale un sottotitolo scelto dall'utente nella sezione "Sottotitoli
 * Online (OpenSubtitles)".
 *
 * Flusso (OpenSubtitles REST API v3):
 * 1. Se il risultato ha un `file_id`, chiama `POST /api/v1/download` e usa il campo `link`
 *    della risposta: quella risposta JSON NON è il file, contiene l'URL temporaneo.
 * 2. Scarica l'URL temporaneo (seguendo i redirect), senza mai loggarlo.
 * 3. Decodifica il payload: ZIP → estrazione del file sottotitolo; GZIP → decompressione;
 *    HTML → errore INVALID_FILE.
 * 4. Salva il file in [targetDir] (cache dell'app) con estensione corretta e restituisce
 *    il [File] locale pronto per essere allegato al player come traccia esterna.
 *
 * Nessuna API key viene inviata al download del file (il link temporaneo è pre-autorizzato)
 * e nessun URL temporaneo viene stampato nei log.
 */
open class OpenSubtitlesSubtitleDownloader(
  private val api: OpenSubtitlesApi = OpenSubtitlesApiClient.api,
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
  private val client: okhttp3.OkHttpClient = defaultDownloadClient(),
  private val maxPayloadBytes: Int = OpenSubtitlesPayloadCodec.DEFAULT_MAX_PAYLOAD_BYTES
) {

  /**
   * Scarica e salva localmente il sottotitolo.
   *
   * @return [Result.success] con il file locale (estensione coerente col contenuto);
   *   [Result.failure] con [OpenSubtitlesDownloadException] per i fallimenti previsti.
   */
  open suspend fun download(subtitle: Subtitle, targetDir: File): Result<File> =
    withContext(ioDispatcher) {
      runCatching {
        coroutineContext.ensureActive()
        val url = resolveDownloadUrl(subtitle).getOrThrow()
        val raw = try {
          fetchBytes(url)
        } catch (e: kotlinx.coroutines.CancellationException) {
          throw e
        } catch (e: OpenSubtitlesDownloadException) {
          throw e
        } catch (e: SocketTimeoutException) {
          throw OpenSubtitlesDownloadException(
            OpenSubtitlesDownloadError.TIMEOUT,
            "Timeout durante il download del sottotitolo",
            e
          )
        } catch (e: IOException) {
          throw OpenSubtitlesDownloadException(
            OpenSubtitlesDownloadError.NETWORK,
            "Errore di rete durante il download del sottotitolo: ${e.javaClass.simpleName}",
            e
          )
        }

        val payload = decodePayload(raw)
        val sourceName = File(url.substringBefore('#').substringBefore('?')).name
        val extension = OpenSubtitlesPayloadCodec.inferSubtitleExtension(payload, sourceName)
        targetDir.mkdirs()
        val outputFile = File(
          targetDir,
          "os_${OpenSubtitlesPayloadCodec.sanitizeFileBaseName(subtitle.id)}$extension"
        )
        outputFile.writeBytes(payload)
        outputFile
      }
    }

  /**
   * Risolve l'URL dal quale scaricare il file: `file_id` → POST /download → link
   * temporaneo; in assenza di file_id, l'URL diretto già presente nel sottotitolo.
   */
  open suspend fun resolveDownloadUrl(subtitle: Subtitle): Result<String> =
    try {
      val fileId = subtitle.fileId
      if (fileId != null && fileId > 0) {
        val response = api.requestDownloadLink(OpenSubtitlesDownloadRequest(fileId))
        if (!response.isSuccessful) {
          throw OpenSubtitlesDownloadException(
            OpenSubtitlesDownloadError.HTTP,
            "OpenSubtitles /download HTTP ${response.code()}"
          )
        }
        val link = response.body()?.link?.trim().orEmpty()
        if (!link.startsWith("http://") && !link.startsWith("https://")) {
          throw OpenSubtitlesDownloadException(
            OpenSubtitlesDownloadError.NO_DOWNLOAD_LINK,
            "La risposta di OpenSubtitles non contiene un link temporaneo utilizzabile"
          )
        }
        Result.success(link)
      } else {
        val direct = subtitle.url.trim()
        if (direct.startsWith("http://") || direct.startsWith("https://")) {
          Result.success(direct)
        } else {
          throw OpenSubtitlesDownloadException(
            OpenSubtitlesDownloadError.NO_DOWNLOAD_LINK,
            "Il sottotitolo selezionato non ha file_id né URL di download"
          )
        }
      }
    } catch (e: kotlinx.coroutines.CancellationException) {
      throw e
    } catch (e: OpenSubtitlesDownloadException) {
      Result.failure(e)
    } catch (e: Exception) {
      Result.failure(
        OpenSubtitlesDownloadException(
          OpenSubtitlesDownloadError.NETWORK,
          "Errore durante la risoluzione del link di download: ${e.javaClass.simpleName}",
          e
        )
      )
    }

  /**
   * Scarica i byte dal link temporaneo. I redirect vengono seguiti automaticamente da
   * OkHttp; il client NON ha logging interceptor: gli URL temporanei non finiscono nei log.
   */
  open suspend fun fetchBytes(url: String): ByteArray = withContext(ioDispatcher) {
    val request = okhttp3.Request.Builder()
      .url(url)
      .header("Accept", "*/*")
      .header("User-Agent", OpenSubtitlesApiClient.DEFAULT_USER_AGENT)
      .build()
    val response = try {
      client.newCall(request).execute()
    } catch (e: SocketTimeoutException) {
      throw OpenSubtitlesDownloadException(
        OpenSubtitlesDownloadError.TIMEOUT,
        "Timeout durante il download del file sottotitolo",
        e
      )
    } catch (e: IOException) {
      throw OpenSubtitlesDownloadException(
        OpenSubtitlesDownloadError.NETWORK,
        "Errore di rete durante il download del file sottotitolo: ${e.javaClass.simpleName}",
        e
      )
    }
    response.use { resp ->
      if (!resp.isSuccessful) {
        throw OpenSubtitlesDownloadException(
          OpenSubtitlesDownloadError.HTTP,
          "Download sottotitolo HTTP ${resp.code}"
        )
      }
      val body = resp.body
        ?: throw OpenSubtitlesDownloadException(
          OpenSubtitlesDownloadError.INVALID_FILE,
          "Risposta di download priva di corpo"
        )
      val stream = body.byteStream()
      val output = ByteArrayOutputStream()
      val buffer = ByteArray(8 * 1024)
      var total = 0
      while (true) {
        val read = stream.read(buffer)
        if (read < 0) break
        total += read
        if (total > maxPayloadBytes) {
          throw OpenSubtitlesDownloadException(
            OpenSubtitlesDownloadError.INVALID_FILE,
            "Il file sottotitolo supera il limite di $maxPayloadBytes byte"
          )
        }
        output.write(buffer, 0, read)
      }
      output.toByteArray()
    }
  }

  /** ZIP → estrazione; GZIP → decompressione; HTML → errore; vuoto → errore. */
  private fun decodePayload(raw: ByteArray): ByteArray {
    if (raw.isEmpty()) {
      throw OpenSubtitlesDownloadException(
        OpenSubtitlesDownloadError.EMPTY_FILE,
        "Il contenuto scaricato è vuoto"
      )
    }
    if (OpenSubtitlesPayloadCodec.isZip(raw)) {
      val extracted = OpenSubtitlesPayloadCodec.extractFirstSubtitleFromZip(raw, maxPayloadBytes)
        ?: throw OpenSubtitlesDownloadException(
          OpenSubtitlesDownloadError.INVALID_FILE,
          "Archivio ZIP privo di un file sottotitolo utilizzabile"
        )
      return extracted
    }
    if (OpenSubtitlesPayloadCodec.isGzip(raw)) {
      return try {
        OpenSubtitlesPayloadCodec.gunzip(raw, maxPayloadBytes)
      } catch (e: OpenSubtitlesDownloadException) {
        throw e
      } catch (e: Exception) {
        throw OpenSubtitlesDownloadException(
          OpenSubtitlesDownloadError.INVALID_FILE,
          "Archivio GZIP non valido: ${e.javaClass.simpleName}",
          e
        )
      }
    }
    if (OpenSubtitlesPayloadCodec.looksLikeHtml(raw)) {
      throw OpenSubtitlesDownloadException(
        OpenSubtitlesDownloadError.INVALID_FILE,
        "Il download ha restituito una pagina HTML, non un file sottotitolo"
      )
    }
    return raw
  }

  companion object {
    /**
     * Client HTTP dedicato al download: NESSUN logging interceptor (gli URL temporanei
     * di OpenSubtitles non devono finire nei log) e timeout corte: è un file piccolo.
     */
    fun defaultDownloadClient(timeoutSeconds: Long = 20L): okhttp3.OkHttpClient =
      okhttp3.OkHttpClient.Builder()
        .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
  }
}

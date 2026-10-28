package com.example.data.stremio

import android.util.Log
import com.squareup.moshi.Moshi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

private const val TAG = "AddonSyncServer"
private const val PORT = 8080

/** Limite massimo delle intestazioni HTTP (16 KB): oltre, la richiesta è malformata. */
private const val MAX_HEADER_BYTES = 16 * 1024

/** Limite massimo del corpo di una richiesta (64 KB): un Content-Length maggiore viene rifiutato senza leggere. */
private const val MAX_BODY_BYTES = 64 * 1024

/** Timeout per operazioni di rete su un singolo client: mai attendere all'infinito. */
private const val CLIENT_TIMEOUT_MS = 10_000

/**
 * Micro-server HTTP locale per la configurazione addon da smartphone.
 *
 * Espone su `http://<IP_LOCALE>:8080`:
 *  - `GET /`            → pagina HTML dark theme (installa/rimuovi addon);
 *  - `GET /api/addons`  → JSON con la lista degli addon configurati;
 *  - `POST /api/addons` → `{"url":"..."}` → installa via [StremioAddonRepository.installAddon];
 *  - `DELETE /api/addons/{id}` → rimuove l'addon.
 *
 * Contratto HTTP (anti-deadlock):
 *  - gli header vengono letti riga-per-riga fino alla riga vuota (`\r\n\r\n`), con cap di
 *    [MAX_HEADER_BYTES]: nessuna attesa infinita per byte che non arrivano;
 *  - GET/HEAD/DELETE non leggono MAI un corpo, anche se un client stravagante dichiara
 *    `Content-Length`;
 *  - il corpo (solo POST) viene letto per esattamente i byte di `Content-Length`
 *    (cap [MAX_BODY_BYTES]), con supporto `Expect: 100-continue`;
 *  - ogni risposta dichiara `Content-Length` + `Connection: close`, viene fatta `flush()`
 *    e il socket del client viene chiuso: il browser sa sempre dove finisce la risposta;
 *  - ogni client è gestito su un thread dedicato, così un client lento non blocca gli altri.
 *
 * Il server va avviato con [start] entrando nella schermata addon e arrestato
 * con [stop] alla chiusura. Lo stato è osservabile tramite [serverState] e
 * l'URL raggiungibile tramite [serverUrl] (per il QR Code).
 */
object AddonSyncServer {
  private val moshi: Moshi = Moshi.Builder().build()

  private var serverSocket: ServerSocket? = null
  private var serverThread: Thread? = null
  private val clientSockets: MutableSet<Socket> = ConcurrentHashMap.newKeySet()

  private val _serverState = MutableStateFlow<ServerState>(ServerState.Idle)
  val serverState: StateFlow<ServerState> = _serverState.asStateFlow()

  private val _serverUrl = MutableStateFlow<String?>(null)
  val serverUrl: StateFlow<String?> = _serverUrl.asStateFlow()

  fun start() {
    if (serverThread != null) return

    _serverState.value = ServerState.Starting
    val socket = try {
      ServerSocket(PORT, 50, InetAddress.getByName("0.0.0.0"))
    } catch (e: Exception) {
      _serverState.value = ServerState.Error("Porta $PORT non disponibile")
      Log.w(TAG, "Impossibile aprire la porta $PORT: ${e.message}")
      return
    }

    serverSocket = socket
    serverThread = Thread {
      val url = "http://${getLocalIpAddress()}:${socket.localPort}"
      _serverUrl.value = url
      _serverState.value = ServerState.Running(url)
      try {
        while (!socket.isClosed) {
          try {
            val client = socket.accept()
            clientSockets.add(client)
            // Thread dedicato per client: un client lento o in attesa non blocca
            // l'accept loop né gli altri client.
            thread(isDaemon = true, name = "AddonSyncClient") {
              try {
                handleClient(client)
              } finally {
                clientSockets.remove(client)
                closeQuietly(client)
              }
            }
          } catch (e: IOException) {
            if (socket.isClosed) break
            Log.w(TAG, "Errore accept: ${e.message}")
          }
        }
      } catch (e: Exception) {
        _serverState.value = ServerState.Error(e.localizedMessage ?: "Errore sconosciuto")
        Log.e(TAG, "Errore server", e)
      } finally {
        closeQuietly(socket)
        if (serverSocket === socket) {
          serverSocket = null
          serverThread = null
        }
        _serverUrl.value = null
        _serverState.value = ServerState.Stopped
      }
    }.also {
      it.isDaemon = true
      it.name = "AddonSyncServer"
      it.start()
    }
  }

  fun stop() {
    val socket = serverSocket
    serverSocket = null
    serverThread = null
    _serverUrl.value = null
    _serverState.value = ServerState.Stopped
    // Chiude ServerSocket (sblocca accept) e tutti i client aperti (sblocca le letture).
    closeQuietly(socket)
    val clients = clientSockets.toList()
    clientSockets.clear()
    clients.forEach { closeQuietly(it) }
  }

  // ── Ciclo HTTP per singolo client ──────────────────────────────────────────

  private fun handleClient(socket: Socket) {
    try {
      socket.soTimeout = CLIENT_TIMEOUT_MS
      val input = socket.getInputStream()
      val output = socket.getOutputStream()

      val request = try {
        readRequest(input, output)
      } catch (e: Exception) {
        Log.w(TAG, "Errore parsing HTTP: ${e.message}")
        null
      }

      if (request == null) {
        send(output, 400, "text/plain; charset=UTF-8", "Bad Request")
        return
      }

      val response = route(request)
      // Per HEAD si dichiara il Content-Length ma non si invia il corpo (RFC 7230).
      send(
        output,
        response.statusCode,
        response.contentType,
        response.body,
        includeBody = request.method != "HEAD"
      )
    } catch (e: Exception) {
      Log.e(TAG, "Errore nella gestione richiesta", e)
      try {
        send(socket.getOutputStream(), 500, "text/plain; charset=UTF-8", "Internal Server Error")
      } catch (ignored: Exception) {}
    } finally {
      // Il flush della risposta è già stato fatto in send(): chiudere il socket
      // segnala al browser che la risposta è completa (niente rotellina infinita).
      try { socket.shutdownOutput() } catch (ignored: Exception) {}
      closeQuietly(socket)
    }
  }

  private fun route(request: HttpRequest): HttpResponse = when {
    request.path == "/" &&
      (request.method == "GET" || request.method == "HEAD") -> handleIndex()
    request.path == "/api/addons" && request.method == "GET" -> handleGetAddons()
    request.path == "/api/addons" && request.method == "POST" -> handleAddAddon(request.body)
    request.path.startsWith("/api/addons/") && request.method == "DELETE" ->
      handleRemoveAddon(request.path.removePrefix("/api/addons/"))
    else -> handleNotFound()
  }

  /**
   * Parsing lineare della richiesta: le intestazioni vengono lette riga-per-riga
   * fino alla riga vuota (`\r\n\r\n`), senza buffering sullo stream sottostante
   * (altrimenti i byte del corpo verrebbero consumati dal buffer).
   *
   *  - GET/HEAD/DELETE: nessuna lettura di corpo, si risponde subito;
   *  - POST: supporto `Expect: 100-continue` e lettura di esattamente
   *    `Content-Length` byte (con cap [MAX_BODY_BYTES]).
   */
  private fun readRequest(input: InputStream, output: OutputStream): HttpRequest? {
    val requestLine = readLine(input) ?: return null
    val parts = requestLine.split(" ")
    if (parts.size < 2) return null

    val method = parts[0].uppercase()
    val path = percentDecode(parts[1])

    var headerBytes = 0
    val headers = mutableMapOf<String, String>()
    while (true) {
      val line = readLine(input) ?: break
      headerBytes += line.length + 2
      if (headerBytes > MAX_HEADER_BYTES) return null
      // Riga vuota = fine delle intestazioni: ci si ferma qui, non si attende altro.
      if (line.isEmpty()) break
      val colonIndex = line.indexOf(':')
      if (colonIndex > 0) {
        headers[line.substring(0, colonIndex).trim().lowercase()] =
          line.substring(colonIndex + 1).trim()
      }
    }

    if (method != "POST") {
      // Nessun corpo per GET/HEAD/DELETE: un Content-Length fantasma non deve
      // mai mettere il thread in attesa di byte che non arriveranno.
      return HttpRequest(method, path, headers, "")
    }

    // Senza "100 Continue" il client resta in attesa e il server in attesa del corpo:
    // deadlock reciproco (pagina che non finisce mai di caricare).
    if (headers["expect"].orEmpty().contains("100-continue", ignoreCase = true)) {
      output.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(StandardCharsets.UTF_8))
      output.flush()
    }

    val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
    if (contentLength <= 0) return HttpRequest(method, path, headers, "")
    if (contentLength > MAX_BODY_BYTES) return null
    return HttpRequest(method, path, headers, readBody(input, contentLength))
  }

  private fun readLine(input: InputStream): String? {
    val builder = StringBuilder()
    while (true) {
      val b = input.read()
      if (b == -1) return if (builder.isEmpty()) null else builder.toString()
      if (b == '\n'.code) {
        if (builder.isNotEmpty() && builder.last() == '\r') builder.setLength(builder.length - 1)
        return builder.toString()
      }
      builder.append(b.toChar())
    }
  }

  /** Legge esattamente [contentLength] byte; un EOF prematuro restituisce quanto letto. */
  private fun readBody(input: InputStream, contentLength: Int): String {
    val bytes = ByteArray(contentLength)
    var read = 0
    while (read < contentLength) {
      val n = input.read(bytes, read, contentLength - read)
      if (n <= 0) break
      read += n
    }
    return String(bytes, 0, read, StandardCharsets.UTF_8)
  }

  /** Decodifica percent-encoding preservando il `+` letterale (URLDecoder lo tratta come spazio). */
  private fun percentDecode(rawPath: String): String = try {
    URLDecoder.decode(rawPath.replace("+", "%2B"), StandardCharsets.UTF_8.name())
  } catch (e: Exception) {
    rawPath
  }

  private fun closeQuietly(closeable: java.io.Closeable?) {
    try { closeable?.close() } catch (ignored: Exception) {}
  }

  // ── Endpoint ───────────────────────────────────────────────────────────────

  private fun handleIndex(): HttpResponse =
    HttpResponse(200, "text/html; charset=UTF-8", ADDON_SYNC_PAGE)

  private fun handleGetAddons(): HttpResponse {
    return try {
      val items = StremioAddonRepository.getInstalledAddons().joinToString(",") { addon ->
        """{"id":${jsonString(addon.id)},"name":${jsonString(addon.manifest.displayTitle)},""" +
          """"url":${jsonString(addon.baseUrl)},"enabled":${addon.isEnabled}}"""
      }
      HttpResponse(200, "application/json; charset=UTF-8", "[$items]")
    } catch (e: Exception) {
      HttpResponse(500, "application/json; charset=UTF-8", errorJson(e.message ?: "Errore sconosciuto"))
    }
  }

  private fun handleAddAddon(body: String): HttpResponse {
    return try {
      val parsed = try {
        moshi.adapter(Any::class.java).fromJson(body)
      } catch (e: Exception) {
        null
      }
      val url = (parsed as? Map<*, *>)?.get("url") as? String
      if (url.isNullOrBlank()) {
        return HttpResponse(400, "application/json; charset=UTF-8", errorJson("URL mancante"))
      }

      val addon = runBlocking { StremioAddonRepository.installAddon(url) }
      HttpResponse(
        200,
        "application/json; charset=UTF-8",
        """{"success":true,"name":${jsonString(addon.manifest.displayTitle)}}"""
      )
    } catch (e: IllegalArgumentException) {
      HttpResponse(400, "application/json; charset=UTF-8", errorJson("URL non valido: ${e.message}"))
    } catch (e: IOException) {
      HttpResponse(500, "application/json; charset=UTF-8", errorJson("Errore di rete: ${e.message}"))
    } catch (e: Exception) {
      HttpResponse(500, "application/json; charset=UTF-8", errorJson(e.message ?: "Errore sconosciuto"))
    }
  }

  private fun handleRemoveAddon(id: String): HttpResponse {
    return try {
      val exists = StremioAddonRepository.getInstalledAddons()
        .any { it.id == id || it.baseUrl == id }
      if (!exists) {
        return HttpResponse(404, "application/json; charset=UTF-8", errorJson("Addon non trovato"))
      }
      StremioAddonRepository.removeAddon(id)
      HttpResponse(200, "application/json; charset=UTF-8", """{"success":true}""")
    } catch (e: Exception) {
      HttpResponse(500, "application/json; charset=UTF-8", errorJson(e.message ?: "Errore sconosciuto"))
    }
  }

  private fun handleNotFound(): HttpResponse =
    HttpResponse(404, "application/json; charset=UTF-8", errorJson("Not found"))

  private fun errorJson(message: String): String =
    """{"success":false,"error":${jsonString(message)}}"""

  private fun jsonString(value: String): String = buildString {
    append('"')
    for (c in value) {
      when (c) {
        '"' -> append("\\\"")
        '\\' -> append("\\\\")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
      }
    }
    append('"')
  }

  // ── Scrittura risposta ─────────────────────────────────────────────────────

  /**
   * Invia la risposta completa in un solo blocco: intestazioni con
   * `Content-Type`, `Content-Length` (in byte UTF-8) e `Connection: close`,
   * poi il corpo, poi `flush()`. Il socket viene chiuso dal chiamante.
   */
  private fun send(
    output: OutputStream,
    statusCode: Int,
    contentType: String,
    body: String,
    includeBody: Boolean = true
  ) {
    val bodyBytes = body.toByteArray(StandardCharsets.UTF_8)
    val head = buildString {
      append("HTTP/1.1 ").append(statusCode).append(' ').append(statusText(statusCode)).append("\r\n")
      append("Content-Type: ").append(contentType).append("\r\n")
      append("Content-Length: ").append(bodyBytes.size).append("\r\n")
      append("Connection: close\r\n")
      append("Cache-Control: no-store\r\n")
      append("\r\n")
    }
    output.write(head.toByteArray(StandardCharsets.UTF_8))
    if (includeBody) output.write(bodyBytes)
    output.flush()
  }

  private fun statusText(code: Int): String = when (code) {
    200 -> "OK"
    400 -> "Bad Request"
    404 -> "Not Found"
    500 -> "Internal Server Error"
    else -> "Unknown"
  }

  /**
   * Indirizzo IPv4 locale (Wi-Fi/Ethernet) della TV, es. `192.168.1.24`.
   * Usato per costruire l'URL `http://<IP>:8080` da encodare nel QR Code.
   */
  fun getLocalIpAddress(): String {
    return try {
      val interfaces = java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())
        .filter { it.isUp && !it.isLoopback }
      for (iface in interfaces) {
        val addresses = java.util.Collections.list(iface.inetAddresses)
        for (addr in addresses) {
          if (addr is Inet4Address && !addr.isLoopbackAddress && addr.isSiteLocalAddress) {
            return addr.hostAddress ?: "127.0.0.1"
          }
        }
      }
      "127.0.0.1"
    } catch (e: Exception) {
      Log.w(TAG, "Errore get IP locale: ${e.message}")
      "127.0.0.1"
    }
  }

  private data class HttpRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    val body: String
  )

  private data class HttpResponse(
    val statusCode: Int,
    val contentType: String,
    val body: String
  )

  sealed class ServerState {
    data object Idle : ServerState()
    data object Starting : ServerState()
    data class Running(val url: String) : ServerState()
    data class Error(val message: String) : ServerState()
    data object Stopped : ServerState()
  }
}

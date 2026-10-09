package com.example.data.stremio

import android.util.Log
import com.squareup.moshi.Moshi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

private const val TAG = "AddonSyncServer"
private const val PORT = 8080

/**
 * Micro-server HTTP locale per la configurazione addon da smartphone.
 *
 * Espone su `http://<IP_LOCALE>:8080`:
 *  - `GET /`            → pagina HTML dark theme (installa/rimuovi addon);
 *  - `GET /api/addons`  → JSON con la lista degli addon configurati;
 *  - `POST /api/addons` → `{"url":"..."}` → installa via [StremioAddonRepository.installAddon];
 *  - `DELETE /api/addons/{id}` → rimuove l'addon.
 *
 * Il server va avviato con [start] entrando nella schermata addon e arrestato
 * con [stop] alla chiusura. Lo stato è osservabile tramite [serverState] e
 * l'URL raggiungibile tramite [serverUrl] (per il QR Code).
 */
object AddonSyncServer {
  private val moshi: Moshi = Moshi.Builder().build()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private var serverSocket: ServerSocket? = null
  private var serverThread: Thread? = null

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
            scope.launch { handleRequest(client) }
          } catch (e: IOException) {
            if (socket.isClosed) break
            Log.w(TAG, "Errore accept: ${e.message}")
          }
        }
      } catch (e: Exception) {
        _serverState.value = ServerState.Error(e.localizedMessage ?: "Errore sconosciuto")
        Log.e(TAG, "Errore server", e)
      } finally {
        try { socket.close() } catch (ignored: Exception) {}
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
    // Chiudere il ServerSocket sblocca accept() e fa terminare il thread.
    try { socket?.close() } catch (ignored: Exception) {}
  }

  private suspend fun handleRequest(socket: Socket) {
    try {
      socket.soTimeout = 15000
      val input = socket.getInputStream()
      val output = socket.getOutputStream()

      val request = parseHttpRequest(input) ?: run {
        sendError(output, 400, "Bad Request")
        return
      }

      val response = when {
        request.path == "/" && request.method == "GET" -> handleIndex()
        request.path == "/api/addons" && request.method == "GET" -> handleGetAddons()
        request.path == "/api/addons" && request.method == "POST" -> handleAddAddon(request.body)
        request.path.startsWith("/api/addons/") && request.method == "DELETE" ->
          handleRemoveAddon(request.path.removePrefix("/api/addons/"))
        else -> handleNotFound()
      }

      sendResponse(output, response)
    } catch (e: Exception) {
      Log.e(TAG, "Errore nella gestione richiesta", e)
      try {
        sendError(socket.getOutputStream(), 500, "Internal Server Error")
      } catch (ignored: Exception) {}
    } finally {
      try { socket.close() } catch (ignored: Exception) {}
    }
  }

  /**
   * Legge la richiesta HTTP senza buffering: le intestazioni sono letta byte-per-byte
   * così il corpo (JSON del POST) resta disponibile sullo stream sottostante.
   */
  private fun parseHttpRequest(input: InputStream): HttpRequest? {
    return try {
      val requestLine = readLine(input) ?: return null
      val parts = requestLine.split(" ")
      if (parts.size < 2) return null

      val method = parts[0]
      val path = percentDecode(parts[1])

      val headers = mutableMapOf<String, String>()
      while (true) {
        val line = readLine(input) ?: break
        if (line.isEmpty()) break
        val colonIndex = line.indexOf(':')
        if (colonIndex > 0) {
          headers[line.substring(0, colonIndex).trim().lowercase()] =
            line.substring(colonIndex + 1).trim()
        }
      }

      val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
      val body = if (contentLength > 0) readBody(input, contentLength) else ""
      HttpRequest(method, path, headers, body)
    } catch (e: Exception) {
      Log.w(TAG, "Errore parsing HTTP: ${e.message}")
      null
    }
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

  /** Decodifica percent-encoding preservando il `+` letterale (URLDecoder lo trattacome spazio). */
  private fun percentDecode(rawPath: String): String = try {
    URLDecoder.decode(rawPath.replace("+", "%2B"), StandardCharsets.UTF_8.name())
  } catch (e: Exception) {
    rawPath
  }

  private fun handleIndex(): HttpResponse =
    HttpResponse(200, "text/html; charset=utf-8", ADDON_SYNC_PAGE)

  private fun handleGetAddons(): HttpResponse {
    return try {
      val items = StremioAddonRepository.getInstalledAddons().joinToString(",") { addon ->
        """{"id":${jsonString(addon.id)},"name":${jsonString(addon.manifest.displayTitle)},""" +
          """"url":${jsonString(addon.baseUrl)},"enabled":${addon.isEnabled}}"""
      }
      HttpResponse(200, "application/json", "[$items]")
    } catch (e: Exception) {
      HttpResponse(500, "application/json", errorJson(e.message ?: "Errore sconosciuto"))
    }
  }

  private suspend fun handleAddAddon(body: String): HttpResponse {
    return try {
      val parsed = try {
        moshi.adapter(Any::class.java).fromJson(body)
      } catch (e: Exception) {
        null
      }
      val url = (parsed as? Map<*, *>)?.get("url") as? String
      if (url.isNullOrBlank()) {
        return HttpResponse(400, "application/json", errorJson("URL mancante"))
      }

      val addon = StremioAddonRepository.installAddon(url)
      HttpResponse(
        200,
        "application/json",
        """{"success":true,"name":${jsonString(addon.manifest.displayTitle)}}"""
      )
    } catch (e: IllegalArgumentException) {
      HttpResponse(400, "application/json", errorJson("URL non valido: ${e.message}"))
    } catch (e: IOException) {
      HttpResponse(500, "application/json", errorJson("Errore di rete: ${e.message}"))
    } catch (e: Exception) {
      HttpResponse(500, "application/json", errorJson(e.message ?: "Errore sconosciuto"))
    }
  }

  private fun handleRemoveAddon(id: String): HttpResponse {
    return try {
      val exists = StremioAddonRepository.getInstalledAddons()
        .any { it.id == id || it.baseUrl == id }
      if (!exists) {
        return HttpResponse(404, "application/json", errorJson("Addon non trovato"))
      }
      StremioAddonRepository.removeAddon(id)
      HttpResponse(200, "application/json", """{"success":true}""")
    } catch (e: Exception) {
      HttpResponse(500, "application/json", errorJson(e.message ?: "Errore sconosciuto"))
    }
  }

  private fun handleNotFound(): HttpResponse =
    HttpResponse(404, "application/json", errorJson("Not found"))

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

  private fun sendResponse(output: OutputStream, response: HttpResponse) {
    val bodyBytes = response.body.toByteArray(StandardCharsets.UTF_8)
    val head = "HTTP/1.1 ${response.statusCode} ${statusText(response.statusCode)}\r\n" +
      "Content-Type: ${response.contentType}\r\n" +
      "Content-Length: ${bodyBytes.size}\r\n" +
      "Connection: close\r\n\r\n"
    output.write(head.toByteArray(StandardCharsets.UTF_8))
    output.write(bodyBytes)
    output.flush()
  }

  private fun sendError(output: OutputStream, statusCode: Int, message: String) {
    val msgBytes = message.toByteArray(StandardCharsets.UTF_8)
    val head = "HTTP/1.1 $statusCode ${statusText(statusCode)}\r\n" +
      "Content-Type: text/plain\r\n" +
      "Content-Length: ${msgBytes.size}\r\n" +
      "Connection: close\r\n\r\n"
    output.write(head.toByteArray(StandardCharsets.UTF_8))
    output.write(msgBytes)
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

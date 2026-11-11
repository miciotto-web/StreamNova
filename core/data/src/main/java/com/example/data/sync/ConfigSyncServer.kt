package com.example.data.sync

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.data.stremio.AddonSyncServer
import com.squareup.moshi.Moshi
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "ConfigSyncServer"

/** Limite massimo delle intestazioni HTTP (16 KB). */
private const val MAX_HEADER_BYTES = 16 * 1024

/**
 * Limite massimo del corpo di una richiesta (32 MB): il PUSH della libreria porta
 * righe Room complete (sinossi, cast, progressi per episodio), centinaia di KB per
 * collezioni ricche; 32 MB coprono librerie molto grandi con margine.
 */
private const val MAX_BODY_BYTES = 32 * 1024 * 1024

/** Timeout per operazioni di rete su un singolo client. */
private const val CLIENT_TIMEOUT_MS = 15_000

/**
 * Richiesta in arrivo da un altro dispositivo StreamNova, in attesa di approvazione
 * dell'utente SU QUESTO dispositivo. L'approvazione è l'unico varcare di soglia per
 * trasferire la configurazione: chiunque sulla rete possa vedere l'annuncio mDNS,
 * ma senza un OK esplicito nessun dato lascia il dispositivo.
 */
data class SyncIncomingRequest(
  val id: String,
  val peerDeviceName: String,
  val direction: SyncDirection,
  val categories: Set<SyncCategory>,
  val createdAt: Long
)

/** Stato interno di una richiesta approvata/rifiutata, interrogabile dal peer. */
private class TransferSlot(val request: SyncIncomingRequest) {
  @Volatile var status: String = "pending"
  @Volatile var payloadJson: String? = null
  @Volatile var resultSummary: String? = null
}

/**
 * Micro-server HTTP + annuncio mDNS per la modalità Sync di StreamNova.
 *
 * Quando la modalità è attiva il dispositivo si dichiara sulla rete locale con
 * NsdManager (tipo `_streamnova._tcp.`) e risponde su `http://<IP>:8090`:
 *  - `GET  /api/sync/hello`         → identità del dispositivo (nome, modello, versione);
 *  - `POST /api/sync/request`       → il peer chiede un trasferimento (PULL o PUSH),
 *                                     compare la richiesta di approvazione in UI;
 *  - `GET  /api/sync/request/{id}`  → il peer interroga lo stato (pending/approved/denied);
 *  - `GET  /api/sync/config?request={id}` → payload (solo se PULL approvato entro il TTL);
 *  - `POST /api/sync/config?request={id}` → payload del peer (PUSH approvato), applicato qui.
 *
 * Il ciclo HTTP replica il contratto anti-deadlock di [AddonSyncServer]: header letti
 * riga per riga con cap, GET/HEAD/DELETE non leggono mai un corpo, risposta con
 * `Content-Length` + `Connection: close`, un thread per client.
 */
object ConfigSyncServer {

  private val moshi: Moshi = Moshi.Builder().build()

  /** Costruzione asincrona dei payload approvati (lettura Room/DataStore). */
  private val buildScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private var serverSocket: ServerSocket? = null
  private var serverThread: Thread? = null
  private val clientSockets: MutableSet<Socket> = ConcurrentHashMap.newKeySet()

  private var appContext: Context? = null
  private var nsdManager: NsdManager? = null
  private var registrationListener: NsdManager.RegistrationListener? = null
  private val mainHandler = Handler(Looper.getMainLooper())

  /** Nome dispositivo dichiarato nell'annuncio (aggiornabile a caldo). */
  @Volatile private var deviceName: String = ""

  /** Slot delle richieste per id: vita breve (TTL), ripulite a ogni nuova richiesta. */
  private val slots = ConcurrentHashMap<String, TransferSlot>()

  private val _serverState = MutableStateFlow<ServerState>(ServerState.Idle)
  val serverState: StateFlow<ServerState> = _serverState.asStateFlow()

  private val _incomingRequest = MutableStateFlow<SyncIncomingRequest?>(null)
  /** Richiesta da approvare: la UI osserva questo flow e mostra il dialog. */
  val incomingRequest: StateFlow<SyncIncomingRequest?> = _incomingRequest.asStateFlow()

  /** Endpoint locale in ascolto (`ip:porta`), per la voce "IP manuale" dell'altro lato. */
  private val _localEndpoint = MutableStateFlow<String?>(null)
  val localEndpoint: StateFlow<String?> = _localEndpoint.asStateFlow()

  fun start(context: Context, name: String) {
    if (serverThread != null) {
      // Già attivo: basta aggiornare il nome nell'annuncio.
      updateDeviceName(name)
      return
    }
    appContext = context.applicationContext
    deviceName = name

    _serverState.value = ServerState.Starting
    val socket = try {
      ServerSocket(SYNC_SERVER_PORT, 50, java.net.InetAddress.getByName("0.0.0.0"))
    } catch (e: Exception) {
      _serverState.value = ServerState.Error("Porta $SYNC_SERVER_PORT non disponibile")
      Log.w(TAG, "Impossibile aprire la porta $SYNC_SERVER_PORT: ${e.message}")
      return
    }

    serverSocket = socket
    serverThread = Thread {
      val ip = AddonSyncServer.getLocalIpAddress()
      _localEndpoint.value = "$ip:${socket.localPort}"
      _serverState.value = ServerState.Running(_localEndpoint.value ?: "")
      registerOnNsd()
      try {
        while (!socket.isClosed) {
          try {
            val client = socket.accept()
            clientSockets.add(client)
            thread(isDaemon = true, name = "SyncClient") {
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
        _localEndpoint.value = null
        _incomingRequest.value = null
        slots.clear()
        unregisterFromNsd()
        _serverState.value = ServerState.Stopped
      }
    }.also {
      it.isDaemon = true
      it.name = "ConfigSyncServer"
      it.start()
    }
  }

  fun stop() {
    val socket = serverSocket
    serverSocket = null
    serverThread = null
    _serverState.value = ServerState.Stopped
    _localEndpoint.value = null
    _incomingRequest.value = null
    slots.clear()
    closeQuietly(socket)
    val clients = clientSockets.toList()
    clientSockets.clear()
    clients.forEach { closeQuietly(it) }
    unregisterFromNsd()
  }

  /** Cambia il nome dichiarato senza far cadere il server (rinnova solo l'annuncio). */
  fun updateDeviceName(name: String) {
    if (name == deviceName) return
    deviceName = name
    if (serverThread != null) {
      unregisterFromNsd()
      registerOnNsd()
    }
  }

  /**
   * Approva la richiesta in attesa. Per un PULL il payload viene costruito in
   * background (lettura DataStore + Room: nulla deve bloccare il main thread): lo
   * stato passa a `approved` solo quando il payload è pronto, così il GET /config
   * del peer non trova mai una corsa.
   */
  fun approve(id: String) {
    val slot = slots[id] ?: return
    if (slot.status != "pending") return
    if (slot.request.direction == SyncDirection.PULL) {
      val context = appContext
      if (context == null) {
        slot.status = "denied"
        _incomingRequest.value = null
        return
      }
      slot.status = "building"
      _incomingRequest.value = null
      buildScope.launch {
        slot.payloadJson = try {
          SyncConfigPayloadAdapter.toJson(
            SyncConfigBuilder.build(context, deviceName, slot.request.categories)
          )
        } catch (e: Exception) {
          Log.e(TAG, "Costruzione payload fallita", e)
          slot.status = "denied"
          return@launch
        }
        slot.status = "approved"
      }
    } else {
      slot.status = "approved"
      _incomingRequest.value = null
    }
  }

  /** Rifiuta la richiesta in attesa. */
  fun deny(id: String) {
    val slot = slots[id] ?: return
    if (slot.status == "pending") slot.status = "denied"
    _incomingRequest.value = null
  }

  // ── Annuncio NSD ─────────────────────────────────────────────────────────────

  private fun registerOnNsd() {
    val context = appContext ?: return
    val manager = nsdManager ?: context.getSystemService(Context.NSD_SERVICE).let {
      (it as? NsdManager).also { nsd -> nsdManager = nsd }
    }
    val managerReady = manager ?: run {
      Log.w(TAG, "NsdManager non disponibile: il dispositivo non sarà visibile in auto-discovery")
      return
    }

    val info = NsdServiceInfo().apply {
      serviceName = nsdServiceName(deviceName)
      serviceType = SYNC_NSD_SERVICE_TYPE
      port = serverSocket?.localPort ?: SYNC_SERVER_PORT
      setAttribute("app", "streamnova")
      setAttribute("v", SYNC_SCHEMA_VERSION.toString())
      setAttribute("dn", asciiOrFallback(deviceName))
    }

    val listener = object : NsdManager.RegistrationListener {
      override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
        Log.i(TAG, "Sync annunciato sulla rete come «${serviceInfo.serviceName}»")
      }

      override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
        Log.w(TAG, "Registrazione NSD fallita (code=$errorCode)")
      }

      override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
      override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
    }
    registrationListener = listener
    mainHandler.post {
      try {
        managerReady.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
      } catch (e: Exception) {
        Log.w(TAG, "registerService fallito: ${e.message}")
      }
    }
  }

  private fun unregisterFromNsd() {
    val listener = registrationListener ?: return
    registrationListener = null
    val manager = nsdManager ?: return
    mainHandler.post {
      try {
        manager.unregisterService(listener)
      } catch (e: Exception) {
        Log.w(TAG, "unregisterService fallito: ${e.message}")
      }
    }
  }

  /**
   * Nome del servizio NSD: il prefisso `StreamNova` è il filtro di discovery, il
   * suffisso con l'id del processo rende il nome unico sulla rete anche con due
   * dispositivi dello stesso modello (una collisione NSD fallisce la registrazione).
   * Il nome UTENTE visibile in lista arriva dall'attributo TXT `dn`.
   */
  private fun nsdServiceName(name: String): String {
    val sanitized = name.trim().ifBlank { "StreamNova" }
    return "StreamNova · $sanitized · ${UUID.randomUUID().toString().take(4)}"
  }

  /** NsdManager TXT: solo ASCII stampabili, fallback sicuro oltre i limiti. */
  private fun asciiOrFallback(value: String): String {
    val ascii = value.filter { it.code in 0x20..0x7E }.trim()
    return when {
      ascii.isEmpty() -> "StreamNova"
      ascii.length > 60 -> ascii.take(60)
      else -> ascii
    }
  }

  // ── Ciclo HTTP per singolo client ────────────────────────────────────────────

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
        send(output, 400, "application/json; charset=UTF-8", errorJson("Bad Request"))
        return
      }

      val response = try {
        route(request)
      } catch (e: Exception) {
        Log.e(TAG, "Errore route ${request.method} ${request.path}", e)
        HttpResponse(500, "application/json; charset=UTF-8", errorJson("Errore interno"))
      }
      send(output, response.statusCode, response.contentType, response.body)
    } catch (e: Exception) {
      Log.e(TAG, "Errore nella gestione richiesta", e)
      try {
        send(socket.getOutputStream(), 500, "application/json; charset=UTF-8", errorJson("Internal Server Error"))
      } catch (ignored: Exception) {}
    } finally {
      try { socket.shutdownOutput() } catch (ignored: Exception) {}
      closeQuietly(socket)
    }
  }

  private fun route(request: HttpRequest): HttpResponse = when {
    request.path == "/api/sync/hello" && request.method == "GET" -> handleHello()
    request.path == "/api/sync/request" && request.method == "POST" -> handleCreateRequest(request.body)
    request.path.startsWith("/api/sync/request/") && request.method == "GET" ->
      handleRequestStatus(request.path.removePrefix("/api/sync/request/"))
    request.path == "/api/sync/config" && request.method == "GET" ->
      handleGetConfig(request.query["request"])
    request.path == "/api/sync/config" && request.method == "POST" ->
      handleReceiveConfig(request.query["request"], request.body)
    else -> HttpResponse(404, "application/json; charset=UTF-8", errorJson("Not found"))
  }

  private fun handleHello(): HttpResponse {
    return HttpResponse(
      200,
      "application/json; charset=UTF-8",
      """{"app":"streamnova","name":${jsonString(deviceName)},""" +
        """"model":${jsonString(Build.MODEL ?: "")},"schemaVersion":$SYNC_SCHEMA_VERSION}"""
    )
  }

  private fun handleCreateRequest(body: String): HttpResponse {
    val parsed = try {
      moshi.adapter(Any::class.java).fromJson(body)
    } catch (e: Exception) {
      null
    } as? Map<*, *> ?: return HttpResponse(400, "application/json; charset=UTF-8", errorJson("Corpo non valido"))

    val peerName = (parsed["deviceName"] as? String)?.trim().orEmpty().ifBlank { "Dispositivo" }
    val direction = SyncDirection.fromName(parsed["direction"] as? String)
      ?: return HttpResponse(400, "application/json; charset=UTF-8", errorJson("Direzione mancante"))
    val categories = SyncCategory.parseList(parsed["categories"] as? List<*>)
    if (categories.isEmpty()) {
      return HttpResponse(400, "application/json; charset=UTF-8", errorJson("Nessuna categoria richiesta"))
    }

    // Una sola richiesta alla volta: la più recente sostituisce la precedente.
    slots.clear()
    val request = SyncIncomingRequest(
      id = UUID.randomUUID().toString(),
      peerDeviceName = peerName,
      direction = direction,
      categories = categories,
      createdAt = System.currentTimeMillis()
    )
    slots[request.id] = TransferSlot(request)
    _incomingRequest.value = request
    return HttpResponse(
      200,
      "application/json; charset=UTF-8",
      """{"requestId":${jsonString(request.id)}}"""
    )
  }

  private fun handleRequestStatus(id: String): HttpResponse {
    val slot = slots[id] ?: return HttpResponse(
      200,
      "application/json; charset=UTF-8",
      """{"status":"unknown"}"""
    )
    return HttpResponse(
      200,
      "application/json; charset=UTF-8",
      """{"status":${jsonString(slot.status)}}"""
    )
  }

  private fun handleGetConfig(requestId: String?): HttpResponse {
    val slot = validApprovedSlot(requestId, SyncDirection.PULL)
      ?: return HttpResponse(403, "application/json; charset=UTF-8", errorJson("Richiesta non approvata"))
    val payload = slot.payloadJson
      ?: return HttpResponse(500, "application/json; charset=UTF-8", errorJson("Payload non disponibile"))
    return HttpResponse(200, "application/json; charset=UTF-8", payload)
  }

  private fun handleReceiveConfig(requestId: String?, body: String): HttpResponse {
    val slot = validApprovedSlot(requestId, SyncDirection.PUSH)
      ?: return HttpResponse(403, "application/json; charset=UTF-8", errorJson("Richiesta non approvata"))
    val context = appContext
      ?: return HttpResponse(500, "application/json; charset=UTF-8", errorJson("Contesto non disponibile"))
    val payload = try {
      SyncConfigPayloadAdapter.fromJson(body)
    } catch (e: Exception) {
      null
    } ?: return HttpResponse(400, "application/json; charset=UTF-8", errorJson("Payload non valido"))

    val result = runBlocking {
      SyncConfigApplier.apply(context, payload, slot.request.categories)
    }
    val summary = result.describe()
    slot.resultSummary = summary
    return HttpResponse(
      200,
      "application/json; charset=UTF-8",
      """{"success":true,"summary":${jsonString(summary)}}"""
    )
  }

  /** La richiesta deve esistere, essere approvata, della direzione giusta e nel TTL. */
  private fun validApprovedSlot(requestId: String?, direction: SyncDirection): TransferSlot? {
    if (requestId.isNullOrBlank()) return null
    val slot = slots[requestId] ?: return null
    if (slot.request.direction != direction) return null
    if (slot.status != "approved") return null
    if (System.currentTimeMillis() - slot.request.createdAt > SYNC_REQUEST_TTL_MS) {
      slot.status = "expired"
      return null
    }
    return slot
  }

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

  // ── Parsing HTTP (contratto di AddonSyncServer) ─────────────────────────────

  private fun readRequest(input: InputStream, output: OutputStream): HttpRequest? {
    val requestLine = readLine(input) ?: return null
    val parts = requestLine.split(" ")
    if (parts.size < 2) return null

    val method = parts[0].uppercase()
    val rawTarget = parts[1]
    val query = mutableMapOf<String, String>()
    val path: String
    val queryIndex = rawTarget.indexOf('?')
    if (queryIndex >= 0) {
      path = percentDecode(rawTarget.substring(0, queryIndex))
      rawTarget.substring(queryIndex + 1).split('&').forEach { pair ->
        val eq = pair.indexOf('=')
        if (eq > 0) {
          query[percentDecode(pair.substring(0, eq))] = percentDecode(pair.substring(eq + 1))
        } else if (pair.isNotEmpty()) {
          query[percentDecode(pair)] = ""
        }
      }
    } else {
      path = percentDecode(rawTarget)
    }

    var headerBytes = 0
    val headers = mutableMapOf<String, String>()
    while (true) {
      val line = readLine(input) ?: break
      headerBytes += line.length + 2
      if (headerBytes > MAX_HEADER_BYTES) return null
      if (line.isEmpty()) break
      val colonIndex = line.indexOf(':')
      if (colonIndex > 0) {
        headers[line.substring(0, colonIndex).trim().lowercase()] =
          line.substring(colonIndex + 1).trim()
      }
    }

    if (method != "POST") {
      return HttpRequest(method, path, query, headers, "")
    }

    if (headers["expect"].orEmpty().contains("100-continue", ignoreCase = true)) {
      output.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(StandardCharsets.UTF_8))
      output.flush()
    }

    val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
    if (contentLength <= 0) return HttpRequest(method, path, query, headers, "")
    if (contentLength > MAX_BODY_BYTES) return null
    return HttpRequest(method, path, query, headers, readBody(input, contentLength))
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

  private fun percentDecode(raw: String): String = try {
    java.net.URLDecoder.decode(raw.replace("+", "%2B"), StandardCharsets.UTF_8.name())
  } catch (e: Exception) {
    raw
  }

  private fun closeQuietly(closeable: java.io.Closeable?) {
    try { closeable?.close() } catch (ignored: Exception) {}
  }

  private fun send(output: OutputStream, statusCode: Int, contentType: String, body: String) {
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
    output.write(bodyBytes)
    output.flush()
  }

  private fun statusText(code: Int): String = when (code) {
    200 -> "OK"
    400 -> "Bad Request"
    403 -> "Forbidden"
    404 -> "Not Found"
    500 -> "Internal Server Error"
    else -> "Unknown"
  }

  private data class HttpRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
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
    data class Running(val endpoint: String) : ServerState()
    data class Error(val message: String) : ServerState()
    data object Stopped : ServerState()
  }
}

/** Encoda un valore query string (usato dal client di [com.example.data.sync.SyncManager]). */
internal fun syncUrlEncode(value: String): String =
  URLEncoder.encode(value, StandardCharsets.UTF_8.name())

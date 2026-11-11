package com.example.data.sync

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private val Context.syncDataStore: DataStore<Preferences> by preferencesDataStore(
  name = "streamnova_sync"
)

private const val TAG = "SyncManager"
private val KEY_SYNC_ENABLED = booleanPreferencesKey("sync_enabled")
private val KEY_SYNC_DEVICE_NAME = stringPreferencesKey("sync_device_name")

/** Intervallo di interrogazione dello stato dell'approvazione sul peer. */
private const val APPROVAL_POLL_MS = 1_200L

/** Tempo massimo di attesa dell'approvazione umana sul peer. */
private const val APPROVAL_TIMEOUT_MS = 120_000L

/**
 * Stato del trasferimento avviato da QUESTO dispositivo (come initiatore).
 * Le richieste in ARRIVO (da approvare) vivono invece in
 * [com.example.data.sync.ConfigSyncServer.incomingRequest].
 */
sealed class SyncTransferState {
  data object Idle : SyncTransferState()
  data class WaitingApproval(val peerName: String, val direction: SyncDirection) : SyncTransferState()
  data class Running(val phase: String) : SyncTransferState()
  data class Denied(val peerName: String) : SyncTransferState()
  data class Completed(val summary: String) : SyncTransferState()
  data class Failed(val message: String) : SyncTransferState()
}

/**
 * Orchestrazione della modalità Sync: attiva/disattiva il server locale e la
 * discovery, avvia i trasferimenti verso i dispositivi trovati (in entrambe le
 * direzioni) ed espone gli stati per la UI delle Impostazioni.
 *
 * Stesso pattern degli altri repository dell'app: singleton `object` inizializzato
 * dall'Application, [StateFlow] come fonte di verità, persistenza su DataStore.
 * L'attivazione sopravvive al riavvio: con la Sync attiva il dispositivo si
 * dichiarare di nuovo sulla rete a ogni avvio dell'app.
 */
object SyncManager {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private var appContext: Context? = null
  private var dataStore: DataStore<Preferences>? = null

  private val _enabled = MutableStateFlow(false)
  /** Modalità Sync attiva: il dispositivo è in ascolto e visibile sulla rete. */
  val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

  private val _deviceName = MutableStateFlow(defaultDeviceName())
  /** Nome con cui questo dispositivo si presenta agli altri. */
  val deviceName: StateFlow<String> = _deviceName.asStateFlow()

  /** Endpoint locale in ascolto (`ip:porta`), null se la Sync è spenta. */
  val localEndpoint: StateFlow<String?> = ConfigSyncServer.localEndpoint

  /** Richiesta in arrivo da approvare/rifiutare (null = nessuna). */
  val incomingRequest: StateFlow<SyncIncomingRequest?> = ConfigSyncServer.incomingRequest

  private val _transferState = MutableStateFlow<SyncTransferState>(SyncTransferState.Idle)
  val transferState: StateFlow<SyncTransferState> = _transferState.asStateFlow()

  /** Dispositivi manualmente aggiunti (per IP), vivono solo per la sessione. */
  private val _manualPeers = MutableStateFlow<List<SyncPeerDevice>>(emptyList())

  private val _devices = MutableStateFlow<List<SyncPeerDevice>>(emptyList())
  /** Dispositivi disponibili (discovery + manuali), pronti per un trasferimento. */
  val devices: StateFlow<List<SyncPeerDevice>> = _devices.asStateFlow()

  private var enabledWrite = false
  private var nameWrite = false
  private var transferJob: Job? = null

  fun init(context: Context) {
    if (dataStore != null) return
    val appContext = context.applicationContext
    this.appContext = appContext
    val store = appContext.syncDataStore
    dataStore = store

    scope.launch {
      store.data.collect { prefs ->
        val isEnabled = if (enabledWrite) _enabled.value else prefs[KEY_SYNC_ENABLED] ?: false
        if (isEnabled != _enabled.value) {
          _enabled.value = isEnabled
          if (isEnabled) startSync() else stopSync()
        }
        val storedName = if (nameWrite) {
          _deviceName.value
        } else {
          prefs[KEY_SYNC_DEVICE_NAME]?.takeIf { it.isNotBlank() }
        }
        val effectiveName = storedName ?: defaultDeviceName()
        if (effectiveName != _deviceName.value) {
          _deviceName.value = effectiveName
        }
      }
    }

    // I dispositivi disponibili sono l'unione tra discovery NSD e inserimenti manuali.
    scope.launch {
      SyncDiscovery.peers.collect { discovered ->
        val manual = _manualPeers.value
        _devices.value = (discovered + manual)
          .distinctBy { it.endpoint }
          .sortedBy { it.displayName.lowercase() }
      }
    }
    scope.launch {
      _manualPeers.collect {
        val discovered = SyncDiscovery.peers.value
        _devices.value = (discovered + it)
          .distinctBy { it.endpoint }
          .sortedBy { it.displayName.lowercase() }
      }
    }
  }

  private fun defaultDeviceName(): String =
    Build.MODEL?.takeIf { it.isNotBlank() } ?: "StreamNova"

  /** Attiva o disattiva la modalità Sync (persistita: sopravvive al riavvio). */
  fun setEnabled(value: Boolean) {
    enabledWrite = true
    _enabled.value = value
    // Persistita in background; il collettore del DataStore è idempotente (al riavvio
    // riparte da solo), qui gestiamo subito il cambio di stato della sessione corrente.
    scope.launch { dataStore?.edit { it[KEY_SYNC_ENABLED] = value } }
    if (value) startSync() else stopSync()
  }

  /** Rinomina questo dispositivo nella rete Sync (persistito). */
  fun setDeviceName(name: String) {
    val clean = name.trim().take(40).ifBlank { defaultDeviceName() }
    nameWrite = true
    _deviceName.value = clean
    ConfigSyncServer.updateDeviceName(clean)
    scope.launch { dataStore?.edit { it[KEY_SYNC_DEVICE_NAME] = clean } }
  }

  private fun startSync() {
    val context = appContext ?: return
    ConfigSyncServer.start(context, _deviceName.value)
    scope.launch {
      // Il server pubblica l'endpoint appena la porta è aperta: con quello si può
      // escludere il proprio dispositivo dalla discovery.
      val endpoint = withTimeoutOrNull(3_000L) {
        ConfigSyncServer.localEndpoint.first { it != null }
      }
      if (_enabled.value) {
        SyncDiscovery.start(context, endpoint)
      }
    }
  }

  private fun stopSync() {
    SyncDiscovery.stop()
    ConfigSyncServer.stop()
    if (transferJob != null) {
      transferJob?.cancel()
      transferJob = null
    }
    _transferState.value = SyncTransferState.Idle
  }

  /** Riavvia la ricerca dei dispositivi sulla rete. */
  fun refreshDevices() {
    val context = appContext ?: return
    SyncDiscovery.refresh(context)
  }

  /**
   * Aggiunge un dispositivo per IP (`192.168.1.10` oppure `192.168.1.10:8090`),
   * verificando prima che risponda all'hello. Ritorna null se non raggiungibile.
   */
  suspend fun addManualDevice(rawInput: String): SyncPeerDevice? {
    val input = rawInput.trim()
    if (input.isEmpty()) return null
    val host: String
    val port: Int
    val colon = input.lastIndexOf(':')
    if (colon > 0 && input.substring(colon + 1).all { it.isDigit() }) {
      host = input.substring(0, colon)
      port = input.substring(colon + 1).toIntOrNull() ?: SYNC_SERVER_PORT
    } else {
      host = input
      port = SYNC_SERVER_PORT
    }
    if (host.isEmpty() || host.length > 253) return null

    val hello = runCatching { httpGetJson(host, port, "/api/sync/hello", timeoutSec = 4L) }
      .getOrNull()
      ?: return null
    val name = hello["name"] as? String
    val peer = SyncPeerDevice(
      displayName = name?.takeIf { it.isNotBlank() } ?: host,
      host = host,
      port = port,
      schemaVersion = (hello["schemaVersion"] as? Double)?.toInt() ?: 0
    )
    _manualPeers.value = (_manualPeers.value.filterNot { it.endpoint == peer.endpoint } + peer)
    return peer
  }

  /**
   * Avvia un trasferimento con il peer indicato.
   *
   * @param direction [SyncDirection.PULL] = importa dal peer, [SyncDirection.PUSH] =
   *   invia la configurazione locale al peer. In entrambi i casi l'APPROVAZIONE
   *   spetta al dispositivo che possiede i dati.
   */
  fun startTransfer(peer: SyncPeerDevice, direction: SyncDirection, categories: Set<SyncCategory>) {
    val context = appContext ?: return
    if (categories.isEmpty()) return
    if (transferJob?.isActive == true) {
      _transferState.value = SyncTransferState.Failed("Un trasferimento è già in corso")
      return
    }
    transferJob = scope.launch {
      try {
        runTransfer(context, peer, direction, categories)
      } catch (e: kotlinx.coroutines.CancellationException) {
        _transferState.value = SyncTransferState.Idle
        throw e
      } catch (e: Exception) {
        Log.e(TAG, "Trasferimento fallito", e)
        _transferState.value = SyncTransferState.Failed(e.message ?: "Errore di rete")
      }
    }
  }

  /** Annulla un trasferimento in corso (attesa approvazione o trasferimento). */
  fun cancelTransfer() {
    val job = transferJob
    transferJob = null
    _transferState.value = SyncTransferState.Idle
    scope.launch { job?.cancelAndJoin() }
  }

  /** Chiude il dialog di esito (completato/rifiutato/fallito): torna a Idle. */
  fun dismissTransferResult() {
    if (transferJob?.isActive != true) {
      _transferState.value = SyncTransferState.Idle
    }
  }

  private suspend fun runTransfer(
    context: Context,
    peer: SyncPeerDevice,
    direction: SyncDirection,
    categories: Set<SyncCategory>
  ) {
    _transferState.value = SyncTransferState.WaitingApproval(peer.displayName, direction)

    // 1. Annuncia la richiesta: sul peer compare il dialog di approvazione.
    val announceBody = """{"deviceName":${peerJsonString(_deviceName.value)},""" +
      """"direction":"${direction.name}",""" +
      """"categories":[${categories.joinToString(",") { "\"${it.name}\"" }}]}"""
    val announce = runCatching {
      httpPostJson(peer.host, peer.port, "/api/sync/request", announceBody)
    }.getOrNull() ?: run {
      _transferState.value = SyncTransferState.Failed(
        "«${peer.displayName}» non risponde. Verifica che la Sync sia attiva e di essere sulla stessa rete."
      )
      return
    }
    val requestId = announce["requestId"] as? String
    if (requestId.isNullOrBlank()) {
      val error = announce["error"] as? String ?: "Richiesta rifiutata dal dispositivo"
      _transferState.value = SyncTransferState.Failed(error)
      return
    }

    // 2. Attende l'approvazione umana sul peer.
    val approved = withTimeoutOrNull(APPROVAL_TIMEOUT_MS) {
      while (isActive) {
        val status = runCatching {
          httpGetJson(peer.host, peer.port, "/api/sync/request/$requestId")
        }.getOrNull()
        val state = status?.get("status") as? String
        when (state) {
          "approved" -> return@withTimeoutOrNull true
          "denied" -> {
            _transferState.value = SyncTransferState.Denied(peer.displayName)
            return@withTimeoutOrNull false
          }
          "unknown", null -> {
            _transferState.value = SyncTransferState.Failed(
              "«${peer.displayName}» non riconosce più la richiesta (Sync disattivata?)"
            )
            return@withTimeoutOrNull false
          }
        }
        delay(APPROVAL_POLL_MS)
      }
      false
    } ?: run {
      _transferState.value = SyncTransferState.Failed(
        "Nessuna approvazione da «${peer.displayName}»: richiesta scaduta"
      )
      return
    }
    if (!approved) return

    // 3. Trasferisce e applica.
    when (direction) {
      SyncDirection.PULL -> {
        _transferState.value = SyncTransferState.Running("Download della configurazione…")
        val body = runCatching {
          httpGetText(peer.host, peer.port, "/api/sync/config?request=${syncUrlEncode(requestId)}")
        }.getOrNull() ?: run {
          _transferState.value = SyncTransferState.Failed("Download della configurazione non riuscito")
          return
        }
        val payload = try {
          SyncConfigPayloadAdapter.fromJson(body)
        } catch (e: Exception) {
          null
        } ?: run {
          _transferState.value = SyncTransferState.Failed("Configurazione ricevuta non valida")
          return
        }
        _transferState.value = SyncTransferState.Running("Applicazione della configurazione…")
        val result = SyncConfigApplier.apply(context, payload, categories)
        _transferState.value = SyncTransferState.Completed(result.describe())
      }

      SyncDirection.PUSH -> {
        _transferState.value = SyncTransferState.Running("Preparazione della configurazione…")
        val payload = SyncConfigBuilder.build(context, _deviceName.value, categories)
        val body = SyncConfigPayloadAdapter.toJson(payload)
        _transferState.value = SyncTransferState.Running("Invio a «${peer.displayName}»…")
        val response = runCatching {
          httpPostJson(
            peer.host,
            peer.port,
            "/api/sync/config?request=${syncUrlEncode(requestId)}",
            body
          )
        }.getOrNull() ?: run {
          _transferState.value = SyncTransferState.Failed("Invio della configurazione non riuscito")
          return
        }
        val summary = response["summary"] as? String
          ?: response["error"] as? String
          ?: "Trasferimento completato"
        if (response["success"] == true) {
          _transferState.value = SyncTransferState.Completed(summary)
        } else {
          _transferState.value = SyncTransferState.Failed(summary)
        }
      }
    }
  }

  /** Approva la richiesta in arrivo (dialog lato "dati"). */
  fun approveIncoming() {
    ConfigSyncServer.incomingRequest.value?.let { ConfigSyncServer.approve(it.id) }
  }

  /** Rifiuta la richiesta in arrivo. */
  fun denyIncoming() {
    ConfigSyncServer.incomingRequest.value?.let { ConfigSyncServer.deny(it.id) }
  }

  // ── Client HTTP minimo ───────────────────────────────────────────────────────
  // Il client parla con il server Sync su SOCKET GREZZO, come lato server fa
  // AddonSyncServer: il traffico HTTP in chiaro verso un IP di rete locale verrebbe
  // bloccato dalla network security policy di default (targetSdk 28+), e allargarla
  // a tutta l'app per il solo Sync non è accettabile. Un socket raw non attraversa
  // lo stack HTTP di sistema: nessuna configurazione permissiva nel manifest.

  private val moshiForAny = com.squareup.moshi.Moshi.Builder().build()

  /** Esito di una richiesta raw: codice HTTP e corpo (solo con Content-Length). */
  private data class RawHttpResponse(val code: Int, val body: String)

  private fun rawHttpRequest(
    host: String,
    port: Int,
    method: String,
    path: String,
    body: String? = null,
    timeoutSec: Long = 30L
  ): RawHttpResponse {
    java.net.Socket().use { socket ->
      socket.connect(java.net.InetSocketAddress(host, port), (timeoutSec * 1000L).toInt())
      socket.soTimeout = (timeoutSec * 1000L).toInt()
      val output = socket.getOutputStream()
      val input = socket.getInputStream()

      val head = buildString {
        append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
        append("Host: ").append(host).append(':').append(port).append("\r\n")
        append("Connection: close\r\n")
        if (body != null) {
          append("Content-Type: application/json; charset=UTF-8\r\n")
          append("Content-Length: ").append(body.toByteArray(Charsets.UTF_8).size).append("\r\n")
        }
        append("\r\n")
      }
      output.write(head.toByteArray(Charsets.UTF_8))
      if (body != null) output.write(body.toByteArray(Charsets.UTF_8))
      output.flush()

      // Header riga per riga fino alla riga vuota (stesso contratto del server).
      val statusLine = readRawLine(input) ?: throw IOException("Risposta vuota dal peer")
      val code = statusLine.split(" ").getOrNull(1)?.toIntOrNull()
        ?: throw IOException("Risposta HTTP non valida")
      var contentLength = -1
      while (true) {
        val line = readRawLine(input) ?: break
        if (line.isEmpty()) break
        val colon = line.indexOf(':')
        if (colon > 0 && line.substring(0, colon).trim().equals("Content-Length", ignoreCase = true)) {
          contentLength = line.substring(colon + 1).trim().toIntOrNull() ?: -1
        }
      }

      val bodyText = if (contentLength > 0) {
        val bytes = ByteArray(contentLength)
        var read = 0
        while (read < contentLength) {
          val n = input.read(bytes, read, contentLength - read)
          if (n <= 0) break
          read += n
        }
        String(bytes, 0, read, Charsets.UTF_8)
      } else {
        ""
      }
      return RawHttpResponse(code, bodyText)
    }
  }

  private fun readRawLine(input: java.io.InputStream): String? {
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

  private fun httpGetJson(host: String, port: Int, path: String, timeoutSec: Long = 10L): Map<*, *>? =
    moshiForAny.adapter(Any::class.java)
      .fromJson(httpGetText(host, port, path, timeoutSec)) as? Map<*, *>

  private fun httpGetText(host: String, port: Int, path: String, timeoutSec: Long = 30L): String {
    val response = rawHttpRequest(host, port, "GET", path, timeoutSec = timeoutSec)
    if (response.code < 200 || response.code >= 300) {
      val error = moshiForAny.adapter(Any::class.java)
        .fromJson(response.body) as? Map<*, *>
      throw IOException((error?.get("error") as? String) ?: "HTTP ${response.code}")
    }
    return response.body
  }

  private fun httpPostJson(host: String, port: Int, path: String, body: String): Map<*, *> {
    val response = rawHttpRequest(host, port, "POST", path, body = body, timeoutSec = 180L)
    val parsed = moshiForAny.adapter(Any::class.java)
      .fromJson(response.body) as? Map<*, *>
    if (response.code < 200 || response.code >= 300) {
      throw IOException((parsed?.get("error") as? String) ?: "HTTP ${response.code}")
    }
    return parsed ?: throw IOException("Risposta non valida")
  }

  /** JSON escape per i corpi costruiti a mano (solo stringhe di controllo). */
  private fun peerJsonString(value: String): String = buildString {
    append('"')
    for (c in value) {
      when (c) {
        '"' -> append("\\\"")
        '\\' -> append("\\\\")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
      }
    }
    append('"')
  }
}

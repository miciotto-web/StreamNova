package com.example.data.sync

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "SyncDiscovery"

/** Dispositivo StreamNova trovato sulla rete locale. */
data class SyncPeerDevice(
  /** Nome UTENTE dichiarato dal dispositivo (TXT `dn`). */
  val displayName: String,
  val host: String,
  val port: Int,
  val schemaVersion: Int
) {
  val endpoint: String get() = "$host:$port"
}

/**
 * Discovery NSD/mDNS dei dispositivi StreamNova con la Sync attiva.
 *
 * Cerca i servizi di tipo [SYNC_NSD_SERVICE_TYPE] annunciati da [ConfigSyncServer],
 * risolve ognuno in host/porta e pubblica la lista osservabile. Il proprio endpoint
 * viene escluso (passato da [start]), così nessun dispositivo si trova da solo.
 */
object SyncDiscovery {

  /** Chiave di deduplicazione: lo stesso dispositivo può annunciarsi su più interfacce. */
  private val _peers = MutableStateFlow<List<SyncPeerDevice>>(emptyList())
  val peers: StateFlow<List<SyncPeerDevice>> = _peers.asStateFlow()

  private val mainHandler = Handler(Looper.getMainLooper())
  private var nsdManager: NsdManager? = null
  private var discoveryListener: NsdManager.DiscoveryListener? = null

  /** serviceName NSD → chiave endpoint risolta (per rimuovere il peer su service lost). */
  private val serviceToEndpoint = ConcurrentHashMap<String, String>()

  /** Resolve già in volo: NsdManager tollera pochi resolve concorrenti. */
  private val resolving = ConcurrentHashMap.newKeySet<String>()

  @Volatile private var selfEndpoint: String? = null

  fun start(context: Context, localEndpoint: String?) {
    selfEndpoint = localEndpoint
    val manager = nsdManager ?: context.getSystemService(Context.NSD_SERVICE).let {
      (it as? NsdManager).also { nsd -> nsdManager = nsd }
    } ?: run {
      Log.w(TAG, "NsdManager non disponibile: discovery disattivata")
      return
    }
    if (discoveryListener != null) return

    val listener = object : NsdManager.DiscoveryListener {
      override fun onDiscoveryStarted(serviceType: String) {
        Log.i(TAG, "Discovery Sync avviata ($serviceType)")
      }

      override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
        Log.w(TAG, "Discovery non avviata (code=$errorCode)")
        discoveryListener = null
      }

      override fun onServiceFound(serviceInfo: NsdServiceInfo) {
        if (!serviceInfo.serviceName.startsWith("StreamNova", ignoreCase = true)) return
        if (serviceInfo.serviceName in resolving) return
        resolving.add(serviceInfo.serviceName)
        mainHandler.post {
          try {
            manager.resolveService(
              serviceInfo,
              object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                  resolving.remove(info.serviceName)
                  Log.w(TAG, "Resolve fallito per ${info.serviceName} (code=$errorCode)")
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                  resolving.remove(info.serviceName)
                  addResolved(info)
                }
              }
            )
          } catch (e: Exception) {
            resolving.remove(serviceInfo.serviceName)
            Log.w(TAG, "resolveService fallito: ${e.message}")
          }
        }
      }

      override fun onServiceLost(serviceInfo: NsdServiceInfo) {
        val endpointKey = serviceToEndpoint.remove(serviceInfo.serviceName)
        if (endpointKey != null) {
          _peers.value = _peers.value.filterNot { it.endpoint == endpointKey }
        }
      }

      override fun onDiscoveryStopped(serviceType: String) {
        discoveryListener = null
      }

      override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
        discoveryListener = null
      }
    }
    discoveryListener = listener
    mainHandler.post {
      try {
        manager.discoverServices(SYNC_NSD_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
      } catch (e: Exception) {
        discoveryListener = null
        Log.w(TAG, "discoverServices fallito: ${e.message}")
      }
    }
  }

  /** Riavvia la discovery da zero (pulsante "Cerca dispositivi"). */
  fun refresh(context: Context) {
    stop()
    start(context, selfEndpoint)
  }

  fun stop() {
    val listener = discoveryListener
    discoveryListener = null
    serviceToEndpoint.clear()
    resolving.clear()
    _peers.value = emptyList()
    val manager = nsdManager
    if (listener != null && manager != null) {
      mainHandler.post {
        try {
          manager.stopServiceDiscovery(listener)
        } catch (e: Exception) {
          Log.w(TAG, "stopServiceDiscovery fallito: ${e.message}")
        }
      }
    }
  }

  private fun addResolved(info: NsdServiceInfo) {
    val host = info.host?.hostAddress?.takeIf { it.isNotBlank() } ?: return
    val peer = SyncPeerDevice(
      displayName = info.attributes["dn"]
        ?.toString(Charsets.US_ASCII)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: info.serviceName.removePrefix("StreamNova").trim(' ', '·'),
      host = host,
      port = info.port,
      schemaVersion = info.attributes["v"]?.toString(Charsets.US_ASCII)?.trim()?.toIntOrNull() ?: 0
    )
    // Il proprio endpoint non entra in lista: non ci si sincronizza da soli.
    if (peer.endpoint == selfEndpoint) return
    serviceToEndpoint[info.serviceName] = peer.endpoint
    _peers.value = (_peers.value.filterNot { it.endpoint == peer.endpoint } + peer)
      .sortedBy { it.displayName.lowercase() }
  }
}

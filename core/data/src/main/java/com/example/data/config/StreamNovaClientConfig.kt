package com.example.data.config

import com.example.data.repository.SimklRepository

/**
 * Risorse locali usate dal catalogo demo quando un titolo non ha ancora
 * un'immagine remota. Sono ID di risorsa dell'app che ospita il core: ogni
 * applicazione (TV o mobile) li fornisce esplicitamente, così il layer dati
 * non dipende dal pacchetto `R` di una singola app.
 *
 * Il valore 0 significa "nessun asset configurato": le card useranno il
 * fallback remoto o nessuna immagine, senza crash.
 */
data class MediaSampleAssets(
  val lastOfUs: Int = 0,
  val cosmos: Int = 0,
  val dune: Int = 0
)

/**
 * Configurazione esplicita del client StreamNova condiviso.
 *
 * Le credenziali non sono mai scritte nel codice sorgente: l'app le risolve
 * dalla propria build (variabili d'ambiente, `local.properties`, `.env`) e le
 * consegna qui prima di inizializzare i repository. Lo stesso vale per le
 * differenze tra l'app TV e l'app mobile (versione, canali, asset demo,
 * catalogo seed e generazione episodi).
 */
data class StreamNovaClientConfig(
  /** Identificativo del client: "tv" oppure "mobile". */
  val appId: String,
  val versionName: String,
  val isDebug: Boolean,
  /** Credenziali TMDB in ordine di priorità (valori vuoti o segnaposto sono scartati). */
  val tmdbApiKeyCandidates: List<String?> = emptyList(),
  /**
   * Canali del Client ID SIMKL in ordine di priorità. Devono essere costanti
   * incluse nella build: il dispositivo non possiede le variabili d'ambiente
   * della macchina che ha compilato l'APK.
   */
  val simklClientIdChannels: List<SimklRepository.SimklClientIdChannel> = emptyList(),
  val mediaSampleAssets: MediaSampleAssets = MediaSampleAssets(),
  /**
   * Il client può usare il catalogo seed/demo locale quando Room è ancora vuota.
   *
   * La TV mantiene il proprio comportamento offline-first (default `true`). Mobile
   * lo disattiva: senza cataloghi reali mostra lo stato vuoto/errore, mai titoli
   * inventati.
   */
  val includeDemoCatalog: Boolean = true,
  /**
   * Genera episodi provvisori per le serie di catalogo finché TMDB non fornisce
   * le stagioni reali. La TV mantiene il comportamento storico (default `true`);
   * Mobile lo disattiva per non mostrare episodi sintetici.
   */
  val generateSyntheticEpisodes: Boolean = true
)

/**
 * Sorgente unica della configurazione runtime del core condiviso.
 *
 * L'[android.app.Application] di ciascuna app chiama [install] prima di
 * inizializzare database e repository. In assenza di installazione il core
 * resta inerte: nessuna credenziale, nessun asset locale, versione vuota.
 */
object StreamNovaConfig {

  @Volatile
  private var installed = StreamNovaClientConfig(
    appId = "unconfigured",
    versionName = "",
    isDebug = false
  )

  fun install(config: StreamNovaClientConfig) {
    installed = config
  }

  val current: StreamNovaClientConfig
    get() = installed
}

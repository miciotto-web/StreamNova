package com.example.data.prefs

/**
 * Provider HTTP selezionabili come "principale" nelle impostazioni.
 *
 * [id] coincide con `StreamProvider.providerId` del provider corrispondente:
 * è il valore persistito da [AppSettingsRepository] e usato da
 * [com.example.data.streaming.StreamManager] per avviare il provider scelto
 * per primo e tenere le sue sorgenti in cima alla lista.
 *
 * Restano esclusi:
 *  - TorBox/Stremio: motore Debrid, non streaming HTTP;
 *  - AnimeStreamProvider: viene già prioritizzato automaticamente solo per i
 *    contenuti anime ed è escluso per tutti gli altri titoli.
 */
data class HttpProviderChoice(
  /** Identificativo stabile: coincide con `StreamProvider.providerId`. */
  val id: String,
  /** Etichetta mostrata nel selettore delle impostazioni. */
  val label: String,
  /**
   * `true` per i provider verificati che restano attivi nella modalità
   * automatica standard. Gli altri sono considerati **sperimentali**: vengono
   * eseguiti solo se l'utente abilita il relativo interruttore (o li sceglie
   * esplicitamente come provider principale).
   */
  val defaultEnabled: Boolean = false
)

/**
 * Catalogo dei provider HTTP mostrati nel selettore "Provider HTTP principale".
 *
 * L'ordine è lo stesso di [com.example.data.streaming.StreamManager]: in
 * modalità Automatico i provider partono nell'ordine storico.
 */
object HttpProviderChoices {

  /** Valore che disattiva la scelta manuale (comportamento storico). */
  const val AUTO_ID = "auto"

  val all: List<HttpProviderChoice> = listOf(
    HttpProviderChoice("MultiEmbedProvider", "MultiEmbed"),
    HttpProviderChoice("VixSrcProvider", "VixSrc", defaultEnabled = true),
    HttpProviderChoice("Altadefinizione", "Altadefinizione", defaultEnabled = true),
    HttpProviderChoice("TwoEmbedProvider", "2Embed"),
    HttpProviderChoice("SuperEmbedProvider", "SuperEmbed"),
    HttpProviderChoice("StreamingCommunityProvider", "StreamingCommunity"),
    HttpProviderChoice("Cb01Provider", "CB01 / CB01 Uno"),
    HttpProviderChoice("EurostreamingProvider", "Eurostreaming")
  )

  /** Provider attivi nella modalità automatica standard (verificati). */
  val defaultEnabledIds: Set<String> =
    all.filter { it.defaultEnabled }.map { it.id }.toSet()

  /**
   * Provider sperimentali/alternativi: non vengono eseguiti in automatico
   * finché l'utente non abilita "Provider sperimentali" (o li sceglie come
   * principale). Alcuni sono bloccati da captcha/403, altri (es.
   * StreamingCommunity) funzionano ma condividono il backend con
   * Altadefinizione e produrrebbero sorgenti duplicate se eseguiti insieme.
   */
  val experimentalIds: Set<String> =
    all.filterNot { it.defaultEnabled }.map { it.id }.toSet()

  /** Etichetta del provider, oppure `null` se l'id non è nel catalogo. */
  fun labelFor(id: String?): String? = all.firstOrNull { it.id == id }?.label

  /** True per "auto" o per un provider presente nel catalogo. */
  fun isKnown(id: String?): Boolean = id == AUTO_ID || labelFor(id) != null
}

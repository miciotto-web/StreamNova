package com.example.data.streaming

/**
 * Gerarchia della **selezione automatica** delle sorgenti HTTP.
 *
 * L'ordine dei provider non è più "il primo che risponde": in modalità
 * automatica Altadefinizione ha la precedenza assoluta, seguito da VixSrc e poi
 * da Streaming Community. All'interno di ogni provider vince la risoluzione
 * più alta **effettivamente riproducibile** (4K/UHD > 1440p/QHD > 1080p >
 * 720p); una risoluzione non identificabile resta in fondo e non viene mai
 * promossa.
 *
 * Il componente è puro (nessuna I/O): la verifica della riproducibilità è
 * demandata a [SourceVerifier] nel livello che orchestra la ricerca.
 */
object AutoSourcePriority {

  /** Identificativo stabile del provider Altadefinizione. */
  const val ALTADEFINIZIONE_ID = "Altadefinizione"

  /** Identificativo stabile di VixSrc. */
  const val VIXSRC_ID = "VixSrcProvider"

  /** Identificativo stabile di Streaming Community. */
  const val STREAMING_COMMUNITY_ID = "StreamingCommunityProvider"

  /** Provider alternativi nell'ordine richiesto dalla politica automatica. */
  val alternativeProviderIds: List<String> = listOf(VIXSRC_ID, STREAMING_COMMUNITY_ID)

  /** Provider della gerarchia automatica, nell'ordine di valutazione. */
  val priorityProviderIds: List<String> = listOf(ALTADEFINIZIONE_ID) + alternativeProviderIds

  /** 0 = Altadefinizione, 1 = VixSrc, 2 = Streaming Community, 3 = altri. */
  fun tierOf(providerId: String?): Int {
    val id = providerId?.trim().orEmpty()
    return when {
      id.equals(ALTADEFINIZIONE_ID, ignoreCase = true) -> 0
      id.equals(VIXSRC_ID, ignoreCase = true) -> 1
      id.equals(STREAMING_COMMUNITY_ID, ignoreCase = true) -> 2
      else -> 3
    }
  }

  /** `true` per i provider che la gerarchia automatica valuta in modo esplicito. */
  fun isPriorityProvider(providerId: String?): Boolean =
    tierOf(providerId) < 3

  /**
   * Ordinamento deterministico per la selezione automatica:
   *  1. provider per fascia (Altadefinizione → VixSrc → Streaming Community → altri);
   *  2. risoluzione più alta prima (altezza convenzionale, 0 = sconosciuta in fondo);
   *  3. chiave stabile finale per un ordine riproducibile.
   */
  fun sortForAutomaticSelection(sources: List<StreamSource>): List<StreamSource> =
    sources.sortedWith(
      compareBy<StreamSource> { tierOf(it.sourceProviderId) }
        .thenByDescending { StreamResolution.heightOf(it) }
        .thenBy { stableKey(it) }
    )

  private fun stableKey(source: StreamSource): String = listOfNotNull(
    source.releaseTitle?.takeIf { it.isNotBlank() },
    source.serverName.takeIf { it.isNotBlank() },
    source.quality,
    source.streamUrl?.takeIf { it.isNotBlank() }
  ).joinToString("|").lowercase()
}

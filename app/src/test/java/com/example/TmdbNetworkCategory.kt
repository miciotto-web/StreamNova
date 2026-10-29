package com.example

import com.example.data.repository.MediaRepository
import org.junit.Assume.assumeTrue

/**
 * Marker per i test che chiamano davvero l'API di The Movie Database.
 *
 * Duplicato nel modulo :app perché i test UI/ViewModel restano qui, mentre il
 * catalogo dati e i suoi test vivono in :core:data (dove il test source set non
 * è visibile al modulo applicazione).
 */
interface TmdbNetworkCategory

/** Utilita' per i test che dipendono dal servizio TMDB reale. */
object TmdbNetwork {

  fun assumeConfiguredApiKey() {
    assumeTrue(
      "TMDB_API_KEY non configurata: definiscila in local.properties o come variabile " +
        "d'ambiente per eseguire i test che richiedono il servizio TMDB reale.",
      MediaRepository.getEffectiveApiKey().isNotBlank()
    )
  }
}

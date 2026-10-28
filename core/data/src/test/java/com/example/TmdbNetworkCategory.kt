package com.example

import com.example.data.repository.MediaRepository
import org.junit.Assume.assumeTrue

/**
 * Marker per i test che chiamano davvero l'API di The Movie Database.
 *
 * I test in questa categoria richiedono una chiave TMDB valida configurata localmente
 * (variabile d'ambiente `TMDB_API_KEY` oppure `local.properties`) e l'accesso alla rete.
 * Non sono test unitari: verificano il comportamento contro il servizio reale.
 *
 * La chiave non e' piu' hardcoded nel sorgente (vedi commit security(tmdb)), quindi su una
 * macchina senza configurazione questi test non possono passare: vengono saltati in modo
 * esplicito invece di fallire, e il report li distingue come "skipped".
 */
interface TmdbNetworkCategory

/** Utilita' per i test che dipendono dal servizio TMDB reale. */
object TmdbNetwork {

    /**
     * Salta il test corrente se non e' configurata una chiave TMDB valida.
     * Usare come prima istruzione di ogni test che chiama l'API reale.
     */
    fun assumeConfiguredApiKey() {
        assumeTrue(
            "TMDB_API_KEY non configurata: definiscila in local.properties o come variabile " +
                "d'ambiente per eseguire i test che richiedono il servizio TMDB reale.",
            MediaRepository.getEffectiveApiKey().isNotBlank()
        )
    }
}
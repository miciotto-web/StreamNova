package com.example.data.debrid

import com.example.data.prefs.AppSettingsRepository
import com.example.data.premiumize.PremiumizeRepository
import com.example.data.realdebrid.RealDebridRepository
import com.example.data.torbox.TorBoxRepository

/**
 * Instrada le operazioni Debrid ("risoluzione infoHash", sblocco, verifica
 * configurazione) al servizio selezionato nelle Impostazioni
 * (`AppSettingsRepository.debridService`).
 *
 * Nei punti di codice storici scritti per TorBox (autoselezione, ripresa da
 * «Continua a guardare», sblocco on-demand al click, fast-path stagione) NON
 * c'è alcun riferimento diretto al singolo servizio: tutto passa da qui, così
 * Real-Debrid e Premiumize sono intercambiabili con TorBox senza duplicare la
 * logica di orchestrazione.
 */
object DebridServiceRouter {

  /** Servizio Debrid selezionato nelle Impostazioni (default TORBOX). */
  fun selectedService(): DebridService = AppSettingsRepository.debridService.value

  /**
   * true se il servizio selezionato può essere usato (toggle Instant Debrid ON
   * + chiave API presente per quel servizio).
   */
  fun isConfigured(): Boolean = when (selectedService()) {
    DebridService.TORBOX -> TorBoxRepository.isConfigured()
    DebridService.REALDEBRID -> RealDebridRepository.isConfigured()
    DebridService.PREMIUMIZE -> PremiumizeRepository.isConfigured()
  }

  /**
   * Risolve un infoHash "grezzo" in un URL CDN/diretto con il servizio
   * selezionato, se il torrent è in cache istantanea.
   */
  suspend fun resolveInfoHash(
    infoHash: String,
    fileIdx: Int?,
    season: Int? = null,
    episode: Int? = null
  ): String? = when (selectedService()) {
    DebridService.TORBOX -> TorBoxRepository.resolveInfoHash(infoHash, fileIdx, season, episode)
    DebridService.REALDEBRID -> RealDebridRepository.resolveInfoHash(infoHash, fileIdx, season, episode)
    DebridService.PREMIUMIZE -> PremiumizeRepository.resolveInfoHash(infoHash, fileIdx, season, episode)
  }

  /**
   * Fast-path bingewatching: file dell'episodio richiesto dentro lo stesso
   * torrent di stagione già in uso, sul servizio selezionato.
   */
  suspend fun resolveSeasonPackEpisode(infoHash: String, season: Int, episode: Int): String? =
    when (selectedService()) {
      DebridService.TORBOX -> TorBoxRepository.resolveSeasonPackEpisode(infoHash, season, episode)
      DebridService.REALDEBRID -> RealDebridRepository.resolveSeasonPackEpisode(infoHash, season, episode)
      DebridService.PREMIUMIZE -> PremiumizeRepository.resolveSeasonPackEpisode(infoHash, season, episode)
    }

  /**
   * Rimuove il link memorizzato per `infoHash` + episodio sul servizio
   * selezionato (link appena fallito in riproduzione).
   */
  fun invalidateResolvedLink(infoHash: String, season: Int?, episode: Int?) {
    when (selectedService()) {
      DebridService.TORBOX -> TorBoxRepository.invalidateResolvedLink(infoHash, season, episode)
      DebridService.REALDEBRID -> RealDebridRepository.invalidateResolvedLink(infoHash, season, episode)
      DebridService.PREMIUMIZE -> PremiumizeRepository.invalidateResolvedLink(infoHash, season, episode)
    }
  }

  /**
   * Metadata legacy stagione/episodio per le richieste TorBox che non li
   * passano esplicitamente (ThreadLocal). È un no-op per gli altri servizi,
   * che non usano metadati thread-local.
   */
  fun setRequestMetadata(season: Int?, episode: Int?) {
    if (selectedService() == DebridService.TORBOX) {
      TorBoxRepository.setRequestMetadata(season, episode)
    }
  }
}

package com.example.data.streaming.extractors

import com.example.data.streaming.StreamSource
import java.io.IOException

/**
 * Contratto di un **estrauttore hoster**: riceve l'URL di una pagina embed
 * (es. `maxstream.video/{user}/{code}`, `mixdrop.co/e/{code}`) e restituisce
 * le sorgenti video dirette (HLS `.m3u8` o MP4) con i relativi header.
 *
 * Ogni implementazione deve essere autonoma: segue i redirect, legge la pagina
 * con User-Agent desktop e solleva [IOException] con un messaggio esplicito
 * quando l'hoster protegge il link (captcha, token scaduto, 404...).
 */
interface VideoExtractor {
  suspend fun extract(embedUrl: String): List<StreamSource>
}

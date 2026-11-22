# STREAMNOVA — Stremio HTTP Proxy Streaming Compatibility

**Audit tecnico, correzione e test di regressione**
Data: 2026-11-21 · Branch: `integration/streamnova-1.0.3-w1-w12` · HEAD di partenza: `1e408b7`

---

## 1. Problema iniziale

StreamNova non riesce a riprodurre correttamente alcuni addon Stremio che
restituiscono lo streaming attraverso un **proxy HTTP esterno** (segnalazione
su Streamfix ed EasyStream). Obiettivo: rendere il supporto **generale** a tutto
il sistema di stream HTTP proxy degli addon Stremio, implementandolo nel
livello comune di gestione degli stream, senza eccezioni dedicate a singoli
addon e senza alterare URL, header o la natura delle sorgenti.

## 2. Root cause confermate

La risposta dell'addon arriva intatta (parsing Moshi corretto, URL e
`behaviorHints.proxyHeaders` conservati), ma **due difetti generici a valle**
impediscono la riproduzione degli stream proxy, più una **politica di rete**
che blocca a monte gli URL in chiaro:

### RC-1 (P0) — HLS-via-proxy forzato sul percorso progressivo

`StreamManager.directStreamSource` impostava `isProgressive = true` **per ogni**
stream Stremio con URL diretto, indipendentemente dal contenuto reale. Il
player ([`PlayerScreen.computeIsHlsStream`](app/src/main/java/com/example/ui/screens/PlayerScreen.kt))
decide HLS vs progressivo con questa priorità:

1. estensione nel path/URL (`.m3u8` → HLS, `.mp4`/`.mkv` → progressivo);
2. hint della sorgente (`isProgressive`) — e per gli URL **senza estensione**
   (i link proxy dinamici: `https://proxy.example/stream?token=...`) è
   l'**unico** segnale.

Risultato: una playlist HLS servita da un proxy su URL dinamico veniva
riprodotta con `ProgressiveMediaSource`; gli extractors progressivi di Media3
non riconoscono il testo `#EXTM3U` → `PARSING_CONTAINER_UNSUPPORTED`,
riproduzione completamente compromessa. Nessuno sniffing del Content-Type
esisteva in tutto il percorso.

### RC-2 (P1) — User-Agent perso o duplicato nel trasporto del player

Due evidenze combinate (la seconda dal disassembly dell'AAR
`app/libs/lib-datasource-okhttp-release.aar`, metodo
`OkHttpDataSource.makeRequest`):

- `PlayerHttpDataSourceFactory.updateHeaders` cercava l'UA con chiave
  **case-sensitive** (`headers["User-Agent"]`): gli addon dichiarano spesso
  `user-agent` minuscolo, quindi `currentUserAgent` restava quello di default;
- il fork Media3 applica le default request properties con
  `Request.Builder.header()` (replace) e poi **riaggiunge** l'UA della factory
  con `addHeader("User-Agent", …)` (append): un UA lasciato nelle default
  properties partiva **duplicato** sulla richiesta — valori identici quando il
  case combacia, **conflittanti** quando non combacia (UA stream + UA default).
  I proxy che validano lo User-Agent (caso tipico degli stream proxy con
  `proxyHeaders`) rispondono 403 o interpretano l'header sbagliato.

### RC-3 (P1, decisione di prodotto) — Cleartext HTTP bloccato

Con `targetSdk 36` il traffico in chiaro è vietato di default: la variante
release non aveva alcuna deroga, la debug solo per `127.0.0.1/localhost`.
Qualsiasi stream proxy su `http://` falliva immediatamente con
`ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED` prima ancora di contattare il server.
Su richiesta esplicita del responsabile, il cleartext è stato **consentito
ovunque** (stesso comportamento del client Stremio Android).

## 3. Cause possibili ma non dimostrate

- Che Streamfix/EasyStream restituiscano URL `http://` in chiaro (RC-3) o HLS
  senza estensione `.m3u8` (RC-1): coerente con l'architettura descritta, ma
  senza l'interrogazione reale degli addon non è osservabile da questo
  ambiente. RC-1 e RC-2 sono difetti generici **confermati nel codice** che
  colpiscono qualunque addon con quelle caratteristiche.
- Configurazioni proxy con chiavi HLS autenticate: mai osservate in
  produzione; il percorso header→chiavi è comunque corretto per costruzione
  (stessa `DataSource.Factory`).

## 4. Architettura prima

```
Addon → Moshi → StremioStreamItem{url, behaviorHints.proxyHeaders}
      → StreamManager.directStreamSource      ← isProgressive = true HARDCODED
      → StreamSource{streamUrl, headers}
      → PlayerPlaybackState{streamHeaders, streamProgressive}
      → PlayerScreen.computeIsHlsStream       ← solo estensione URL + hint
      → PlayerHttpDataSourceFactory           ← UA case-sensitive + UA duplicato
      → OkHttpDataSource (fork Media3) → ExoPlayer
```

## 5. Architettura dopo

```
Addon → Moshi → StremioStreamItem{url, behaviorHints.proxyHeaders}
      → StreamManager.directStreamSource      ← fast-path estensione (tristate)
      → StremioHttpStreamClassifier.refineSources   ← NUOVO: sonda sul contenuto
        (max 8 sonde parallele con gli header dello stream; Content-Type →
         #EXTM3U / ftyp → HLS|PROGRESSIVE; inconclusivo → null)
      → StreamSource{streamUrl, headers, isProgressive corretto}
      → PlayerScreen.computeIsHlsStream       ← invariato: ora l'hint è corretto
      → PlayerHttpDataSourceFactory           ← UA case-insensitive, un solo UA
      → OkHttpDataSource (fork Media3) → ExoPlayer
```

Il classificatore è un **livello comune** (`data/streaming`, addon-agnostico):
viene applicato nei due punti in cui nascono le sorgenti Stremio dirette
(`resolveStremioAddonSources` e canali live nel ViewModel). Nessun URL viene
alterato; nessuna logica Debrid applicata; nessuna dipendenza nuova; nessun
proxy locale; nessun refactoring fuori dal perimetro.

## 6. File creati

| File | Contenuto |
|---|---|
| `app/src/main/java/com/example/data/streaming/StremioHttpStreamClassifier.kt` | Classificazione del tipo effettivo (HLS/progressivo) degli stream HTTP Stremio: fast-path sull'estensione del path + sonda HTTP con header dello stream |
| `app/src/test/java/com/example/data/streaming/StremioHttpStreamClassifierTest.kt` | 23 test del classificatore (estensioni, Content-Type, sniffing, sonde, cap, ordine, URL intatti) |
| `app/src/test/java/com/example/data/stremio/StremioStreamProxyParsingTest.kt` | 9 test di parsing Moshi per stream proxy (URL con token byte-per-byte, proxyHeaders case misto, Authorization Basic, campi sconosciuti, infoHash non confuso) |

## 7. File modificati

| File | Modifica |
|---|---|
| `app/src/main/java/com/example/data/streaming/StreamManager.kt` | `directStreamSource`: `isProgressive` tristate dal fast-path estensione (era hardcoded `true`); `resolveStremioAddonSources`: classificazione del contenuto via `refineSources` |
| `app/src/main/java/com/example/ui/viewmodel/StreamNovaViewModel.kt` | Percorso canali live: stessa classificazione dopo la conversione delle sorgenti |
| `app/src/main/java/com/example/ui/screens/player/PlayerHttpDataSourceFactory.kt` | `updateHeaders`: UA cercato case-insensitive, **escluso** dalle default request properties (un solo header UA sulla richiesta), UA dello stream precede il default |
| `app/src/main/AndroidManifest.xml` | `android:usesCleartextTraffic="true"` (decisione esplicita del 2026-11-21) |
| `app/src/debug/res/xml/network_security_config.xml` | `base-config cleartextTrafficPermitted="true"`: la NSC del debug (che avrebbe **preceduto** e annullato il flag del manifest) resta allineata alla release; il domain-config loopback storico resta documentato |
| `app/src/test/java/com/example/data/streaming/StreamManagerDirectSourceTest.kt` | +5 test: `.m3u8` → false, `.mp4` → true, URL dinamico → null, estensione solo in query → null, UA minuscolo propagato intatto |
| `app/src/test/java/com/example/ui/screens/player/PlayerHttpDataSourceFactoryTest.kt` | +3 test: UA case-insensitive, UA escluso dalle default properties, UA vuoto → default |

## 8. Gestione header HTTP

- **Propagazione verificata end-to-end**: `behaviorHints.proxyHeaders.request`
  → `StremioStreamItem.proxyHeaders` → `StreamSource.headers` →
  `PlayerPlaybackState.streamHeaders` → `OkHttpDataSource.Factory.setDefaultRequestProperties`
  → applicate a manifest, playlist di variante, segmenti, chiavi HLS e tracce
  sottotitoli (stessa `DataSource.Factory`).
- **Precedenza definita e corretta**: UA dello stream (case-insensitive) >
  UA di default; UA inviato UNA sola volta per richiesta; gli altri header
  dello stream transitano come dichiarati; i dati a livello `DataSpec`
  (non usati dal percorso media) resterebbero comunque superiori per
  contratto Media3.
- **Nessun cross-contamination**: gli header sono per-sorgente (la factory
  viene aggiornata al cambio sorgente), mai per-addon.

## 9. Gestione URL proxy

- URL conservato byte-per-byte: nessuna riscrittura di query, token o
  parametri firmati (verificato da test dedicati).
- La classificazione non usa più l'estensione come verità: il **contenuto**
  decide quando il path è ambiguo (sonda GET leggera, ≤ 256 KB letti,
  `callTimeout` 4 s, max 8 sonde parallele, con gli header dello stream).
- `isPlayableDirectUrl` resta invariato (rigetto di `.json`/`manifest.json`
  tenuto per prudenza; documentato come limite teorico).

## 10. Gestione HLS

- Playlist principale, playlist di variante, segmenti (`.ts`/`.m4s`), init
  segment e chiavi: tutti caricati dalla stessa factory → header propagati
  a ogni richiesta dipendente.
- URL relativi/assoluti: gestiti da `HlsMediaSource` (Media3), invariato.
- `PlayerLoadErrorHandlingPolicy` invariata (404 su segmento → esclusione
  rendition; 400/401/403/404/410 fatali → fallback sorgente).

## 11. Gestione redirect

- OkHttp `followRedirects(true)` + `followSslRedirects(true)` — nessuna
  gestione custom introdotta (quella standard era già corretta).
- La sonda di classificazione segue i redirect e registra diagnostica
  host-only (`via=redirected`).

## 12. Diagnostica

- Nuovo log `DIAG-CLASSIFY host=… via=… kind=…` (tag `StremioHttpCompat`):
  solo hostname, tipo osservato e Content-Type — mai path, query, token o
  valori di header.
- La classificazione esistente (`PlaybackErrorClassifier`) copriva già DNS,
  connessione rifiutata, timeout, TLS, 401/403/404/429/5xx, manifest/segmento,
  decoder e cleartext con sanitizzazione (`StreamDiagnostics.sanitize`):
  nessun duplicato introdotto.

## 13. Test aggiunti (37 nuovi)

- **Classificatore (23)**: estensioni path (9), Content-Type/corpo (8),
  sonda HTTP con header dello stream (5), tristate e cap (6 di cui cap
  MAX_PROBES, ordine, URL invariati, sorgenti senza URL).
- **Parsing proxy (9)**: URL+token byte-per-byte, percorsi dinamici,
  proxyHeaders case misto / Authorization / response, `notWebReady`+`filename`
  +`videoSize`, campi sconosciuti, torrent non confuso, hints vuoti.
- **directStreamSource (5)**: classificazione tristate + UA minuscolo.
- **Factory player (3)**: UA case-insensitive, dedup UA, UA vuoto.

## 14. Test eseguiti

- Target: le 4 classi interessate → **55 test, 0 fallimenti**
  (classifier 23, parsing 9, directSource 15, factory 8).
- **Suite completa** `:app:testDebugUnitTest` → **2915 test, 0 fallimenti,
  0 errori, 5 skipped** (skip preesistenti). Nessun test esistente modificato
  per "renderlo verde"; gli unici file di test toccati aggiungono casi.
- Test preesistenti della stessa area (`StreamManagerDedupTest`,
  `StremioStreamQualityTest`, `StremioBehaviorHintsDiagTest`,
  `StremioSubtitleAdapterTest`, provider HTTP, Debrid, diagnostica HLS)
  inclusi nella suite completa senza modifiche.

## 15. Build

- `:app:compileDebugKotlin` → **PASS**.
- `:app:assembleDebug` → **PASS** (`BUILD SUCCESSFUL`, exit code 0; APK
  universal/arm64-v8a/armv7/x86/x86_64 generati in `app/build/outputs/apk/debug/`).

## 16. Test reali su emulatore

**NOT VERIFIED.** In questa sessione gli strumenti Android MCP non sono
disponibili (connessione del plugin fallita) e l'uso diretto di `adb` è
bloccato dall'ambiente: l'APK non è stato installato e nessun addon reale
è stato riprodotto. Di conseguenza **Streamfix ed EasyStream NON possono
essere dichiarati risolti**: la correzione è generale e verificata a livello
di unit test + analisi del codice; la conferma empirica richiede una
riproduzione reale con gli addon installati.

## 17. Problemi residui

- URL proxy contenenti `/manifest.json` o terminanti `.json` nel path vengono
  scartati da `isPlayableDirectUrl` (falso negativo teorico; tenuto per non
  allargare la superficie di accettazione).
- `computeIsHlsStream` conserva l'euristica di substring (`.m3u8`/`.mp4`
  anche in query) PRIMA dell'hint: un token contenente `.mp4` su uno stream
  HLS classificato potrebbe essere misrouted (caso estremamente raro; il
  rischio di riordino è stato ritenuto superiore al beneficio).
- Sorgenti oltre il cap di 8 sonde con URL ambiguo restano sul default del
  player (HLS): comportamento identico al preesistente per URL senza estensione.

## 18. Limitazioni

- Nessuna verifica empirica su addon reali/emulatore (vedi §16).
- La modalità `HTTP_WEB` non interroga gli addon Stremio (architettura
  preesistente e invariata): gli stream proxy si risolvono in modalità Debrid
  o dai canali live. Modificarla sarebbe un cambio di prodotto fuori perimetro.
- Content-Type `application/octet-stream` classificato progressivo: un server
  proxy che servisse HLS con quel MIME e corpo che non inizia con `#EXTM3U`
  nei primi byte resterebbe ambiguo (default del player).

## 19. Rischi di regressione

- **TorBox / Real-Debrid / Premiumize**: percorsi torrent invariati
  (`streamUrl=null`, `isProgressive=true` al momento dell'unlock CDN resta).
- **Addoni con URL `.mp4`/`.mkv`**: fast-path produce lo stesso flag
  (`true`) di prima → nessun cambiamento di percorso.
- **Addoni con URL `.m3u8`**: prima `isProgressive=true` ma il player li
  instradava comunque a HLS via substring; ora il flag è corretto (`false`)
  e il percorso resta HLS. Persistita anche nella cache "ultimo flusso".
- **Provider HTTP integrati** (VixSrc, Altadefinizione, ecc.): nessun punto
  di contatto con le modifiche (classificazione solo per sorgenti Stremio).
- **UA**: sorgenti che NON dichiarano UA → default (come prima); sorgenti che
  lo dichiarano → ora arriva un solo header con il valore corretto (prima:
  duplicato o conflittuale). Le richieste verso hoster con UA estrattore non
  cambiano perché gli extractor usano il proprio client, non questa factory.

---

## 20. Stato di compatibilità

- **Streamfix**: compatibilità generale implementata (classificazione dal
  contenuto, header, cleartext). **Verifica empirica pendente** — NON
  dichiarato risolto.
- **EasyStream**: idem Streamfix. Per l'addon Easynews/EasyStream (che
  tipicamente usa `proxyHeaders` con Authorization/UA) il fix UA (RC-2) è
  direttamente pertinente. **Verifica empirica pendente.**
- **Addon normali**: nessun cambiamento di comportamento atteso; regressione
  coperta dai test della suite (dedup, qualità, subtitle, provider HTTP,
  Debrid).

## 21. Tabella finale

| Verifica | Esito |
|---|---|
| Audit root cause | PASS (RC-1/RC-2 confermate su codice+AAR; RC-3 su manifest) |
| Parsing proxy headers | PASS (9 test dedicati, case misto e Authorization) |
| URL proxy preservation | PASS (test byte-per-byte, query/token intatti) |
| Header propagation | PASS (end-to-end, UA unico e corretto; 3 test factory) |
| HLS compatibility | PASS (classificazione dal contenuto + factory condivisa; test 23) |
| Redirect handling | PASS (OkHttp standard, invariato; sonda segue redirect) |
| HTTP Range | PASS (OkHttpDataSource via DataSpec, bytecode verificato; invariato) |
| Streamfix | NOT VERIFIED (nessun addon reale riprodotto) |
| EasyStream | NOT VERIFIED (nessun addon reale riprodotto) |
| Addon HTTP normali | PASS WITH NOTES (percorso invariato; suite regressione verde) |
| Addon HLS normali | PASS WITH NOTES (flag ora corretto, percorso HLS invariato) |
| TorBox regression | PASS (test suite; percorsi torrent invariati) |
| Real-Debrid regression | PASS (test suite; percorso condiviso TorBox invariato) |
| Premiumize regression | PASS (test suite; percorso condiviso invariato) |
| Unit tests | PASS (55 test target, 0 fallimenti) |
| Full test suite | PASS (2915 test, 0 fallimenti, 0 errori, 5 skip preesistenti) |
| compileDebugKotlin | PASS |
| assembleDebug | PASS (exit code 0, APK debug generati) |
| Emulator verification | NOT VERIFIED (MCP Android indisponibile, adb bloccato) |

# STREAMNOVA — Fix errore 502 su stream proxy EasyStream/Easynews

**Data: 2026-11-21** · Branch: `integration/streamnova-1.0.3-w1-w12`
Sintomo riportato: *"Errore di Riproduzione — Errore playback: HTTP 502 Bad
Gateway [ERROR_CODE_IO_BAD_HTTP_STATUS] - Server stream non disponibile (502)"*
durante la riproduzione di uno stream tramite Easy proxy (EasyStream/Easynews).

Nessun commit e nessun push: tutte le modifiche sono nel working tree.

---

## 1. Analisi del sintomo

Il messaggio è la renderizzazione StreamNova di un `InvalidResponseCodeException`
con `responseCode = 502` ricevuta dal player
([`PlayerRuntimeControllerErrorRecovery.kt:105`](app/src/main/java/com/example/ui/screens/player/PlayerRuntimeControllerErrorRecovery.kt)).
Il proxy risponde → DNS/TLS/connessione sani → il fallimento avviene **a valle**:
o il proxy non raggiunge il suo upstream, o **la richiesta del player non è
quella che il proxy/CDN si attende** (credenziali/UA mancanti).

## 2. Root cause confermata (con evidenza)

### L'addon incorpora le credenziali nell'URL, non negli header

Il sorgente dell'addon Easynews/EasyStream
(`sleeyax/stremio-easynews-addon`, `packages/addon/src/utils.ts` →
`createStreamUrl`) costruisce lo stream così:

```ts
const downURLWithAuth = downURL.replace('://', `://${usename}:${password}@`);
return `${downURLWithAuth}/${dlFarm}/${dlPort}`;
// → https://user:pass@video.easynews.com/<farm>/<port>/<postHash.ext>/<title.ext>
```

e `mapStream` (`addon.ts`) compone `behaviorHints` con **solo**
`fileName`/`videoSize`/`bingeGroup`: **nessun `behaviorHints.proxyHeaders`**.
L'autenticazione DEVE avvenire tramite il componente **userinfo dell'URL** —
la convenzione dei link di download dei browser, che lo convertono in header
`Authorization: Basic` (RFC 7617). Gli issue noti degli addon Easynews su
client Android ("unauthorized (Authorization Failed)", easynews-plus-plus #78)
e la documentazione di troubleshooting Easynews confermano che il **502 di
Easynews è un fallimento di autenticazione mascherato**: i client che non
convertono l'userinfo falliscono.

### OkHttp (trasporto di ExoPlayer) scarta l'userinfo in silenzio

`OkHttpDataSource.makeRequest` (fork Media3/NuvioTV in `app/libs`) costruisce
la richiesta con `Request.Builder.url(uri)`: OkHttp **parsa** l'userinfo
(`HttpUrl.username/password`) ma **non lo invia mai** come header. Risultato
StreamNova: la richiesta parte **senza credenziali** → il CDN risponde 502.

NuvioTV gestisce esattamente questo caso con `extractUserInfoAuth`
(`PlayerMediaSourceFactory.kt:1122`: *"Extracts user:pass from a URL's
userinfo component and converts it to a Basic Auth header"*), gap già
identificato nell'audit comparativo del 2026-11-21 (gap #3) e ora confermato
dalla sorgente reale dell'addon.

**Catena causale completa**: addon restituisce `https://user:pass@…` →
StreamNova passa l'URL a OkHttp intatto → OkHttp non autentica → CDN Easynews
rifiuta → Media3 `ERROR_CODE_IO_BAD_HTTP_STATUS` (502) → toast "Server stream
non disponibile".

## 3. Fix implementato (generale, nessuna logica per-addon)

### 3.1 Nuovo: `PlaybackRequestNormalizer` (`data/streaming`)

[`PlaybackRequestNormalizer.kt`](app/src/main/java/com/example/data/streaming/PlaybackRequestNormalizer.kt)
— funzione pura, testabile JVM:

- individua l'userinfo (`user:pass@`) nell'authority dell'URL;
- lo **percent-decodifica** (proteggendo il `+` letterale) e produce
  `Authorization: Basic base64(user:pass)` (RFC 7617; userinfo senza `:` →
  password vuota);
- **rimuove l'userinfo dall'URL** mantenendo path, query, token e fragment
  byte-per-byte;
- **non sovrascrive** un `Authorization` già dichiarato dall'addon in
  `proxyHeaders.request` (case-insensitive);
- URL senza userinfo, non-HTTP, malformati o vuoti → richiesta restituita
  **identica** (nessun valore inventato, nessuna mutazione della mappa caller).

### 3.2 Collegamento nel player (punto unico)

[`PlayerScreen.kt`](app/src/main/java/com/example/ui/screens/PlayerScreen.kt):
la coppia (URL, header) viene normalizzata **una volta** alla derivazione:

```kotlin
val playbackRequest = remember(playbackState.streamUrl, playbackState.streamHeaders) {
  PlaybackRequestNormalizer.normalize(playbackState.streamUrl, playbackState.streamHeaders)
}
val streamHeaders = playbackRequest.headers
val videoUrl = … playbackRequest.url …
```

Copre **tutti** i percorsi di caricamento (primo avvio, cambio episodio,
cambio sorgente manuale, riuso "ultimo flusso", autoplay episodio successivo,
canali live): entrambi i call point di `loadStreamOnPlayer` consumano
`videoUrl`, e `updateHeaders(streamHeaders)` applica l'header Basic alla
factory condivisa (manifest, segmenti, chiavi HLS, sottotitoli).

Corretta anche l'attribuzione/recupero sottotitoli
(`activeVideoUri = videoUrl ?: playbackState.streamUrl`): il confronto con
l'URI della richiesta fallita e il rebuild del MediaItem usano l'URL
**effettivamente caricato** (normalizzato).

### 3.3 Collegamento alla sonda HTTP

[`OkHttpStreamProbe.get`](app/src/main/java/com/example/data/streaming/StreamSourceVerifier.kt)
(classificazione HLS/progressivo e verifica di riproducibilità) normalizza
allo stesso modo: la sonda osserva il comportamento **reale** della
riproduzione, non una richiesta senza credenziali.

### 3.4 Cosa NON è stato toccato

- Nessuna modifica a TorBox/Real-Debrid/Premiumize (URL CDN senza userinfo:
  normalizzazione no-op).
- Nessun rewriting di token/query/filename; nessun proxy locale; nessuna
  dipendenza nuova; nessuna eccezione dedicata a EasyStream (la trasformazione
  vale per QUALSIASI addon che usi userinfo nell'URL — è il comportamento
  standard HTTP dei browser).
- La normale sorgente HTTP senza credenziali non cambia di una riga.

## 4. Test

Nuovi test (`PlaybackRequestNormalizerTest`, 15 casi):

| Caso | Esito atteso |
|---|---|
| URL Easynews-style `user:pass@host/…mp4/…mp4` | `Authorization: Basic` + URL pulito |
| path/query/fragment con token | byte-per-byte invariati |
| password percent-encoded (`p%40ss%2Bx`) | decodificata nell'header Basic |
| userinfo senza password | `Basic base64(user:)` (RFC 7617) |
| altri header dell'addon | preservati, Basic aggiunto senza duplicazioni |
| `Authorization` già presente (anche case diverso) | conservato quello dell'addon, no duplicati |
| URL senza userinfo | richiesta identica (token/query intatti) |
| `@` nel path/query (non userinfo) | nessuna trasformazione |
| URL null/vuoto/non-HTTP/malformato | identico, nessun header inventato |
| mappa caller | mai mutata |

Regressioni ri-eseguite sul target: classificatore proxy (23), direct source
(15), factory UA (8), parsing proxy (9) — **70 test, 0 fallimenti**.

## 5. Build

- `:app:compileDebugKotlin` → **PASS**
- `:app:testDebugUnitTest` (suite completa) + `:app:assembleDebug` → esito in tabella finale.

## 6. Limiti e note

- **502 vs 401**: la classificazione diagnostica StreamNova riporta il codice
  HTTP ricevuto. Easynews risponde con codici 5xx anche per credenziali
  assenti/errate (sigla nota "502 Authentication Failed"): con il fix la
  richiesta ora porta le credenziali, quindi un eventuale 401/502 residuo
  indica credenziali sbagliate nel config dell'addon (o limite di connessioni
  dell'account), non più un difetto del client.
- URL con caratteri non validi per `java.net.URI` (es. password con caratteri
  speciali non percent-encoded nell'addon): fallback prudente = richiesta
  invariata (stesso limite di NuvioTV). Nessun caso noto su Easynews
  (credenziali alfanumeriche).
- Le tracce sottotitolo continuano a usare la factory condivisa (header dello
  stream, Basic incluso): miglioramento futuro già mappato nell'audit NuvioTV
  (gap #5, `SubtitleRoutingDataSourceFactory`), fuori dal perimetro di questo fix.
- **Verifica empirica su emulatore: NOT VERIFIED** (strumenti Android MCP
  indisponibili in sessione, adb bloccato).

## 7. Tabella finale

| Verifica | Esito |
|---|---|
| Root cause 502 (userinfo non convertito in Basic) | PASS (sorgente addon + OkHttp + NuvioTV + issue note) |
| Normalizzatore userinfo→Basic | PASS (15 test) |
| URL/token preservation | PASS (byte-per-byte, test dedicati) |
| Precedenza Authorization addon | PASS |
| Regressione classificatore/sonda | PASS (23 test) |
| Regressione direct source / factory / parsing | PASS (32 test) |
| Suite completa | **PASS — 2930 test, 0 fallimenti, 0 errori, 5 skip preesistenti** |
| `:app:assembleDebug` | **PASS (exit 0, APK debug con il fix, 14:41)** |
| Verifica reale EasyStream su emulatore | NOT VERIFIED (MCP/adb indisponibili) |

**Nota flaky (documentata, non una regressione):** durante il primo run della
suite `EpisodeManualWatchedTest` è fallito una volta su un `assertNotNull`
dopo `upsertExternalItem`; al run completo successivo la classe passa (9/9).
È lo stesso pattern di stato condiviso tra classi Robolectric già corretto dal
team nel commit `60ff095` ("test: snapshot raccolte concorrenti e attesa
DataStore nei flaky") per altre classi: `EpisodeManualWatchedTest` non era
tra le classi corrette. Nessun legame con le modifiche di questo fix
(la persistenza episodi non è toccata).

## 8. Come verificare empiricamente il fix

1. Installare l'APK debug ricostruito (`app/build/outputs/apk/debug/`).
2. Riprodurre uno stream EasyStream/Easynews: la richiesta verso
   `https://user:pass@…` ora parte con `Authorization: Basic` (l'URL ripulito
   compare anche nei log `[NEXT_EP_DEBUG]`/`HTTP_TRACE` senza credenziali).
3. Se il CDN risponde ancora 401/502: credenziali sbagliate nel config
   dell'addon o limite connessioni dell'account Easynews (non più un difetto
   del client: il header Basic è ora presente sulla richiesta).

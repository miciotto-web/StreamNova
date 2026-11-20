# Rapporto di fine lavoro — Integrazione servizi Debrid Real-Debrid e Premiumize accostati a TorBox

**Data:** 19 novembre 2026
**Branch:** `integration/streamnova-1.0.3-w1-w12`
**Build verificata:** `:app:compileDebugKotlin` OK, `:app:assembleDebug` OK
**Test:** suite unit completa `:app:testDebugUnitTest` → **2858 test, 0 fallimenti, 0 errori, 5 skipped**
**Commit/push:** NON eseguiti su richiesta.

---

## 1. Richiesta

Integrare in StreamNova anche i servizi **Real-Debrid** e **Premiumize**, da accostare al
servizio **TorBox** già presente. L'integrazione va fatta nelle Impostazioni, sezione
**"Motore di riproduzione"**, replicando il modello già usato per TorBox: **login tramite
chiave API del servizio selezionato**. Nessun commit/push.

## 2. Modello di integrazione

I tre servizi sono **accostati e intercambiabili**: il motore Debrid (Stremio Addon +
Instant Debrid) parla con **UN solo servizio alla volta**, scelto dall'utente. Per ogni
servizio:

- **Login con chiave API** (popup di testo, chiave mascherata nella riga impostazioni);
- **Verifica account** con l'endpoint utente nativo del servizio, indicatore
  "Collegato" verde / "Verifica Account" rosso, verifica automatica all'ingresso in Impostazioni;
- **Toggle Instant Debrid** per servizio;
- **Risoluzione delle sorgenti** con la stessa strategia NuvioTV usata da TorBox:
  verifica batch della cache istantanea → card con `streamUrl = null` → sblocco
  **on-demand al click** → URL diretto per ExoPlayer;
- **Selezione del file dell'episodio** nei torrent di stagione (pattern `SxxExx`, fallback
  `fileIdx`, fallback video più grande) e cache dei link **distinta per episodio**
  (TTL 15 min, single-flight per hash+episodio).

## 3. File creati (nuovi)

### Data layer — tipi e routing condivisi (`com.example.data.debrid`)
| File | Contenuto |
|---|---|
| `data/debrid/DebridService.kt` | Enum `TORBOX / REALDEBRID / PREMIUMIZE` con valore stabile per DataStore (`fromStorage` con fallback sicuro su TORBOX). |
| `data/debrid/DebridCacheTypes.kt` | Tipi comuni `DebridFile` / `DebridCacheEntry` / interfaccia `DebridCacheGateway` e `DebridEpisodeMatcher` (matcher SxxExx condiviso, stessi pattern del repository TorBox). |
| `data/debrid/DebridServiceRouter.kt` | **Router verso il servizio selezionato**: `isConfigured`, `resolveInfoHash`, `resolveSeasonPackEpisode`, `invalidateResolvedLink`, `setRequestMetadata`. Tutti i punti storici scritti per TorBox passano da qui. |
| `data/debrid/DebridStreamResolver.kt` | Risoluzione condivisa dei candidati torrent: deduplica per installazione+hash+fileIdx, UN solo check multi-hash, costruzione delle `StreamSource` cached (identica a `TorBoxStreamProvider.resolveItems`). |

### Data layer — Real-Debrid (`com.example.data.realdebrid`)
| File | Contenuto |
|---|---|
| `data/realdebrid/RealDebridApi.kt` | Client Retrofit su `https://api.real-debrid.com/rest/1.0/` con autenticazione `Bearer` (chiave letta a ogni richiesta dall'interceptor). Endpoint: `user`, `torrents/instantAvailability/{hash}`, `torrents/addMagnet`, `torrents/info/{id}`, `torrents/selectFiles/{id}`, `unrestrict/link`. DTO Moshi + `setApiForTesting` per i test. |
| `data/realdebrid/RealDebridRepository.kt` | Repository operativo: verifica account (`GET /user`), cache istantanea per hash (`rd` non vuoto → cached), sblocco cached-only `addMagnet → selectFiles (file dell'episodio) → info → unrestrict/link`, cache link TTL 15 min + single-flight, `resolveSeasonPackEpisode`, invalidazione link fallito. |

### Data layer — Premiumize (`com.example.data.premiumize`)
| File | Contenuto |
|---|---|
| `data/premiumize/PremiumizeApi.kt` | Client Retrofit su `https://www.premiumize.me/api/` con chiave API aggiunta come query param `apikey` dall'interceptor. Endpoint: `account/info`, `cache/check` (magnet → booleani), `transfer/create`, `item/details`. DTO Moshi (`@Json(name=...)` sui campi snake_case) + `setApiForTesting`. |
| `data/premiumize/PremiumizeRepository.kt` | Repository operativo: verifica account, cache check via magnet derivato dall'hash (`magnet:?xt=urn:btih:...`), sblocco cached-only `transfer/create → item/details (polling breve) → stream_link` con selezione episodio, cache link TTL + single-flight, invalidazione link fallito. |

### Stream provider
| File | Contenuto |
|---|---|
| `data/streaming/RealDebridStreamProvider.kt` | Provider Debrid Real-Debrid: candidati dagli addon Stremio (anche per IMDb ID), verifica cache, card cached con sblocco on-demand. |
| `data/streaming/PremiumizeStreamProvider.kt` | Provider Debrid Premiumize: stesso schema. |

### Test (nuovi)
| File | Contenuto |
|---|---|
| `test/.../realdebrid/RealDebridRepositoryTest.kt` | 8 test con fake API: parse `instantAvailability` (cached/non-cached), sblocco con selezione file SxxExx (id selezionato = episodio richiesto), link distinti per episodio, riuso solo per lo stesso episodio, hash non cached → nessuno sblocco, chiave non valida (HTTP 401), servizio non configurato → nessuna chiamata. |
| `test/.../premiumize/PremiumizeRepositoryTest.kt` | 9 test con fake API: hash convertiti in magnet, solo hash cached generano transfer, selezione file SxxExx → `stream_link`, cache per episodio, account valido/invalido, servizio non configurato. |
| `test/.../debrid/DebridSharedTypesTest.kt` | 7 test: roundtrip persistenza `DebridService`, matcher episodio (match, preferenza per token di release, episodio assente, best video ignora sample/srt). |
| `test/.../streaming/DebridStreamResolverTest.kt` | 4 test con gateway finto: un solo check multi-hash, solo hash cached generano card con `streamUrl = null`, ordinamento italiano→qualità, servizio non configurato → zero chiamate. |

## 4. File modificati

### `data/prefs/AppSettingsRepository.kt`
- Nuove chiavi DataStore stabili: `debrid_service`, `realdebrid_api_key`,
  `realdebrid_instant_debrid_enabled`, `premiumize_api_key`, `premiumize_instant_debrid_enabled`.
- Nuovi `StateFlow` + setter con flag "scrittura locale" (stesso pattern delle altre
  preferenze, così il read-back asincrono non sovrascrive mai una scelta dell'utente).
- `resetForTesting` aggiornato con i nuovi default.
- `autoDetectStreamingEngineMode()` ora entra in modalità Debrid con **almeno una** chiave
  dei tre servizi (prima solo TorBox).

### `data/streaming/StreamManager.kt`
- `defaultProviders`: aggiunti `RealDebridStreamProvider` e `PremiumizeStreamProvider`
  accanto a `TorBoxStreamProvider` (testa della lista: priorità massima alle sorgenti debrid).
- **Routing per servizio**: in modalità Debrid viene eseguito SOLO il provider del
  servizio selezionato; in HTTP_WEB nessuno (nuovi helper `isDebridProvider(provider)` e
  `isDebridProvider(provider, service)` nel companion).
- `invokeProvider`: rami per i due nuovi provider (firma con ID IMDb, come TorBox).
- Selezione automatica delle sorgenti: sblocco infoHash via `DebridServiceRouter`.

### `ui/viewmodel/StreamNovaViewModel.kt`
- Flussi UI per la selezione servizio e le chiavi: `debridService`, `realDebridApiKey`,
  `premiumizeApiKey`, toggle Instant Debrid per servizio, `setDebridService` (invalida gli
  stati di verifica al cambio servizio).
- Nuovo stato account `DebridAccountState` (Unknown/Checking/NotConfigured/Connected/Error)
  + `realDebridAccountState` / `premiumizeAccountState`.
- `verifyRealDebridAccount()` (`GET /user`) e `verifyPremiumizeAccount()` (`GET /account/info`),
  stesso schema di `verifyTorBoxAccount()`.
- Tutti i percorsi Debrid storici instradati al servizio selezionato via `DebridServiceRouter`:
  autoselezione (`resolveAutoPickSource`), sblocco manuale al click, ripresa da "Continua a
  guardare" (`reusedStreamUnlocker`, `tryResolveNextEpisodeFromSameTorrent`), fallback link
  fallito (`invalidateResolvedLink`), azzeramento metadata legacy.

### `ui/screens/SubScreens.kt` (Impostazioni → "Motore di riproduzione")
- Nuova riga **"Servizio Debrid"** con `TrackSelectionDialog` a tre voci (TorBox, Real-Debrid,
  Premiumize, con chevron e localizzazione).
- Le righe chiave API / stato account / toggle diventano **condizionali al servizio scelto**:
  - TORBOX → righe storiche invariate;
  - REALDEBRID → "Chiave API Real-Debrid" (`TextInputDialog`, chiave mascherata, verifica
    automatica dopo il salvataggio) + stato account + toggle "Usa Real-Debrid Instant Debrid";
  - PREMIUMIZE → stessa struttura per Premiumize.
- Ripristino del focus TV sulle righe che aprono i popup (stesso schema TorBox/OpenSubtitles),
  verifica automatica all'ingresso in Impostazioni anche per i due nuovi servizi.

### `ui/components/DebridSourceCardPresentation.kt` + `SourceSelectionDialog.kt`
- `DebridProvider` esteso con `REALDEBRID` e `PREMIUMIZE` (etichetta "Debrid via …" senza
  asset grafico: nessun logo inventato, l'immagine compare solo per TorBox).
- `providerOf`: una sorgente con `infoHash` ora attribuisce il **servizio selezionato**
  nelle Impostazioni (default TORBOX: comportamento visivo invariato).

### `ui/components/StreamStatusOverlay.kt`
- Il sottotitolo del caricamento ("TorBox • Stremio Addons") segue il servizio selezionato:
  nuove stringhe "Real-Debrid • Stremio Addons" / "Premiumize • Stremio Addons".

### Localizzazioni — 5 file (`values`, `values-en`, `values-es`, `values-fr`, `values-de`)
- Nuove stringhe: `settings_debrid_service` (+ tre nomi servizio), `settings_realdebrid_api_key`,
  `settings_realdebrid_key_hint`, `settings_use_realdebrid_instant`,
  `settings_premiumize_api_key`, `settings_premiumize_key_hint`,
  `settings_use_premiumize_instant`, `stream_status_realdebrid_stremio`,
  `stream_status_premiumize_stremio`.
- Aggiornata `settings_mode_debrid`: "Debrid TorBox (1080p / 4K Ultra HD)" →
  **"Debrid (1080p / 4K Ultra HD)"** (ora il motore Debrid non è più solo TorBox).

### Test aggiornati
- `HttpProviderSelectionTest`: l'esclusione dei provider Debrid dal catalogo HTTP usa il
  nuovo helper `StreamManager.isDebridProvider` (copre i tre servizi).

## 5. Comportamento runtime

- **TorBox selezionato (default)**: nessun cambiamento rispetto a prima — stesse API,
  stesso percorso cached-only, stessa UI.
- **Real-Debrid selezionato**: gli infoHash degli addon Stremio vengono verificati con
  `instantAvailability`; al click: `addMagnet` → selezione file dell'episodio →
  `unrestrict/link` → CDN diretto per ExoPlayer.
- **Premiumize selezionato**: verifica cache via `cache/check` su magnet; al click:
  `transfer/create` → `item/details` → `stream_link` diretto per ExoPlayer.
- L'utente deve comunque configurare gli addon Stremio (Torrentio ecc.): i servizi Debrid
  sbloccano gli infoHash che gli addon forniscono, come già per TorBox.
- La modalità Sync (`SyncConfigApplier`) continua a propagare solo la chiave TorBox:
  estendere il payload di sincronizzazione alle nuove chiavi esula da questa richiesta
  (lavoro possibile come passo successivo).

## 6. Verifiche eseguite

| Verifica | Esito |
|---|---|
| `:app:compileDebugKotlin` | OK |
| `:app:assembleDebug` (validazione risorse incluse) | OK |
| Suite completa `:app:testDebugUnitTest` | **2858 test, 0 failed, 0 errors** (5 skipped, pre-esistenti) |
| Nuovi test dedicati (28) | Tutti verdi |
| Regressione TorBox (`com.example.data.torbox.*`, 12 test) | Tutti verdi |
| Regressione streaming/Stremio/ViewModel (≈800 test selezionati) | Verdi (1 test aggiornato: esclusione famiglia Debrid) |

Nota tecnica: durante i test è stato individuato e corretto un potenziale **deadlock del
single-flight** (la risoluzione avviava il flight e poi tentava di rientrarcisi per lo
sblocco): la struttura dei repository ora chiama direttamente `unlockInternal` dentro il
flight, con commento esplicito nel codice per evitare regressioni.

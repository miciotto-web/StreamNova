# STREAMNOVA 1.2.0 — AUDIT INDIPENDENTE HLS DIAGNOSTICS ENGINE

**Artefatto di audit** — prodotto come unico file aggiunto al repository dall'attività di verifica. Nessun altro file è stato modificato, formattato, ripristinato o cancellato.

---

## 1. SINTESI ESECUTIVA

L'implementazione HLS Diagnostics Engine **esiste realmente** ed è nel complesso di qualità buona-superiore alla media: i componenti puri (classificatore, sessione, registry, parser HLS) sono ben scritti, thread-safe, senza dati inventati, con sanificazione efficace e una suite di test JVM deterministica e significativa (57 nuovi test, tutti verdi, ri-eseguiti durante l'audit). La build `:app:assembleDebug` riesce. Nessuna dipendenza è stata aggiunta. Le priorità dei provider, `StreamManager`, `AutoSourcePriority`, `PlayerLoadErrorHandlingPolicy`, `PlayerSourceFallback`, `StreamNova4KDiag`, le integrazioni TorBox/Stremio/SIMKL e le migrazioni Room sono **intatte**.

Tuttavia l'audit ha individuato **un difetto P1** che rientra esplicitamente tra i criteri di non approvazione:

> **La sessione diagnostica non è isolata tra riproduzioni differenti.** La sessione è legata all'istanza ExoPlayer (`remember(exoPlayer)`), ma il player è volutamente riutilizzato tra episodi e cambi sorgente (invariante documentato in `PlayerScreen.kt`). Un'unica sessione accumula quindi eventi HTTP, rebuffer, errori e diagnosi di più episodi/sorgenti, e `providerId`/`declaredResolution` restano congelati al valore del primo avvio: dopo un cambio sorgente manuale lo stesso tipo (es. HLS→HLS) gli errori della nuova sorgente vengono attribuiti al provider precedente.

Ad essa si aggiungono tre P2 (doppia registrazione dell'errore terminale con gonfiaggio di `attemptCount` e confidenza; categoria `BUFFERING_TIMEOUT` mai valutata a runtime; parser HLS mai collegato ai report reali) e sei P3.

**Verdetto: NON APPROVATO** (criterio: esiste almeno un problema P1). La base tecnica è solida e quasi tutta la funzionalità dichiarata è corretta e verificata; il difetto P1 è localizzato e riparabile con un cambio contenuto (reset/ricreazione sessione al cambio episodio/sorgente).

| Area | Esito |
|---|---|
| Stato repository e integrità | PASS |
| Componenti riusati | PASS WITH NOTES |
| Nuovi componenti (classificatore, sessione, report, registry) | PASS WITH NOTES |
| `HlsPlaylistSummary` (parser) | PASS (ma non integrato a runtime → A-04) |
| Integrazione Media3 / `PlayerScreen` | FAIL (A-01 isolamento sessioni; A-02 doppia registrazione) |
| Risoluzioni e soglie | PASS WITH NOTES (A-05 verticale) |
| Pannello diagnostico | PASS WITH NOTES (A-06 i18n; accesso touch-only come dichiarato) |
| Test | PASS WITH NOTES (copertura puro JVM ottima; listener non coperto; sesto file dichiarato non individuato) |
| Build | PASS |
| Regressioni e perimetro | PASS |
| Limitazioni dichiarate | PASS (tutte reali e giustificate, più due capacità non integrate: A-03, A-04) |

---

## 2. STATO INIZIALE DEL REPOSITORY

Verifiche effettuate prima di ogni analisi (comandi solo lettura):

| Elemento | Valore osservato | Atteso | Esito |
|---|---|---|---|
| Percorso | `D:\VSCODE\Progetti\StreamNova_integration` | idem | ✔ |
| Branch | `integration/streamnova-1.0.3-w1-w12` | idem | ✔ |
| HEAD | `46b6170b11e17d7dd41172b5e506a1a916617829` ("feat(streaming): AnimeWorld, GuardaPlay e nuovi estrattori hoster") | — | registrato |
| `git status --short` | 37 voci: 25 modificati, 3 cancellati, 9 non tracciati | — | registrato |
| Snapshot | `D:\VSCODE\Progetti\_wip_snapshot_streamnova\` — `wip_tracked_2026-11-18.patch` (101 KB) + `wip_untracked_2026-11-18.txt` | — | vedi sotto |

**Modifiche locali presenti (WIP misto, preesistente all'audit).** Il working tree contiene **più feature non committate** sovrapposte:

1. **WIP coperto dallo snapshot** (categorie Ricerca "TV Show", multi-audio VixCloud, annuncio Telegram): `TmdbApi.kt`, `MediaModels.kt`, `HeroSynopsisLocalizer.kt`, `MediaRepository.kt`, `StreamModels.kt` (campi `preferredAudioLanguage`/`preferredSubtitleLanguage`/`subtitlesEnabled`), `StreamProvider.kt` (solo commento), `VixSrcProvider.kt` (+260 righe), `StreamingCommunityProvider.kt`, `SubScreens.kt`, `StreamNovaViewModel.kt`, `strings.xml` ×5, test `SearchCategoryTalk*` cancellati e `SearchCategoryTvShow*` aggiunti, `docs/telegram/*`.
2. **Feature HLS Diagnostics Engine oggetto dell'audit**: `HlsPlaylist.kt` e `StreamResolution.kt` (modificati, **non presenti nello snapshot**), `PlayerScreen.kt` (delta diagnostico sopra il WIP dello snapshot), più i file non tracciati nuovi.

**Confronto con lo snapshot.** Lo snapshot (creato 2026-11-18 17:16) **NON contiene la feature diagnostica**: zero occorrenze di `PlaybackDiagnostics*`/`HlsPlaybackSession`/`HlsPlaylistSummary` nella patch; `HlsPlaylist.kt`, `StreamResolution.kt` e i file non tracciati diagnostici sono assenti; il suo diff di `PlayerScreen.kt` (6 hunk, 0 righe "diagnostica") corrisponde solo al WIP multi-audio. Verifica incrociata eseguita in directory temporanea **fuori dal repository**: applicando al contenuto HEAD l'hunk PlayerScreen dello snapshot si ottiene la versione pre-diagnostica; il delta con il file attuale è di 78 righe aggiunte / 46 rimosse, **tutte** appartenenti alla feature diagnostica (import, sessione, listener, pannello, etichette risoluzione). Il delta diagnostico è quindi perfettamente separabile dal resto del WIP.

> ⚠️ Nota operativa: un eventuale ripristino dallo snapshot `wip_tracked_2026-11-18.patch` **perderebbe** l'intera implementazione diagnostica (che esiste solo nel working tree attuale). Questa constatazione è registrata senza alcun intervento.

---

## 3. FILE CONTROLLATI

**Nuovi (non tracciati) — letti integralmente:**

| File | Righe | Contenuto |
|---|---|---|
| `app/src/main/java/com/example/data/streaming/HlsPlaylistSummary.kt` | 173 | Parser di sintesi HLS additivo |
| `.../data/streaming/diagnostics/playback/PlaybackDiagnosis.kt` | 383 | Fasi, categorie, confidenza, `PlaybackErrorClassifier` |
| `.../diagnostics/playback/HlsPlaybackSession.kt` | 437 | Collector di sessione thread-safe |
| `.../diagnostics/playback/HlsDiagnosticsReport.kt` | 111 | Modello report + eventi |
| `.../diagnostics/playback/PlaybackDiagnosticsRegistry.kt` | 103 | Registry LRU + `providerStats()` |
| `app/src/main/java/com/example/ui/screens/player/PlaybackDiagnosticsListener.kt` | 225 | Traduttore Media3 → sessione |
| `app/src/main/java/com/example/ui/screens/player/PlaybackDiagnosticsPanel.kt` | 257 | Dialog diagnostico |
| Test: `HlsPlaylistSummaryTest.kt` (12), `StreamResolutionThresholdTest.kt` (3), `PlaybackErrorClassifierTest.kt` (17), `HlsPlaybackSessionTest.kt` (16), `HlsDiagnosticsNetworkTest.kt` (9) | 997 | 57 test |

**Modificati tracciati — letti integralmente o via diff/uso:** `HlsPlaylist.kt`, `StreamResolution.kt`, `StreamModels.kt`, `StreamProvider.kt`, `PlayerScreen.kt` (diff completo 137 righe + delta da snapshot), `StreamDiagnostics.kt` (invariato).

**Verificati come invariati (diff vs HEAD vuoto):** `PlayerLoadErrorHandlingPolicy.kt`, `PlayerSourceFallback.kt`, `StreamNova4KDiag.kt`, `AutoSourcePriority.kt`, `StreamManager.kt`, `app/build.gradle.kts`, `data/streaming/diagnostics/StreamDiagnostics.kt`.

**Consultati per perimetro:** `Vidxgo.kt` (unico consumatore esterno di `heightOfResolution`), `.gitignore`, `app/build.gradle.kts` (coordinate Media3 fork), file di test preesistenti che referenziano `StreamResolution`.

---

## 4. COMPONENTI RIUSATI

| Voce | Esito | Evidenza |
|---|---|---|
| Riutilizzo senza duplicazioni | PASS | Il nuovo codice riusa `StreamDiagnostics.sanitize/hostId`, `DiagnosticClock`, `HlsPlaylist.isHls/variants/resolveUri`, `StreamResolution`. Nessuna reimplementazione di sanificazione, parsing HLS o soglie. |
| Modifiche a `HlsPlaylist` additive | PASS | Diff vs HEAD: solo campo `codecs: List<String> = emptyList()` su `HlsVariant` (valore predefinito compatibile coi costruttori preesistenti, la build compila) + estrazione `CODECS=` + passaggio a `HlsVariant(...)`. Resolver (`firstSegmentUri`, `initSegmentUri`, `resolveUri`) intatti. |
| Logica resolver non alterata | PASS WITH NOTES | L'unica variazione funzionale è la soglia 4K di `heightOfResolution`: `w>=3000 || h>=2000` → `w>=3800 (MIN_WIDTH_4K) || h>=2100 (MIN_HEIGHT_4K)`. È la correzione dichiarata (il 1440p ultrawide non è 4K) ed è **coerente in tutti i punti**: l'unico consumatore esterno (`Vidxgo.kt:127`) passa dal punto unico; non esistono più soglie duplicate (grep su `3000`/soglie: nessuna copia residua). |
| `PlayerLoadErrorHandlingPolicy`, `PlayerSourceFallback`, `StreamNova4KDiag` invariati | PASS | `git diff HEAD` vuoto su tutti e tre; `StreamNova4KDiag` continua a loggare `onVideoCodecError`/`onAudioCodecError` (limitazione #4 verificata: il nuovo listener NON li duplica). |
| Sanificazione | PASS | `sanitize()`: URL ridotti a `scheme://host[:port]` (anche token nel path), Bearer/Basic redacted, cookie redacted, parametri segreti (`token|api_key|signature|expires|…`) redacted, cap 240 char. `hostId()`: solo host lowercase. La sessione non conserva mai URL completi (verificato nel codice e dal test `reportSenzaCredenziali`). |
| `DiagnosticClock` | PASS | `System.nanoTime()/1e6` (monotono), iniettabile; riusato dalla sessione così com'è. |

---

## 5. NUOVI COMPONENTI

### 5.1 `HlsPlaylistSummary` — PASS

- Distinzione master/media: via `HlsPlaylist.variants` (master = varianti non vuote). `NOT_HLS` se manca `#EXTM3U` (testato con pagina HTML). *Nota:* una master malformata senza varianti riconoscibili viene classificata MEDIA (caso limite accettabile, parser tollerante per progetto).
- Varianti, `CODECS=`, `RESOLUTION=`, `BANDWIDTH=` dal parser esistente; renditions `#EXT-X-MEDIA` (AUDIO/SUBTITLES) con regex attributi robusta (valori quotati con virgole gestiti correttamente, case-insensitive, cache thread-safe); righe senza `TYPE` scartate senza inventare dati.
- Segmenti `#EXTINF` contati; durate sommate solo se valide (`hasDurations`), `targetDurationSec`, `#EXT-X-MAP`, `#EXT-X-ENDLIST` riconosciuti.
- Live/VOD: `isLiveStream = true` solo per media playlist senza ENDLIST; `null` per master e per assenza di indizi — nessuna deduzione.
- **Non confonde dichiarato/rilevato**: il modello espone solo l'altezza dichiarata (`declaredResolutionHeight`); selezione e rilevamento sono campi del report alimentati dal collector.
- ⚠️ **Nessun chiamante in produzione** → A-04 (P2): la capacità esiste ed è testata, ma non è connessa ai report reali.

### 5.2 `PlaybackErrorClassifier` — PASS WITH NOTES

Verificati tutti gli 11 stati di `HlsErrorCategory` e l'ordine di priorità: (1) classi SSL/TLS → `TLS_ERROR` HIGH temporaneo; (2) status HTTP esplicito → `classifyHttpStatus`; (3) codici Media3 specchiati (valori verificati corrispondenti a `PlaybackException.ERROR_CODE_*`: 2004/3001/4003/5002 testati); (4) classi di trasporto → `NETWORK_ERROR`; (5) marker testuali MEDIUM; (6) ripiego `UNKNOWN_ERROR` LOW temporaneo. Catena cause con profondità max 8.

Casi obbligatori:

| Caso | Comportamento | Esito |
|---|---|---|
| 401 / 403 | `AUTHORIZATION_ERROR`, definitivo, HIGH | ✔ (401 stesso branch di 403; testato solo 403) |
| 404 manifest/variante | `SOURCE_UNAVAILABLE`, definitivo | ✔ |
| 404 segmento | `SEGMENT_ERROR`, temporaneo | ✔ |
| 429 | `HTTP_ERROR` temporaneo (in `TRANSIENT_HTTP_STATUS_CODES` con 408/425/5xx) | ✔ |
| 500 | `HTTP_ERROR` temporaneo | ✔ |
| Timeout | `NETWORK_ERROR` sempre temporaneo, mai definitivo | ✔ (anche su `ERROR_CODE_TIMEOUT` MEDIUM) |
| Manifest vs segmento | Distinti per fase (`MANIFEST_ERROR` vs `SEGMENT_ERROR`) | ✔ |
| Indisponibilità vs transitorio | 5xx/timeout transitori; 404-manifest/401/403 definitivi | ✔ |

Combinazioni ambigue: TLS ha priorità sullo status HTTP (ragionevole); 404 su `VARIANT_PLAYLIST` cade nel branch "non segmento" → `SOURCE_UNAVAILABLE` (coerente: la variante manca, la sorgente è parzialmente irraggiungibile). Riserve: `ERROR_CODE_IO_BAD_HTTP_STATUS` senza status → `HTTP_ERROR` con `temporary=false` (un HTTP generico senza evidenza di codice è trattato come definitivo — prudente ma discutibile); classificazione 2xx (`UNKNOWN_ERROR` LOW) che il chiamante non deve mai invocare per caricamenti riusciti (il listener non lo fa).

### 5.3 `HlsPlaybackSession` — PASS WITH NOTES

- Thread-safety reale: un solo lock per le strutture mutabili (`httpEvents`, `diagnoses`, `bufferingEvents`, `errorCounts`, `segmentCompletions`); campi volatili per stato/dimensioni. Nessun deadlock possibile (nessun lock annidato verso il registry — il path inverso `providerStats()` prende registry→sessione, mai il contrario).
- Coda circolare 200 eventi (`MAX_HTTP_EVENTS`, remove-first) — testata senza crescita infinita.
- Aggregazione per firma `category|phase|httpCode` con `attemptCount`; righe non duplicate (testato con 3 occorrenze → 1 riga).
- Rebuffer: conteggio solo su transizione da READY/ENDED (IDLE iniziale escluso), durate chiuse correttamente anche su chiusura sessione (`buildReport` chiude l'evento aperto).
- Primo frame misurato una sola volta; timestamp coerenti col clock monotono iniettato.
- `BUFFERING_TIMEOUT`: soglia continua (15 s default) o 3+ rebuffer, sempre temporaneo — logica corretta **ma mai valutata a runtime** → A-03 (P2).
- Contaminazione tra sessioni: nessuna (oggetti indipendenti, testato). ⚠️ La **contaminazione interna di una sessione** attraverso più riproduzioni (player riusato) esiste → **A-01 (P1)**, trattata al §6.

### 5.4 `PlaybackDiagnosticsRegistry` — PASS WITH NOTES

- Max 8 sessioni con espulsione della più vecchia — testato (12 registrazioni → 8, la 100ª assente, la 111ª presente).
- Isolamento per sessionId; duplicazione di `register()` sostituisce il valore (comportamento ragionevole).
- `providerStats()`: aggregazione corretta (definitivi/transitori/senza problemi separati), nessun consumo da parte della selezione provider, nessun circuit breaker. ⚠️ Due riserve: (a) chiama `buildReport()` dentro il lock del registry e `buildReport` ha il side-effect di chiudere l'evento di buffering aperto → non è *puramente* osservativo in senso stretto (A-10, P3; oggi non chiamato in produzione); (b) la politica è FIFO-per-inserimento, non LRU per accesso (A-08, P3).

---

## 6. AUDIT INTEGRAZIONE MEDIA3 (`PlaybackDiagnosticsListener` + `PlayerScreen`)

Verifiche specifiche richieste:

| # | Verifica | Esito |
|---|---|---|
| 1 | Collector associato all'istanza ExoPlayer corretta | PASS — sessione e `DisposableEffect` con la stessa chiave `exoPlayer` (`PlayerScreen.kt:2227`, `:2621`); listener aggiunto/rimosso su `exoPlayer` |
| 2 | Nessun listener equivalente duplicato | PASS — l'unico traduttore verso la sessione è `PlaybackDiagnosticsListener`; `StreamNova4KDiag` logga su sink separato senza scrivere la sessione |
| 3 | Listener rimosso al rilascio | PASS — `onDispose` chiama `removeAnalyticsListener(playbackDiagnosticsListener)` prima di `stop()`/`clearMediaItems()` |
| 4 | Nessun riferimento a player distrutti | PASS — sessione/listener/registry non referenziano il player |
| 5 | **Sessioni isolate tra riproduzioni differenti** | **FAIL — A-01 (P1)**: `ExoPlayer` è riutilizzato per design tra episodi e cambi sorgente (invariante `PlayerScreen.kt:947-963`: le chiavi del `remember` escludono `videoUrl`/`currentEpKey`; il cambio episodio passa da `loadStreamOnPlayer` sulla stessa istanza, `:1815-1830`). La sessione è `remember(exoPlayer)` e `setSource`/`setDeclaredResolution`/`providerId` vengono fissati una sola volta: nessun reset a `isEpisodeChange`/`isUrlChange`/`isManualSourceChange`. Conseguenze osservate nel codice: (a) episodi consecutivi accumulano eventi/rebuffer/errori nella stessa sessione (`totalDurationMs`, `rebufferCount`, `mediaSegmentCount` cross-episodio, diagnosi finali miste); (b) cambio sorgente manuale HLS→HLS (factory invariata, stesso player): `providerId`, `protocol`, `declaredResolution` restano quelli della sorgente precedente → gli errori della nuova sorgente sono attribuiti al provider vecchio, vanificando lo scopo dichiarato del registry (distinguere provider instabili); (c) `contentLabel` resta il titolo del media (corretto per episodi, fuorviante per cambio sorgente con titolo diverso dallo stesso film/serie). |
| 6 | Traduzione fasi da `MediaLoadData.dataType` | PASS WITH NOTES — `DATA_TYPE_MANIFEST→MANIFEST`, `MEDIA_INITIALIZATION→INIT_SEGMENT`, `MEDIA→MEDIA_SEGMENT`, coerente col limite dichiarato (manifest e playlist di variante indistinti; il valore d'enum `VARIANT_PLAYLIST` resta mai prodotto, A-07) |
| 7 | Nessun dato simulato per ciò che il fork non espone | PASS — status su caricamenti riusciti = `null` dichiarato; redirect = 0 dichiarato; content-type letto dagli header reali di `LoadEventInfo.responseHeaders` |
| 8 | Caricamenti riusciti mai classificati errori | PASS — `onLoadCompleted` → `isError=false`, nessuna `onError`; il classificatore 2xx non è mai invocato da questo percorso |
| 9 | Raccolta senza rallentare il playback | PASS — operazioni leggere (parse URI → host, regex di sanificazione solo su errori, strutture limitate); nessun I/O, nessun lock conteso col playback |
| 10 | Niente lavoro costoso improprio sul main thread | PASS — tutto il lavoro è O(1)/O(200) su eventi già sul main thread; `buildReport` nel pannello è leggero e a cadenza 1 s solo col dialog aperto |

Eventi raccolti: manifest/segmenti ✔, host sanificato ✔, durata richieste ✔, content-type ✔ (quando presente), byte ✔, status HTTP sugli errori ✔ (via `InvalidResponseCodeException` nella catena cause), stati IDLE/BUFFERING/READY/ENDED ✔, posizione/buffer ai cambi di stato ✔, rebuffer ✔, primo frame ✔, risoluzione rilevata ✔ (`onVideoSizeChanged`), tracce selezionate ✔ (`onTracksChanged`, traccia video selezionata), codec ✔ (`Format.codecs`/sampleMime), errori decoder ✔ (`onPlayerError` con fasi DECODE), diagnosi finale ✔.

**A-02 (P2) — doppia registrazione dell'errore terminale.** `onLoadError` (:79-110) e, per lo stesso fallimento non recuperato, `onPlayerError` (:127-144) invocano entrambe `session.onError` con firma identica (stesso status/classi/fase: la catena cause dell'eccezione player contiene la stessa `InvalidResponseCodeException`/`IOException`). Effetti: `attemptCount` vale 2 al **primo** fallimento fisico; `REPEATED_ERROR_CONFIRMATIONS = 2` porta la confidenza a HIGH alla prima occorrenza reale, vanificando la semantica "le ripetizioni rafforzano l'evidenza"; il pannello mostra "x2" per un errore singolo. I test JVM non possono vederlo (alimentano la sessione direttamente senza simulare la coppia di callback Media3).

**Integrazione pannello/overlay:** tocco lungo sull'etichetta risoluzione via `pointerInput(detectTapGestures(onLongPress))` (`:3974-3983`); `showDiagnosticsPanel` incluso in `anyModalOpen` (`:697`); dialog montato in modo condizionale (`:4648-4652`); nessun pulsante aggiunto alla barra di controllo ✔.

---

## 7. AUDIT RISOLUZIONI

**Punto unico verificato.** Le etichette derivano tutte da `StreamResolution`: HUD e`updateResolutionFromHeight` → `labelForMeasured(w,h)` (`PlayerScreen.kt:1349-1355`); menu Qualità e `applyPreferredResolution` → `labelForHeight` (`:1374-1382`, `:1464-1470`); selezione manuale dal modal Qualità → `labelForMeasured(0,targetHeight)` (`:4693-4695`); report → `labelForMeasured` in `HlsPlaybackSession.detectedResolution` e `PlaybackDiagnosticsListener.onTracksChanged`; varianti playlist → `heightOfResolution` in `HlsPlaylist.variants`; provider `Vidxgo` → `heightOfResolution`. **Non esistono più soglie indipendenti**: le vecchie mappe locali (`h>=1400 → "4K"`, ecc.) sono state eliminate (il vecchio bug "1440p etichettato 4K" è corretto e testato).

Soglie dichiarate: 4K `w>=3800 || h>=2100` ✔; 1440p `w>=2400 || h>=1400` ✔; 1080p `w>=1800 || h>=1000` ✔; 720p `w>=1100 || h>=650` ✔.

| Dimensioni | Atteso | `heightOfResolution` / `labelForMeasured` | Esito |
|---|---|---|---|
| 3840×2160 | 4K | 4K | ✔ (testato) |
| 4096×2160 | 4K | 4K | ✔ (testato) |
| 3440×1440 | 1440p | 1440p | ✔ (testato, bug storico chiuso) |
| 2560×1440 | 1440p | 1440p | ✔ (testato) |
| 1920×1080 | 1080p | 1080p | ✔ (testato) |
| 1280×720 | 720p | 720p | ✔ (testato) |

Casi aggiuntivi: verticali (vedi A-05), `0,0` → "Auto", larghezza ignota con altezza nota (es. `0,1440` → "1440p", testato), sotto-soglia `2099` → non 4K (testato), `3840x1600` crop → 4K (testato), negativi → `coerceAtLeast(0)`.

**Tre qualità distinte** ✔: `declaredResolution` (provider/playlist, `setDeclaredResolution(playbackState.streamQuality)`), `selectedResolution` (`onTracksChanged`), `detectedResolution` (`onVideoSizeChanged`) sono campi separati del report, mai scambiati; test dedicato `qualitaDichiarataSelezionataERilevataRestanoDistinte` e `qhdRilevataNonDiventa4k`. Nota minore: `labelForMeasured` usa "SD" mentre `labelForHeight` usa "480p"/"Auto" — insiemi di etichette diversi tra dichiarato e misurato (coerente con la volontà di tenerle distinte, ma documentabile meglio).

---

## 8. AUDIT PANNELLO DIAGNOSTICO

- Apertura/chiusura ✔ (long-press → `showDiagnosticsPanel`; `onDismiss` su dialog e bottone "Chiudi"); aggiornamento live ~1 s con `LaunchedEffect` che si cancella alla chiusura (nessun leak) ✔.
- Robustezza ✔: ogni valore è null-safe con fallback "—"; sessione senza eventi → "Nessun problema segnalato"; nessun crash possibile con dati incompleti (costruzione report difensiva, liste vuote gestite).
- Sicurezza ✔: il pannello mostra solo hostId, categorie, contatori e messaggi già sanificati; nessun URL completo/token/credenziale; i token/query non compaiono nemmeno in `toString()` del report (testato).
- Nessun elemento aggiunto alla barra del player ✔ (solo gesture sull'etichetta esistente).
- **Compatibilità TV**: l'accesso è **solo touch** (long-press su `Text` non focusable; nessun D-pad path, nessuna voce impostazioni). Registrazione conforme al limite dichiarato — il pannello non è raggiungibile con telecomando Android TV; resta un limite funzionale documentato (nessuna implementazione richiesta dall'audit).
- Riserva (A-06, P3): tutte le stringhe sono hardcoded in italiano in un'app multilingua (values-de/en/es/fr esistenti, nessuna voce aggiunta).

---

## 9. AUDIT TEST

**File trovati: 5** (12+3+17+16+9 = **57 test**), tutti deterministici (FakeClock / MockWebServer locale, nessun host reale), con asserzioni che verificano realmente i comportamenti (non smoke test):

| File | Cosa dimostra realmente |
|---|---|
| `HlsPlaylistSummaryTest` (12) | Master/media, 3 varianti, renditions, codec in ordine, token propagato su URI relative, durate sommate (16.012 s), TARGETDURATION, init segment, ENDLIST→VOD, live senza ENDLIST, NOT_HLS, variante senza risoluzione → null, 2160p/1440p/1080p(anche 1920×800)/720p, 3440×1440≠4K |
| `StreamResolutionThresholdTest` (3) | Soglie 4K su dimensioni reali, boundary 2099/2100, `labelForMeasured` per HUD (incluso 1920×800→1080p e 0,0→Auto) |
| `PlaybackErrorClassifierTest` (17) | 403→AUTH definitivo, 404 manifest→SOURCE_UNAVAILABLE, 404 segmento→SEGMENT temporaneo, 429/500→HTTP temporanei, timeout mai definitivo, TLS, manifest malformato, segmento mancante senza status, decoder temporaneo, formatounsupported definitivo, rete, UNKNOWN LOW, cancellazione→null, valori costanti Media3 stabili, campi completi |
| `HlsPlaybackSessionTest` (16) | Report senza credenziali (token/query assenti dal serialized), startup primo frame (misurato una volta), rebuffer contati con durate, timeout di buffering a soglia, buffering ripetuto (3° in corso), fuori BUFFERING→no timeout, sessioni indipendenti, dedup firma (3→1 riga, count 3, confidenza HIGH), coda limitata a 200, timeout singolo non dichiara indisponibilità, definitivo prevale su transitorio, sessione pulita→finalDiagnosis null, tre qualità distinte, QHD rilevata≠4K, messaggi sanificati |
| `HlsDiagnosticsNetworkTest` (9) | MockWebServer locale: manifest reale servito→2160/1440 distinte; 403/404/429/500 → verdetto sonda (`Unavailable`/`TransientFailure` riusato, non duplicato) + categoria classificatore; timeout→transitorio; manifest malformato≠rete; registry separa sessioni e aggrega per provider; registry cap 8 con espulsione delle più vecchie |

**Copertura delle voci dichiarate (§9 del mandato):** 16/16 voci verificabili risultano coperte da asserzioni reali, con le riserve seguenti:
- 401 non testato esplicitamente (stesso branch di 403);
- "manifest malformato" coperto a livello classificatore (codice Media3) e NOT_HLS a livello parser, ma **non** con una media playlist sintatticamente mutilata (es. EXTINF senza URI);
- TLS solo via classificazione pura delle classi SSL (coerente con la limitazione dichiarata);
- **Assenza di duplicazioni**: coperta a livello di aggregazione in sessione, NON a livello di coppia di callback Media3 (→ A-02 non intercettato dai test);
- Nessun test per `PlaybackDiagnosticsListener` (traduzione Media3→sessione: `phaseOf`, mapping stati, `onTracksChanged`, estrazione status/content-type) → lacuna di copertura sull'unico componente non-puro (P3);
- **Sesto file di test dichiarato non individuato**: nel repository esistono esattamente 5 nuovi file di test della feature; nessun altro test nuovo tocca i simboli diagnostici (grep su tutta la cartella test: solo test preesistenti e non modificati che già usavano `StreamResolution`). Il "sesto file" citato dal mandato non è identificabile — discrepanza da chiarire con il report originario (non costituisce difetto del codice).

---

## 10. RISULTATO BUILD

Valutazione del rischio prima dell'esecuzione: `build/`, `.gradle/`, `.kotlin/` sono tutti in `.gitignore`; l'esecuzione Gradle non tocca file tracciati né dati utente → esecuzione consentita come verifica non distruttiva. Nessun task di clean è stato invocato (nessuna cancellazione).

| Verifica | Comando | Esito |
|---|---|---|
| Suite completa | `./gradlew test` | **BUILD SUCCESSFUL** — task `UP-TO-DATE` con input identici all'ultima esecuzione reale (risultati JUnit generati 2026-11-18 17:01 UTC su queste sorgenti): **2826 test, 0 failure, 0 errori, 5 skipped** — coincide esattamente con quanto dichiarato |
| Ri-esecuzione fresca dei test diagnostici | `./gradlew :app:testDebugUnitTest --tests <5 classi>` | **57 test, 0 failure** — rieseguiti durante l'audit (timestamp 18:34 UTC), non solo letti dai report precedenti |
| Build | `./gradlew :app:assembleDebug` | **BUILD SUCCESSFUL** (UP-TO-DATE; `compileDebugKotlin` corrente) — nessun errore di compilazione, nessuna dipendenza mancante |

---

## 11. ANALISI REGRESSIONI E PERIMETRO

Verificati come **non modificati** (diff vs HEAD vuoto): `AutoSourcePriority.kt`, `StreamManager.kt`, `PlayerSourceFallback.kt`, `PlayerLoadErrorHandlingPolicy.kt`, `StreamNova4KDiag.kt`, `app/build.gradle.kts` (quindi **nessuna nuova dipendenza**, nessun circuit breaker), TorBox/Stremio/SIMKL (nessun file toccato dalla feature), database/Room (nessun file toccato), `mobile/` (non toccato).

Il collector: non aggiunge richieste di rete (nessun fetch di playlist per diagnostica); non introduce ritardi all'avvio; non blocca il main thread; non accumula eventi illimitatamente (200 eventi HTTP, 8 sessioni, messaggi ≤240 char); non logga dati sensibili in release (messaggi già sanificati; nessun nuovo `Log` oltre a quelli preesistenti); non influenza selezione provider né retry. **La diagnostica osserva il playback senza controllarlo** — rispetto al perimetro l'esito è PASS, con l'eccezione funzionale A-01 che degrada l'affidabilità *dei dati osservati* (attribuzione provider/sessione), non il comportamento del player.

Le modifiche estranee presenti nel working tree (TV Show, multi-audio VixSrc, annuncio) sono WIP preesistente, coperto (in parte) dallo snapshot e **esterno** alla feature auditata; non sono state valutate come regressioni della diagnostica.

---

## 12. LIMITAZIONI DICHIARATE

| # | Limitazione dichiarata | Verifica | Giudizio |
|---|---|---|---|
| 1 | TLS solo via classificazione pura delle eccezioni SSL | Real: nessun handshake simulato nei test; classi `SSLException`/`SSLHandshakeException`/`CertPathValidatorException` + marker testuali | Reale e giustificata |
| 2 | Nessun status HTTP sui caricamenti riusciti | Real: `onLoadCompleted` pone `httpStatus=null`; lo status entra solo su errori via `InvalidResponseCodeException` | Reale e giustificata |
| 3 | Nessun conteggio redirect | Real: `redirectCount=0` sempre | Reale e giustificata |
| 4 | Errori codec non fatali già in `StreamNova4KDiag`, senza duplicazione | Real: listener non implementa `onVideoCodecError`/`onAudioCodecError`; 4KDiag (invariato) li logga | Reale e giustificata |
| 5 | Pannello accessibile solo via touch | Real: long-press touch-only, nessun D-pad path | Reale; limite funzionale da documentare (accesso TV è attività separata) |
| 6 | `providerStats()` puramente osservativo | Quasi: nessun consumo decisionale ✔, ma chiama `buildReport()` che chiude l'evento di buffering aperto (A-10, P3); oggi non chiamata in produzione | Accettabile per questa fase, da correggere prima di un uso futuro |
| 7 | Nessun circuit breaker | Real: nessuno | Reale e giustificata |
| 8 | Nessun retry automatico | Real: nessuno | Reale e giustificata |

**Non coperte dalle limitazioni dichiarate** (quindi anomalie, non limiti): A-01, A-02, A-03, A-04.

---

## 13. ANOMALIE

### A-01 — P1 — Sessione diagnostica non isolata tra riproduzioni differenti
- **File:** `app/src/main/java/com/example/ui/screens/PlayerScreen.kt:2227-2244` (creazione `remember(exoPlayer)`, `setSource`/`setDeclaredResolution` una sola volta, registrazione al registry); interazione con `PlayerScreen.kt:947-963` (invariante di riuso del player) e `:1815-1830` (cambio episodio/sorgente sulla stessa istanza).
- **Atteso:** ogni riproduzione (episodio/sorgente) ha la propria sessione; provider e qualità dichiarata riflettono la sorgente corrente.
- **Effettivo:** la sessione vive quanto l'istanza ExoPlayer; episodi consecutivi e cambi sorgente dello stesso tipo condividono la stessa sessione; `providerId`/`declaredResolution`/`protocol` restano quelli del primo avvio.
- **Evidenza:** nessun reset della sessione in `loadStreamOnPlayer` né su `isEpisodeChange/isUrlChange/isManualSourceChange`; unici punti di scrittura delle informazioni sorgente dentro il blocco `remember`.
- **Impatto:** report e pannello presentano aggregati cross-episodio (rebuffer/segmenti/durata/diagnosi finale miste); dopo un cambio sorgente manuale gli errori sono attribuiti al provider precedente; `providerStats()` (uso futuro dichiarato del registry) conterebbe affidabilità per il provider sbagliato. La diagnostica diventa non affidabile come attribuzione — malfunzionamento significativo (criterio P1; rientra anche nei criteri "le sessioni possono contaminarsi" del mandato).
- **Suggerimento:** rigenerare la sessione al cambio di contenuto/sorgente (es. chiave `remember` che includa la sorgente corrente o `currentEpKey`, oppure reset esplicito con nuovo `sessionId` dentro `loadStreamOnPlayer`), aggiornando `setSource`/`setDeclaredResolution` e registrando la nuova sessione nel registry.

### A-02 — P2 — Doppia registrazione dello stesso fallimento terminale
- **File:** `PlaybackDiagnosticsListener.kt:79-110` (`onLoadError`) e `:127-144` (`onPlayerError`).
- **Atteso:** un fallimento fisico = una occorrenza; le "ripetizioni" indicano fallimenti distinti.
- **Effettivo:** lo stesso `IOException` non recuperato viene classificato due volte (load + player) con firma identica: `attemptCount`=2 al primo fallimento e confidenza auto-elevata a HIGH (`REPEATED_ERROR_CONFIRMATIONS=2`), pannello "x2".
- **Impatto:** contatori gonfiati, semantica della confidenza indebolita; categorie comunque corrette (non è P1).
- **Suggerimento:** deduplicare per `loadEventInfo.identifier` (il load già registrato non va riclassificato in `onPlayerError`) o registrare in `onPlayerError` solo se la causa non è già stata registrata.

### A-03 — P2 — `BUFFERING_TIMEOUT` mai valutato a runtime
- **File:** `HlsPlaybackSession.kt:331-352` (`isBufferingTimeout`/`bufferingTimeoutDiagnosis`); nessun chiamante in produzione (grep su tutto `app/src/main`).
- **Atteso (piano):** la condizione di buffering prolungato/ripetuto produce la categoria BUFFERING_TIMEOUT nei report reali.
- **Effettivo:** la logica esiste ed è testata, ma nulla la interroga durante la riproduzione: la categoria non può mai comparire nei report; un stall prolungato senza errore di rete resta invisibile alla diagnosi finale.
- **Impatto:** capacità dichiarata non effettiva (assenza di dati falsi, ma diagnostica incompleta).
- **Suggerimento:** valutare la condizione durante BUFFERING (es. polling in `LaunchedEffect` o su `onPlaybackStateChanged`/`onPlayerError`).

### A-04 — P2 — `HlsPlaylistSummaryParser` non collegato ai report reali
- **File:** `HlsPlaylistSummary.kt` (nessun chiamante in produzione); `HlsPlaybackSession.setPlaylistKind`/`setDeclaredHeight` mai invocati.
- **Atteso:** parsing master/media a supporto del report di riproduzione (FASE 3 del piano).
- **Effettivo:** componente completo e testato ma morto a runtime: `playlistKind` resta `UNKNOWN` (il pannello mostra "tipo ignoto"), `declaredCodecs`, renditions, segmenti, live/VOD non alimentano mai il report.
- **Impatto:** parte della funzionalità dichiarata è effettiva solo nei test; nessun dato inventato (il pannello degrada con grazia), ma la feature non è completa come descritta.
- **Suggerimento:** alimentare la sessione dal risultato del parser quando il flusso HLS è risolto (o dichiarare esplicitamente la fase come "componente predisposto, integrazione rimandata").

### A-05 — P3 — Video verticali mal etichettati dalla regola max-dimensione
- **File:** `StreamResolution.kt:114-125` (`labelForMeasured`) e `:92-103` (`heightOfResolution`).
- **Effettivo:** 1080×1920 (portrait FHD) → "1440p" (h=1920≥1400); la regola documentata "vale la dimensione più alta" non distingue l'orientamento.
- **Impatto:** etichetta fuorviante su contenuti portrait (rari nel dominio streaming film/serie TV).
- **Suggerimento:** usare `min(w,h)` come classe per orientamenti non landscape, o documentare il comportamento.

### A-06 — P3 — Pannello non internazionalizzato
- **File:** `PlaybackDiagnosticsPanel.kt` (tutte le stringhe hardcoded in italiano); nessuna voce in `strings.xml` (che pure è stato modificato dal WIP per altre feature).
- **Impatto:** incoerenza con l'app multilingua (de/en/es/fr); usabilità per utenti non italofoni.

### A-07 — P3 — Elementi dichiarati ma mai prodotti
- **File:** `PlaybackDiagnosis.kt:14-22` (`PlaybackPhase.VARIANT_PLAYLIST` mai restituito da `phaseOf`, il limite "MANIFEST copre anche le varianti" è dichiarato ma l'enum suggerisce una distinzione che non avviene); `HlsPlaybackSession.kt` (`sessionEpochMs`/`wallClockEpochMs` e `lastCompletedPhase` scritti, mai letti); `mediaSegmentCount` include anche gli INIT segment (denominazione imprecisa).
- **Impatto:** nessuno funzionale; pulizia/documentazione.

### A-08 — P3 — Registry "LRU" in realtà FIFO per inserimento
- **File:** `PlaybackDiagnosticsRegistry.kt:20-31` — `LinkedHashMap` senza `accessOrder=true`; `register()` su sessionId esistente non aggiorna la posizione.
- **Impatto:** nessuno con l'uso attuale (una registrazione per player); denominazione imprecisa e nessun test sulla sostituzione duplicata.

### A-09 — P3 — Caricamenti cancellati marcati come errore negli eventi HTTP
- **File:** `PlaybackDiagnosticsListener.kt:86-98` — `onLoadError` con `wasCanceled=true` registra comunque `isError=true` (nessuna diagnosi, corretto).
- **Impatto:** potenziale rumore per futuri consumatori degli `httpEvents` (cerchi/seek generano cancellazioni); nessun consumer oggi.

### A-10 — P3 — `providerStats()` ha un lieve side-effect di stato
- **File:** `PlaybackDiagnosticsRegistry.kt:62-66` → `HlsPlaybackSession.buildReport()` chiude l'evento di buffering aperto (la durata resta congelata al momento della chiamata).
- **Impatto:** nessuno oggi (funzione non chiamata in produzione); da rendere realmente read-only prima di un uso futuro.

### A-11 — P3 — Copertura test: listener Media3 non testato; sesto file dichiarato non individuato
- **File:** nessun test per `PlaybackDiagnosticsListener`; nel repo esistono 5 (non 6) nuovi file di test della feature.
- **Impatto:** l'unico componente non-puro (traduzione eventi) è privo di asserzioni automatizzate; discrepanza di conteggio con il report dichiarato da chiarire (non è un difetto del codice).

---

## 14. CONFRONTO TRA REPORT DICHIARATO E RISULTATI EFFETTIVI

| Dichiarazione | Risultato effettivo dell'audit |
|---|---|
| Componenti nuovi presenti e completi | ✔ presenti; completi a livello di componente (A-03/A-04: due capacità non collegate a runtime) |
| Raccolta eventi Media3 affidabile | ✔ per singola riproduzione; ✘ per attribuzione su riproduzioni multiple (A-01) e doppio conteggio terminale (A-02) |
| Classificazione errori corretta | ✔ (priorità, fasi, temporaneità, 401/403/404/429/500, timeout mai definitivi); riserva su `IO_BAD_HTTP_STATUS` senza status (definitivo) |
| Correzioni risoluzioni coerenti ovunque | ✔ soglie uniche (3800/2100), 3440×1440→1440p, nessuna logica duplicata, tre qualità distinte; riserva verticale (A-05) |
| Test significativi e verdi | ✔ 57 test deterministici e significativi, ri-eseguiti: tutti verdi; riserve A-11 |
| 2.826 test, 0 failure, 0 errori, 5 skipped | ✔ confermato dai risultati JUnit generati su queste esatte sorgenti e dalla ri-esecuzione fresca dei test diagnostici |
| Build riuscita | ✔ `:app:assembleDebug` BUILD SUCCESSFUL |
| Nessuna regressione / perimetro intatto | ✔ (StreamManager, AutoSourcePriority, policy, fallback, TorBox/Stremio/SIMKL, Room, mobile, dipendenze: intatti) |
| Repository invariato dall'audit | ✔ (solo questo report è stato aggiunto) |
| Snapshot WIP come base di confronto | ⚠️ lo snapshot **non copre** la feature diagnostica; un ripristino dallo snapshot perderebbe l'implementazione |

---

## 15. VERDETTO FINALE

### NON APPROVATO

Motivazione, secondo i criteri del mandato:

1. **Esiste almeno un problema P1 (A-01).** Le sessioni diagnostica non sono isolate tra riproduzioni differenti: il player è riutilizzato per design tra episodi e cambi sorgente e la sessione — ancorata all'istanza ExoPlayer — accumula eventi e diagnosi di riproduzioni diverse, con `providerId` e qualità dichiarata congelati al primo avvio. Il mandato elenca esplicitamente la contaminazione tra sessioni tra i criteri di non approvazione, e l'attribuzione per provider (scopo dichiarato del registry) risulta inaffidabile nel percorso d'uso normale (binge-watching e cambio sorgente manuale).
2. Le anomalie P2 (A-02 doppio conteggio con confidenza prematura, A-03 BUFFERING_TIMEOUT irraggiungibile, A-04 parser non integrato) rafforzano la valutazione ma non sarebbero da sole bloccanti.

Contestualmente si registra che:

- le **funzionalità fondamentali** (classificazione, sanificazione, report, soglie risoluzione, pannello, registry) sono reali, corrette e verificate; la suite dichiarata (2.826/0/0/5) è confermata; la build riesce; **nessuna regressione** è stata introdotta nel player o nelle funzionalità preesistenti;
- il difetto bloccante è **localizzato** (creazione/riciclo della sessione in `PlayerScreen.kt`) e la riparazione è circoscritta: isolare/azzerare la sessione al cambio episodio/sorgente, deduplicare la coppia load/player error, collegare (o dichiarare come predisposizione) il parser e la valutazione del buffering timeout.

**L'audit si ferma qui.** Nessun problema è stato corretto, nessun file modificato se non questo report. L'approvazione resta subordinata a una successiva verifica dopo le correzioni.

---

### 16. CHIUSURA E CONTROLLO DI INTEGRITÀ

Branch e HEAD riletti a fine audit: `integration/streamnova-1.0.3-w1-w12` / `46b6170b11e17d7dd41172b5e506a1a916617829` — invariati. Le 37 voci iniziali di `git status --short` sono invariate; l'unico file aggiunto dall'audit è questo report (`STREAMNOVA_1.2.0_HLS_DIAGNOSTICS_INDEPENDENT_AUDIT.md`), chiaramente identificato come artefatto. Nessun checkout/reset/clean/stash, nessun commit/push/tag, nessuna cancellazione. Le verifiche dinamiche (Gradle) hanno scritto esclusivamente in path gitignored; la ricostruzione del confronto con lo snapshot è avvenuta in directory temporanea esterna al repository.

# STREAMNOVA 1.2.0 — SECONDO AUDIT INDIPENDENTE
## HLS Diagnostics Engine — Verifica delle correzioni A-01–A-04

**Artefatto di audit** — unico file aggiunto al repository da questa attività. Nessun file applicativo è stato modificato; nessuna correzione è stata applicata.

---

## 1. SINTESI ESECUTIVA

Le quattro anomalie del primo audit sono **realmente corrette**:

- **A-01 (P1)**: la sessione diagnostica segue ora la *riproduzione*, non il player. Ogni cambio episodio/sorgente passa per `loadStreamOnPlayer`, che apre una nuova sessione dopo `stop()`/`clearMediaItems()` e prima di `prepare()`. I tre percorsi che avviano il player senza passare da lì sono **continuazioni della stessa sorgente** (sottotitolo allegati, fallback codec, retry manuale dello stesso URL) e la continuità di sessione è semanticamente corretta. La contaminazione sistematica (accumulo su più episodi, provider obsoleto) è eliminata. **Residuo documentato (P3)**: nella finestra sincrona di swap, eventi asincroni del loader pubblicati dal thread di lavoro possono essere consegnati dopo lo swap e attribuiti alla sessione nuova (finestra di millisecondi, impatto limitato, e il caso più comune — la cancellazione da `stop()` — non produce alcun evento perché il listener non sovrascrive `onLoadCanceled`). Vedere A-12.
- **A-02 (P2)**: la deduplicazione per identità è corretta e **verificata sul bytecode reale del fork**: `handleIoException` → `ExoPlaybackException.createForSource(IOException, int)` → costruttore → `PlaybackException` → `Exception.<init>(message, cause)` passano **lo stesso riferimento** dell'`IOException` consegnata a `onLoadError`. Tutti e 10 i casi obbligatori analizzati: nessuna perdita, nessuna duplicazione, nessun trattenimento illimitato di riferimenti.
- **A-03 (P2)**: `BUFFERING_TIMEOUT` è ora raggiungibile a runtime (tick osservativo di 1 s), idempotente per episodio di buffering (testato), con clock monotono e ciclo di vita coroutine corretto (nessuna sopravvivenza a cambio sessione o chiusura player).
- **A-04 (P2)**: la catena parser → `StreamSource.hlsPlaylistSummary` → sessione → report è completa e osservativa; copertura onesta e parziale (VixSrc e cascata automatica; i restanti casi restano `null` senza invenzione).

**Nessun P0/P1. Nessuna regressione significativa.** Il perimetro (AutoSourcePriority, fallback, policy, 4KDiag, Gradle, TorBox/Stremio/SIMKL, Room, mobile) è intatto.

Nota sui test: la run completa di questo audit ha mostrato **1 failure in `OpenSubtitlesApiKeyStorageTest`** (sottosistema prefs, fuori dal perimetro diagnostico, file **preesistente e mai modificato**): intermittenza dimostrata — 2 esecuzioni fallite, poi 2 esecuzioni verdi su sorgenti identici (`--rerun-tasks`). Registrata come osservazione A-13; non correggibile nel perimetro di questo audit.

**Verdetto: APPROVATO CON RISERVE** (motivazione al §13).

| Area | Esito |
|---|---|
| A-01 isolamento sessioni | PASS WITH NOTES |
| A-02 deduplicazione errori | PASS |
| A-03 BUFFERING_TIMEOUT | PASS |
| A-04 integrazione parser HLS | PASS WITH NOTES |
| A-05–A-11 residuali | PASS WITH NOTES (tutti P3, invariati, non bloccanti) |
| Test e build | PASS WITH NOTES (intermittenza fuori perimetro documentata) |
| Regressioni e perimetro | PASS |

---

## 2. STATO INIZIALE DEL REPOSITORY

| Elemento | Valore osservato | Esito |
|---|---|---|
| Percorso | `D:\VSCODE\Progetti\StreamNova_integration` | ✔ |
| Branch | `integration/streamnova-1.0.3-w1-w12` | ✔ atteso |
| HEAD | `46b6170b11e17d7dd41172b5e506a1a916617829` | ✔ atteso (`46b6170`) |
| `git status --short` | **40 voci** = 38 del primo audit + `StreamManager.kt` + `StreamSourceVerifier.kt` (aggiunte della fase correttiva) | ✔ coerente |
| Perimetro non toccato dalla correttiva | `AutoSourcePriority.kt`, `PlayerSourceFallback.kt`, `PlayerLoadErrorHandlingPolicy.kt`, `StreamNova4KDiag.kt`, `app/build.gradle.kts` — **assenti** dalla lista modificati | ✔ |
| `HlsPlaylist.kt` / `StreamResolution.kt` | diff identico a quello del primo audit (13/39 righe: `codecs` + soglie 4K) — la correttiva **non** li ha ritoccati | ✔ |

File della fase correttiva esaminati integralmente: `PlaybackDiagnosticsListener.kt`, `HlsPlaybackSession.kt`, `PlayerScreen.kt` (blocchi diagnostici), `HlsPlaylistSummary.kt`, `StreamModels.kt` (campo nuovo), `StreamSourceVerifier.kt`, `StreamManager.kt` (funzione di valutazione candidati), `VixSrcProvider.kt` (2 siti di attach), 3 file di test aggiornati.

---

## 3. VERIFICA A-01 — ISOLAMENTO DELLE SESSIONI

### 3.1 Ciclo di vita — PASS

`loadStreamOnPlayer` (`PlayerScreen.kt:968-987`) esegue nell'ordine verificato: (1) `stop()` → (2) `clearMediaItems()` → **(3) swap sessione** (`diagSessionHolder.value = newPlaybackDiagnosticsSession(url)`, `:975-978`) → (4) `setMediaItem` → (5) `prepare` → (6) `seekTo`/`playWhenReady`. Lo swap è **dopo** l'arresto/pulizia e **prima** della preparazione, come dichiarato.

**Dimostrazione che i cambi di contenuto/sorgente passano tutti da `loadStreamOnPlayer`** (grep su `setMediaItem`/`prepare` in tutto `PlayerScreen.kt`):

| Percorso (§3.1) | Rotta | Esito |
|---|---|---|
| Primo avvio | `remember` del player → `loadStreamOnPlayer` (`:1108`) quando `videoUrl != null` | ✔ |
| Cambio episodio | `LaunchedEffect(currentEpKey, videoUrl, exoPlayer, manualSourceSelectionToken)` → `isEpisodeChange` → `loadStreamOnPlayer` (`:1913`) | ✔ |
| Cambio stagione | come cambio episodio (chiave `currentEpKey`) | ✔ |
| Cambio sorgente automatico (post-errore, riuso debrid) | `viewModel.handleSourcePlaybackFailure` / `handleReusedStreamFailure` → nuovo `videoUrl`/token → stesso `LaunchedEffect` → `loadStreamOnPlayer` | ✔ |
| Cambio sorgente manuale HLS→HLS | `manualSourceSelectionToken` cambia anche a URL coincidente → `loadStreamOnPlayer` | ✔ |
| Cambio sorgente verso progressivo | `isHlsStream` cambia → factory cambia → **player ricreato** → initializer → `loadStreamOnPlayer` | ✔ |
| Ripresa dopo errore (riuso link) | ViewModel invalida e ri-risolve → nuovo URL/token → `loadStreamOnPlayer` | ✔ |
| Nuovo tentativo manuale (pulsante Riprova, `:3860`) | `setMediaItem`+`prepare` **senza** `loadStreamOnPlayer` — **stesso URL/stessa sorgente**: la sessione prosegue con lo stesso provider/contenuto (semantica corretta; i tentativi della stessa sorgente si aggregano in `attemptCount`) | ✔ conforme |
| Chiusura player | `onDispose` rimuove il listener prima di `release()`; la sessione resta nel registry con il suo report | ✔ |
| Annullamento durante il caricamento | release → load cancellati → `onLoadCanceled` (non sovrascritto: nessun evento diagnostico); sessione parziale limitata | ✔ |

I due unici `loadStreamOnPlayer` call-site (`:1108`, `:1913`) e i tre percorsi alternativi (`:2065` sottotitolo online allegato al MediaItem corrente, `:2616` re-prepare del fallback codec 720p, `:3860` retry manuale) sono **tutti continuazioni della stessa sorgente**: nessuno di essi cambia URL/provider/contenuto → la sessione deve continuare, non ricrearsi. Nessun percorso di cambio sorgente aggira lo swap.

### 3.2 Eventi tardivi — PASS WITH NOTES (residuo A-12, P3)

Il listener legge `diagSessionHolder.value` a ogni evento e **non esiste un tag di generazione** (nessun confronto `MediaPeriodId`/`windowSequenceNumber`): ogni evento va alla sessione corrente. Analisi della finestra:

- Eventi già accodati sul main looper **prima** del blocco di swap → consegnati prima (FIFO) → sessione vecchia ✔.
- `stop()` notifica IDLE **sincronamente** sul main thread → ricevuto dalla sessione vecchia prima dello swap ✔.
- `prepare()` della nuova sorgente → tutti gli eventi nuovi → sessione nuova ✔.
- **Finestra residua**: eventi pubblicati da thread di lavoro (loader/renderer) **durante** il blocco sincrono di swap vengono consegnati dopo → attribuiti alla sessione nuova. Verificato sul bytecode del fork (`app/libs/lib-exoplayer-release.aar`): esiste `onLoadCanceled(EventTime, LoadEventInfo, MediaLoadData)` **distinto** da `onLoadError(..., boolean wasCanceled)`; il listener non sovrascrive `onLoadCanceled`, quindi la cancellazione da `stop()` — il caso più frequente in un cambio durante streaming attivo — **non produce alcun evento diagnostico**. Un `onLoadError` tardivo consegna al massimo un evento HTTP `isError=true` (se `wasCanceled=true` nessuna diagnosi); solo un errore di load pienamente formato nel micro-intervallo potrebbe attribuire una diagnosi alla sessione nuova. Scenari principali a confronto: cambio **dopo errore** (player già IDLE, nessun load in volo) → finestra vuota; cambio **durante streaming sano** → load in volo ma quasi mai in errore. Impatto: riga singola, limitata, senza effetto su `providerStats` in pratica (vedi A-12, P3).
- Aggravanti assenti: `lastRecordedLoadError` sopravvive allo swap, quindi l'errore terminale della sorgente vecchio che arrivasse **dopo** lo swap come `onPlayerError` viene comunque soppresso dal dedup A-02 (non finisce nella sessione nuova).
- Eventi benigni tardivi (`onVideoSizeChanged`/`onRenderedFirstFrame`): stessa finestra, autocorretti dal valore successivo della nuova sorgente; `startupMs` protetto dall'idempotenza del primo frame.

Conclusione: la contaminazione **sistematica** è eliminata e dimostrata; residua una corsa circoscritta alla finestra di swap (P3, documentata, con meccanismo di mitigazione futuro identificato: tag di generazione via `MediaPeriodId`).

### 3.3 Stato delle sessioni precedenti — PASS

- La vecchia sessione riceve IDLE **prima** dello swap (stop sincrono) → l'eventuale buffering aperto viene chiuso ✔.
- Nessun componente riassegna eventi a una sessione vecchia (il provider legge solo l'holder corrente) ✔.
- Resta consultabile nel registry (chiave `sessionId` monotono `AtomicLong`, mai riusato) ✔; i dati storici sono immutabili dopo la chiusura (nessun reset) ✔.
- I tick osservativi interrogano solo la sessione corrente ✔; `providerStats()` le legge senza mutarle oltre la chiusura pigrizia dell'evento di buffering aperto (A-11 residuo del primo audit, P3).

### 3.4 Provider e qualità — PASS

`newPlaybackDiagnosticsSession` legge `playbackState` **al momento della chiamata** (delegate state, non valore catturato): provider (`sourceProviderId ?: streamServer`), contenuto (`media.title`), URL (`setSource(url, kind)` — protocollo + `hostId` sanificato per evento), qualità dichiarata (`streamQuality`), sintesi playlist (A-04). Dopo un cambio HLS→HLS la sessione nuova porta il provider nuovo ✔. `providerStats()` aggrega per `providerId` di sessione: con l'isolamento, ogni errore resta nella sessione della riproduzione che l'ha generato (salvo la finestra A-12).

---

## 4. VERIFICA A-02 — DEDUPLICAZIONE DEGLI ERRORI

**Assunzione "IOException propagata intatta": VERIFICATA SUL BYTECODE DEL FORK** (non per definizione):

1. `ExoPlayerImplInternal.handleIoException(IOException, int)` → `invokestatic ExoPlaybackException.createForSource(IOException, int)` con `aload_1` = **l'istanza catturata** (bytecode, `lib-exoplayer-release.aar`).
2. `createForSource` → `new ExoPlaybackException` con `aload_0` (la stessa `IOException`) come `Throwable` del costruttore.
3. `PlaybackException(String, Throwable, int, Bundle, long)` → `invokespecial java/lang/Exception."<init>":(Ljava/lang/String;Ljava/lang/Throwable;)V` con `aload_2` (la stessa istanza) → `getCause()` restituisce **lo stesso riferimento** visto da `onLoadError`.

La dedup `generateSequence(error) { it.cause }.take(8).any { it === lastRecordedLoadError }` therefore incontra l'istanza reale.

**Casi obbligatori (analisi statica sul codice finale):**

| # | Caso | Comportamento | Esito |
|---|---|---|---|
| 1 | Stessa eccezione in `onLoadError` poi `onPlayerError` | `TYPE_SOURCE` + identità in catena → soppresso: **1 occorrenza** | ✔ |
| 2 | Due errori distinti, stesso messaggio | istanze diverse → entrambi classificati | ✔ |
| 3 | Eccezioni equivalenti, identità diversa | entrambi classificati | ✔ |
| 4 | Eccezione annidata in catena cause | `generateSequence` fino a profondità 8 la trova | ✔ |
| 5 | Errori renderer | `TYPE_RENDERER` → dedup bypassata, fase DECODE | ✔ |
| 6 | Errori decoder | come sopra (4001/4003/…) | ✔ |
| 7 | Errori runtime | `TYPE_UNEXPECTED` → bypassata | ✔ |
| 8 | Behind-live-window | `TYPE_SOURCE` ma identità non in catena → classificato `SEGMENT_ERROR` temporaneo | ✔ |
| 9 | Player error senza precedente `onLoadError` | marker `null`/obsoleto → classificato | ✔ |
| 10 | Due errori consecutivi della stessa sorgente | marker sostituito ad ogni `onLoadError` → ciascun fallimento conta 1 volta | ✔ |

- `attemptCount` non incrementato due volte per lo stesso fallimento ✔; la confidenza non sale a HIGH alla prima occorrenza fisica ✔ (ripristino della semantica `REPEATED_ERROR_CONFIRMATIONS`).
- Nessun errore reale nascosto: la soppressione richiede identità di istanza nella catena di un `TYPE_SOURCE` ✔.
- Riferimenti: **un solo** `lastRecordedLoadError` (riferimento singolo, sostituito) — nessun accumulo ✔.
- Thread-safety: i callback `AnalyticsListener` del fork arrivano sul main looper; il marker è accessed solo lì ✔.

Riserva: la dedup non ha test automatizzato (il listener non è testabile in JVM puro senza Robolectric — vedi §8).

---

## 5. VERIFICA A-03 — BUFFERING_TIMEOUT

| # | Verifica | Risultato |
|---|---|---|
| 1 | Misura della durata | `bufferingStartedAtMs = clock.nowMs()` all'ingresso in BUFFERING; confronto con `clock.nowMs()` corrente |
| 2 | Soglia | 15 s (`DEFAULT_BUFFERING_TIMEOUT_MS`) **o** 3+ rebuffer in sessione |
| 3 | Configurabilità | parametri costruttore (`bufferingTimeoutThresholdMs`); in produzione costante 15 s, nei test iniettabile |
| 4 | Tick eseguito | `LaunchedEffect(diagSessionHolder.value)` → `while(true) { delay(1s); holder.value?.bufferingTimeoutDiagnosis() }` — chiave su sessione, lettura corrente a ogni iterazione |
| 5 | Fermata al cambio sessione | chiave dell'effect = sessione corrente → restart; vecchia coroutine cancellata da Compose |
| 6 | Fermata alla chiusura player | la composizione esce → effect cancellato |
| 7 | Coroutine vecchie sopravviventi | impossibile in pratica; anche nel caso limite il corpo rilegge `holder.value` (sarebbe la sessione corrente) |
| 8 | Una diagnosi per episodio | `bufferingTimeoutRecordedEpisode` confrontato con `bufferingEvents.size`; **testato** (tick ripetuti → `attemptCount` invariato) |
| 9 | Nuovo episodio → nuova diagnosi | **testato** (terzo rebuffer in corso → seconda occorrenza, aggregata: stessa riga, `attemptCount=2`) |
| 10 | Sempre temporaneo | ✔ (testato) |
| 11 | Clock monotono | `DiagnosticClock.SYSTEM` = `System.nanoTime()/1e6` ✔ |
| 12 | Pause/READY/ENDED/IDLE | pausa non cambia lo stato del player (nessun falso rebuffer); uscita da BUFFERING chiude l'evento; IDLE→BUFFERING iniziale non conta rebuffer ma abilita il timeout per durata (buffering iniziale prolungato coperto) |

Casi limite: buffering interrotto prima della soglia → nessuna diagnosi ✔; episodi consecutivi ✔ (testato); cambio sorgente durante buffering → `stop()`→IDLE chiude l'evento nella sessione vecchia **prima** dello swap ✔; chiusura player durante buffering → nessun altro tick, evento chiuso pigramente dal report ✔; tick ripetuti senza cambiamento → idempotente ✔ (testato). Il tick non tocca mai il player: sola lettura + registrazione ✔.

---

## 6. VERIFICA A-04 — INTEGRAZIONE DEL PARSER HLS

### 6.1 Produttori — PASS
- `VixSrcProvider.get()` (`:507`) restituisce **il corpo completo** (`resp.body?.string()`) e lancia su non-2xx → il parser riceve solo playlist scaricate integralmente e validate ✔. Attach a `:296` (sorgente singola, master validata) e `:412` (varianti multi-audio, corpo variante) ✔.
- `HttpSourceVerifier.verifyDetailed` (`StreamSourceVerifier.kt:106`): analizza il testo **già scaricato dalla sonda** (`peekBody` fino a 512 KB, ben oltre la dimensione reale delle playlist) — nessuna richiesta aggiuntiva ✔; quando segue una master fino alla variante, unisce qualità/codec dichiarati dalla master alla sintesi della playlist giocata (dati reali, merge documentato) ✔.

### 6.2 verifyDetailed — PASS
- `verify()` = `verifyDetailed(...).playability`: **comportamento identico** (ogni early-return mappato con la stessa `SourcePlayability`, stesso flusso di richieste, stesse eccezioni → `TransientFailure`) ✔.
- Playability e sintesi separate; sintesi `null` per progressivi e corpi non-HLS ✔.

### 6.3 StreamSource — PASS
- Campo `hlsPlaylistSummary: HlsPlaylistSummary? = null` in coda alla data class: default compatibile con costruttori esistenti ✔.
- **Non serializzata**: `StreamModels.kt` non usa `kotlinx.serialization` (verificato: nessun `@Serializable`/import) → nessun impatto serializzativo ✔.
- Non fa parte delle chiavi di deduplica (URL+header / infoHash+fileIdx) → non altera l'identità ✔.
- Nessun consumo decisionale: `StreamManager` la **trasporta** (`:622`, `:721`), `PlayerScreen` la legge per la sessione; nessun confronto né ranking la usa ✔; non è prova di riproducibilità (quella resta `availability`/`playability`) ✔.

### 6.4 Consumo in PlayerScreen — PASS
- Trasferita alla sessione creata per la riproduzione corrente (`newPlaybackDiagnosticsSession`, `:943-949`) ✔.
- Mapping `toReportPlaylistKind` totale e senza default nascosti (testato) ✔.
- `declaredResolution` (provider → poi raffinata da `setDeclaredHeight` della playlist), `selectedResolution` (`onTracksChanged`), `detectedResolution` (`onVideoSizeChanged`) restano **tre campi distinti** ✔.
- Codec dichiarato impostato solo se presente e **sostituito** dal codec della traccia attiva quando arriva ✔.
- Sintesi assente → nessuna chiamata → nessun dato inventato ✔.

### 6.5 Isolamento funzionale — PASS
- Selezione provider/priorità/qualità automatica/fallback/playability: nessun codice legge la sintesi per decidere (grep completo su `hlsPlaylistSummary`: solo produttori, trasporto e consumo diagnostico) ✔.
- `providerStats()` invariato e osservativo ✔.

---

## 7. STATO A-05–A-11 (numerazione del mandato; mappatura col primo audit in parentesi)

| ID | Descrizione | Stato | Classificazione |
|---|---|---|---|
| A-05 (A-05) | Video verticali: `labelForMeasured(1080,1920)` → "1440p" | Presente, invariato (`StreamResolution.kt` non toccato dalla correttiva) | Limite accettabile (P3) — caso raro nel dominio |
| A-06 (A-06) | Pannello non localizzato (stringhe hardcoded IT) | Presente, invariato | Miglioramento facoltativo (P3) |
| A-07 (A-08) | Registry FIFO-per-inserimento etichettato LRU | Presente, invariato | Debito tecnico (P3) — con una registrazione per riproduzione il comportamento pratico è corretto |
| A-08 (A-07) | Campi inutilizzati (`sessionEpochMs`, `lastCompletedPhase`, fase `VARIANT_PLAYLIST` mai prodotta) | Presente, invariato (verificato: scritti, mai letti) | Debito tecnico (P3) |
| A-09 (A-11) | Listener Media3 senza test automatizzati | Presente | Debito tecnico (P3) — la traduzione eventi resta verificata solo staticamente |
| A-10 (A-11) | "Sesto file di test" dichiarato dal primo report non individuato | Invariato (5 file, 61 test) | Discrepanza documentativa (P3) |
| A-11 (A-09, A-10) | Caricamenti error-e-canceled marcati `isError=true`; `providerStats()` → `buildReport()` chiude l'evento di buffering aperto | Presenti, invariati | Debito tecnico (P3) — nessun consumer oggi |

Nessuno di questi rivela un rischio più grave: nessun blocco.

---

## 8. VERIFICA DEI TEST

**Copertura delle correzioni — dichiarazione esplicita:**

| Caso richiesto dal mandato | Copertura |
|---|---|
| Isolamento tra due riproduzioni (livello sessione) | ✔ `HlsPlaybackSessionTest.leSessioniSonoIndipendenti` |
| Isolamento a livello `loadStreamOnPlayer` (PlayerScreen) | ✖ NON automatizzato — verificato staticamente (§3.1); richiederebbe test Robolectric/Compose |
| Cambio sorgente HLS→HLS | ✖ NON automatizzato — verificato staticamente |
| Eventi tardivi della sessione precedente | ✖ NON automatizzato — analizzato come A-12 |
| Aggiornamento provider al cambio | Parziale: test di sessione/registry; swap non automatizzato |
| Deduplicazione per identità (listener) | ✖ NON automatizzato — verificato via bytecode del fork + analisi statica |
| Errori distinti con messaggi uguali | Parziale (livello classificatore/sessione, non listener) |
| Idempotenza del timeout | ✔ NUOVO test (`bufferingTimeoutDiagnosiUnaVoltaPerEpisodioDiBuffering`) |
| Più episodi di buffering | ✔ coperto dallo stesso nuovo test |
| Catena di propagazione HLS summary | Parziale: ✔ `verifyDetailedRestituisceLaSintesiDellaPlaylistOsservata` + `verifyDetailedSuFileProgressivoNonHaSintesi` + mapping `mappingTipoPlaylistVersoReportETotale`; ✖ attach VixSrc e consumo PlayerScreen non automatizzati |

Le mancanze sono compatibili con l'architettura (componenti Compose/Media3 non JVM-puri) e sono dichiarate, non nascoste.

**Esecuzione (valutazione rischio: output solo in path gitignored `build/`/`.gradle/`; nessun clean):**

| Esecuzione | Risultato |
|---|---|
| Suite completa di questo audit (`./gradlew test :app:assembleDebug`, 19:59 UTC) | 2830 test, **1 failure** — `OpenSubtitlesApiKeyStorageTest.unValoreVuotoRimuoveLaConfigurazione` (fuori perimetro); le **5 classi diagnostichi: 61 test, 0 failure** (timestamp della stessa run) |
| Rerun classe OpenSubtitles (incrementale, 20:11) | di nuovo FAILED |
| 2 rerun forzati `--rerun-tasks` (20:14-20:19) | **entrambi BUILD SUCCESSFUL, 0 failure** |
| `:app:assembleDebug` pulito (21:20 UTC) | **BUILD SUCCESSFUL** |

Il risultato dichiarato dalla fase correttiva (2830/0/0/5) era vero per la sua esecuzione; in questo audit lo stesso test è risultato **intermittente** su sorgenti identici (2 fail → 2 pass). File preesistente, mai modificato, sottosistema prefs senza alcun collegamento col codice diagnostico → osservazione A-13, non imputabile alla feature e non correggibile entro i vincoli.

---

## 9. VERIFICA BUILD

`./gradlew :app:assembleDebug` → **BUILD SUCCESSFUL** (`:app:compileDebugKotlin` corrente su tutte le sorgenti finali). Nessun errore di compilazione; nessuna dipendenza aggiunta; il fork Media3 (AAR locali `app/libs/*.aar`) è usato senza modifiche.

---

## 10. ANALISI REGRESSIONI

| Componente | Stato |
|---|---|
| `StreamManager` | Modificato **additivamente** (A-04): `isPlayableForAutomaticSelection` sostituito da `playabilityForAutomaticSelection` con mapping di playability **identico** (Verified→Playable, Unavailable→false, tier≥3→Playable, verifier→stessi esiti e stesso log transitorio) e attach della sola sintesi sulla sorgente selezionata. Nessuna decisione di selezione cambia. Rimozione del wrapper booleano morto (nessun chiamante esterno, verificato via grep). |
| `AutoSourcePriority`, `PlayerSourceFallback`, `PlayerLoadErrorHandlingPolicy`, `StreamNova4KDiag` | Non toccati (assenti da `git status`) ✔ |
| TorBox / Stremio / SIMKL / Room / mobile / Gradle | Non toccati ✔ |
| `HlsPlaylist.kt`, `StreamResolution.kt` | Diff identico al primo audit (nessuna variazione da parte della correttiva) ✔ |
| Percorsi `setMediaItem`/`prepare` alternativi | Preesistenti e invariati; analizzati per l'attribuzione di sessione (§3.1) |

La diagnostica resta osservativa: il tick legge, la sessione registra, il registry conserva; nessun componente diagnostico influisce su selezione, retry o riproduzione.

---

## 11. ANOMALIE RESIDUE

| ID | Gravità | Descrizione | Impatto |
|---|---|---|---|
| A-12 | **P3** | Finestra di swap senza tag di generazione: eventi asincroni del loader pubblicati durante il blocco sincrono `stop/clear/swap/prepare` vengono attribuiti alla sessione nuova. Mitigazioni esistenti: `onLoadCanceled` non sovrascritto (cancellazioni = nessun evento), dedup A-02 sopprime l'errore terminale tardivo della sorgente vecchia, `onHttpCompleted` con `wasCanceled=true` non produce diagnosi. Raro (richiede un errore di load formato nell'intervallo di ms), impatto ≤ 1 riga. | Contaminazione puntuale teorica; nessun effetto pratico osservabile su `providerStats` nei percorsi comuni. Mitigazione futura: confronto `MediaPeriodId`/`windowSequenceNumber` in `EventTime`. |
| A-13 | P3 (osservazione) | `OpenSubtitlesApiKeyStorageTest` intermittente (2 fail → 2 pass su sorgenti identici). Fuori perimetro diagnostico, file preesistente e mai modificato. | Flakiness della suite, non collegata alla feature. |
| A-05–A-11 | P3 | Come da §7 | Non bloccanti |

Nessun P0/P1/P2 residuo.

---

## 12. CONFRONTO CON IL PRIMO AUDIT

| Anomalia | Primo audit | Secondo audit |
|---|---|---|
| A-01 (P1) contaminazione sessioni | NON APPROVATO | **Corretta e verificata** (ciclo di vita dimostrato su tutti i percorsi; residuo A-12 circoscritto a P3) |
| A-02 (P2) doppia registrazione | — | **Corretta e verificata su bytecode del fork** (identità IOException confermata) |
| A-03 (P2) BUFFERING_TIMEOUT irraggiungibile | — | **Corretta** (tick osservativo + idempotenza, testata) |
| A-04 (P2) parser non collegato | — | **Corretta** (catena completa, copertura onesta parziale) |
| A-05…A-11 (P3) | Presenti | Presenti e invariati (non era in perimetro correggerli) |

Le dichiarazioni della fase correttiva risultano corrispondere al codice reale: nessuna correzione fittizia, nessun test manipolato per far passare il verde (i nuovi test verificano comportamenti reali e il caso non automatizzato è dichiarato).

---

## 13. VERDETTO FINALE

### APPROVATO CON RISERVE

Motivazione:

1. **A-01–A-04 superate.** L'isolamento delle sessioni è dimostrato su tutti i percorsi di cambio riproduzione; la doppia registrazione è eliminata con evidenza fondata sul bytecode del fork; il timeout di buffering è raggiungibile e idempotente; il parser HLS alimenta i report reali. Nessuna delle condizioni di NON APPROVATO si verifica.
2. **Nessun P0/P1.** I residui sono tutti P3, circoscritti e documentati; il più rilevante (A-12, finestra di swap) è mitigato da tre meccanismi esistenti e non produce effetti pratici sui percorsi d'uso comuni.
3. **Nessuna regressione significativa.** Le modifiche a `StreamManager`/`StreamSourceVerifier`/`VixSrcProvider`/`StreamModels` sono additive e non decisionali; il perimetro è intatto.
4. **Riserve esplicite:** (a) i comportamenti a livello PlayerScreen/listener (swap sessioni, dedup per identità, eventi tardivi) non hanno test automatizzati e sono verificati staticamente/bytecode — la robustezza del caso A-12 merita un meccanismo di generazione-tag in una fase futura; (b) il test OpenSubtitles mostra intermittenza fuori perimetro (A-13); (c) i P3 A-05–A-11 restano debito tecnico documentato.

L'approvazione definitiva della 1.2.0 (incluse eventuali release) resta di competenza del richiedente; questo audit constata la correttezza delle correzioni e l'assenza di bloccanti.

---

## CONTROLLO FINALE DI INTEGRITÀ

Branch e HEAD riletti a chiusura: invariati. `git status --short`: 41 voci attese (40 iniziali + questo report). Nessun file applicativo modificato dall'audit; nessuna correzione applicata; nessun commit/push/tag/release. Le esecuzioni Gradle hanno scritto solo in path gitignored; le ispezioni del fork hanno usato directory temporanee esterne al repository.

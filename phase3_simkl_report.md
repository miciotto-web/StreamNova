# Fase 3 — Login SIMKL (QR + PIN) e sincronizzazione account: audit, implementazione, verifica

**Stato**: Fase A (audit) completata e documentata qui sotto **prima** di ogni modifica al codice.
Fasi 1 e 2 intatte. Nessun commit, push, tag o release. Nessuna operazione sulla Fire TV fisica.

---

## FASE A — Audit e verifica della documentazione SIMKL

### A.1 Stato del repository al momento dell'audit (26-10-2026)

| Voce | Valore |
|---|---|
| Branch | `backup/streamnova-working-20261016` |
| HEAD | `e215915` — *fix(updater): keep updates on the installed ABI for v1.0.2* |
| `main` | `0099cdd` (branch diverso, non toccato) |
| Modifiche tracked non committate | 4 file = **Fasi 1–2** ([[HomeScreen.kt]], [[PlayerScreen.kt]], `NuvioExoPlayerPerformanceHelper.kt`, `NuvioTrackSelector.kt`) |
| Untracked | 271 voci (harness di test, report Fase 2, log build, screenshot) |
| Index | vuoto (niente in stage) |

Le 4 modifiche tracked corrispondono esattamente alle Fasi 1–2 già approvate: nessuna è stata
toccata, sovrascritta o eliminata in questa fase.

### A.2 Architettura SIMKL esistente (codice effettivo)

| Componente | File | Ruolo attuale |
|---|---|---|
| Endpoint HTTP | `data/remote/simkl/SimklApi.kt` | Retrofit su `https://api.simkl.com/`. **AUTH V1 PIN** (`GET /oauth/pin`, `GET /oauth/pin/{user_code}`), `GET /users/settings`, `POST /scrobble/start|pause|stop`, `POST /sync/history`, `GET /sync/all-items/shows`. Interceptor che aggiunge `app-name` + `app-version` e header `User-Agent` |
| Modelli | `data/remote/simkl/SimklModels.kt` | `SimklDeviceCode`, `SimklPinPollResponse`, `SimklUserSettingsResponse`, `SimklAccountState`, `SimklPinState`; `SimklMovie`/`SimklShow`/`SimklIds` **non usati** (residui) |
| Persistenza + logica | `data/repository/SimklRepository.kt` (object singleton) | Token in DataStore `simkl_preferences` (access_token, expires_at, username, account_id, account_type); `requestDeviceCode()` + `pollDeviceCode()` (V1); scrobble; `markWatched()` (**mai chiamato da nessuna parte**: la spunta "visto" in Dettaglio non scrive su SIMKL); `getWatchedEpisodes()` con cache 5 min per `(account, tmdbId, stagione)`; `parseWatchedEpisodes()` (pura, testata) |
| Scrobbler | `ui/screens/player/SimklScrobbler.kt` | start/pause/stop dal player (progresso in **percentuale**, come da API) |
| UI | `ui/screens/SubScreens.kt` (Impostazioni → "Account SIMKL") | Client ID in DataStore con dialog; "Collega SIMKL" → richiesta PIN V1 + polling inline nella LazyColumn; mostra **QR 136 dp del solo `verification_uri`** e il codice a 5 caratteri; nessun conto alla rovescia, nessun annulla, nessun dialogo dedicato |
| Dettaglio | `ui/screens/DetailScreen.kt` | Badge "Visto su SIMKL" per episodio, ottenuto con `getWatchedEpisodes(tmdbId, stagione)` → **una chiamata a `sync/all-items/shows` per ogni stagione visualizzata** |
| Avvio | `StreamNovaApplication.kt` | `SimklRepository.init(this)`; nessuna sincronizzazione all'avvio |
| QR | `ui/components/QrCodeImage.kt` | Renderer QR già esistente (ZXing 3.5.3 già in `build.gradle.kts`) |

Conseguenze dell'audit: **esiste già il 70 % dell'infrastruttura** (token store, endpoint V1,
scrobbler, badge episodi, renderer QR). Manca: device flow V2, QR con codice precompilato,
refresh dei token, pannello di login dedicato, e **tutta** la sincronizzazione account → app.

### A.3 Documentazione ufficiale SIMKL (verificata, non assunta)

Fonte: la documentazione è **migrata** da Apiary (`simkl.docs.apiary.io`, in dismissione da
ottobre 2026 — il blueprint risponde solo "THESE DOCS HAVE MOVED") a **`https://api.simkl.org`**
(Mintlify), con indice per LLM (`/llms.txt`), guida completa (`/resources/llms-full`) e
**OpenAPI 3.1 completa** (`/openapi.json`, 55 path) — scaricata e ispezionata.

#### A.3.1 Flusso device ufficiale — due generazioni di auth

| | AUTH V1 (PIN) | AUTH V2 (device flow, RFC 8628) |
|---|---|---|
| Richiesta codice | `GET /oauth/pin?client_id=…` | `POST /oauth2/device` (body: `client_id`, `scope`) |
| Codice | **5 caratteri** | **8 caratteri** `XXXX-YYYY`, case-insensitive, I/L/O ≈ 1/1/0 |
| URL | `verification_uri` = `https://simkl.com/pin` | `verification_uri` **+ `verification_uri_complete`** = `https://simkl.com/pin?user_code=XXXX-YYYY` |
| Polling | `GET /oauth/pin/{user_code}` ogni `interval` (5 s) | `POST /oauth2/token` con `grant_type=urn:ietf:params:oauth:grant-type:device_code` |
| Risposta in attesa | `{"result":"KO","message":"Authorization pending"}` | `400 {"error":"authorization_pending"}` (o `slow_down`, `expired_token`) |
| Scadenza | `expires_in` 900 s | `expires_in` 900 s, `interval` 5 s |
| Token | `{"result":"OK","access_token":…}`, validità **5 anni**, nessun refresh | `access_token` **7 giorni** + `refresh_token` **180 giorni** (sliding, **non rotante**) |
| Revoca | non esiste | `POST /oauth2/revoke` (200 sempre, nessun segnale di esito) |
| Scope | nessuno (token full-access) | `media:read` (default) / `media:write` (necessario per **scrobble e scritture**) |
| Client type | implicito | **fisso alla registrazione**: "Mobile/desktop/browser", "TV, devices & command line", "Server apps" |
| Ritiro | **~aprile 2027** | flusso raccomandato per le nuove app |

**Vincolo assoluto, verificato**: *«An AUTH V2 client_id cannot use this endpoint … A registration
is V1 or V2, never both.»* — e in senso inverso `/oauth2/*` rifiuta un client V1 con
`401 invalid_client`. Un `client_id` V1 **non è aggiornabile**: per il device flow V2 serve una
**nuova registrazione** (client type "TV, devices & command line", senza secret).

**Sonde live eseguite con un `client_id` fittizio** (nessun dato utente, nessuna credenziale reale):

| Richiesta | Risposta reale |
|---|---|
| `POST /oauth2/device` con client inesistente | `401 {"error":"invalid_client","error_description":"Unknown or missing client_id"}` |
| `GET /oauth/pin` con client inesistente | `412 {"error":"client_id_failed","code":412,"message":"Your client_id is wrong. Try another one"}` |

Quindi le due firme d'errore sono **distinguibili a runtime**: è la base del rilevamento
automatico del tipo di client implementato in Fase B.

Nel repository **non è configurato alcun `client_id`** (env `SIMKL_CLIENT_ID` vuota,
nessuna chiave in `local.properties`/`.env`): la richiesta di codice e il polling sono
verificabili solo con la configurazione dell'utente.

#### A.3.2 Cosa ricostruisce davvero "Continua a guardare"

La documentazione è esplicita e conferma la diffida del committente: **la cronologia NON è
"Continua a guardare"**.

- `GET /sync/playback`, `GET /sync/playback/episodes`, `GET /sync/playback/movies` restituiscono i
  **punti di pausa salvati**: «Playbacks are how Simkl powers "Continue Watching" UIs».
- Ogni voce ha: `id` (per DELETE), `progress` **percentuale 0-100**, `paused_at` ISO-8601,
  `type` = `"episode"|"movie"`, `episode{season,number,title,tvdb_season,tvdb_number}`,
  e il contenitore `movie` / `show` / `anime` con `title`, `year`, `ids`.
- La cronologia (`/sync/all-items/...`) serve per i badge "visto", non per la posizione:
  un item completato **non** ha posizione di ripresa.
- `hide_watched` (default `true`) non nasconde le ri-visioni in corso; dalla revisione di
  agosto 2026 nasconde solo i playback "superati" da una marcatura vista successiva.
- **Retention** (pruning giornaliero): Free **7 giorni**, PRO 30, VIP 90 → un playback vecchio
  può sparire da solo; la sincronizzazione non può quindi basarsi su di essi per costruire una
  cronologia permanente.
- Gating consigliato: **`GET /sync/activities` prima di ogni pull** (timestamp `playback` per
  bucket); refetch solo dei bucket mossi. `/sync/all-items` a timer è esplicitamente vietato
  («Apps that do this will have their client_id suspended»).

**Dato NON disponibile nelle API**: la *durata* dell'item/episodio. Il playback dà solo la
percentuale. Per trasformarla in una posizione di ripresa (millisecondi) serve la durata, che
va presa da TMDB (runtime del film / runtime dell'episodio) o dalla durata già nota in locale.
Il report non inventa durate: se la durata non è nota, la voce adotta la percentuale con la
durata del titolo solo se disponibile, altrimenti viene contata come "senza durata" e non
sovrascrive nulla.

#### A.3.3 Identificatori: SIMKL ↔ TMDB ↔ catalogo locale

| Sistema | Chiavi disponibili | Uso in StreamNova |
|---|---|---|
| SIMKL (risposte) | `ids.simkl` (int), `slug`, **`ids.tmdb` (stringa!)**, `ids.imdb` (`tt…`), `ids.tvdb` | `SimklIds.tmdb: String?` già modellato correttamente |
| Catalogo locale | `MediaItem.tmdbId: Int?`, `imdbId: String?`, `id` = `tmdb_m_<id>` / `tmdb_tv_<id>` o `tt…` | `DetailScreen` già usa il `tmdb_id` verso SIMKL |
| Ponte esterno | `MediaRepository.resolveImdbToTmdbId(imdbId, isTv)` (TMDB `find/{external_id}`) e `TmdbApiClient.service.getMovieDetails/getTvDetails/getSeasonDetails` | riuso diretto per i titoli non presenti in locale |

Strategia di match (in ordine): **`tmdb`** → **`imdb`** (`resolveImdbToTmdbId`) →
`(titolo normalizzato, anno, tipo)`. Nessun match affidabile → la voce viene **saltata e
registrata nel report di sincronizzazione**, non inventata.

Per le serie SIMKL l'episodio arriva come `season`/`number` **canonici**; per gli anime la
documentazione avverte che `tvdb_season`/`tvdb_number` possono differire dalla numerazione
AniDB: si usano `season`/`number` e, se il match locale non esiste, la voce viene saltata
(nessuna conversione inventata).

### A.4 Difetti e lacune trovati nell'implementazione esistente

| # | Difetto | Evidenza |
|---|---|---|
| D1 | **QR inutile per l'autenticazione**: il QR contiene solo `https://simkl.com/pin`, il codice va digitato comunque. Il precompilato esiste solo in V2 (`verification_uri_complete`) | `SubScreens.kt:1939` |
| D2 | Nessun supporto AUTH V2 → nessun refresh, e il flusso V1 morirà ad aprile 2027 | `SimklApi.kt` (solo `/oauth/*`) |
| D3 | Login senza scadenza visibile, senza annullo, senza "nuovo codice"; vive dentro la lista Impostazioni e non in un modale | `SubScreens.kt:1913-1959` |
| D4 | `signOut()` locale: nessuna revoca server (V2 ha `POST /oauth2/revoke`) | `SimklRepository.kt:129` |
| D5 | **Nessuna sincronizzazione**: nessun uso di `/sync/playback`, `/sync/activities`; il badge episodi richiama `sync/all-items/shows` **per ogni stagione aperta** (payload intero ogni volta) | `SimklRepository.kt:174-185` |
| D6 | `markWatched()` è codice morto: la spunta "visto" in Dettaglio non arriva a SIMKL | `SimklRepository.kt:165` (nessun chiamante) |
| D7 | `SimklMovie`, `SimklIds`, `SimklShow` non usati | `SimklModels.kt:44-67` |
| D8 | Nessun try/catch sull'`app-name`/`User-Agent`: manca `app-version` come parametro URL (accettato, ma il docs lo elenca tra i tre parametri obbligatori insieme a `app-name`) | `SimklApi.kt:78-95` — *non è un difetto funzionale*: Simkl accetta `app-version` anche solo nell'header? → verificato: la doc elenca **tre parametri URL**; l'header `User-Agent` è a parte. L'`app-version` va aggiunto come query param per conformità |

### A.5 Piano implementativo (Fasi B–D) — deciso dopo l'audit

**Fase B — Login QR + PIN**
1. Modelli e endpoint nuovi: `POST /oauth2/device`, `POST /oauth2/token` (device_code + refresh_token), `POST /oauth2/revoke`; mantenuti gli endpoint V1 come fallback.
2. **Rilevamento automatico del flusso**: tenta V2 → `401 invalid_client` ⇒ fallback V1 → `412 client_id_failed`/`400 unauthorized_client` ⇒ errore chiaro di configurazione. Esito memorizzato in DataStore per non ripetere la sonda a ogni login.
3. Token V2: access+refresh in DataStore, **refresh proattivo** (finestra 1 giorno) + retry singolo su 401, con mutex single-flight (strategia "one owner" della doc).
4. UI: **modale dedicato** `SimklLoginDialog` con QR grande (contenuto: `verification_uri_complete` in V2, `verification_uri` in V1), codice a gruppi, conto alla rovescia, spinner, "Annulla", "Nuovo codice" dopo la scadenza, focus TV trappolato (`ModalOverlayState` + `TvFocusableBox` + `focusGroup`).
5. `signOut()`: revoca server best-effort (solo V2), poi pulizia locale. **Mai** cancellare dati utente per errori temporanei.

**Fase C — Sincronizzazione account → StreamNova**
1. `SimklSyncManager` (nuovo): pull gated da `GET /sync/activities` (snapshot persistito), bucket `playback` e `tv_shows/anime` (watchlist/visto).
2. **Continua a guardare**: `/sync/playback` (+ `/episodes`, `/movies`) → match locale → `MediaRepository.updateProgress` / `updateEpisodeProgress` (percentuale × durata nota o TMDB). Titoli non in catalogo: metadati TMDB (dettagli + stagione per le serie) → nuovo `MediaRepository.upsertExternalItem(...)` → poi stesso percorso di scrittura.
3. **Politica di merge**: vince il più recente (`paused_at` SIMKL vs `lastWatchedAt` locale), mai regressioni a 0, mai cancellazioni, idempotente; un fallimento non modifica nulla e non salva lo snapshot.
4. **Badge episodi**: il pull completo di `/sync/all-items/shows` alimenta una cache locale per `(tmdbId, stagione)`, così i badge non richiedono una chiamata di rete per stagione.
5. UI: riga "Sincronizza ora" + esito e data ultima sincronizzazione; sincronizzazione automatica dopo il login e all'avvio dell'app (se `activities` è mosso).

**Fase D — Verifica**
- Test JVM (JUnit4, `@Config(sdk=[34])` dove serve) con `MockWebServer` per: device flow V2 (pending → token, slow_down, expired_token, scope write), fallback V1/V2 per firma d'errore, refresh proattivo e single-flight, parsing playback, gating activities, politica di merge, upsert idempotente, revoca al logout.
- Verifica su **emulatore Android locale** (nessun accesso alla Fire TV): rendering del modale QR con `client_id` reale se disponibile (altrimenti la UI viene coperta dai test di composizione), comportamento su scadenza.
- Ciò che resta all'utente: approvazione reale sull'account e verifica su Fire TV.

*(Le sezioni Fasi B–D con esiti ed evidenze vengono compilate al termine dell'implementazione.)*

---

## FASE B — Login QR + PIN: cosa è stato implementato

### B.1 File nuovi

| File | Contenuto |
|---|---|
| `data/remote/simkl/SimklAuthClient.kt` | `SimklAuthFlow` (V2_DEVICE / V1_PIN), `SimklLoginPrompt`, `SimklTokenBundle`, eccezioni tipizzate (`SimklLoginExpiredException`, `SimklClientConfigException`, `SimklRefreshException` con `needsReauthorization`) e la logica dei due flussi: **rilevamento automatico** (V2 → su `401 invalid_client` fallback V1; il flusso memorizzato viene provato per primo), polling V2 con `authorization_pending` / `slow_down` (+5 s) / `expired_token`, polling V1 con le due forme documentate, refresh V2, revoca. Nessuna dipendenza Android: `now()` e `sleeper()` iniettabili → test con tempo virtuale |
| `ui/components/SimklLoginDialog.kt` | Pannello di login TV: QR 224 dp, codice a gruppi, dominio, **conto alla rovescia reale** che si ferma a 0, pulsanti "Annulla" / "Nuovo codice", errore visibile anche quando il codice non è stato richiesto (`prompt == null`), `BackHandler`, `ModalOverlayState`, chiusura con X |

### B.2 File modificati (solo Fase 3)

| File | Modifica |
|---|---|
| `data/remote/simkl/SimklApi.kt` | `POST /oauth2/device`, `POST /oauth2/token` (device_code **e** refresh_token), `POST /oauth2/revoke`, `GET /sync/playback`, `GET /sync/activities`; `app-version` aggiunto come parametro URL (la doc ne richiede tre) |
| `data/remote/simkl/SimklModels.kt` | `SimklDeviceAuthorization`, `SimklTokenResponse`, `SimklOAuthError`; `SimklAccountState` esteso con refresh token, scadenza in epoch, scope, flusso, `sessionExpired`, `isReadOnly` |
| `data/repository/SimklRepository.kt` | `startLogin()` / `awaitLogin()` (QR + PIN), persistenza token V2, **refresh proattivo** a <1 giorno con mutex single-flight, **retry singolo su 401**, revoca best-effort al logout (solo V2), `fetchActivities()` / `fetchPlaybackSessions()` / `fetchWatchedLibrary()`, snapshot attività + data ultima sincronizzazione in DataStore, cache della libreria vista (i badge del Dettaglio non rifanno più una chiamata per stagione) |
| `data/repository/MediaRepository.kt` | `buildExternalItem(tmdbId, isTv, stagione)` (scheda da TMDB; `totalDurationMs = 0` se il runtime è ignoto → nessuna percentuale convertita a caso), `upsertExternalItem(item)` (inserimento/aggiornamento additivo che preserva lo stato utente), `getMeasuredEpisodeDurationMs()` |
| `ui/screens/SubScreens.kt` | Sezione "Account SIMKL": riga "Sincronizza ora" con esito e data, avvisi per sessione scaduta / token di sola lettura, login che apre il pannello e **sincronizza subito dopo il collegamento**, hint aggiornato (client "TV, devices & command line") |
| `ui/components/QrCodeImage.kt` | `contentDescription` applicato al QR (accessibilità + verificabilità) |
| `StreamNovaApplication.kt` | Sincronizzazione SIMKL all'avvio, **dopo** il caricamento del catalogo locale e solo se l'account è collegato (il gate `activities` la rende una richiesta sola quando non cambia nulla) |

**Nessuna modifica alle Fasi 1–2**: i 4 file tracked `HomeScreen.kt`, `PlayerScreen.kt`, `NuvioTrackSelector.kt`, `NuvioExoPlayerPerformanceHelper.kt` restano esattamente come erano (+241/−45, non toccati).

---

## FASE C — Sincronizzazione account → StreamNova

### C.1 File nuovi

| File | Contenuto |
|---|---|
| `data/remote/simkl/SimklSyncModels.kt` | `SimklPlaybackEntry` + `SimklPlaybackParser` (percentuale, `paused_at` ISO con/senza millisecondi, `episode` **o** `number`, contenitori `movie`/`show`/`anime`, id numerici o stringa), `SimklActivitiesSnapshot` (appiattimento `dominio.bucket`, gate `PLAYBACK_KEYS`/`WATCHLIST_KEYS`, serializzazione stabile), `SimklProgressMergePolicy`, `SimklWatchedLibrary`, `SimklSyncResult` |
| `data/repository/SimklSyncManager.kt` | Orchestrazione: gate `activities` → pull completo **prima** di ogni scrittura → applicazione dell'unico piano → snapshot salvato **solo** se tutto è riuscito; `SimklLocalMatcher` (tmdb → imdb → titolo normalizzato + anno + tipo) |

### C.2 Cosa viene sincronizzato (e cosa no)

| Dato SIMKL | Dove finisce | Note |
|---|---|---|
| `GET /sync/playback` (punti di pausa) | riga **Continua a guardare** (`updateProgress` / `updateEpisodeProgress`) | È la fonte documentata del "Continue Watching"; per le serie si riprende **l'episodio** indicato dalla sessione |
| `GET /sync/all-items/shows` (episodi visti) | **badge "Visto su SIMKL"** del Dettaglio | Un pull alimenta la cache locale: nessuna richiesta per stagione |
| Watchlist (`plantowatch`/`watching`/…) | **non importata** | StreamNova non ha una superficie "La mia lista"; costruirne una sarebbe una funzione nuova, non una sincronizzazione |
| Posizione in millisecondi | ricostruita da percentuale × durata **reale** (player o TMDB) | Senza durata reale la voce viene **saltata e conteggiata**, mai inventata |

### C.3 Invarianti di sicurezza implementati

1. **Vince il più recente**: `paused_at` SIMKL vs `lastWatchedAt` locale; un progresso locale successivo non viene mai regredito (`LOCAL_NEWER`).
2. **Nessuna cancellazione**: la sincronizzazione è additiva/aggiornante; non rimuove titoli né azzera progressi.
3. **Nessuna scrittura se già allineato** (`ALREADY_IN_SYNC`) → sync ripetuti non generano traffico su Room.
4. **O tutto o niente**: si scarica prima, si scrive dopo; se un pull fallisce lo snapshot **non** viene salvato, quindi il `date_from` successivo recupera ciò che è andato perso.
5. **Errori temporanei ≠ perdita di dati**: un refresh fallito per rete non cancella i token; un refresh rifiutato (`invalid_grant`) marca la sessione come scaduta e chiede un nuovo collegamento, senza rimuovere credenziali.
6. **Niente polling a timer**: solo il gate `activities` (la doc minaccia la sospensione del `client_id` per chi fa diversamente).

---

## FASE D — Verifica

### D.1 Test automatici (JVM)

| Classe | Copre |
|---|---|
| `SimklAuthClientTest` (13) | device flow V2 con QR precompilato, fallback V1 sulla firma `401 invalid_client`, ordine col flusso memorizzato, client inutilizzabile per entrambi i flussi, `authorization_pending` → token, `slow_down` (+5 s), `expired_token`, scadenza senza interrogare, PIN V1 pending → token, codice consumato, refresh che conserva il refresh token non rotante, `invalid_grant` → `needsReauthorization`, revoca, parsing degli errori non-JSON |
| `SimklPlaybackParserTest` (7) | **risposta reale della documentazione** end-to-end (Retrofit + Moshi + parser), chiavi `episode`/`number`, anime, sessioni incomplete, date ISO con/senza millisecondi, date non riconoscibili → `null` |
| `SimklActivitiesSnapshotTest` (6) | appiattimento, gate playback vs watchlist, primo sync, round-trip di serializzazione |
| `SimklProgressMergePolicyTest` (9) | adozione del più recente, `LOCAL_NEWER`, durata ignota, idempotenza, data assente, sessione incompleta, titolo non riconosciuto, percentuali fuori scala, ri-visione in corso |
| `SimklLocalMatcherTest` (8) | match tmdb → imdb → titolo+anno, tipo obbligatorio, normalizzazione, libreria vista per serie/stagione |
| `SimklSyncManagerGuardTest` (1) | senza account collegato la sincronizzazione non parte |
| `SimklLoginDialogTest` (5) | QR/codice/dominio/conto alla rovescia, hint V1 vs V2, scadenza con "Nuovo codice", assenza del QR quando il codice non è stato richiesto, chiusura |
| `SimklRepositoryTest` (esistente, 3) | invariato e verde: il parser è stato rifattorizzato dentro `SimklWatchedLibrary` senza cambiarne la semantica |

**Suite completa: 1392 test, 0 failure, 0 error, 2 skip** (i due skip sono test di rete preesistenti, marcati `@Ignore`; conteggio letto dai 159 XML con `--rerun`, vedi nota sul filtro `--tests` rotto con AGP 9.1.1).

### D.2 Verifica su emulatore Android locale (nessun accesso alla Fire TV)

AVD `Television_1080p` (1920×1080, density 320), APK debug installato via Gradle, navigazione con `adb shell input keyevent`, screenshot in `app/build/emulcheck_p3/`.

| Passo | Esito | Evidenza |
|---|---|---|
| App avviata dopo l'installazione | nessun crash; Home con catalogo TMDB reale | `01_home.png` |
| Sezione "Account SIMKL" | renderizzata con l'hint aggiornato e i pulsanti nuovi; senza account **non** compare "Sincronizza ora" | `03_settings.png`, `32_anchor.png` |
| Client ID salvato | mascherato e verde; avviso "Client ID SIMKL salvato…" | `09_saved.png` |
| "Collega SIMKL" con un client_id **non valido** | V2 tentato per primo e rifiutato con `401 invalid_client`, poi fallback V1 rifiutato con `412 client_id_failed`: **entrambe le firme reali**, coerenti con la Fase A | logcat: `--> POST /oauth2/device` → `401`; `--> GET /oauth/pin` → `412` |
| Pannello di login | modale "Collega SIMKL" con l'errore completo, senza QR (nessun codice richiesto) e con i due pulsanti | `35_err.png`, `44_err.png`, `52_login_final.png` |
| "Nuovo codice" col telecomando | **riavvia davvero il flusso** (secondo giro di richieste in log) | `45_retry.png` + logcat 06:36:09 |
| "Annulla" col telecomando | chiude il pannello, focus alla sidebar, app viva | `53_closed.png` |

**Difetto trovato e corretto durante la verifica (D-1)**: la prima versione del pannello usava un "focus trap" (`onFocusChanged` + ri-richiesta del focus sul tasto chiudi a ogni frame) copiato da `TextInputDialog`. Con il telecomando, la pressione del tasto centrale veniva **rubata** al pulsante "Nuovo codice" mentre il focus veniva riassegnato: il pannello si chiudeva senza riavviare il flusso (logcat: nessuna richiesta dopo la pressione). Sostituito con un **grafo di focus esplicito** (i tre elementi si puntano a vicenda, nessuna via d'uscita dal pannello, focus iniziale sul pulsante utile) e senza trap. Verificato: `45_retry.png` e le richieste del secondo giro. Diagnostica temporanea rimossa (zero occorrenze di `SIMKL_DIAG` nel codice).

### D.3 Cosa NON è verificabile qui (e perché)

1. **QR con codice precompilato e autorizzazione reale**: nessun `client_id` valido è configurato su questa macchina (`SMKL_CLIENT_ID` vuota, nessuna chiave in `local.properties`/`.env`) e l'approvazione richiede l'accesso dell'utente al proprio account. La richiesta del codice è però esercitata end-to-end (richieste reali verso `api.simkl.com`), e la forma del QR è coperta da test (`qrPayload == verification_uri_complete`).
2. **Sincronizzazione con dati veri**: richiede un account collegato. La logica è coperta al 100 % dai test puri (parsing, gate, politica, matcher); il percorso orchestrato è verificato solo per la guardia "non collegato" e per lettura di codice.
3. **Fire TV fisica**: vietata da questa fase, come da istruzioni. Tutte le verifiche hardware nuove restano a carico dell'utente.

### D.4 Per l'utente: cosa serve per vedere il login QR completo

1. Creare su `simkl.com/settings/developer` una **nuova app** di tipo **"TV, devices & command line"** (un `client_id` V1 non è convertibile; la registrazione V2 è separata e non consuma slot V1).
2. Inserire il nuovo Client ID in Impostazioni → Account SIMKL.
3. "Collega SIMKL": il QR conterrà `https://simkl.com/pin?user_code=…` con il codice già inserito; dopo l'approvazione l'app scarica subito punti di ripresa e libreria.
   Con il vecchio client_id V1 tutto continua a funzionare, ma il QR non può precompilare il codice (5 caratteri da digitare) e la revoca al logout non esiste (V1 non ha endpoint di revoca).

---

## Riepilogo finale

- **Commit, push, tag, release: nessuno.** Index vuoto; le Fasi 1–2 intatte; nessuna operazione sulla Fire TV.
- **Nuovi file**: 4 sorgenti + 7 classi di test + report + init script di filtro test.
- **File modificati**: 7 (di cui 4 nuovi di Fase 3 in `app/src/main`), più `StreamNovaApplication.kt`, `SubScreens.kt`, `MediaRepository.kt`, `SimklApi.kt`, `SimklModels.kt`, `SimklRepository.kt`, `QrCodeImage.kt`.
- **Suite**: 1392/0/2 con `--rerun`.
- **Emulatore**: login/errore/retry/annullo verificati al telecomando, difetto di focus trovato e corretto.
- **Aperto (utente)**: registrazione app V2 per il QR precompilato; verifica su Fire TV.


# Login SIMKL allineato al flusso di riferimento (Nuvio) — audit, correzione, verifiche

**Data**: 26-10-2026 · **Branch**: `backup/streamnova-working-20261016` · **HEAD**: `e215915`
**Nessun commit, push, tag o release.** Fire TV fisica non toccata: le verifiche hardware restano a te.

---

## 1. Esito in una riga

Il pannello «Collega account SIMKL» ora è la schermata del riferimento (wordmark Simkl, istruzione in
italiano, QR grande, codice, `https://simkl.com/pin`, conto alla rovescia, attesa, «Annulla»), il
**Client ID è diventato una configurazione di build** che l'utente non vede né inserisce, e il ciclo di
vita del login (un solo polling, annullamento, scadenza, «Nuovo codice», errori di rete) è isolato in una
classe testata. Suite JVM: **1419 test, 0 fallimenti, 2 skip** (i due skip di rete previsti dal progetto).

---

## 2. Flusso effettivamente usato (protocollo ufficiale, verificato)

Documentazione ufficiale: `api.simkl.org` (`/api-reference/pin.md`, `/api-reference/oauth2-device.md`,
`/api-reference/simkl/oauth2-token.md`, OpenAPI `openapi.json`). Nessun endpoint inventato.

| | AUTH V2 — device flow (RFC 8628) | AUTH V1 — PIN |
|---|---|---|
| Richiesta codice | `POST /oauth2/device` (`client_id`, `scope=media:read media:write`) | `GET /oauth/pin?client_id=…` |
| Codice | 8 caratteri `XXXX-YYYY` | 5 caratteri (es. `3552F`) |
| URL di verifica | `verification_uri` = `https://simkl.com/pin` **+ `verification_uri_complete`** = `https://simkl.com/pin?user_code=XXXX-YYYY` | solo `https://simkl.com/pin` (nessun campo per precompilare) |
| Contenuto del QR | `verification_uri_complete` → **codice già inserito** | `https://simkl.com/pin` → il codice va digitato |
| Polling | `POST /oauth2/token` (`grant_type=urn:ietf:params:oauth:grant-type:device_code`) ogni `interval` (5 s) | `GET /oauth/pin/{user_code}` ogni 5 s |
| Attesa | `400 {"error":"authorization_pending"}` (`slow_down` → +5 s) | `{"result":"KO","message":"Authorization pending"}` |
| Scadenza | 900 s (`expired_token`) | 900 s (risposta con un nuovo `user_code` = codice morto) |
| Token | access 7 giorni + refresh 180 giorni | token a lunga durata, nessun refresh |

**Quale dei due**: StreamNova prova prima il V2 e ripiega sul V1 sulla firma d'errore reale
(`401 invalid_client` ⇒ il client non è abilitato per OAuth 2.0; `412 client_id_failed` ⇒ client V1
inesistente). Un'app Simkl è V1 **o** V2, mai entrambe: il flusso riconosciuto viene memorizzato per non
ripetere la richiesta inutile.

**Verità del QR** (requisito esplicito): il pannello dichiara «il codice è già inserito» **solo** se il QR
contiene davvero `verification_uri_complete`. La proprietà è `SimklLoginPrompt.qrPrefillsCode` (calcolata
dal payload, non dedotta dal flusso): se SIMKL non restituisce quel campo, il QR torna a essere il solo
`https://simkl.com/pin` e il testo chiede di digitare il codice — come nel riferimento.

---

## 3. Client ID: configurazione dello sviluppatore (l'utente non c'entra)

**L'utente non deve registrare nessuna app, non deve procurarsi né inserire un Client ID.** Il campo che
esisteva in Impostazioni è stato rimosso insieme alla sua spiegazione: la schermata ora offre solo
«Collega account SIMKL».

Canali, in ordine di priorità (`SimklRepository.simklClientIdChannels`, testato):

1. `BuildConfig.ENV_SIMKL_CLIENT_ID` — valorizzato **in fase di build** da `SIMKL_CLIENT_ID` della
   variabile d'ambiente, di `local.properties` o di `.env` (tutti fuori dal repository);
2. `BuildConfig.SIMKL_CLIENT_ID` — generato dal plugin `secrets` a partire da `.env`
   (`.env.example` contiene il segnaposto `DEFAULT_SIMKL_CLIENT_ID`);
3. valore residuo in DataStore scritto dalle build di Fase 3 (nessuna UI lo scrive più).

I segnaposto (`DEFAULT_…`, `YOUR_…`) vengono **scartati**: senza configurazione reale l'app si comporta
come se il Client ID mancasse, invece di spedire richieste con un ID finto. In quello stato il pannello
mostra «Il collegamento a SIMKL non è disponibile in questa build di StreamNova.» (nessun gergo tecnico,
e nessun invito ad aggiornare l'app: la versione installata non è il problema) e in Logcat finisce una
sola riga con la riga da configurare e lo stato di ogni canale.

> Aggiornamento 26-10-2026: la diagnosi di quello stato — perché a schermo si vedeva «Nessun codice» — e
> il prerequisito di build sono in [`simkl_client_id_report.md`](simkl_client_id_report.md). Il canale
> «variabile d'ambiente letta a runtime» che era elencato qui al punto 3 è stato **rimosso**:
> l'ambiente resta un canale di *build*, non di esecuzione.

**Cosa deve predisporre lo sviluppatore** (non l'utente):

| Passo | Dove |
|---|---|
| Registrare un'app su `simkl.com/settings/developer` — per il QR con codice già inserito serve un client **V2** (tipo «TV, devices & command line», flusso device, senza secret) | portale Simkl |
| Copiare l'ID ottenuto in `SIMKL_CLIENT_ID=<id>` in `.env`, oppure in `local.properties`, oppure esportarlo come variabile d'ambiente prima di Gradle | repository locale |
| Ricompilare: `BuildConfig.ENV_SIMKL_CLIENT_ID` / `SIMKL_CLIENT_ID` finiscono nell'APK | build |

Nota: un client **V1** funziona comunque (fallback PIN, codice digitato), ma non offre il QR precompilato.

---

## 4. File toccati

Nuovi:

| File | Ruolo |
|---|---|
| `app/src/main/java/com/example/ui/components/SimklLoginController.kt` | ciclo di vita del login: richiesta codice, un solo polling, annullamento, scadenza, retry, errori; `onAuthorized` per la sincronizzazione post-login |
| `app/src/test/.../SimklLoginControllerTest.kt` | 11 test sul ciclo di vita |
| `app/src/test/.../SimklClientIdConfigurationTest.kt` | priorità dei canali del Client ID e rifiuto dei segnaposto |
| `app/src/test/.../SimklLoginPersistenceTest.kt` | login reale (MockWebServer) → token nel DataStore |
| `app/src/test/.../SimklApiLoggingTest.kt` | nessun PIN nelle righe di log |
| `app/src/test/.../SettingsSimklAccountRowsTest.kt` | Impostazioni: nessun campo/istruzione su Client ID |
| `_tmp_test_filter_simkl_login.init.gradle` | filtro di run mirato (workaround al filtro CLI rotto, come nelle fasi precedenti) |

Modificati (solo la parte di login SIMKL):

| File | Modifica |
|---|---|
| `app/src/main/java/com/example/ui/components/SimklLoginDialog.kt` | riscritto sul riferimento: wordmark, istruzione, QR 132–216 dp adattivo, codice, URL, countdown `14m 41s`, attesa, «Annulla»/«Nuovo codice», pannello che entra in 960×540 dp (geometria reale di una TV 1080p) |
| `app/src/main/java/com/example/ui/screens/SubScreens.kt` | rimossi riga/popup del Client ID; «Collega account SIMKL» usa il controller; esito del login e data della sincronizzazione via `LaunchedEffect` |
| `app/src/main/java/com/example/data/repository/SimklRepository.kt` | risoluzione del Client ID di build + `SimklClientConfigException`; `init` riconfigurabile se cambia l'API iniettata; lettura di verifica `persistedAccessToken()` |
| `app/src/main/java/com/example/data/remote/simkl/SimklAuthClient.kt` | `SimklLoginPrompt.qrPrefillsCode` + documentazione del QR per flusso |
| `app/src/main/java/com/example/data/remote/simkl/SimklApi.kt` | logger HTTP che redige il codice utente (`redactSimklLogLine`) |
| `app/build.gradle.kts`, `.env.example` | canale di build del Client ID, stesso schema di `TMDB_API_KEY` |

La sincronizzazione della Fase 3 (`SimklSyncManager`, `SimklSyncModels`, scrobble, badge, merge
conservativo, logout) **non è stata modificata**: cambia solo *chi* la avvia dopo il login.

---

## 5. Stato e ciclo di vita (regole garantite e testate)

- un **solo** ciclo di polling per volta: un secondo tocco o «Nuovo codice» annulla il precedente;
- il polling **finisce** per approvazione, «Annulla» (o BACK) o scadenza — e non si riavvia da solo;
- «Nuovo codice» **non chiude il pannello**: resta aperto con lo stato «Richiesta del codice di accesso…»;
- countdown in tempo reale, `Il codice scade in 14m 41s` → `Il codice è scaduto.` (arancio) e pulsante
  «Nuovo codice»;
- errori di rete/risposte non valide → messaggio comprensibile, pannello aperto, nuovo codice offribile;
- D-pad: grafo di focus esplicito (solo i due pulsanti tra loro, `Cancel` altrove), focus iniziale
  sull'azione utile, nessun «focus trap» che riassegna il focus a ogni frame; OK attiva, BACK annulla;
- **nessun PIN e nessun token nei log**: livello `BASIC` (metodo, URL, stato; mai body o header) e
  redazione del codice utente che comparirebbe nel percorso del polling V1.

---

## 6. Test

Comando (filtro CLI rotto con Gradle 9.3.1 + AGP 9.1.1: si usa l'init script):

```
./gradlew.bat :app:testDebugUnitTest --rerun -I _tmp_test_filter_simkl_login.init.gradle   # 82 test, 0 falliti
./gradlew.bat :app:testDebugUnitTest --rerun                                               # suite completa
```

Suite completa: **1419 test / 0 fallimenti / 2 skip** (164 classi; gli skip sono i due test di rete
`@Ignore` previsti da `NewProvidersTest`). Classi nuove/aggiornate:

| Classe | Test | Copre |
|---|---|---|
| `SimklLoginControllerTest` | 11 | un solo polling, doppio tocco, successo + sincronizzazione una volta sola, errore di sincronizzazione che non fallisce il login, errore di rete, scadenza senza richieste automatiche, «Nuovo codice» senza chiusura, annullamento, Client ID mancante, messaggi d'errore, `clearResult` |
| `SimklLoginDialogTest` | 12 | composizione del riferimento, QR/codice/URL/countdown/attesa, istruzione V1 vs V2, scadenza + «Nuovo codice», stati senza prompt, D-pad (LEFT → «Annulla», OK che attiva e non chiude), nessuna via d'uscita con le frecce, `Il pannello entra in 960×540 dp`, formati del countdown, verità del testo sull'QR |
| `SimklAuthClientTest` | 15 | generazione del codice V2/V1, contenuto del QR, `verification_uri`, V2 senza `verification_uri_complete`, polling/pending/slow_down/scadenza/forme V1 |
| `SimklApiLoggingTest` | 4 | redazione del PIN nelle righe di log, righe senza codice intatte, URL reale del polling |
| `SimklClientIdConfigurationTest` | 3 | priorità dei canali, segnaposto rifiutati, assenza di Client ID |
| `SimklLoginPersistenceTest` | 1 | login reale → token e dati account nel DataStore |
| `SettingsSimklAccountRowsTest` | 1 | Impostazioni: nessun campo/istruzione su Client ID |

Le regressioni di Fasi 1–2 e della sincronizzazione di Fase 3 sono coperte dalla suite completa
(le modifiche non committate di Fasi 1–2 sono intatte nel working tree).

**Verifica visiva** (Roborazzi, geometria 1920×1080 px a densità 320 = 960×540 dp, come una TV 1080p):
`app/build/panel_v1.png`, `panel_v2.png`, `panel_expired.png`, `panel_requesting.png`,
`panel_unavailable.png`. Il pannello entra nella schermata con ~23 dp di margine nel caso più alto
(istruzione V2 su due righe).

---

## 7. Limiti

1. **Autorizzazione reale non esercitabile qui**: senza un Client ID valido (`SIMKL_CLIENT_ID` non
   configurato su questa macchina) SIMKL risponde `401`/`412` e non esiste un QR da approvare. Il
   percorso completo QR → approvazione → token è verificato con MockWebServer e nelle forme di risposta
   documentate, non contro il server reale.
2. Lo stato «senza configurazione» è quello che si vede in una build senza Client ID: il pannello lo
   dice in modo comprensibile, ma per vedere il QR serve una build configurata.
3. Verifica su Fire TV fisica non eseguita (fuori perimetro per tua indicazione).
4. Lo sfondo del pannello è un nodo semantico che unisce i discendenti (ha l'azione di click che blocca i
   tocchi): difetto **preesistente** della Fase 3, non una regressione; non incide sull'uso col telecomando,
   ma un lettore di schermo legge il pannello come un blocco unico. I test usano tag dedicati sui due
   pulsanti per verificare il focus reale.
5. Il logo è un wordmark testuale in stile (nessun asset di terze parti nel repository).

---

## 8. Checklist manuale (a carico dell'utente)

Su una build con `SIMKL_CLIENT_ID` configurato:

1. Impostazioni → «ACCOUNT SIMKL»: deve esistere **solo** «Collega account SIMKL» (nessun campo Client ID).
2. Selezionarlo: il pannello mostra wordmark, istruzione, QR grande, codice, `https://simkl.com/pin`,
   conto alla rovescia che scorre e «In attesa dell'approvazione di Simkl…»; focus su «Annulla».
3. Scansionare il QR col telefono: con un client V2 la pagina si apre **con il codice già inserito**;
   con un client V1 si apre `simkl.com/pin` e il codice va digitato.
4. Approvare sul telefono: il pannello si chiude da solo e compare «Account collegato: <nome>»; la
   sincronizzazione parte subito («Ultima: …»).
5. Verificare «Continua a guardare» e i badge «Visto su SIMKL» (invariati rispetto alla Fase 3).
6. Riaprire il pannello e provare: freccia sinistra/destra fra i pulsanti, OK su «Annulla», BACK.
7. Con un codice scaduto (o aspettando la scadenza): «Il codice è scaduto.» + «Nuovo codice»; premendo OK
   su «Nuovo codice» il pannello **resta aperto** e il conto alla rovescia riparte da 15 minuti.
8. Scansione del QR da ~2 m di distanza sulla TV (dimensione e contrasto).
9. Logcat con tag `SimklHttp`: nelle righe del polling V1 il codice utente deve risultare `<codice-omesso>`.

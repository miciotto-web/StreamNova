# QR SIMKL non visibile — diagnosi, correzione e prerequisito di build

Data: 26-10-2026. Stato: modifiche **non committate** (nessun commit, push, tag o release).
Perimetro: configurazione di build del Client ID SIMKL, diagnosi dei guasti del login,
verificabilità del contenuto del QR. La grafica approvata del pannello non è stata toccata.

## 1. Sintomo riportato

Nello screenshot della schermata di login SIMKL la card del QR mostra **«Nessun codice»**
e sotto compare:

> Il collegamento a SIMKL non è disponibile in questa versione dell'app. Aggiorna StreamNova e
> riprova.

Nessun errore, nessun crash: il pannello si apre regolarmente, resta aperto, mostra «Annulla»
e «Nuovo codice». Semplicemente, **non esiste alcun codice da mostrare**.

## 2. La catena reale, dal tocco al QR

| Passo | Dove |
| --- | --- |
| Impostazioni → riga «Collega account SIMKL» → `simklLoginController.start()` | `ui/screens/SubScreens.kt:1917`, `:1938` |
| Il controller apre il pannello in stato "richiesta in corso" | `ui/components/SimklLoginController.kt:79` |
| `requestPrompt = { SimklRepository.startLogin() }` | `ui/screens/SubScreens.kt:1824-1832` |
| `startLogin` chiede il Client ID → `requireClientId()` | `data/repository/SimklRepository.kt:236`, `:221` |
| `authClient.startLogin(clientId)`: `POST /oauth2/device` (V2) e, se rifiutato, `GET /oauth/pin` (V1) | `data/remote/simkl/SimklAuthClient.kt:138` |
| Il prompt (`userCode`, `verificationUri`, `qrPayload`, scadenza, intervallo) torna alla UI | `data/remote/simkl/SimklAuthClient.kt:31-52` |
| Il pannello disegna `QrCodeImage(prompt.qrPayload)`, il codice e il conto alla rovescia | `ui/components/SimklLoginDialog.kt:181`, `:357-373`, `:106-114` |

Tutti i passi dopo `requireClientId()` sono corretti: il pannello, il QR (ZXing), il codice,
il conto alla rovescia e il ciclo di polling funzionano ognuno per conto proprio. Il punto di
rottura è **prima della prima richiesta HTTP**.

## 3. Causa esatta

`requireClientId()` (SimklRepository.kt:221) solleva `SimklClientConfigException` **senza
contattare SIMKL**, perché nessuno dei canali di configurazione contiene un Client ID
utilizzabile:

* `.env` **non esiste** nel repository (il plugin `secrets` ripiega su `.env.example`);
* `local.properties` contiene `sdk.dir` e `TMDB_API_KEY`, **non** `SIMKL_CLIENT_ID`;
* la variabile d'ambiente `SIMKL_CLIENT_ID` non è impostata.

Prova generata, `app/build/generated/source/buildConfig/debug/com/example/BuildConfig.java`:

```
public static final String ENV_SIMKL_CLIENT_ID = "";                       // vuoto
public static final String SIMKL_CLIENT_ID = "DEFAULT_SIMKL_CLIENT_ID";    // segnaposto di .env.example
```

Il segnaposto viene **giustamente rifiutato** da `sanitizeSimklClientId`
(SimklRepository.kt:192): con un ID finto la richiesta partirebbe e fallirebbe, nascondendo il
vero motivo. Risultato: `hasClientId() == false`.

Conseguenza a schermo, esattamente quella dello screenshot:

1. `SimklClientConfigException` → `fail(prompt = null, error)` (SimklLoginController.kt:133);
2. `prompt == null` e `isError == true` → `QrSlot` disegna la card del segnaposto con
   **«Nessun codice»** (SimklLoginDialog.kt:374-386);
3. `statusMessage` = `SIMKL_NOT_AVAILABLE_MESSAGE`, che all'epoca diceva «…in questa
   **versione dell'app**. Aggiorna StreamNova e riprova.»;
4. la riga in Impostazioni mostra «Non disponibile» (SubScreens.kt:1921).

Due difetti distinti, quindi:

* **il buco di configurazione**: una build senza Client ID compila, si installa e si avvia
  senza dire niente; il guasto si scopre solo dopo aver toccato la riga del menu;
* **la diagnosi fuorviante**: lo stesso messaggio copriva «build senza Client ID» e «SIMKL ha
  rifiutato l'app», e suggeriva un aggiornamento che **non avrebbe risolto nulla** (la versione
  installata non c'entra). Nello stesso mucchio finivano anche risposte 2xx illeggibili, che
  apparivano come «Connessione a SIMKL non riuscita».

Nota: il `BuildConfig` di release presente in `app/build/` è **stantio** (25-10 20:30,
precedente al lavoro SIMKL): non contiene alcun campo SIMKL e non va letto come stato attuale.

## 4. Correzione

### 4.1 Build — `app/build.gradle.kts`

* `SIMKL_CLIENT_ID` viene letto in fase di build da **tre sedi**, in ordine: variabile
  d'ambiente, `local.properties`, `.env`. `.env` era coperto solo dal plugin `secrets`; ora
  Gradle lo legge anche per il campo `ENV_SIMKL_CLIENT_ID`, così i due campi inclusi nell'APK
  concordano invece di lasciare il segnaposto come unico valore.
* Se il valore risolto è vuoto o un segnaposto la build **stampa un avviso** con le tre sedi
  in cui impostarlo (`isUsableSimklClientId`, `buildLogger.warn`). Non blocca la build — il
  fail-fast resta riservato alla firma release — ma il buco non è più silenzioso. Testo in
  ASCII perché l'avviso finisce su console Windows.

### 4.2 Repository — `data/repository/SimklRepository.kt`

* canali di configurazione **espliciti e nominati** (`simklClientIdChannels`, :179): i due
  campi inclusi nell'APK, poi il residuo su disco scritto dalle build di Fase 3. Il canale
  `System.getenv("SIMKL_CLIENT_ID")` letto **a runtime** è stato rimosso: su una TV
  quell'ambiente non esiste e il login non deve dipenderne (l'ambiente resta un canale di
  *build*).
* `resolveSimklClientId` (:186) restituisce anche **quale canale** ha fornito il valore.
* `requireClientId` (:221) distingue il guasto (`SimklClientIdMissingException`) e registra
  **una sola** riga diagnostica con lo stato di ogni canale — `vuoto` / `segnaposto (rifiutato)`
  / `utilizzabile` — **senza mai stampare il valore**.

### 4.3 Client — `data/remote/simkl/SimklAuthClient.kt`

* `SimklClientRejectedException` (:95): il server ha rifiutato il client su entrambi i flussi.
  Non è una build senza configurazione e non è detto che sia definitivo.
* `SimklInvalidResponseException` (:103): 2xx inutilizzabile — corpo assente o vuoto, non JSON,
  campi del protocollo mancanti o vuoti (`requireCode`, :222), token senza `access_token`
  (:257). Le conversioni passano da `oauthCall` (:204).
* Gli errori di rete restano `IOException` (test dedicato: connessione caduta con
  `SocketPolicy.DISCONNECT_AT_START`).
* Nel refresh una risposta illeggibile **non** è un grant morto (:324): resta
  `needsReauthorization = false`, quindi si riprova più tardi invece di chiedere di ricollegare
  l'account.
* `SimklDeviceCode.expiresIn` ha un default documentato di 900 s (come il flusso V2): una
  risposta V1 senza `expires_in` non fa buttare via il codice appena ottenuto.

### 4.4 Pannello — `ui/components/SimklLoginController.kt`

| Guasto | Messaggio all'utente | Riprova |
| --- | --- | --- |
| configurazione di build assente | «Il collegamento a SIMKL non è disponibile in questa build di StreamNova.» | sì, senza avviare alcun polling |
| client rifiutato da SIMKL | «SIMKL non ha accettato la richiesta di accesso. Riprova tra qualche minuto.» | sì |
| errore di rete | «Connessione a SIMKL non riuscita. Controlla la rete e riprova.» | sì |
| risposta API non valida | «SIMKL ha risposto in modo inatteso. Riprova tra qualche minuto.» | sì |
| codice scaduto | messaggio di protocollo («Codice SIMKL scaduto. Richiedine uno nuovo.») | sì |
| guasto non classificato | «Collegamento a SIMKL non riuscito. Riprova.» | sì |

Nessuno di questi testi nomina `Client ID`, `SIMKL_CLIENT_ID`, `.env` o un aggiornamento
dell'app (verificato dai test). Il pannello resta aperto, «Annulla» e «Nuovo codice»
funzionano come prima, il polling resta uno solo per volta.

### 4.5 QR verificabile — `ui/components/QrCodeImage.kt`

`encodeQrCode` e `QrCodeMatrix` sono ora `internal` invece di `private`: il contenuto del QR
si può **rileggere** in un test con lo stesso motore che userebbe un telefono, invece di
limitarsi a contare i nodi a schermo.

## 5. Prerequisito per lo sviluppatore (da fare una volta)

Il QR **non può esistere senza un Client ID applicativo**: l'app non lo inventa e non lo chiede
all'utente. Va impostato dallo sviluppatore, una volta, e deve essere presente al momento della
compilazione.

1. **Registra/recupera il client** su <https://simkl.com/settings/developer>. Per ottenere il
   QR con il codice già inserito serve un client di tipo **"TV, devices and command line"**
   (flusso AUTH V2); qualsiasi altro client ripiega sul PIN V1, che funziona comunque con QR,
   codice a schermo e conto alla rovescia.
2. **Metti il valore** in una delle sedi seguenti (nessuna è tracciata da Git), in ordine di
   priorità:
   * `local.properties` → `SIMKL_CLIENT_ID=<id>` accanto a `sdk.dir`;
   * variabile d'ambiente → `$env:SIMKL_CLIENT_ID = "<id>"` prima di Gradle;
   * `.env` → copia `.env.example` in `.env` e compila `SIMKL_CLIENT_ID`.
3. **Ricompila e reinstalla**. Se il valore manca, la build lo dice subito; con il valore
   presente, Impostazioni → «Collega account SIMKL» mostra QR, codice temporaneo e countdown.
4. **Verifica in Logcat**: tag `SimklLogin` (riga diagnostica con lo stato dei canali in caso
   di assenza) e `SimklHttp` (richieste verso `api.simkl.com`). Token e codici utente non
   vengono mai registrati: le righe HTTP passano da `redactSimklLogLine`.

**Il prerequisito non è aggirabile nel codice**: senza il valore non c'è alcun flusso di
autorizzazione da eseguire, e mostrare un codice finto significherebbe mandare l'utente a
digitare un PIN che SIMKL non conosce.

## 6. Test

Run mirato (filtro installato da `_tmp_test_filter_simkl_qr.init.gradle`, il filtro da CLI è
rotto con Gradle 9.3.1 + AGP 9.1.1):

```
./gradlew :app:testDebugUnitTest -I _tmp_test_filter_simkl_qr.init.gradle --rerun
```

Risultato del run mirato: **99 test, 0 falliti, 0 saltati** (14 classi).

Suite completa (controllo di non-regressione, stesso comando senza `-I`):

```
./gradlew :app:testDebugUnitTest --rerun
```

Risultato: **1436 test, 0 falliti, 0 errori, 2 saltati** (165 classi). I due saltati sono i test
di rete marcati `@Ignore` in `NewProvidersTest` (preesistenti, non toccati).

| Classe | Test | Copertura aggiunta |
| --- | --- | --- |
| `SimklAuthClientTest` | 21 | corpo non JSON dopo 200, 200 senza campi del protocollo, 200 con corpo vuoto, polling senza `access_token`, connessione caduta = errore di rete, rifiuto del client ≠ configurazione assente, risposta V1 senza `expires_in` |
| `SimklClientIdConfigurationTest` | 7 | priorità dei canali con il **nome del canale**, valore valido trasferito intero (spazi e virgolette), segnaposto di `.env.example` = nessuna configurazione, canali effettivi del runtime (i campi di build per primi, nessun `System.getenv`) |
| `SimklLoginControllerTest` | 14 | messaggi distinti per i cinque guasti, nessun messaggio con gergo di configurazione o «Aggiorna», client rifiutato con pannello aperto, risposta non valida ≠ errore di rete, guasto non classificato ≠ errore di rete |
| `QrCodeContentTest` | 4 (nuova) | QR riletto e confrontato con l'URL precompilato (V2), con l'indirizzo di verifica (V1), QR diverso per codici diversi, nessuna matrice per contenuto non codificabile |
| `SimklLoginDialogTest` | 12 | invariata nel comportamento: pannello, focus D-pad, countdown, assenza di QR senza prompt |

Regressioni Fasi 1–3: coperte dal run completo della suite (sezione 8).

## 7. Limiti di questa verifica

* **Nessuna credenziale reale disponibile qui**: il QR non è stato richiesto ad `api.simkl.com`
  da questa macchina. Le forme di risposta sono verificate su MockWebServer secondo il
  protocollo documentato (V2 con e senza `verification_uri_complete`, V1 PIN); l'esercizio
  end-to-end con un Client ID vero richiede il passo 5 e va fatto da chi possiede l'ID.
* Variante **release** non compilata: la sua firma richiede le credenziali di upload e il
  perimetro di questa correzione non prevede build di release. La modifica a `build.gradle.kts`
  agisce in fase di configurazione e vale per tutte le varianti; la compilazione verificata è
  quella `debug` (più la suite completa).
* Nessuna verifica su Fire TV: non prevista dal perimetro di questa correzione.
* L'ABI/aggiornamento in place e gli artefatti di release non sono stati toccati.

## 8. File toccati

| File | Intervento |
| --- | --- |
| `app/build.gradle.kts` | lettura di `.env` per il campo di build, avviso quando il Client ID manca |
| `.env.example` | commento: ordine di risoluzione e conseguenza dell'assenza |
| `README.md` | sezione "Configurazione del Client ID SIMKL" (prerequisito dello sviluppatore) |
| `simkl_client_id_report.md` | questo report |
| `data/repository/SimklRepository.kt` | canali nominati, niente `System.getenv` a runtime, eccezione e diagnosi dedicate |
| `data/remote/simkl/SimklAuthClient.kt` | `SimklClientIdMissingException`, `SimklClientRejectedException`, `SimklInvalidResponseException`, `oauthCall` |
| `data/remote/simkl/SimklModels.kt` | `expires_in` con default documentato nel flusso V1 |
| `ui/components/SimklLoginController.kt` | messaggi distinti per i cinque guasti |
| `ui/components/QrCodeImage.kt` | `encodeQrCode`/`QrCodeMatrix` `internal`, per il test sul contenuto |
| Test: `SimklAuthClientTest`, `SimklClientIdConfigurationTest`, `SimklLoginControllerTest`, `QrCodeContentTest` (nuovo), `SimklLoginDialogTest` | nuovi casi e aggiornamento dei testi |

## 9. Configurazione applicata e verifica sul campo (26-10-2026)

Il prerequisito del §5 è stato soddisfatto: il Client ID applicativo è ora **nella build**. Questa
sezione documenta dove è stato messo, cosa è stato verificato e cosa resta all'utente.

### 9.1 Dove sta il valore

| Voce | Stato |
| --- | --- |
| Canale usato | `local.properties`, riga `SIMKL_CLIENT_ID=<valore>` accanto a `sdk.dir` e `TMDB_API_KEY` |
| Esclusione da Git | `.gitignore:62` (`local.properties`); `.env` non è stato creato |
| File tracciati che contengono il valore | **nessuno** (verificato con `git grep` sull'albero tracciato) |
| Impronta SHA-256 (12) del valore configurato | `59e9d80797b1` — per confrontarlo con la propria copia senza rivelarlo |
| `BuildConfig.SIMKL_CLIENT_ID` (plugin `secrets`) | resta il segnaposto di `.env.example`: previsto e innocuo, il campo di build ha la precedenza (fissato dai test) |

### 9.2 Il valore è davvero finito nella build

`python _tmp_verify_simkl_client_id.py` (mostra solo impronte, mai il valore):

| Controllo | Esito |
| --- | --- |
| `BuildConfig` debug / release `ENV_SIMKL_CLIENT_ID` | uguale al valore configurato (`59e9d80797b1`) |
| APK debug (5 split) | valore **presente** in `classes13.dex` |
| APK release (5 split) | valore **presente** in `classes.dex` |
| Firma APK release | `CN=StreamNova Release`, SHA-256 `3175a7df…` (chiave ufficiale) |
| Firma APK debug | chiave di debug (non per distribuzione) |

### 9.3 Compilazione e test

| Comando | Esito |
| --- | --- |
| `gradlew :app:compileDebugKotlin` | BUILD SUCCESSFUL, **senza** l'avviso «SIMKL_CLIENT_ID is not set» (che invece compare a valore assente, come verificato prima della configurazione) |
| `gradlew :app:testDebugUnitTest --rerun` | **1436 test, 0 falliti, 0 errori, 2 saltati** (i due `@Ignore` di rete in `NewProvidersTest`), 165 classi |
| `assembleDebug` | 5 APK debug |
| `assembleRelease` firmata in locale | 5 APK release, firmati con la chiave ufficiale, **nessuna pubblicazione** |

### 9.4 Protocollo reale (api.simkl.com, due sole richieste)

`python _tmp_verify_simkl_protocol.py` (client id, `device_code` e `user_code` omessi nell'output):

* `POST /oauth2/device` → **HTTP 200** con `device_code`, `user_code` (9 caratteri), `verification_uri=https://simkl.com/pin`, **`verification_uri_complete` presente** (base corretta e codice già dentro), `expires_in=900`, `interval=5` → il Client ID è un client **AUTH V2**: il QR contiene l'URL precompilato e l'utente non digita nulla.
* `POST /oauth2/token` (`grant_type=device_code`, senza approvazione) → **HTTP 400 `authorization_pending`**, esattamente la risposta che il ciclo di polling dell'app attende prima di riprovare.
* Percorso «client rifiutato» (evidenza reale nel buffer Logcat dell'emulatore, da una prova precedente con un id non valido): `POST /oauth2/device` → **401** e `GET /oauth/pin?client_id=streamnova-probe-invalid` → **412**: un id inutilizzabile viene riconosciuto come *rifiuto del client*, non come configurazione assente.

### 9.5 Verifica su emulatore locale (nessuna Fire TV coinvolta)

AVD `Television_1080p` (API 34, x86, 1920×1080 @320 = 960×540 dp, come la UI della Fire TV), APK
debug installato dopo disinstallazione pulita:

* Impostazioni → «Collega account SIMKL» → valore **«Non collegato»** (non più «Non disponibile»): il Client ID viene letto a runtime dal valore incluso nell'app.
* Il pannello mostra **QR**, codice temporaneo, `https://simkl.com/pin`, conto alla rovescia che parte da **14m 54s** e cala (14m 54s → 14m 51s in ~3 s), «In attesa dell'approvazione di Simkl…» e «Annulla». **Nessun «Nessun codice».**
* Il QR **disegnato a schermo** è stato riletto con ZXing dallo screenshot (`MultiFormatReader` su `07_panel.png`): contiene `https://simkl.com/pin?user_code=<codice mostrato sotto il QR>`, cioè la forma `verification_uri_complete` restituita dal server, con lo stesso codice visibile nel pannello.
* **Login reale riuscito**: durante la verifica l'utente ha approvato l'accesso dal proprio telefono (il device flow non può completarsi altrimenti). Il server ha confermato restituendo un `access_token` al polling — la riga `SimklHttp` mostra la richiesta `/oauth2/token` che passa da `authorization_pending` a 200 — e `/users/settings` ha restituito il nome utente. Il pannello si è quindi chiuso da sé e la riga è diventata «Account collegato» con «Sincronizza ora · Ultima: …», seguita dalla sincronizzazione post-login (`sync/activities`, `sync/all-items/shows`, `sync/playback`, tutte 200). Il token è **persistito su disco**: in `files/datastore/simkl_preferences.preferences_pb` (lettura con `run-as`, senza stampare i valori) risultano presenti `access_token`, `refresh_token`, `token_scope`, `auth_flow`, `username`; **assenti** `device_code` e qualunque traccia del Client ID.
* Screenshot della sequenza in `app/build/emulcheck/` (01…08), dentro l'output di build ignorato da Git.

Non esercitati sul device, perché il login si è concluso prima che potessi premerli: «Annulla» e
«Nuovo codice» (coperti da `SimklLoginControllerTest` + `SimklLoginDialogTest`, 26 test: un solo
polling per volta, chiusura che ferma il polling, nuovo codice senza chiudere il pannello) e il
messaggio di errore di rete a pannello aperto (coperto dai test; il percorso server è comunque
verificato dal 401/412 del §9.4).

### 9.6 Igiene dei segreti e stato dei file

* Nessun commit, push, tag, release o upload: `HEAD` è ancora `e215915`.
* Nessun file tracciato contiene il valore; `local.properties` resta ignorato.
* Strumenti temporanei non tracciati (convenzione `_tmp_*` del repository): `_tmp_verify_simkl_client_id.py`, `_tmp_verify_simkl_protocol.py`, `_tmp_build_release_with_client_id.ps1`, `_tmp_test_filter_simkl_qr.init.gradle`.
* **Due disclosure accidentali del valore nel log di sessione, causate da miei comandi** (non da file): un `grep` la cui maschera `sed` non è stata applicata, e un dump `logcat` con le URL `SimklHttp` (dove il `client_id` è visibile per progetto, come documentato in `redactSimklLogLine`). Da qui in avanti le ispezioni passano solo dagli strumenti Python, che mascherano nel codice.

### 9.7 Cosa resta da fare (lato utente)

1. **Collaudo sulla Fire TV**: non l'ho toccata (vincolo esplicito). Installazione dell'APK, Impostazioni → «Collega account SIMKL», scansione del QR e approvazione.
2. **Gli APK release in `app/build/outputs/apk/release/` non sono pubblicabili come 1.0.2**: sono firmati con la chiave ufficiale e contengono il Client ID, ma nascono da un working tree con modifiche non committate e hanno lo stesso `versionCode 1004` della 1.0.2 già pubblicata; servono solo come prova che anche la variante di distribuzione è configurata. Gli artefatti ufficiali restano in `%USERPROFILE%\StreamNovaArtifacts\v1.0.2`. Impronte degli APK costruiti ora (prime 16 cifre SHA-256): universal `8a27bf16a29780a6`, armeabi-v7a `bfa468b81c020316`, arm64-v8a `55b6140683f11991`, x86 `dbe762e182a5f2c3`, x86_64 `092989b287e38af7`.
3. **L'emulatore locale conserva il token del tuo account Simkl** nell'app installata (`files/datastore/simkl_preferences.preferences_pb`): per rimuoverlo, disconnetti l'account nell'app («Account collegato» → esegue il sign-out) o disinstalla l'app dall'AVD. Il refresh token V2 dura a lungo: se preferisci, revoca l'accesso dalle impostazioni Simkl.

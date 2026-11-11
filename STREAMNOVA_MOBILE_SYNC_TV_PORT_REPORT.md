# StreamNova Mobile — Porting della modalità Sync da StreamNova TV

**Data:** 2026-11-11
**Branch:** `feature/streamnova-mobile-phase2`
**Fonte:** StreamNova TV (repo `D:\VSCODE\Progetti\StreamNova_integration`, branch `integration/streamnova-1.0.3-w1-w12`), lavoro Sync **in progress** (file nuovi `data/sync/` + `ui/screens/SyncSettings.kt` + test, diff su `MainActivity`, `StreamNovaApplication`, `AppSettingsRepository`, `SimklRepository`, `SubScreens`).

## Cosa fa la funzione Sync (identica a TV)

Replica la configurazione completa su altri dispositivi StreamNova della stessa rete:

- **Attivazione persistente**: con la Sync attiva il dispositivo apre un micro-server HTTP su porta `8090` e si annuncia via NSD/mDNS (`_streamnova._tcp.`); lo stato sopravvive al riavvio (`SyncManager.init` dall'Application).
- **Discovery**: i dispositivi si trovano da soli (filtro nome `StreamNova*`, esclusione del proprio endpoint); aggiunta manuale per IP con verifica hello.
- **Trasferimenti bidirezionali**: PULL (importa dal peer) e PUSH (invia la propria configurazione), con **approvazione obbligatoria sul dispositivo che possiede i dati** — dialog globale che compare su qualunque schermata (TTL richiesta: 2 minuti, polling approvazione 1,2 s, timeout 120 s).
- **Quattro categorie**: SETTINGS (impostazioni generali + riproduzione/avanzate, incluse chiavi API TorBox/OpenSubtitles/SIMKL client e PIN genitori **solo come hash SHA-256**), ADDONS (lista addon Stremio con reconcile: rimozione locali estranei — mai quelli di sistema — installazione/abilitazione degli assenti), SIMKL (replica dell'account collegato, senza login QR sul ricevente), LIBRARY (preferiti, Continue Watching e progressi episodio).
- **Fusione non distruttiva** della libreria: vince la visione più recente (`lastWatchedAt`), il preferito è in unione, episodi per `updatedAt` più recente (in parità vince l'arrivo); righe solo-sorgente mai cancellate sul ricevente.
- **Protocollo HTTP su socket grezzo** (stesso contratto anti-deadlock di `AddonSyncServer`: header con cap 16 KB, corpo max 32 MB, `Content-Length` + `Connection: close`, un thread per client) — nessuna configurazione `cleartextTrafficPermitted` nel manifest. Endpoints: `GET /api/sync/hello`, `POST /api/sync/request`, `GET /api/sync/request/{id}`, `GET|POST /api/sync/config?request={id}`.
- **Schema payload versionato** (`SYNC_SCHEMA_VERSION = 1`): il ricevente rifiuta payload più recenti della sua build; parsing tollerante per enum/categorie ignote.

## File toccati (mobile)

**Nuovi (9):**

| Percorso | Contenuto |
|---|---|
| `core/data/.../data/sync/SyncPayload.kt` | Modelli payload Moshi, categorie, direzioni, costanti protocollo |
| `core/data/.../data/sync/SyncMerge.kt` | Fusione libreria pura (JVM-testabile) |
| `core/data/.../data/sync/SyncConfigBuilder.kt` | Costruzione payload da DataStore + Room (sola lettura) |
| `core/data/.../data/sync/SyncConfigApplier.kt` | Applicazione payload ricevuto (unica controparte in scrittura) |
| `core/data/.../data/sync/SyncDiscovery.kt` | Discovery NSD dei peer |
| `core/data/.../data/sync/ConfigSyncServer.kt` | Micro-server HTTP + annuncio mDNS |
| `core/data/.../data/sync/SyncManager.kt` | Orchestrazione: server, discovery, trasferimenti, stati per la UI |
| `core/data/test/.../data/sync/SyncPayloadTest.kt` | 9 test: round trip payload, tolleranza campi mancanti/categorie ignote, regole di fusione |
| `mobile/.../ui/screens/SyncSettings.kt` | Sezione Impostazioni + dialog trasferimento/approvazione/stato + overlay globale di approvazione |

I 7 file `data/sync` sono **copiati 1:1 dalla TV** (nessuna modifica di logica): le API di cui dipendono esistevano già identiche in `core/data` (`AppSettingsRepository`, `SettingsRepository`, `SimklRepository`, `StremioAddonRepository`, `StreamNovaDatabase`/DAO/entities, `AddonSyncServer.getLocalIpAddress`, enum prefs in `core:model`).

**Modificati (8):**

| File | Modifica |
|---|---|
| `core/data/.../prefs/AppSettingsRepository.kt` | `setParentalControlPinHash(hash)`: scrive SOLO esadecimale a 64 caratteri (il PIN in chiaro non esce mai dalla sorgente) |
| `core/data/.../repository/SimklRepository.kt` | `importAccount(state)`: replica credenziali via `writeAccount` senza rete; l'epoca di sessione NON cambia; chiamabile solo con stato collegato |
| `core/data/.../local/EpisodeProgressDao.kt` | Overload bulk `saveEpisodeProgress(List<EpisodeProgressEntity>)` (già presente sulla TV, necessario all'applier) |
| `mobile/.../MobileApplication.kt` | `SyncManager.init(this)` dopo `ProviderCatalogResolvers.init` |
| `mobile/.../ui/MobileApp.kt` | `SyncApprovalOverlay()` composto alla radice (dialog globale, player compreso — come l'overlay TV in `MainActivity`) |
| `mobile/.../ui/screens/SettingsScreen.kt` | `item(key = "sync") { SyncSettingsSection() }` tra SIMKL e Generale (stessa posizione della TV) |
| `mobile/.../ui/screens/SyncSettings.kt` | Porting UI della sezione TV: toggle, endpoint locale, nome dispositivo, ricerca, lista peer, IP manuale, dialog trasferimento (radio direzione + checkbox categorie), approvazione, stati (attesa/rifiutato/completato/fallito) |
| `mobile/src/main/res/values*/strings.xml` (×5) | 39 stringhe `settings_sync_*` in IT/EN/DE/ES/FR |
| `mobile/build.gradle.kts` | versionCode 6, versionName **1.0.2** |

## Adattamenti mobile (funzioni identiche, interazione touch)

- I dialog TV con focus-trap D-pad (`TvFocusableBox`, `SyncDialogScaffold`, `ModalOverlayState`) diventano `AlertDialog` material3 touch: radio per la direzione, checkbox per le categorie, stessi testi e stessa semantica dei pulsanti (Annulla/Avvia, Rifiuta/Trasferisci, Chiudi).
- Le etichette UI statiche sono **localizzate** (convenzione mobile, 5 lingue) invece delle stringhe italiane hardcoded della TV. I messaggi prodotti dal DATA layer (fasi `Running`, errori di `SyncManager`, riepilogo `SyncApplyResult.describe()`) restano **identici alla TV**: lo schema di protocollo e i testi di esito non divergono tra le due app.
- `collectAsStateWithLifecycle` al posto di `collectAsState`; nessun `BackHandler`/focus requesters (gestione touch standard).

## Verifica

- **Compilazione**: `:core:data:compileDebugKotlin`, `:mobile:compileDebugKotlin`, `:app:compileDebugKotlin` — verdi.
- **Test**: `:core:data:testDebugUnitTest` (include i 9 test di `SyncPayloadTest`: 9/9 pass, 0 failure) e `:mobile:testDebugUnitTest` — suite complete verdi.
- **Build release**: `:mobile:assembleRelease` via `.artifacts/build_mobile_release.ps1` → `mobile-release.apk`, **versionCode 6 / 1.0.2**, firma upload key abituale.
- **Smoke end-to-end su emulatore (API 36, 1280×2856)** con APK debug v6:
  1. Impostazioni → sezione «SINCRONIZZAZIONE» visibile tra SIMKL e Generale;
  2. Toggle «Modalità Sync» ON → logcat `SyncDiscovery: Discovery Sync avviata (_streamnova._tcp.)`, riga «Questo dispositivo» = `10.0.2.15:8090`;
  3. `adb forward tcp:18090 tcp:8090` + `GET /api/sync/hello` → `{"app":"streamnova","name":"sdk_gphone64_x86_64","model":"sdk_gphone64_x86_64","schemaVersion":1}`;
  4. `POST /api/sync/request` (PULL, SETTINGS, peer "TestPC") → **dialog di approvazione globale** con categoria «Impostazioni e chiavi API» e pulsanti Rifiuta/Trasferisci;
  5. Tap «Trasferisci» → stato `approved`, `GET /api/sync/config?request=…` → payload completo (`appSettings` + `playbackSettings`) costruito dal `SyncConfigBuilder` mobile;
  6. force-stop + rilancio → la Sync risulta ancora attiva con lo stesso endpoint (persistenza DataStore).

## Note

- Il modulo `:app` (TV legacy dentro il repo mobile) condivide il data layer aggiornato ma NON riceve il wiring UI: come le porte più recenti (es. Continue Watching 1.0.7), il target è `:mobile`.
- La porta 8090 e il tipo NSD sono gli stessi della TV: un dispositivo TV e uno mobile con Sync attiva si scoprono e si sincronizzano **reciprocamente** (stesso schema payload v1).
- Non ancora committato: il lavoro resta in working tree per revisione (commit proposto: `feat(mobile): port device Sync mode from StreamNova TV, release 1.0.2`).

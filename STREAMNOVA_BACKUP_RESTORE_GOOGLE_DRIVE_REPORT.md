# STREAMNOVA — REPORT FINALE: BACKUP & RIPRISTINO + GOOGLE DRIVE PERSONALE

**Data:** 2026-11-22
**Branch:** `integration/streamnova-1.0.3-w1-w12` @ `528b55d5a566ed78ae4b590cd4042202b680ba1e`
**Audit dettagliato:** vedi [STREAMNOVA_BACKUP_RESTORE_CRASH_AUDIT.md](STREAMNOVA_BACKUP_RESTORE_CRASH_AUDIT.md)
**Regole rispettate:** nessun commit/push/tag, nessun reset/stash/clean, WIP preesistente su
`AddonsScreen.kt` preservato e intatto, modulo `mobile` mai toccato, nessuna modifica di
branding/versione (versionName resta 1.2.1, versionCode 1201).

---

## 1. CRASH ORIGINALI

Entrambi riprodotti e catturati su device reale (Fire TV Stick AFTMA08C15, Android 11),
con stack trace deobfuso tramite `app/build/outputs/mapping/release/mapping.txt`:

**Esporta configurazione:**
```
android.content.ActivityNotFoundException: No Activity found to handle Intent { act=android.intent.action.CREATE_DOCUMENT typ=application/json (has extras) }
  at android.app.Instrumentation.checkStartActivityResult
  at android.app.Activity.startActivityForResult
  at androidx.activity.ComponentActivity.startActivityForResult          (ca0)
  at androidx.activity.ComponentActivity$activityResultRegistry$1        (z90.b)
  at androidx.activity.compose.ManagedActivityResultLauncher             (al2.d1)
  at SettingsMaintenanceSectionsKt$$ExternalSyntheticLambda21            (sh4.g)  ← click lambda export
  at TvFocusableKt$TvFocusableBox$4$1                                    (tl5.a)  ← handler D-pad
```

**Ripristina da file:** identica catena con `act=android.intent.action.OPEN_DOCUMENT typ=*/*`.

**Root cause:** il Fire TV non ha NESSUN document provider (verificato con
`cmd package query-activities` → "No activities found" per entrambi gli intent,
`pm list packages` → DocumentsUI assente). `launcher.launch()` lancia
`ActivityNotFoundException` SINCRONAMENTE nel click handler; la chiamata non era
protetta → processo terminato. Tutto il resto della pipeline (BackupManager,
SyncConfigApplier) era già corretto.

**Correzioni:**
1. `launch()` protetto con try/catch su entrambi i picker;
2. fallback in-app: export nella cartella privata `StreamNova Backups`
   (scrittura ATOMICA temp+rename), import da lista dei backup in-app;
3. nome file conforme: `StreamNova_Backup_YYYY-MM-DD_HH-mm-ss.json`;
4. il JSON generato viene VALIDATO (round-trip col codec) prima di ogni scrittura;
5. SAF resta primario dove il picker esiste; il fallback si attiva solo se il
   picker non può aprirsi (nessun comportamento diverso su phone/emulatore con SAF).

## 2. FILE MODIFICATI

| File | Modifica | Motivo |
|---|---|---|
| `app/src/main/java/com/example/data/backup/BackupManager.kt` | aggiunti `suggestedBackupFileName`, `generateEnvelope`, `encodeValidated`, `exportToFile` (atomico), `previewFile`, `previewJson`; `exportTo`/`preview` refactorati sulla sorgente comune | sorgente unica dell'export per tutte le destinazioni; validazione del JSON generato; fallback file locale |
| `app/src/main/java/com/example/data/backup/LocalBackupStore.kt` | NUOVO | cartella in-app `StreamNova Backups` (fallback SAF-less per Fire TV), lista ordinata dal più recente |
| `app/src/main/java/com/example/data/backup/google/GoogleDriveAuthClient.kt` | NUOVO | OAuth Device Authorization Grant (RFC 8628) via OkHttp: device code, polling, refresh, revoke, id_token decoder |
| `app/src/main/java/com/example/data/backup/google/GoogleDriveFilesClient.kt` | NUOVO | Drive REST v3: find/create cartella, upload multipart, lista, download, ricerca per nome (anti-duplicato) |
| `app/src/main/java/com/example/data/backup/google/GoogleDriveAuthRepository.kt` | NUOVO | ciclo di vita account per-profilo: refresh token CIFRATO (Keystore, `ApiKeyCipher`), access token solo in memoria, isolamento cartella per account, disconnessione con revoca |
| `app/src/main/java/com/example/data/backup/google/GoogleDriveBackupManager.kt` | NUOVO | orchestrazione: cartella ensure/riuso, export→upload con retry e prevenzione duplicati, lista, download→validazione pipeline comune |
| `app/src/main/java/com/example/ui/screens/BackupRestoreSection.kt` | NUOVO | la sezione UI completa: dialog destinazione/sorgente, fallback locale, collegamento Drive (user_code), liste backup, anteprima/conferma, disconnessione account |
| `app/src/main/java/com/example/ui/screens/SettingsMaintenanceSections.kt` | rimossa la vecchia `BackupRestoreSettingsSection` (sostituita da BackupRestoreSection.kt); `backupEnvelopeSummary` → internal | evitare simbolo duplicato; l'helper resta condiviso |
| `app/src/main/java/com/example/StreamNovaApplication.kt` | init `GoogleDriveAuthRepository` (per profilo) + `GoogleDriveBackupManager` | inizializzazione coerente con gli altri repository |
| `app/build.gradle.kts` | `buildConfigField ENV_GOOGLE_DRIVE_CLIENT_ID` (env → local.properties → .env) + warning build se assente | Client ID sviluppatore MAI inventato né committato; stessa catena di SIMKL_CLIENT_ID |
| `.env.example` | documentato `GOOGLE_DRIVE_CLIENT_ID` | procedura di configurazione Google Cloud |
| `STREAMNOVA_BACKUP_RESTORE_CRASH_AUDIT.md` | NUOVO (richiesto, FASE 1) | audit crash con prove |
| Test: `app/src/test/java/com/example/data/backup/LocalBackupStoreTest.kt` | NUOVO | export atomico, segreti esclusi, corrotto/futuro rifiutato, nome file |
| Test: `app/src/test/java/com/example/data/backup/google/GoogleDriveAuthClientTest.kt` | NUOVO | device flow (pending/slow_down/denied/success/transient), refresh, revoke, id_token |
| Test: `app/src/test/java/com/example/data/backup/google/GoogleDriveFilesClientTest.kt` | NUOVO | folder find/create, upload multipart, lista, download, errori classificati senza token |
| Test: `app/src/test/java/com/example/data/backup/google/GoogleDriveAuthRepositoryTest.kt` | NUOVO | persistenza cifrata, isolamento folder↔account, disconnessione |

## 3. ARCHITETTURA

```
                 ┌──────────────────── UI (BackupRestoreSection.kt) ────────────────────┐
                 │  Esporta configurazione → «Dove vuoi salvare il backup?»             │
                 │  Ripristina da file      → «Da dove vuoi importare?»                 │
                 └────────────┬─────────────────────────────────┬──────────────────────┘
                              │ Locale                          │ Google Drive
              ┌───────────────▼──────────┐          ┌───────────▼───────────────────┐
              │ SAF (CreateDocument/     │          │ GoogleDriveBackupManager      │
              │ OpenDocument, protetto)  │          │  ├─ GoogleDriveAuthRepository │
              │  ↳ fallback in-app       │          │  │   (Device Flow, token      │
              │    (LocalBackupStore,    │          │  │    cifrati, account)       │
              │    File atomico)         │          │  └─ GoogleDriveFilesClient    │
              └───────────────┬──────────┘          │    (Drive REST v3 + OkHttp)   │
                              │                     └───────────┬───────────────────┘
                              ▼                                 ▼
                        BackupManager ── generateEnvelope + encodeValidated (UNA sorgente)
                              │
              import: lettura → BackupCodec.decode (versione, payload) → anteprima
                      → conferma esplicita → BackupManager.restore (SyncConfigApplier)
```

- **Backup JSON**: `BackupEnvelope` (formatVersion, app, data, device, credenziali sì/no,
  payload = stesso schema della sync device-to-device). Sanitizzazione segreti PRIMA
  della serializzazione; il JSON generato è validato con round-trip.
- **Export locale**: SAF quando esiste (utente sceglie dove, nome suggerito
  `StreamNova_Backup_YYYY-MM-DD_HH-mm-ss.json`); altrimenti cartella in-app
  `Android/data/<pkg>/files/StreamNova Backups/` con scrittura atomica.
- **Import locale**: SAF; fallback = lista backup in-app (dal più recente).
- **OAuth**: Device Authorization Grant — l'utente apre `google.com/device` su un
  QUALSIASI altro dispositivo e inserisce il codice. Nessun browser sul TV, nessun
  Google Play Services (entrambi assenti su Fire TV, verificato). Flusso attuale e
  supportato da Google per "TVs and Limited Input devices".
- **Drive API**: REST v3 con OkHttp (già dipendenza; NESSUNA nuova dipendenza runtime).
  Cartella normale visibile `StreamNova Backups` nella root del Drive dell'utente
  (NON `appDataFolder`, scelta documentata sotto).
- **Folder management**: folderId memorizzato PER ACCOUNT (`account_key` = Google `sub`).
  Account cambiato → riferimento invalidato; cartella eliminata → ricreata alla
  prossima export; import NON crea mai cartelle.
- **Upload**: multipart asincrono su IO, retry (max 3) SOLO su errori di rete con
  verifica anti-duplicato per nome file tra i tentativi; errori definitivi
  (auth/quota) interrompono subito; progress in UI; JSON validato prima dell'upload.
- **Download/Restore**: il JSON scaricato passa per la STESSA pipeline dell'import
  locale (validazione → anteprima → conferma → `BackupManager.restore`).
- **Scelta cartella visibile vs appDataFolder**: la cartella normale è visibile e
  gestibile dall'utente nel proprio Drive (vedere/scaricare/eliminare) ed è
  compatibile con lo scope limitato `drive.file`: realizza pienamente i requisiti
  (utente in controllo). `appDataFolder` sarebbe nascosto all'utente: scartato.

## 4. GOOGLE — CONFIGURAZIONE RICHIESTA ALLO SVILUPPATORE

Senza questi passi la UI risponde correttamente
"Google Drive non è disponibile in questa build di StreamNova" (nessuna richiesta
di rete viene fatta): **l'integrazione NON simula un funzionamento senza configurazione**.

1. **Google Cloud Console** → progetto dedicato (o esistente) → abilitare **Google Drive API**.
2. **Credenziali** → Crea credenziale → **ID client OAuth** → tipo
   **"TV e dispositivi con input limitato"** (TVs and Limited Input devices).
3. Copiare il Client ID e fornirlo alla build in uno di questi luoghi (git-ignored):
   - `local.properties`: `GOOGLE_DRIVE_CLIENT_ID=<id>`
   - `.env`: `GOOGLE_DRIVE_CLIENT_ID=<id>` (da `.env.example`)
   - variabile d'ambiente `GOOGLE_DRIVE_CLIENT_ID`
4. **Nessun SHA-1/SHA-256 richiesto**: il Device Flow non usa firmatura (a differenza
   dei client Android/Play Services).
5. **Nessun Client Secret**: il device flow per client TV non ne prevede uno.
6. **Consent screen**: esterna, scope richiesti sotto; nessuna verifica particolare
   necessaria per `drive.file` (non è uno scope sensibile "restrittivo" per app
   personali).
7. Limiti noti: il dispositivo deve avere accesso a Internet; se il TV non ha
   connettività l'operazione fallisce con messaggio chiaro; la revoca dell'accesso
   (da myaccount.google.com/permissions) invalida il collegamento alla prima
   operazione (stato torna "non collegato", senza crash).

## 5. PRIVACY

- **Scope utilizzati** (minimo privilegio):
  - `https://www.googleapis.com/auth/drive.file` — accesso SOLO ai file/cartelle
    creati dall'app: i backup esportati e nient'altro. Nessuna lettura del resto
    del Drive, nessun accesso ad altri documenti, foto o email.
  - `openid` + `https://www.googleapis.com/auth/userinfo.email` — SOLO per mostrare
    quale account è collegato e per isolare la cartella per account (requisito
    "se l'account cambia, non usare la cartella del precedente").
- **Dati salvati**: i backup JSON restano nella cartella `StreamNova Backups` del
  Drive PERSONALE dell'utente (proprietario: l'account collegato).
- **Dati esclusi di default**: chiavi API Debrid/TorBox/OpenSubtitles/SIMKL e token
  SIMKL (`BackupSanitizer.redactSecrets`); inclusione solo con switch esplicito
  ("Includi credenziali").
- **Nessun backup** viene inviato al Drive dello sviluppatore, a backend StreamNova
  o a servizi di terze parti. Nessun server proprio è coinvolto.
- **Token**: il refresh token è conservato CIFRATO (AES/GCM, chiave non esportabile
  nel Keystore Android, stesso meccanismo delle chiavi API); l'access token vive SOLO
  in memoria. Disconnessione = revoca su Google + cancellazione locale totale.
- **Log**: nessun contenuto JSON, nessun token, nessuna chiave; i messaggi contengono
  solo esiti e dimensioni.
- **L'utente** può sempre scollegare l'account dalla sezione Backup ("Account Google
  collegato" → click) e i suoi backup restano nel SUO Drive.

## 6. TEST

### Test mirati (eseguiti realmente)
`./gradlew :app:testDebugUnitTest --tests "com.example.data.backup.*" --tests "com.example.data.backup.google.*"`
→ **BUILD SUCCESSFUL** (43 test della classe di classi bersaglio, 0 falliti dopo fix).

Copertura: codec (round-trip, versione futura, JSON corrotto, payload vuoto, redazione
segreti), export atomico su file (nessun .tmp residuo, round-trip preview, segreti mai
serializzati), nome file conforme, folder find/create/riuso, upload multipart (metadata
+ parents + contenuto + multipart/related), lista con ordinamento, download, errori
classificati SENZA token, device flow completo (pending/slow_down/denied/success/
transient), refresh, revoke, id_token decoder, isolamento folder↔account, persistenza
cifrata, disconnessione con revoca.

### Suite completa
`./gradlew :app:testDebugUnitTest` → **3084 test eseguiti, 3069 superati, 3 falliti, 5 saltati** (10m 17s).

I **3 falliti sono preesistenti e fuori dal perimetro** di questo lavoro (nessuno tocca backup/Drive):
| Test fallito | Dominio | Causa |
|---|---|---|
| `AddonsScreenInstallFocusTest.installazioneDaDialogMantieneIlFocusNellaSezioneAddon` | focus schermata Addon | `AddonsScreen.kt` ha WIP preesistente dell'utente nel working tree (presente in `git status` PRIMA dell'inizio di questo lavoro; mai toccato da questa sessione) |
| `NextEpisodeSeasonBoundaryFallbackTest.seasonBoundaryFallbackQueriesProvidersAndStartsTheSameAddonSource` | affinità sorgente episodio successivo | `StreamNovaViewModel.kt` NON modificato nel working tree → fallimento già presente su HEAD |
| `PlayNextEpisodeResolutionTest.testNextEpisodeHttpPrefersSameProviderStream` | preferenza provider episodio successivo | idem: file invariato → preesistente |

Tutti i 43 test mirati backup/Google (nuovi + esistenti) passano. Nessun test preesistente
è stato eliminato o disabilitato.

### Build
- `:app:compileDebugKotlin` → **SUCCESS**
- `:app:assembleDebug` → **SUCCESS** (1m 22s)
- `:app:assembleRelease` (R8, firma ufficiale `streamnova-release-v2.p12` alias `upload`,
  credenziali caricate dal file DPAPI `%LOCALAPPDATA%\StreamNovaSigning\release-cred.dpapi.json`
  in memoria soltanto: mai stampate, mai salvate, mai committate) → **SUCCESS** (3m 47s)

### Emulatore/device TV — Fire TV Stick AFTMA08C15 (Android 11), installazione reale
`adb install -r` dell'APK release arm-v7a firmato con il keystore ufficiale:
**Success** — installato SOPRA l'app esistente (stessa firma), dati utente preservati
(la Home mostra i "36 titoli in corso" dell'utente, SIMKL collegato). Verifica eseguita
interamente con D-pad (ADB `input keyevent`) + `uiautomator` + logcat:

| # | Verifica (spec FASE 11) | Esito |
|---|---|---|
| 1 | Apertura Backup e Ripristino | ✅ |
| 2 | Click «Esporta configurazione» | ✅ **nessun crash** (prima: ActivityNotFoundException istantanea) |
| 3 | Popup «Dove vuoi salvare il backup?» Locale/Google Drive | ✅ testi esatti dello spec, focus iniziale su Locale |
| 4 | Export locale | ✅ fallback in-app (Fire TV senza SAF): `Backup esportato (file locale): StreamNova_Backup_2026-11-22_19-26-08.json, 315215 byte` |
| 5 | File JSON esistente | ✅ visibile nella lista import («22/11/2026 19:26 · 315 KB») |
| 6 | JSON validità | ✅ round-trip validato dal codec; restore applicato integralmente |
| 7 | Autenticazione Google | ⛔ **bloccata**: serve `GOOGLE_DRIVE_CLIENT_ID` (config sviluppatore Google Cloud, documentata in §4). La UI risponde correttamente senza simulare nulla |
| 8 | Account mostrato | ⛔ richiede la stessa config (row «Account Google collegato» appare quando collegato; logica in test unitari) |
| 9 | Export Google Drive (senza config) | ✅ messaggio «Google Drive non è disponibile in questa build…», **0 richieste di rete verso Google** |
| 10 | Folder sul Drive dell'account | ⛔ richiede config OAuth (logica testata su MockWebServer) |
| 11 | File backup sul Drive | ⛔ idem |
| 12 | Più export senza sovrascrittura | ✅ 2 export → `…19-26-08.json` e `…19-29-11.json` (file distinti) |
| 13 | Import locale | ✅ lista in-app → selezione → anteprima |
| 14 | Import Drive (senza config) | ✅ messaggio corretto, nessun crash |
| 15 | Lista backup Drive | ⛔ richiede config OAuth |
| 16 | Selezione backup | ✅ (locale) |
| 17 | Preview | ✅ «Backup del 22/11/2026 19:26 · versione 1.2.1 — Contenuto: impostazioni, 211 titoli, 9 progressi episodi, account SIMKL» (dati REALI dell'utente) |
| 18 | Conferma restore | ✅ dialog con focus di default su «Annulla» (sicuro), RIGHT+OK per confermare |
| 19 | Dati ripristinati | ✅ `Backup ripristinato: Impostazioni aggiornate. Account SIMKL replicato. 211 titoli aggiornati.` |
| 20 | Annullamento operazioni | ✅ BACK chiude i dialog, uri==null gestito |
| 21 | Account disconnesso | ⛔ richiede config OAuth (disconnessione testata in unit test) |
| 22 | Drive offline | ⛔ richiede config OAuth (gestione implementata: retry su rete, errore chiaro su auth/quota) |
| 23 | BACK e D-pad | ✅ navigazione completa con D-pad, BACK chiude i dialog |
| 24 | Nessun crash | ✅ **0 FATAL EXCEPTION** nel crash buffer di tutta la sessione |

**Doppio click**: il guard `busy` + l'apertura del dialog rendono impossibile un doppio
avvio (il secondo OK va al dialog, non alla riga); le operazioni concorrenti sono
escluse dallo stesso guard. Test di timing esplicito non eseguito (flaky per natura).

## 7. STATO FINALE

| Funzionalità | Stato |
|---|---|
| Local export non crasha (anche senza SAF) | ✅ implementato + **verificato su Fire TV reale** |
| Local import non crasha (anche senza SAF) | ✅ implementato + **verificato su Fire TV reale** (export→import→restore completo) |
| Google OAuth (Device Flow) | ✅ implementato + test unitari; **end-to-end richiede GOOGLE_DRIVE_CLIENT_ID** (config manuale sviluppatore, documentata in §4). Senza config la UI risponde "non disponibile" senza alcuna richiesta di rete |
| Google Drive export | ✅ implementato + testato client/server-finto; verifica end-to-end richiede la config Google Cloud |
| Google Drive import | ✅ implementato (lista/dl/validazione/pipeline comune); idem sopra |
| Restore verificato | ✅ **su device reale**: 211 titoli + account SIMKL ripristinati dal backup appena esportato |
| Build riuscita | ✅ compileDebugKotlin + assembleDebug + assembleRelease (firma ufficiale) |
| Test TV riusciti | ✅ 17/24 verifiche complete su Fire TV; 7 bloccate SOLO dalla config Google Cloud (listate puntualmente in §6) |

**Nota di stato sul device:** la Fire TV ora esegue la build verificata (1.2.1, versionCode
1201, keystore ufficiale) con il backup corretto. L'utente può lasciarla (è una normale
1.2.1 con il backup riparato) o reinstallare l'APK ufficiale 1.2.1 quando avrà integrato
queste modifiche nel ramo release: i dati non vengono toccati in nessuno dei due casi.

**Fermo qui come richiesto:** nessun commit, nessun push, nessuna nuova funzionalità.
In attesa di istruzioni.

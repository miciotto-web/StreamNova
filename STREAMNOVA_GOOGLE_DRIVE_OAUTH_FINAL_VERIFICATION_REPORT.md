# STREAMNOVA — REPORT FINALE VERIFICA GOOGLE DRIVE + OAUTH (REALE)

**Data:** 2026-11-22
**Branch:** `integration/streamnova-1.0.3-w1-w12` @ `528b55d5a566ed78ae4b590cd4042202b680ba1e`
**Device:** Fire TV Stick AFTMA08C15 (Android 11, armeabi-v7a) — IP `192.168.1.7:5555`
**Account Google di test:** miciotto@gmail.com (Test User del progetto Cloud, modalità Testing)
**Report precedenti:** [STREAMNOVA_BACKUP_RESTORE_CRASH_AUDIT.md](STREAMNOVA_BACKUP_RESTORE_CRASH_AUDIT.md) · [STREAMNOVA_BACKUP_RESTORE_GOOGLE_DRIVE_REPORT.md](STREAMNOVA_BACKUP_RESTORE_GOOGLE_DRIVE_REPORT.md)

---

## 1. AUDIT INIZIALE (FASE 1)

Verificato sul codice esistente (nessuna duplicazione):
- **Meccanismo di configurazione** già previsto: `GOOGLE_DRIVE_CLIENT_ID` risolto da
  environment → `local.properties` → `.env` e esposto via `BuildConfig.ENV_GOOGLE_DRIVE_CLIENT_ID`
  (`app/build.gradle.kts`); consumo in `BackupRestoreSection.kt` con protezione
  valore mancante/placeholder → funzionalità risponde "non disponibile", 0 richieste di rete.
- `.gitignore` copre `local.properties` (righe 14, 63) e `.env`/`.env.*` (22-23, 58-59).
- Flusso OAuth: **Device Authorization Grant (RFC 8628)** — compatibile con il client
  creato per `TVs and Limited Input devices` (nessun redirect, nessun browser, nessun
  Play Services). Gestione token già conforme: refresh token cifrato (Keystore),
  access token solo in memoria, revoca alla disconnessione.
- Upload/list/download/restore già implementati e testati a livello unitario (43 test).

## 2. FILE MODIFICATI IN QUESTA SESSIONE (solo ciò che era strettamente necessario)

| File | Modifica | Motivo |
|---|---|---|
| `local.properties` (git-ignored, **verificato ignorato**) | aggiunti `GOOGLE_DRIVE_CLIENT_ID` e `GOOGLE_DRIVE_CLIENT_SECRET` | configurazione locale non versionata (FASE 2) |
| `app/build.gradle.kts` | `buildConfigField ENV_GOOGLE_DRIVE_CLIENT_SECRET` + warning build aggiornato (ID **o** secret mancanti) | Google **richiede** il secret sui token endpoint del device flow (prova reale in §4) |
| `data/backup/google/GoogleDriveAuthClient.kt` | `pollTokenOnce` e `refreshAccessToken` accettano e inviano `client_secret` | l'endpoint token rispondeva `Missing required parameter: client_secret` |
| `data/backup/google/GoogleDriveAuthRepository.kt` | campo `clientSecret` + parametro `init(oauthClientSecret=)` + fail fast con messaggio chiaro se assente + pass-through a poll/refresh | wiring sicuro, nessun fallback silenzioso |
| `app/src/main/java/com/example/StreamNovaApplication.kt` | passa `BuildConfig.ENV_GOOGLE_DRIVE_CLIENT_SECRET` al repository | inizializzazione dalla build |
| `.env.example` | documentato `GOOGLE_DRIVE_CLIENT_SECRET` | procedura sviluppatore |
| `app/src/test/.../GoogleDriveAuthClientTest.kt`, `GoogleDriveAuthRepositoryTest.kt` | firme aggiornate + assertion `client_secret=…` nei corpi delle richieste token | regressioni |

**Nessun altro file toccato.** WIP utente `AddonsScreen.kt` intatto. Nessun commit/push.

## 3. CONFIGURAZIONE CLIENT ID E SICUREZZA

- Client ID `581395774698-…apps.googleusercontent.com` (identificativo **pubblico**)
  e Client Secret `GOCSPX-…` **solo** in `local.properties` (git-ignored;
  `git check-ignore` OK; `git status` non li contiene mai).
- Il Client ID NON è mai stato trattato come secret e nessun secret è stato inventato.
- **Nota trasparenza sul secret TV:** per il device flow su client "TVs and Limited
  Input devices" Google richiede il client_secret sulle chiamate token; per natura del
  flusso TV il segreto vive nell'APK (comportamento standard e documentato da Google
  per le app installate). Nessun token OAuth è mai stato stampato nei log (unica
  riga "token" nel codice: esito "rifiutato", mai valore).
- Il refresh token resta cifrato con `ApiKeyCipher` (Keystore, AES/GCM, chiave non
  esportabile); l'access token vive solo in memoria.

## 4. PROBLEMA INCONTRATO E RISOLTO

**Errore reale sul device:** primo tentativo di collegamento → il dialog device flow
compariva con il codice, ma il **primo poll** falliva:
`Collegamento Google non riuscito: Missing required parameter: client_secret`.
Causa: Google richiede il `client_secret` sulle chiamate al token endpoint (poll e
refresh) **anche** per i client TV (documentazione ufficiale del Device Authorization
Grant). `device/code` invece lo accetta senza (il codice veniva mostrato).
**Fix:** supporto del secret nelle due chiamate token (vedi §2), fail fast se assente,
test aggiornati. Nessun'altra architettura modificata.

## 5. TEST ESEGUIBULE SU PC (FASE 4)

| Verifica | Esito |
|---|---|
| `:app:compileDebugKotlin` | **PASS** |
| Test unitari backup/Google (43: codec, redazione segreti, export atomico, nome file, client OAuth con secret, client Drive, repository/isolamento account) | **PASS 43/43** |
| Suite completa `:app:testDebugUnitTest` (sessione precedente, nessuna modifica ad aree estranee) | 3084 test, 3 falliti **preesistenti** (AddonsScreen WIP utente; 2 Next-Episode su file non toccati) |
| `:app:assembleDebug` | **PASS** |
| `:app:assembleRelease` (firma ufficiale `streamnova-release-v2.p12`, credenziali via DPAPI in memoria, mai stampate) | **PASS** (×2: con ID, poi con ID+secret) |
| BuildConfig contiene ID+secret | **PASS** (verificato nel generated, valori mascherati negli output) |

## 6. TEST REALI SU FIRE TV (FASE 5) — evidenze

Installazione: `adb install -r` APK release arm-v7a **Success**, dati utente preservati.

| # | Test | Esito | Evidenza |
|---|---|---|---|
| 1 | Device flow mostrato a schermo (titolo, URL `google.com/device`, codice, attesa) | **PASS** | dialog `FBD-PCM-PRD` catturato via uiautomator |
| 2 | Autorizzazione umana completata su browser (utente, account Test User) | **PASS** | attestazione utente + esito §3 |
| 3 | Riconoscimento account | **PASS** | riga `Account Google collegato: miciotto@gmail.com` |
| 4 | Export su Drive con creazione cartella dedicata | **PASS** | status `Backup salvato nel tuo Google Drive (StreamNova_Backup_2026-11-22_20-38-06.json, 264 KB)` + logcat `Cartella 'StreamNova Backups' creata sul Drive dell'utente` |
| 5 | File presente e integro SUL DRIVE (prova server-side) | **PASS** | lista import Drive via API dell'account: `StreamNova_Backup_2026-11-22_20-38-06.json · 22/11/2026 20:38 · 264 KB` (match esatto con l'upload) |
| 6 | Download + validazione da Drive | **PASS** | anteprima: `Backup del 22/11/2026 20:38 · versione 1.2.1 — Contenuto: impostazioni, 182 titoli, 8 progressi episodi, account SIMKL` |
| 7 | Restore da Drive | **PASS** | eseguito manualmente dall'utente, esito positivo dichiarato; (logcat ruotato prima della lettura: prove log non disponibili per questo passaggio) |
| 8 | Local export/import/restore (fallback in-app su Fire TV senza SAF) | **PASS** | sessione precedente: logcat `Backup esportato (file locale): …json, 315215 byte` + `Backup ripristinato: Impostazioni aggiornate. Account SIMKL replicato. 211 titoli aggiornati.` + 2 file distinti (nessuna sovrascrittura) |
| 9 | Nessun crash su tutte le sessioni | **PASS** | 0 `FATAL EXCEPTION` nel crash buffer |
| 10 | BACK/dialog D-pad | **PASS** | navigazione completa eseguita via D-pad |
| 11 | Disconnessione account (revoca + pulizia locale) | **NOT TESTED su device** (unit test PASS; l'utente ha chiuso la sessione di test TV prima) |
| 12 | Nuova autorizzazione dopo disconnessione | **NOT TESTED su device** (stesso flusso di #1-#4 già verificato; richiederebbe una seconda autorizzazione umana) |
| 13 | Token scaduto/revocato lato Google | **NOT TESTED su device** (unit test: `invalid_grant` → `TOKEN_REJECTED` → stato "non collegato", nessun crash) |
| 14 | Comportamento senza rete | **NOT TESTED su device** (ADB è su Wi-Fi: disabilitarlo avrebbe interrotto la sessione di test; gestione offline coperta dai test unitari: `Transient`/retry, errore chiaro) |
| 15 | Riuso cartella al secondo export (anti-duplicazione) | **NOT TESTED su device** (logcat ruotato prima della ripetizione; logica unit-testata: cache folderId per account + `files.list` per nome) |

**Nota:** a richiesta dell'utente i test TV si fermano qui; i test 11-15 restano
ripetibili in pochi minuti con la procedura già documentata.

## 7. LIMITAZIONI

- Modalità Testing Google: durante l'autorizzazione può comparire l'avviso
  "app non verificata" (Avanzate → vai all'app) — normale per app in testing con
  Test User. Alla pubblicazione, rimuovendo la modalità Testing l'avviso scompare.
- Il client_secret TV vive nell'APK per natura del flusso (vedi §3).
- Il buffer logcat del device ha ruotato durante i test manuali dell'utente: per il
  restore manuale da Drive (test 7) l'evidenza è l'attestazione dell'utente + la
  prova server-side del file (test 5) e della validazione del contenuto (test 6).

## 8. PROCEDURA PER RIPETERE I TEST RIMANENTI (11-15)

1. **Disconnessione:** Impostazioni → Backup e ripristino → riga `Account Google collegato` → OK →
   attendere messaggio `Account Google scollegato`; la riga scompare.
2. **Nuova autorizzazione:** Esporta configurazione → Google Drive → rifare il flusso
   `google.com/device` (come in §6 #1-#4).
3. **Riuso cartella:** dopo la riconnessione, secondo export → logcat deve mostrare
   riuso (nessuna seconda "creata"); su Drive: sempre UNA sola cartella.
4. **Offline:** disattivare il Wi-Fi dal menu Fire TV (NON via adb, che gira sul Wi-Fi),
   provare un export → errore chiaro senza crash; riattivare il Wi-Fi.
5. **Token revocato:** myaccount.google.com/permissions → rimuovere StreamNova →
   operazione Drive successiva → stato torna "non collegato" con messaggio chiaro.

## 9. RISULTATO FINALE

| Area | Stato |
|---|---|
| Configurazione Google Cloud + Client ID/Secret | ✅ operativa |
| OAuth Device Flow reale (utente personale) | ✅ **PASS** |
| Export su Drive personale (cartella `StreamNova Backups`) | ✅ **PASS** (prova server-side) |
| Import da Drive (lista/download/validazione/preview) | ✅ **PASS** |
| Restore da Drive | ✅ **PASS** (utente) |
| Nessun account centralizzato / backend / Drive sviluppatore | ✅ confermato (file nel Drive di miciotto@gmail.com) |
| Sicurezza (secret non versionato, token cifrati, nessun token nei log) | ✅ |
| Nessun crash | ✅ |
| Test residui (11-15) | 🟡 NOT TESTED su device — procedura in §8 |

**FERMATO come richiesto:** nessun commit, nessun push, nessuna cancellazione.

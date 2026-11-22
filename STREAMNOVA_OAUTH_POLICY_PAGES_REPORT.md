# StreamNova — Report Pagine Privacy Policy e Terms of Service (OAuth Google)

**Data:** 22 novembre 2026
**Branch di lavoro:** `integration/streamnova-1.0.3-w1-w12` (22 commit avanti su `origin/main`, 0 indietro)
**Obiettivo:** creare e rendere pubblicamente accessibili le due pagine richieste dal Branding OAuth di Google Cloud (Privacy Policy URL e Terms of Service URL) per la pubblicazione dell'app OAuth in produzione.

---

## 1. Audit effettuato

Analisi condotta direttamente sul codice sorgente di questo clone (`D:\VSCODE\Progetti\StreamNova_integration`, working tree pulito a inizio lavoro). I documenti creati contengono **esclusivamente** affermazioni verificabili nel codice seguente.

### 1.1 OAuth Device Authorization Grant (RFC 8628) — Google Drive

Fonte: `app/src/main/java/com/example/data/backup/google/GoogleDriveAuthClient.kt`, `GoogleDriveAuthRepository.kt`, `GoogleDriveFilesClient.kt`

- Endpoint Google usati: `oauth2.googleapis.com/device/code`, `/token`, `/revoke`, `openidconnect.googleapis.com/v1/userinfo`.
- **Scopes richiesti:** `https://www.googleapis.com/auth/drive.file` + `openid` + `https://www.googleapis.com/auth/userinfo.email` (stringa `Scopes.DEVICE_FLOW`).
- Client OAuth di tipo "TVs and Limited Input devices"; Client ID/Secret da BuildConfig (nessuna credenziale nei documenti o nel sito).
- **`drive.file` = accesso solo ai file/cartelle creati dall'app**: il codice opera unicamente su `files.list/create/download` limitati alla cartella `StreamNova Backups` (`GoogleDriveFilesClient.kt`, query `name = 'StreamNova Backups'`, `'folderId' in parents`).
- Dati account trattati: **e-mail** (da id_token o userinfo) e **`sub`** (`GoogleIdTokenDecoder`) — commento nel codice: "NESSUN altro uso"; usati per mostrare l'account collegato e isolare i profili/la cartella per account.
- **Token:** refresh token persistito **cifrato** (`ApiKeyCipher` / Android Keystore, alias `streamnova_google_drive_token`); access token **solo in memoria**, rinnovato on-demand; nessun token nei log (verificati i messaggi `Log.w` del repository).
- **Disconnessione:** `disconnect()` revoca il token su Google (`/revoke`) e cancella ogni dato locale (token, e-mail, folder id). Verificato anche l'invalidamento automatico se il refresh viene rifiutato.
- **Nessuna API di cancellazione file su Drive nel codice** (nessun `files.delete`): i backup restano nel Drive dell'utente e sono gestiti/eliminati dall'utente su Google Drive; `disconnect()` non cancella i backup.
- Il collegamento è **per profilo** (DataStore `google_drive_preferences_<profile>`); cambio account ⇒ riferimento cartella invalidato.

### 1.2 Backup / Restore

Fonte: `data/backup/BackupManager.kt`, `LocalBackupStore.kt`, `data/sync/SyncPayload.kt`, `ui/screens/BackupRestoreSection.kt`

- Formato: JSON (`BACKUP_FORMAT_VERSION = 1`), estensione `.streamnova.json`, leggibile e validato (rifiuto versioni future, payload vuoto).
- **Contenuto:** impostazioni generali, impostazioni di riproduzione, addon Stremio (URL/nome/stato), preferiti, "Continua a guardare", progressi episodi, metadati catalogo.
- **Credenziali escluse di default** (`includesCredentials = false`; `BackupSanitizer.redactSecrets` rimuove chiavi TorBox/OpenSubtitles/Simkl): incluse solo su scelta esplicita dell'utente.
- Intestazione backup: versione app, data, **modello del dispositivo** (`Build.MODEL`), flag credenziali.
- PIN controllo parentale: conservato e trasferito (se richiesto) solo come **hash SHA-256**, mai in chiaro.
- Backup locali: file privati dell'app (`LocalBackupStore`) o percorso scelto dall'utente; cancellabili localmente.

### 1.3 Sincronizzazione device-to-device

Fonte: `data/sync/ConfigSyncServer.kt`, `SyncDiscovery.kt`

- Solo **rete locale** (mDNS `_streamnova._tcp.`, socket diretto); nessun server esterno.
- Trasferimento solo dopo **approvazione esplicita sul dispositivo ricevente** (`SyncIncomingRequest`).

### 1.4 Altri servizi di terze parti (verificati negli endpoint del codice)

- **TMDB** (`api.themoviedb.org`, `image.tmdb.org`) — cataloghi/metadati; chiave app da build (README: fuori dal repository).
- **Addon Stremio** — URL predefiniti/configurati dall'utente; richieste di cataloghi/stream/metadati.
- **Simkl** (`api.simkl.com`) — account **opzionale**, device OAuth PIN, scrobble start/stop/pause, `sync/history` (+remove), `sync/all-items`; revoca token alla disconnessione.
- **Debrid** — Real-Debrid (`api.real-debrid.com/rest/1.0`), TorBox (`api.torbox.app/v1/api`), Premiumize (`api.premiumize.me`); chiavi API dell'utente; invio hash torrent/magnet/link per risoluzione.
- **OpenSubtitles** (`api.opensubtitles.com`) — ricerca sottotitoli con chiave configurata dall'utente.
- **Aggiornamenti** — `api.github.com/repos/.../releases/latest` (nessun dato personale); APK dalle release ufficiali GitHub.
- **Nessun analytics/telemetria/ads/crash-reporting**: `firebase-bom` presente nel Gradle ma tutti i moduli commentati e nessun import `com.google.firebase` nel sorgente; nessun altro SDK di tracking.

### 1.5 Identità e contatti

- Repository pubblico `github.com/miciotto-web/StreamNova` (owner User; `has_issues: true`); sviluppatore = account GitHub **miciotto-web**.
- **Nessun e-mail di contatto pubblicato nel repository**: nei documenti come contatto è usata la sezione Issues del repository (canale reale e verificato).
- Disclaimer in-app già presente e allineato (`res/values/strings.xml:466-479`): "contenitore e interfaccia tecnologica", "non ospita…", "l'utente è l'unico responsabile delle sorgenti" — i Termini ne riprendono il contenuto.

---

## 2. File creati (in questo clone, branch di integrazione)

| File | Scopo |
|---|---|
| `PRIVACY_POLICY.md` | Informativa privacy specifica StreamNova (15 sezioni) |
| `TERMS_OF_SERVICE.md` | Termini di servizio specifici StreamNova (10 sezioni) |
| `_layouts/default.html` | Layout Jekyll minimo responsive (mobile-friendly) per le pagine pubblicate |
| `_config.yml` | Esclude dal sito Pages codice, screenshot e file di lavoro (repo ~227 MB tracciati → sito di pochi MB) |
| `STREAMNOVA_OAUTH_POLICY_PAGES_REPORT.md` | Questo report |

Nessun file esistente è stato modificato o cancellato. Nessun file dell'app (Kotlin/Gradle/manifest/firma) è stato toccato. Nessuna credenziale è inclusa nei documenti.

## 3. Contenuti verificati nei documenti

- Google Drive: collegamento **facoltativo**, per account dell'utente, device flow `google.com/device`, scope `drive.file`+openid+email (tabella scope nella policy), backup **nel Drive personale dell'utente** (nessun Drive dello sviluppatore), token cifrati/revocati, gestione ed eliminazione dei backup **da Google Drive da parte dell'utente**, revoca anche da `myaccount.google.com/permissions`.
- Nessuna affermazione di "lettura di tutto il Drive", nessuna certificazione o conformità legale non verificabile.
- Credenziali nei backup escluse di default; modello dispositivo e hash PIN descritti correttamente.
- Assenza di telemetria e assenza di server dello sviluppatore dichiarate (verificate in §1.4).
- Descrizione sincronizzazione LAN con approvazione esplicita.
- Termini: responsabilità utente, sorgenti di terze parti, backup/restore (conservazione copie e verifica ripristino), disponibilità/interruzioni, limitazioni, IP/branding, modifiche, contatti — coerenti con il disclaimer in-app esistente.

## 4. Assunzioni e informazioni mancanti

1. **Contatto e-mail assente nel repository** → usate le Issues GitHub. Se viene attivato un e-mail di supporto, aggiornare le sezioni "Contatti" dei due documenti.
2. **Giurisdizione/legge applicabile non indicata nei Termini**: nessuna informazione nel repository consente di dichiararla senza inventare; clausola omessa (nessuna scelta di foro).
3. **Licenza software**: nel repository non c'è file LICENSE; i Termini non dichiarano licenze open del progetto e descrivono un diritto d'uso personale. Se verrà pubblicata una LICENSE, aggiornare il §7.
4. Lingua dei documenti: **italiano** (lingua dell'app, del repository e degli annunci). Google accetta policy nella lingua dell'app; traduzioni non fornite in questa fase.

## 5. Pubblicazione GitHub Pages — stato

- **GitHub Pages al momento NON è attivo** sul repository (`has_pages: false`, API `GET /repos/miciotto-web/StreamNova/pages` → 404).
- Non esisteva alcuna modalità di pubblicazione documentale preesistente: la cartella `docs/` contiene documenti (guida utente, annunci Telegram), non un sito; il file `index.html` alla radice è un'app a sé ("Snake Neon 3.0"), lasciata **intatta** — con Pages dalla radice resterà la landing del sito (sostituibile in futuro a cura del maintainer, fuori dal perimetro di questa attività).
- Soluzione predisposta (minima e stabile): **Pages da branch `main`, cartella radice**, con Jekyll (default GitHub Pages) + `_config.yml` con `exclude` per mantenere il sito leggero e rapido.
- URL attesi (coerenti con la richiesta): `https://miciotto-web.github.io/StreamNova/privacy` e `https://miciotto-web.github.io/StreamNova/terms` (permalink Jekyll `/privacy/` e `/terms/`).
- **Verifiche pre-build effettuate:** nessun file markdown/html tracciato contiene front matter spurio (`---` iniziale) o sintassi Liquid `{{`/`{%` che possano rompere la build Jekyll.

## 6. URL pubblici e verifiche HTTPS / accesso anonimo / mobile

⚠️ **Le pagine NON sono ancora pubblicate**: attualmente i due URL restituiscono 404 (Pages disattivato) e le verifiche seguenti NON sono state eseguite. Non è dichiarato alcun funzionamento non verificato.

Dopo commit + push + attivazione Pages, verificare (previste in §8):

1. `https://miciotto-web.github.io/StreamNova/privacy` → 200, contenuto Privacy Policy.
2. `https://miciotto-web.github.io/StreamNova/terms` → 200, contenuto Termini.
3. Accesso **senza login** GitHub (browser anonimo / `curl` senza cookie).
4. **Mobile:** il layout ha viewport meta e CSS responsive; verificare rendering reale.

## 7. Stato Git

- Prima delle modifiche: working tree **pulito** su `integration/streamnova-1.0.3-w1-w12`.
- Dopo le modifiche: 5 **nuovi** file non tracciati (§2); nessuna modifica a file esistenti; nessun commit o push eseguito (in attesa di autorizzazione esplicita, come richiesto).
- Clone `D:\VSCODE\Progetti\StreamNova` (branch `backup/streamnova-working-20261016`, con modifiche WIP): **non toccato** — tutte le modifiche WIP preservate.
- OAuth/Client ID/Client Secret/token, firma, Application ID (`com.aistudio.streamnova.tvapp`), canale di aggiornamento: **non modificati** e non inclusi nei documenti.

## 8. Azioni manuali ancora necessarie (in attesa di autorizzazione)

1. **Autorizzazione a commit e push** dei 5 nuovi file (su `integration/streamnova-1.0.3-w1-w12` e merge/push su `main`, oppure commit diretto su `main`).
2. **Attivazione GitHub Pages**: Settings → Pages → "Deploy from a branch" → branch `main`, cartella `/ (root)`; in alternativa può essere effettuata via API (`POST /repos/miciotto-web/StreamNova/pages`) dopo autorizzazione.
3. **Verifica post-pubblicazione** dei due URL (200, accesso anonimo, rendering mobile) e aggiornamento di questo report con gli esiti reali.
4. Inserire gli URL verificati nei campi **Privacy Policy URL** e **Terms of Service URL** della schermata OAuth Branding di Google Cloud, quindi procedere alla verifica/pubblicazione dell'app OAuth.

---

**Esito:** documenti pronti e conformi ai fatti verificati nel codice; pubblicazione e verifica URL reali pendenti sull'autorizzazione ai punti §8.1–8.2.

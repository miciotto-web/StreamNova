# STREAMNOVA — AUDIT CRASH BACKUP & RIPRISTINO

**Data:** 2026-11-22
**Branch:** `integration/streamnova-1.0.3-w1-w12` @ `528b55d5a566ed78ae4b590cd4042202b680ba1e`
**Build analizzata:** 1.2.1 (versionCode 1201) — release, R8, installata sul device reale.
**Device di riproduzione:** Amazon Fire TV Stick **AFTMA08C15**, Android 11 (API 30), collegato via ADB (`192.168.1.2:5555`).

---

## 1. CRASH RIPRODOTTI

Entrambi i crash sono stati riprodotti realmente sul device con navigazione D-pad
(ADB `input keyevent`) fino alle voci di Impostazioni → «BACKUP E RIPRISTINO»,
con buffer crash logcat pulito prima di ogni pressione.

### 1.1 Crash «Esporta configurazione»

Focus verificato via `uiautomator dump` sulla riga «Esporta configurazione»
(bounds `[184,881][1856,987]`), pressione OK (keyevent 23):

```
11-22 18:06:59.777 E/AndroidRuntime(28583): FATAL EXCEPTION: main
11-22 18:06:59.777 E/AndroidRuntime(28583): Process: com.aistudio.streamnova.tvapp, PID: 28583
11-22 18:06:59.777 E/AndroidRuntime(28583): android.content.ActivityNotFoundException: No Activity found to handle Intent { act=android.intent.action.CREATE_DOCUMENT typ=application/json (has extras) }
11-22 18:06:59.777 E/AndroidRuntime(28583): 	at android.app.Instrumentation.checkStartActivityResult(Instrumentation.java:2067)
11-22 18:06:59.777 E/AndroidRuntime(28583): 	at android.app.Instrumentation.execStartActivity(Instrumentation.java:1727)
11-22 18:06:59.777 E/AndroidRuntime(28583): 	at android.app.Activity.startActivityForResult(Activity.java:5337)
11-22 18:06:59.777 E/AndroidRuntime(28583): 	at androidx.activity.ComponentActivity.startActivityForResult        (ca0)
11-22 18:06:59.777 E/AndroidRuntime(28583): 	at androidx.activity.ComponentActivity$activityResultRegistry$1       (z90.b)
11-22 18:06:59.777 E/AndroidRuntime(28583): 	at androidx.activity.compose.ManagedActivityResultLauncher            (al2.d1)
11-22 18:06:59.777 E/AndroidRuntime(28583): 	at com.example.ui.screens.SettingsMaintenanceSectionsKt$$ExternalSyntheticLambda21 (sh4.g)   ← lambda di click dell'EXPORT
11-22 18:06:59.777 E/AndroidRuntime(28583): 	at com.example.ui.components.TvFocusableKt$TvFocusableBox$4$1         (tl5.a)  ← handler click D-pad
11-22 18:06:59.777 E/AndroidRuntime(28583): 	at androidx.compose.ui.input.key.KeyInputNode                          (v72.S)
11-22 18:06:59.777 E/AndroidRuntime(28583): 	at androidx.compose.ui.focus.FocusOwnerImpl                            (zc1.b)
11-22 18:06:59.777 E/AndroidRuntime(28583): 	at androidx.compose.ui.platform.AndroidComposeView.dispatchKeyEvent    (gc)
```

Esito: `ps -A | grep streamnova` → **PROCESS_GONE** (l'app si chiude).

### 1.2 Crash «Ripristina da file»

Focus verificato sulla riga «Ripristina da file» (preceduta da «Esporta configurazione»
e dallo switch «Includi credenziali»), pressione OK:

```
11-22 18:11:43.671 E/AndroidRuntime(303): FATAL EXCEPTION: main
11-22 18:11:43.671 E/AndroidRuntime(303): Process: com.aistudio.streamnova.tvapp, PID: 303
11-22 18:11:43.671 E/AndroidRuntime(303): android.content.ActivityNotFoundException: No Activity found to handle Intent { act=android.intent.action.OPEN_DOCUMENT typ=*/* (has extras) }
11-22 18:11:43.671 E/AndroidRuntime(303): 	at android.app.Instrumentation.checkStartActivityResult(Instrumentation.java:2067)
11-22 18:11:43.671 E/AndroidRuntime(303): 	... stessa catena (sh4.g → al2.d1 → z90.b → ca0 → Instrumentation)
```

Esito: **PROCESS_GONE**.

---

## 2. EVIDENZA AMBIENTALE

Il Fire TV Stick **non ha nessun gestore documenti** (SAF UI). Verifiche sul device:

```
$ cmd package query-activities -a android.intent.action.CREATE_DOCUMENT -t application/json
No activities found

$ cmd package query-activities -a android.intent.action.OPEN_DOCUMENT -t application/json
No activities found

$ pm list packages | grep -i documentsui
(vuoto — DocumentsUI assente)

$ pm list packages | grep -i gms
(vuoto — niente Google Play Services sul Fire TV)
```

Conseguenza: su Fire TV (e su qualsiasi TV box senza file manager SAF) **ogni**
`ActivityResultContracts.CreateDocument`/`OpenDocument` è irrisolvibile.

## 3. ROOT CAUSE

In `app/src/main/java/com/example/ui/screens/SettingsMaintenanceSections.kt`:

- **Export** (riga 114): `exportLauncher.launch("StreamNova-backup-$stamp.json")`
- **Import** (riga 129): `importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))`

`ManagedActivityResultLauncher.launch()` → `ActivityResultRegistry.launch()` →
`ComponentActivity.startActivityForResult()` → `Instrumentation.checkStartActivityResult()`
lancia **sincronamente** `ActivityNotFoundException` quando l'Intent non ha handler.
Le due chiamate `launch()` non sono protette da `try/catch` e la lambda è invocata
direttamente dal gestore tasti D-pad (`TvFocusableBox`): l'eccezione esplode sul
main thread **fuori** da qualsiasi `Result`/coroutine → crash immediato.

Tutto il resto della pipeline è **corretto** (verificato per esclusione, il crash
avviene PRIMA di raggiungerlo):

- `BackupManager.exportTo/preview/restore` girano su `Dispatchers.IO`, ritornano
  `Result`, catturano ogni eccezione — nessun percorso crashante trovato;
- il flusso import fa preview+validazione prima del restore, con conferma esplicita;
- `SyncConfigApplier.apply` applica solo le categorie presenti, unisce la libreria
  senza cancellazioni, cattura gli errori per-addon;
- il guard `busy` esiste per i doppi click; i callback post-awaiter usano il
  `rememberCoroutineScope` (annullato alla distruzione del composable, nessun
  callback su Activity morta).

### Root cause única

> Chiamata `launch()` dei SAF launcher NON protetta su dispositivi senza
> document provider (Fire TV senza DocumentsUI) → `ActivityNotFoundException`
> sincrona nel click handler → chiusura dell'app.

## 4. CORREZIONI NECESSARIE

1. **Protezione del `launch()`**: try/catch attorno a entrambe le chiamate;
   in caso di `ActivityNotFoundException` (o altro errore di risoluzione)
   attivare il **fallback in-app** invece di crashare.
2. **Fallback export locale in-app**: cartella privata dell'app
   (`Android/data/<pkg>/files/StreamNova Backups/`) scrivibile senza permessi e
   senza SAF, con scrittura atomico (file temporaneo + rename) così un file
   incompleto non viene mai presentato come backup valido.
3. **Fallback import locale in-app**: lista dei backup JSON presenti nella
   cartella in-app (ordinati dal più recente), alimentata dallo stesso export
   fallback; convergere comunque nella pipeline preview → conferma → restore.
4. **Nome file conforme alle specifiche**: `StreamNova_Backup_YYYY-MM-DD_HH-mm-ss.json`
   (l'attuale è `StreamNova-backup-yyyyMMdd-HHmm.json`, senza secondi).
5. SAF resta la scelta PRIMARIA dove disponibile (phone/emulatore/box con
   DocumentsUI): il fallback si attiva solo se il picker non può aprirsi.
6. Annullamento (`uri == null`), errori di scrittura e lettura già trattati:
   conservati e testati.

## 5. RISCHI RESIDUI (post-fisso, già gestiti o monitorati)

| Rischio | Stato |
|---|---|
| `openOutputStream(uri,"wt")` su provider senza supporto "wt" | Catturato da `Result.failure` — nessun crash; il fallback in-app scrive via `File` API |
| Toast da coroutine annullata alla distruzione del composable | Safe: `Toast.makeText` su context applicazione + scope annullato non crasha |
| Restore parziale (settings applicate, poi errore addon) | Errori addon contati in `addonErrors` e mostrati; le preferenze usano i setter con flag "scrittura locale"; nessun stato incoerente osservato |
| Token/credenziali nei log | `BackupSanitizer.redactSecrets` esclude i segreti PRIMA della serializzazione; nessun JSON di backup viene loggato |
| Doppi click / operazioni concorrenti | Guard `busy` esistente; verrà esteso agli stati della FASE 9 |

## 6. NOTE PER L'INTEGRAZIONE GOOGLE DRIVE (FASE 4-8)

Verificato sullo stesso device:
- **Nessun Google Play Services** → escludono Credential Manager / `AuthorizationClient` / Google Sign-In SDK;
- **Nessun browser** (niente Silk/Chrome/Firefox) → esclude AppAuth con Custom Tabs;
- **Flusso scelto: OAuth 2.0 Device Authorization Grant (RFC 8628)** — il flusso
  Google ufficialmente supportato per "TV and Limited Input devices":
  l'utente apre `google.com/device` su QUALSIASI altro dispositivo, inserisce il
  codice mostrato a schermo e autorizza. Nessun browser sul TV, nessun Play
  Services. Scopo: `https://www.googleapis.com/auth/drive.file` (minimo privilegio).
- Richiede configurazione manuale Google Cloud (OAuth client tipo
  "TVs and Limited Input devices"): documentata nel report finale; senza Client ID
  configurato la sezione Drive risponde "non disponibile in questa build"
  (stesso pattern già usato per SIMKL_CLIENT_ID).

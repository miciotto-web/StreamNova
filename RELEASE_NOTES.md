## StreamNova v1.0.0 — Baseline ufficiale

Prima baseline ufficiale di **StreamNova** per Android TV e Fire TV Stick.
Versione dichiarata in `app/build.gradle.kts`: **versionName 1.0.0, versionCode 100**.

Il documento distingue ciò che è già presente nella cronologia del branch da
ciò che arriva con il presente commit (note comprese).

## Già nella cronologia del branch `backup/streamnova-working-20261016`

- **Scrubbing D-Pad del player** (`0099cdd`): avanzamento continuo a passi da
  10 s con debounce di 900 ms, anteprima a schermo intero con HUD nascosto e
  protezione del focus tramite `onPreviewKeyEvent`.
- **Baseline 1.0.0 e firma dual-schema** (`c7af852`): la configurazione di
  build abilita la firma schema v1 (JAR) e v2 (`enableV1Signing` /
  `enableV2Signing`) sia per release sia per debug. Nessun APK è incluso nel
  repository: la firma effettiva va verificata sull'artefatto ricostruito
  (es. `apksigner verify --verbose <apk>`) prima di distribuirlo.
- **Icone e banner launcher Fire TV** (`a41e7aa`): banner 16:9 alle densità
  corrette (160×90 dp), livello adattivo a 108 dp e manifest aggiornato; le
  mattonelle del launcher non vengono più tagliate.

## Incluso nel presente commit

- **Sidebar dinamica**: la voce "Addon" compare solo in modalità Debrid
  TorBox. Un unico predicato (`StreamingEngineMode.supportsAddonsSection`)
  governa il filtro della lista del menu, il ripristino della sezione al
  cambio di modalità o alla risalita dal player e il fallback dell'area a
  monte dell'interfaccia.
- **Focus iniziale dei dialog di impostazioni**: `TrackSelectionDialog` posa
  il focus D-Pad sulla voce già attiva, non sulla prima della lista. La
  correzione copre tutti i dialoghi di `SettingsScreen` costruiti sul
  componente (modalità di riproduzione, risoluzione preferita, cache,
  dimensione/sfondo/posizione sottotitoli, lingue audio e app, parental
  control, buffer avanzati): un OK immediato del telecomando non cambia più
  un'impostazione per errore.
- **Descrizione Web Streaming**: `settings_http_desc` riscritta in italiano,
  inglese, tedesco, spagnolo e francese: in questa modalità StreamNova
  riproduce direttamente i flussi degli scraper web gratuiti, senza account
  Debrid né chiavi API esterne.
- **Test**: `SidebarMenuTest` esteso alla visibilità del menu nelle due
  modalità di riproduzione; nuovo `TrackSelectionDialogFocusTest` per
  l'indice di focus iniziale e i relativi fallback.

## Verifiche

La suite di unit test JVM (640 test) è stata eseguita in locale al momento
del commit con esito positivo (un test di rete resta ignorato). L'esito di
una singola esecuzione è un evento di verifica, non una garanzia;
misurazioni puntuali come i tempi di avvio non sono riportate perché non
riproducibili dal repository.

## Installazione via ADB

Ricostruire l'artefatto dal sorgente e installarlo sul dispositivo:

    ./gradlew.bat assembleDebug
    adb install -r app/build/outputs/apk/debug/StreamNova-debug.apk

La variante debug è firmata con il debug keystore. Per la variante release
occorrono keystore e password (`KEYSTORE_PATH`, `STORE_PASSWORD`,
`KEY_PASSWORD`); verificare la firma dell'artefatto anche in quel caso.

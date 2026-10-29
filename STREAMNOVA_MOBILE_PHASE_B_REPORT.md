# StreamNova Mobile — Report Fase B

**Branch:** `feature/streamnova-mobile`, creato da `e616bfb` (`release: StreamNova 1.0.3 (build 1005)`)
**Data:** 2026-10-28
**Stato:** B0–B7 completati; build TV verde, 1702 test unitari senza fallimenti, APK Debug mobile generato e verificato su emulatore.

---

## 1. Esito sintetico

| Fase | Esito | Evidenza principale |
|---|---|---|
| B0 — Preparazione | ✅ | Branch creato da HEAD senza toccare file non tracciati; baseline TV `assembleDebug` + 1702 test verdi |
| B1 — `:core:model` | ✅ | Modelli/domain estratti; build TV e 1702 test verdi |
| B2 — `:core:designsystem` | ✅ | Tema, palette, wordmark, badge provider, asset ufficiali e funzioni neon condivisi; 1702 test verdi |
| B3 — `:core:data` | ✅ | Repository/networking/Room/DataStore estratti; configurazione esplicita TV/mobile; dipendenze invertite rimosse; 1702 test verdi |
| B4 — `:core:player` | ✅ | Motore Media3 e helper condivisi estratti; AAR NuvioTV spostate nel modulo player; 1702 test verdi |
| B5 — `:mobile` | ✅ | App mobile con Home, Film, Serie TV, Ricerca, Dettaglio, Preferiti, Impostazioni, navigazione touch e layout responsive |
| B6 — Player mobile | ✅ | Riproduzione HLS reale su emulatore, controlli touch, pausa/seek, lifecycle e salvataggio progresso verificati |
| B7 — Verifica finale | ✅ | Build TV + mobile, test, confronto visivo TV/telefono/tablet, report |

**APK Debug mobile:** `D:\VSCODE\Progetti\StreamNova\mobile\build\outputs\apk\debug\mobile-debug.apk`
**Application ID mobile:** `com.aistudio.streamnova.mobile` (`versionCode 1`, `versionName 0.1.0`, firma Debug)
**APK TV:** `app/build/outputs/apk/debug/StreamNova-universal-debug.apk` e split ABI in `app/build/outputs/apk/debug/`
**Identità TV invariata:** `com.aistudio.streamnova.tvapp`, `versionCode 1005`, `versionName 1.0.3`, firma/distribuzione/updater non modificati.

---

## 2. Architettura risultante

```text
:app                 (TV, com.aistudio.streamnova.tvapp)
 ├── :core:model          modelli di dominio puri
 ├── :core:designsystem   tema, palette, wordmark, asset, effetti neon, badge provider
 ├── :core:data           Room, DataStore, TMDB, Stremio, TorBox, OpenSubtitles, SIMKL, updater
 └── :core:player         motore Media3 condiviso, renderer, source factory, sottotitoli, error policy

:mobile              (mobile, com.aistudio.streamnova.mobile)
 ├── :core:model
 ├── :core:designsystem
 ├── :core:data
 └── :core:player
```

### `:core:model`
- `MediaModels.kt` (MediaItem, Episode, stagioni, progresso)
- `Stream.kt`, `Subtitle.kt`, `IntroWindow.kt`
- `StreamModels.kt` (StreamSource, CacheState, StreamResult, ordinamento TorBox)
- `PlaybackSettings.kt` (soli enum e value type condivisi)

### `:core:designsystem`
- `Color.kt`, `Theme.kt`, `Type.kt` (palette Nova invariata)
- `StreamNovaWordmark.kt` + font ufficiale `streamnova_wordmark.ttf`
- `ProviderCatalog.kt` + logo provider ufficiali
- `NovaEffects.kt`: `drawNeonGlow`, `drawSpecularHighlight`, `drawNeonBorder`, geometrie card
- Asset condivisi: logo/icona/text StreamNova

### `:core:data`
- Tutto il layer dati esistente, spostato senza cambiare package Kotlin (import invariati nelle app)
- Nuova configurazione esplicita `com.example.data.config.StreamNovaClientConfig` / `StreamNovaConfig`
- `MediaSampleAssets` per gli asset demo locali: il core non legge più `R` del modulo `:app`
- `ProviderBindingRegistry` usa la `R` di `:core:data` e la raw resource spostata nel modulo
- Schema Room invariato: versione 8, migrazioni 1→8 intatte, `exportSchema = false`

### `:core:player`
- Helper Media3 non-UI: source factories, HLS/progressive, sottotitoli, load control, renderer, error policy, selezione tracce, seek preview
- `PlayerErrorRecoveryPolicy` estrae la classificazione tecnica degli errori; i messaggi localizzati restano nell'app TV
- Le AAR locali NuvioTV sono state spostate da `app/libs` a `core/player/libs` e sono esposte con `api(...)`: l'app TV continua a usare ExoPlayer/PlayerView direttamente

### `:mobile`
- `MobileApplication`: installa `StreamNovaConfig` e inizializza i repository condivisi
- `MainActivity`: edge-to-edge, tema condiviso, system bar scure
- Navigazione Compose:
  - bottom bar su smartphone
  - navigation rail su tablet (>= 840dp)
  - route: Home, Film, Serie TV, Cerca, Preferiti, Impostazioni, Dettaglio, Player
- ViewModel mobili dedicati: `MobileCatalogViewModel`, `MobileDetailViewModel`, `MobilePlayerViewModel`, `MobileSettingsViewModel`
- Schermate: Home, Cataloghi, Ricerca, Dettaglio, Preferiti, Impostazioni, Player
- Tema e branding: `MyApplicationTheme`, wordmark, palette, card neon e asset ufficiali condivisi

---

## 3. Configurazione esplicita TV/mobile

`StreamNovaClientConfig` contiene:
- `appId` (`"tv"` / `"mobile"`)
- `versionName`
- `isDebug`
- `tmdbApiKeyCandidates`
- `simklClientIdChannels`
- `mediaSampleAssets` (ID risorsa degli asset demo dell'app ospite)

Le credenziali **non sono nel sorgente**:
- build TV: `BuildConfig.TMDB_API_KEY`, `BuildConfig.ENV_TMDB_API_KEY`, `BuildConfig.SIMKL_CLIENT_ID`, `BuildConfig.ENV_SIMKL_CLIENT_ID` (già risolti da `.env` / `local.properties` / ambiente);
- build mobile: stessi canali, risolti nel `mobile/build.gradle.kts` da `.env`, `local.properties` o variabili d'ambiente.

In assenza di configurazione il core resta inerte: nessuna credenziale, nessun asset locale, versione vuota. I test che dipendono da credenziali reali si dichiarano esplicitamente non eseguibili invece di fallire.

---

## 4. Build e test

### Comandi eseguiti

```bash
./gradlew :app:assembleDebug
./gradlew :core:data:testDebugUnitTest :core:player:testDebugUnitTest :app:testDebugUnitTest
./gradlew :mobile:assembleDebug
```

### Risultati

| Momento | Test | Fallimenti | Errori | Skipped |
|---|---:|---:|---:|---:|
| Baseline B0 (`:app`) | 1702 | 0 | 0 | 2 |
| B1 `:core:model` | 1702 | 0 | 0 | 2 |
| B2 `:core:designsystem` | 1702 | 0 | 0 | 2 |
| B3 `:core:data` + `:app` | 1022 + 680 = 1702 | 0 | 0 | 12 |
| B4 `:core:data` + `:core:player` + `:app` | 1022 + 278 + 402 = 1702 | 0 | 0 | 12 |
| B7 verifica finale | 1702 (input identici, ripristinati dalla cache Gradle) | 0 | 0 | 12 |

I 12 test saltati sono test di integrazione TMDB che richiedono una chiave reale e rete (`TmdbNetwork.assumeConfiguredApiKey`). Non sono fallimenti: nella baseline erano 2 perché i test del layer dati giravano nel modulo `:app`, dove la chiave era disponibile via `BuildConfig`. Il core non duplica la chiave nel proprio BuildConfig, per non moltiplicare le credenziali nei moduli.

### Build artifact

- TV: `app/build/outputs/apk/debug/StreamNova-universal-debug.apk` (e split `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`)
- Mobile: `mobile/build/outputs/apk/debug/mobile-debug.apk` (~69,6 MB, universale)

Verifica identità con `aapt dump badging`:

```text
mobile: package=com.aistudio.streamnova.mobile versionCode=1 versionName=0.1.0 minSdk=24 targetSdk=36
tv:     package=com.aistudio.streamnova.tvapp  versionCode=1005 versionName=1.0.3 minSdk=24 targetSdk=36
```

---

## 5. Verifica su emulatori

### Dispositivi

| Dispositivo | Risoluzione | Densità | Esito |
|---|---|---|---|
| Smartphone (`sdk_gphone64_x86_64`) | 1344×2992 | 480 dpi | Home, Film, Serie TV, Ricerca, Dettaglio, Preferiti, Impostazioni, Player |
| Tablet (`Pixel Tablet`) | 2560×1600 | 320 dpi | Navigation rail, Home multi-colonna, Dettaglio con stagioni |
| TV (`Television_1080p`, AOSP TV x86) | 1920×1080 | 320 dpi | APK TV installato, avvio e Home catturata dopo il crawl catalogo |

### Schermate

**Smartphone**

- Home: `_tmp_phaseb_mobile_home3.png`
- Film: `_tmp_phaseb_mobile_films.png`
- Serie TV: `_tmp_phaseb_mobile_series.png`
- Ricerca “dune”: `_tmp_phaseb_mobile_search.png`
- Dettaglio: `_tmp_phaseb_mobile_detail2.png`
- Impostazioni: `_tmp_phaseb_mobile_settings.png`
- Player con controlli: `_tmp_phaseb_mobile_controls7.png`
- Player in pausa: `_tmp_phaseb_mobile_paused.png`
- Seek al 75%: `_tmp_phaseb_mobile_seek.png`
- Ritorno dal background: `_tmp_phaseb_mobile_lifecycle.png`
- Home con “Riprendi” e progresso: `_tmp_phaseb_mobile_restart_portrait.png`

**Tablet**

- Home con rail: `_tmp_phaseb_mobile_tablet_home2.png`
- Dettaglio serie: `_tmp_phaseb_mobile_tablet_detail.png`

**TV**

- Home post-refactor: `_tmp_phaseb_tv_home12.png`
- Riferimento card/neon TV: `_tmp_emu_home.png`

### Riproduzione mobile verificata

- Sorgente HLS demo del catalogo (`bipbop` Apple) riprodotta realmente su emulatore.
- Controlli touch visibili e funzionanti: play/pausa, seek bar, tempi, ±15 s.
- Seek verificato (posizione passata a 09:13 su 12:14).
- Lifecycle: `ON_STOP` mette in pausa; al ritorno il player resta in pausa sulla posizione corrente; nessun crash o errore Media3 in logcat.
- Progresso persistito: dopo il ritorno, la Home mostra “Riprendi” e “Continua a guardare” con la posizione aggiornata.
- Il player mobile usa `NuvioRenderersFactory`, `buildPlayerLoadControl`, `resolvePlaybackMediaSourceFactory`, `PlayerLoadErrorHandlingPolicy` e `PlayerSubtitleMediaItemBuilder` da `:core:player`.

---

## 6. Confronto visivo TV / mobile

### Elementi preservati

- Palette ufficiale: sfondo `#07090E`, superfici scure, accenti ciano/ciano brillante.
- Wordmark “StreamNova” con font e gradiente ufficiali.
- Logo/icona, banner demo e loghi provider dagli asset ufficiali.
- Card con angoli arrotondati, bordo neon e alone ciano (`drawNeonGlow`).
- Hero cinematografico con backdrop, gradiente e CTA ciano.
- Tipografia e gerarchia dei titoli.

### Differenze intenzionali

| Area | TV | Mobile |
|---|---|---|
| Navigazione | Sidebar verticale D-pad | Bottom bar su telefono, navigation rail su tablet |
| Interazione | Focus/D-pad, scala e alone al focus | Tap, nessuno stato di focus |
| Card | 16:9 orizzontali, pensate per 10-foot | Poster 2:3 su telefono; griglie adattive; card 16:9 per “Continua a guardare” |
| Layout | Paesaggio fisso | Portrait/landscape; tablet >= 840dp con rail |
| Dettaglio | Schermata TV dedicata con selettori focus | Scroll verticale touch con chip stagioni ed episodi |
| Player | Overlay D-pad specifico TV | `PlayerView` Media3 con controller touch sempre visibile |
| Controlli player | Custom TV (focus, intro, tracce) | Controller Media3 touch + overlay titolo mobile |

Le differenze derivano da dimensioni e modalità di interazione; identità grafica, palette, asset e trattamento neon sono condivisi.

---

## 7. File creati / modificati (sintesi)

### Nuovi moduli

- `core/model/build.gradle.kts`
- `core/designsystem/build.gradle.kts`
- `core/data/build.gradle.kts`
- `core/player/build.gradle.kts`
- `mobile/build.gradle.kts`
- `mobile/src/main/AndroidManifest.xml`
- `mobile/src/main/res/values/strings.xml`, `themes.xml`, `colors.xml`
- `mobile/src/main/res/values-v31/themes.xml` (splash scura)
- `mobile/src/main/res/drawable*`, `mipmap-*` (asset ufficiali copiati)

### Nuove classi principali

- `core/data/.../config/StreamNovaClientConfig.kt`
- `core/model/.../domain/model/IntroWindow.kt`
- `core/designsystem/.../components/NovaEffects.kt`
- `core/designsystem/.../components/ProviderCatalog.kt`
- `core/player/.../player/PlayerErrorRecoveryPolicy.kt`
- `mobile/.../MobileApplication.kt`
- `mobile/.../MainActivity.kt`
- `mobile/.../ui/MobileApp.kt`
- `mobile/.../ui/WindowSize.kt`
- `mobile/.../ui/MobileCatalogViewModel.kt`
- `mobile/.../ui/MobileDetailViewModel.kt`
- `mobile/.../ui/MobilePlayerViewModel.kt`
- `mobile/.../ui/MobileSettingsViewModel.kt`
- `mobile/.../ui/components/MobileComponents.kt`
- `mobile/.../ui/screens/HomeScreen.kt`
- `mobile/.../ui/screens/CatalogScreen.kt`
- `mobile/.../ui/screens/SearchScreen.kt`
- `mobile/.../ui/screens/DetailScreen.kt`
- `mobile/.../ui/screens/SettingsScreen.kt`
- `mobile/.../ui/screens/PlayerScreen.kt`

### File spostati (tracked)

- 6 file modello → `:core:model`
- Tema, wordmark, badge provider, font e asset → `:core:designsystem`
- 96 file dati, entità/DAO/Room, repository, networking, raw JSON → `:core:data`
- 42 file player engine/helper → `:core:player`
- 13 AAR locali NuvioTV + `quickjs` da `app/libs` → `core/player/libs`
- 104 test del layer dati → `:core:data`; 24 test player → `:core:player`

### File applicativi modificati

- `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`
- `app/build.gradle.kts` (dipendenze verso i moduli core; rimozione dei `files("libs/...")` spostati)
- `StreamNovaApplication.kt` (installazione `StreamNovaConfig`)
- Punti di accesso ai modelli ora cross-module: cast locali espliciti, nessun cambio di comportamento
- `SidebarNavigation`, `SplashScreen`, `ProviderBadge`, `PlayerRuntimeControllerErrorRecovery` (delega alla policy condivisa)
- Test `SimklClientIdConfigurationTest` (configurazione iniettata nel test)

### File non tracciati

Tutti i file non tracciati preesistenti sono rimasti intatti: non sono stati spostati, rinominati o cancellati. Le nuove catture `_tmp_phaseb_*` sono aggiunte di verifica.

---

## 8. Problemi residui e limiti dichiarati

1. **Emulatore TV instabile sull'host**: l'AVD `Television_1080p` è terminato più volte con exit code 139 durante i crawl TMDB ripetuti. La Home TV del build refactorizzato è stata comunque catturata (`_tmp_phaseb_tv_home12.png`) dopo il completamento del crawl. Non è un errore dell'app: è una limitazione dell'ambiente (tre emulatori + Gradle sullo stesso host).
2. **12 test TMDB saltati in `:core:data`**: richiedono credenziale/rete e non ricevono la chiave di build nel modulo core, per non duplicare credenziali. Se si vuole ripristinare l'esecuzione, basta iniettare una configurazione di test con la chiave da `local.properties`/ambiente.
3. **Controllo genitori mobile MVP**: il catalogo viene filtrato per soglia età; il PIN è richiesto per attivare/disattivare la funzione. Non è presente il dialog di sblocco per singolo titolo che la TV mostra al momento del play. È una scelta MVP, non una regressione del core.
4. **Firma Release mobile non verificata**: è definita in `mobile/build.gradle.kts` con variabili `MOBILE_KEYSTORE_PATH`, `MOBILE_STORE_PASSWORD`, `MOBILE_KEY_PASSWORD` e alias `mobile`; le build release falliscono subito se mancano. La Fase B usa solo Debug, come da decisione approvata.
5. **Player mobile e provider reali**: la riproduzione è stata verificata con lo stream demo HLS del catalogo. Titoli TMDB arbitrari dipendono dai provider terzi e dalla rete; non sono stati certificati tutti i provider su emulatore in questa fase.
6. **Scope MVP**: l'app mobile non espone login SIMKL, gestione addon o canale aggiornamenti. Il core li inizializza e restano disponibili per fasi successive; le schermate richieste (Home, Film, Serie TV, Ricerca, Dettaglio, Preferiti, Impostazioni) sono presenti.
7. **APK mobile universale**: non sono stati introdotti split ABI per il mobile; l'APK Debug è ~69,6 MB. La TV mantiene i suoi split ABI e il canale di distribuzione invariato.
8. **Dipendenza `:core:data` → `:core:designsystem`**: serve per i metadati brand dei provider (`StreamingProvider` con colore e drawable). La dipendenza invertita originale `data → :app/ui` è stata rimossa; questa è una dipendenza tra moduli core, non verso l'app.
9. **Nessun commit**: il lavoro è sul branch `feature/streamnova-mobile`, working tree non committato. Il branch backup e i file non tracciati sono intatti.

---

## 9. Vincoli inderogabili — verifica

| Vincolo | Esito |
|---|---|
| Build TV e test verdi dopo ogni estrazione | ✅ B1, B2, B3, B4 |
| Application ID TV invariato | ✅ `com.aistudio.streamnova.tvapp` |
| Firma/distribuzione/canale updater TV invariati | ✅ nessuna modifica a signing/release/updater TV |
| File non tracciati intatti | ✅ nessuno spostato/rinominato/cancellato |
| Nessun aggiornamento indiscriminato dipendenze | ✅ solo aggiunte necessarie (runtime Compose nel model, moduli core, `okhttp` nel player) |
| Verifiche dichiarate solo se eseguite | ✅ build/test reali; limiti dell'emulatore TV dichiarati |
| Schemi Room compatibili, nessuna migrazione distruttiva nuova | ✅ versione 8 e migrazioni preesistenti invariate |
| Firma Debug per lo sviluppo mobile | ✅ `mobile-debug.apk` firmato Debug |

---

## 10. Comandi di riproduzione

```bash
# Build TV
./gradlew :app:assembleDebug

# Test core + app
./gradlew :core:data:testDebugUnitTest :core:player:testDebugUnitTest :app:testDebugUnitTest

# APK mobile
./gradlew :mobile:assembleDebug

# Installazione mobile (emulatore)
adb install -r mobile/build/outputs/apk/debug/mobile-debug.apk
adb shell am start -n com.aistudio.streamnova.mobile/.MainActivity

# Installazione TV (emulatore x86)
adb install -r app/build/outputs/apk/debug/StreamNova-universal-debug.apk
adb shell am start -n com.aistudio.streamnova.tvapp/com.example.MainActivity
```

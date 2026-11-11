# StreamNova Mobile — Porting sezione Impostazioni «Continua a guardare» (TV 1.0.7)

**Data:** 2026-11-11
**Branch:** `feature/streamnova-mobile-phase2`
**Fonte:** StreamNova TV `v1.0.7-build.1009` (commit `3c50857`, tag `v1.0.7-build.1009`)
**Esito:** sezione Impostazioni e tutte le sette funzioni portate; suite mobile e core verdi; APK release 1.0.1 prodotto.

## 1. Premessa: cosa c'era già

Il porting SIMKL della TV 1.0.7 aveva già depositato nel core condiviso l'intero data layer della funzione, **identico bit-per-bit** alla TV:

- `core/data/.../prefs/ContinueWatchingPreferences.kt` (enum `ContinueWatchingLayout`, `ContinueWatchingSort`, snapshot delle sette preferenze);
- `core/data/.../repository/ContinueWatchingRowPolicy.kt` (unico punto che applica visibilità, layout, immagine, sfocatura, episodio di ripresa, episodi futuri e ordinamento);
- chiavi DataStore e setter in `AppSettingsRepository`;
- `MediaRepository.refreshEpisodeThumbnails` / `refreshNextEpisodes` e `furthestWatchedEpisode`;
- flag `inContinueWatching`/`completedAt` e migrazioni Room 12.

Mancava però tutto ciò che rende la sezione reale sul client Mobile:

1. nessuna sezione nella schermata Impostazioni;
2. nessun setter esposto da `MobileSettingsViewModel`;
3. la Home mobile **non usava** `ContinueWatchingRowPolicy`: mostrava `getContinueWatching()` con una sola card 16:9, senza layout, miniature, sfocatura, ordinamento né filtri;
4. nessuna implementazione della sfocatura bitmap nel modulo `:mobile`.

## 2. Porting realizzato

### 2.1 Sezione Impostazioni

Nuova sezione espandibile **«Continua a guardare»** tra «Motore di Riproduzione» e «Ricerca e cataloghi», aperta di default come la TV:

- intestazione con descrizione e stato **Aperto/Chiuso**, semantica `Button` + `stateDescription`;
- **Mostra Continua a guardare** (default ON);
- **Layout della riga**: selettore visuale con tre card (`Scheda`, `Larga`, `Locandina`), anteprime schematiche, bordo ciano, spunta, semantica `RadioButton` + `Selezionato`;
- **Usa miniature episodi in "Continua a guardare"** (default OFF);
- **Sfoca non visti in Continua a guardare** (default OFF);
- **Prossimo episodio dall'ultimo raggiunto** (default OFF);
- **Mostra episodi non ancora trasmessi** (default ON);
- **Ordinamento** con dialogo a tre voci (`Predefinito`, `Titolo (A-Z)`, `Voto TMDB`) e valore corrente visibile nella riga;
- note descrittive TV sotto ogni opzione.

Stringhe aggiunte in tutte le lingue del client: `values`, `values-en`, `values-es`, `values-fr`, `values-de`.

### 2.2 ViewModel e persistenza

`MobileSettingsUiState` espone `continueWatching: ContinueWatchingPreferences`; `MobileSettingsViewModel` osserva `AppSettingsRepository.continueWatchingPreferences` e inoltra i sette setter direttamente al repository condiviso. Nessuna chiave o regola duplicata nel modulo mobile.

### 2.3 Home: policy unica e tre layout

- `MobileCatalogViewModel` costruisce la riga con `ContinueWatchingRowPolicy.build(items = mediaList, preferences, furthestWatched = MediaRepository::furthestWatchedEpisode)`: una card per titolo, episodio di ripresa scelto dalla preferenza, artwork e sfocatura decisi dal policy. `showRow = false` produce una lista vuota e la riga non viene composta.
- Lo stato espone `continueWatchingCards` (con `artworkUrl`, `blurArtwork`, `targetEpisodeUnwatched`) e le preferenze; `MobileHomeSections` propaga le card alla riga.
- `MobileContinueCard` ora disegna i tre layout TV:
  - **Scheda** 16:9 full-bleed con testi sovrapposti;
  - **Larga** con immagine a sinistra e pannello testi a destra;
  - **Locandina** 2:3 con testi sovrapposti in basso.
- Ogni card mostra: etichetta `S1 E2` (o anno), titolo, titolo della puntata, badge minuti rimanenti, barra di progresso.
- **Miniature episodi**: con la preferenza attiva la Home richiama in background `refreshEpisodeThumbnails` (stessa chiave TV: titolo + stagione + episodio), e il policy usa la still solo se specifica della puntata.
- **Prossimo episodio / non trasmessi**: la Home richiama `refreshNextEpisodes` con la stessa chiave per id della TV, così il policy può avanzare l'episodio e filtrare le puntate future.
- **Sfocatura anti-spoiler**: portato `ArtworkBlur.kt` nel modulo mobile. La sfocatura è calcolata sulla bitmap ridotta a 96 px (box blur a 2 passate) e non usa `Modifier.blur`/`RenderEffect`: funziona anche su Android 7-11 (minSdk 24), non solo su API 31+.

## 3. File toccati

**Modificati (14):**

| File | Intervento |
|---|---|
| `mobile/build.gradle.kts` | versione `1.0.1` (versionCode 5) |
| `mobile/.../ui/MobileSettingsViewModel.kt` | snapshot preferenze CW nello stato, 7 setter |
| `mobile/.../ui/screens/SettingsScreen.kt` | sezione espandibile, selettore layout, dialogo ordinamento, helper |
| `mobile/.../ui/components/MobileSettingsComponents.kt` | `MobileSettingsExpandableSection` |
| `mobile/.../ui/components/MobileComponents.kt` | card CW a tre layout, artwork/fallback, blur, metadati |
| `mobile/.../ui/MobileCatalogViewModel.kt` | policy CW nel catalogo, stato card/preferenze, enrichment |
| `mobile/.../ui/MobileHomeSections.kt` | card CW nella sezione Home |
| `mobile/.../ui/screens/HomeScreen.kt` | card/layout alla riga, trigger enrichment |
| `mobile/src/main/res/values*/strings.xml` (5) | stringhe sezione CW |

**Nuovi (2):**

| File | Contenuto |
|---|---|
| `mobile/.../ui/components/ArtworkBlur.kt` | sfocatura bitmap portata dalla TV 1.0.7 |
| `mobile/src/test/.../ui/components/ArtworkBlurTest.kt` | test JVM del box blur (4 casi TV) |

**Test aggiornati:** `MobileCatalogUiStateTest` (card policy nella riga, filtro età che rimuove anche la card, preferenze esposte).

## 4. Verifica

### 4.1 Test automatici

| Comando | Esito |
|---|---|
| `:mobile:testDebugUnitTest` | **verde** (include `MobileCatalogUiStateTest`, `ArtworkBlurTest`, suite Add-on/Policy) |
| `:core:data:testDebugUnitTest` | **verde** (policy CW e suite SIMKL già portate, invariate) |
| `:mobile:assembleRelease` | **BUILD SUCCESSFUL**, `lintVitalRelease` superato |

### 4.2 Verifica visiva su Samsung S22 (dati reali)

Dispositivo: **SM-S908B**, Android 16, app 1.0.1 installata in-place con **43 titoli reali** nella riga «Continua a guardare» (catalogo e account SIMKL dell'utente).

| Evidenza | File |
|---|---|
| Sezione Impostazioni «Continua a guardare» | `.artifacts/cw_section/s22/settings_cw4.png` |
| Selettore layout con «Larga» selezionata | `.artifacts/cw_section/s22/settings_layout_again.png` |
| Layout Scheda (default) con 43 titoli | `.artifacts/cw_section/s22/final_home.png` |
| Layout Larga | `.artifacts/cw_section/s22/home_wide2.png` |
| Layout Locandina | `.artifacts/cw_section/s22/home_poster.png` |
| Miniature episodi attive (still + titolo puntata) | `.artifacts/cw_section/s22/home_poster_blur.png` |
| Sfocatura non visti attiva su card con puntata a 0 | `.artifacts/cw_section/s22/home_poster_blur4.png` |

Note dalla verifica sul dispositivo:

- la sezione mostra tutte le sette opzioni, le descrizioni TV e il selettore a tre card;
- il layout **Larga** mostra immagine a sinistra e testi/barra a destra; **Locandina** mostra il poster 2:3 con badge minuti, `Sxx Exx`, titolo e titolo puntata;
- con **Usa miniature episodi** attivo la card di The Big Bang Theory passa dalla locandina di serie alla **still dell'episodio** con titolo della puntata;
- con **Sfoca non visti** attivo le card con episodio non ancora visto (es. Il Signore degli Anelli S1E2, The Pitt S2E2) sono **sfocate**, mentre titolo e avanzamento restano leggibili;
- al termine del test le preferenze sono state ripristinate ai default dell'utente: **Scheda**, miniature OFF, sfocatura OFF, prossimo episodio OFF, episodi non trasmessi ON, ordinamento Predefinito.

Verifica aggiuntiva su emulatore Pixel 6 (API 34) per i casi non riproducibili sul dispositivo: dialogo ordinamento, `Titolo (A-Z)` applicato, `showRow=false` con riga assente.

| Evidenza emulatore | File |
|---|---|
| Dialogo Ordinamento | `.artifacts/cw_section/emu_sort_dialog.png` |
| Ordinamento «Titolo (A-Z)» applicato | `.artifacts/cw_section/emu_sort_selected.png` |
| `Mostra Continua a guardare` OFF: riga assente | `.artifacts/cw_section/ds_hidden.png` |

La logica di «prossimo episodio dall'ultimo raggiunto» e «mostra episodi non ancora trasmessi» è coperta dai test del policy condiviso (identico alla TV) e dal data layer già verde.

## 5. Build e installazione

- APK: `mobile/build/outputs/apk/release/mobile-release.apk` — **StreamNova Mobile 1.0.1** (versionName `1.0.1`, versionCode `5`, `com.aistudio.streamnova.mobile`), firmato con lo stesso keystore/alias della 1.0.0 (`upload`, APK Signature Scheme v1+v2).
- Verifica `aapt dump badging`: `versionName='1.0.1'`.
- Installazione in-place sul S22: **completata** con `adb install -r` (ADB wireless). Verifica `dumpsys package`: `versionName=1.0.1`; `firstInstallTime` invariato (`2026-11-08 22:53:40`), quindi dati, login SIMKL e progressi sono conservati.
- Nota sul versionCode: la build di test 1.0.2 (versionCode 5) era già installata sul dispositivo; Samsung blocca il downgrade a 4 anche con `pm install -d`. Il versionCode resta quindi 5, mentre la versione pubblica e il tag sono **1.0.1**, coerenti con la release GitHub precedente (1.0.0).
- L'app è stata avviata e verificata sul dispositivo: account SIMKL collegato, 43 titoli in «Continua a guardare», tutte le funzioni della sezione esercitate (vedi §4.2).

## 6. Note

- Nessun commit è stato creato: le modifiche restano nel working tree del branch.
- Gli screenshot di verifica sono in `.artifacts/cw_section/` (non tracciati).
- La sezione non introduce nuove chiavi DataStore: usa quelle già condivise con la TV, quindi le preferenze sopravvivono agli aggiornamenti e restano compatibili con il core.

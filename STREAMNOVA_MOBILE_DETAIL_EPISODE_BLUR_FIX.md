# StreamNova Mobile — Sfocatura miniature episodi nelle schede delle serie TV

**Data:** 11/11/2026
**Branch:** `feature/streamnova-mobile-phase2`
**Versione:** **1.0.1 (versionCode 5) invariata** — correzione della build pubblicata, non un aggiornamento
**Esito:** corretto, verificato su Samsung S22 (SM-S908B) e pubblicato sul canale GitHub Mobile

---

## 1. Problema segnalato

La sfocatura anti-spoiler delle anteprime funzionava sulle card di **Continua a guardare**, ma non sulle miniature degli episodi nella **scheda dettaglio delle serie TV**, anche con la preferenza attiva.

## 2. Causa

La preferenza **«Sfoca episodi non visti»** era letta soltanto dal policy della Home:

- `ContinueWatchingRowPolicy` decide `blurArtwork` per la riga «Continua a guardare»;
- la scheda dettaglio componeva la miniatura episodio in `EpisodeThumbnail`
  (`mobile/.../ui/components/MobileDetailComponents.kt`) con un semplice `AsyncImage`;
- `DetailScreen` non leggeva `AppSettingsRepository.continueWatchingPreferences` e
  `MobileEpisodeCard` non aveva alcun parametro di sfocatura.

Non era quindi un difetto di resa grafica: il collegamento tra preferenza e scheda dettaglio
era **assente**. Inoltre l'etichetta parlava solo di «Continua a guardare», mentre lo scopo
della preferenza è proteggere dalle anticipazioni in tutte le liste di episodi.

## 3. Correzione

### 3.1 Regola pura e testabile

In `MobileDetailPolicy` è stata aggiunta `shouldBlurEpisodeThumbnail(episode, blurUnwatched, isSimklWatched)`:

| Stato episodio | Miniatura |
|---|---|
| Preferenza OFF | nitida |
| Non visto (progresso 0, non concluso, non visto su SIMKL) | **sfocata** |
| In corso (progresso > 0 e non concluso) | nitida |
| Concluso (≥ 90% della durata) | nitida |
| Marcato visto su SIMKL | nitida |

La puntata in corso resta nitida di proposito: nasconderla confonderebbe il punto di ripresa.
Lo stato SIMKL ha la precedenza perché può marcare una puntata come vista prima che il
progresso locale sia stato scritto.

### 3.2 Wiring UI

- `DetailScreen` raccoglie `AppSettingsRepository.continueWatchingPreferences` con
  `collectAsStateWithLifecycle`: la modifica della preferenza si riflette subito, senza riavvio.
- `MobileEpisodeCard` e `EpisodeThumbnail` ricevono `blurThumbnail` e usano
  `SpoilerBlurredImage`, la stessa sfocatura bitmap (riduzione a 96 px + box blur a due passate)
  della riga «Continua a guardare». Funziona da Android 7 (minSdk 24), non richiede Android 12
  e non usa `Modifier.blur`/`RenderEffect`.
- Etichetta e descrizione aggiornate in **italiano, inglese, tedesco, spagnolo e francese** per
  indicare che la sfocatura vale sia in Continua a guardare sia nelle schede delle serie TV.

### 3.3 File modificati

| File | Modifica |
|---|---|
| `mobile/.../ui/MobileDetailPolicy.kt` | nuova regola `shouldBlurEpisodeThumbnail` |
| `mobile/.../ui/components/MobileDetailComponents.kt` | `blurThumbnail` su card e miniatura, `SpoilerBlurredImage` |
| `mobile/.../ui/screens/DetailScreen.kt` | lettura preferenza e passaggio del flag per episodio |
| `mobile/src/main/res/values*/strings.xml` | etichetta e descrizione aggiornate (5 lingue) |
| `mobile/src/test/.../MobileDetailPolicyTest.kt` | 4 test sulla regola (preferenza, in corso/concluso, SIMKL, episodio assente) |

Nessuna modifica a `:app` (TV), al core condiviso, al versionCode o al versionName.

## 4. Verifica

### 4.1 Test automatici

| Comando | Esito |
|---|---|
| `:mobile:testDebugUnitTest` | **245 test, 0 fallimenti, 0 errori, 0 saltati** |
| `:mobile:assembleRelease` | **BUILD SUCCESSFUL**, `lintVitalRelease` superato |

### 4.2 Test su Samsung S22 (dati reali)

Dispositivo: **SM-S908B**, Android 16 (API 36), ADB wireless via mDNS.
APK installato: release firmata, `versionCode=5`, `versionName=1.0.1`.

| Caso | Evidenza | Esito |
|---|---|---|
| Fallout S1: E01–E03 mai viste con preferenza ON | `.artifacts/detail_blur_s22/db_04_episodes.png` | miniature **sfocate**, titoli/icone nitidi |
| The Big Bang Theory S1: E01–E03 viste su SIMKL | `.artifacts/detail_blur_s22/db_06_tbbt.png` | miniature **nitide** |
| TBBT S1 nella stessa lista: E06/E07 viste, E08 non vista | `.artifacts/detail_blur_s22/db_07_tbbt_mid.png` | E06/E07 **nitide**, E08 **sfocata** |
| Preferenza OFF sulla stessa lista | `.artifacts/detail_blur_s22/db_09_off_episodes.png` | E08–E11 **nitide** |
| Preferenza OFF su Continua a guardare | `.artifacts/detail_blur_s22/db_08_blur_off.png` | card **nitida** |
| Etichetta aggiornata e switch ripristinato ON | `.artifacts/detail_blur_s22/db_10_settings_on.png` | «Sfoca episodi non visti» + descrizione estesa |
| Home finale | `.artifacts/detail_blur_s22/db_11_final_home.png` | app funzionante, dati conservati |

La lista TBBT con E06/E07 nitide ed E08 sfocata nella stessa schermata è la prova che la
regola è per singolo episodio e non per titolo o per schermata.

### 4.3 Installazione: sostituzione, non aggiornamento

- `firstInstallTime` **invariato**: `2026-11-11 10:18:29`;
- `lastUpdateTime` aggiornato all'installazione: `2026-11-11 14:00:05`;
- stesso `versionCode=5`, stesso `versionName=1.0.1`, stessa firma
  (`CN=Fabio Lo Reto`, SHA-256 `c97954610ba03cd4d392c1c2278c6e52f90b55d3cd631af1ff897c6fcc3f25e0`);
- dati, login SIMKL, progressi e preferenze conservati.

La preferenza è stata ripristinata allo stato iniziale trovato sul dispositivo: **ON**.

## 5. Build e pubblicazione

| | |
|---|---|
| APK | `mobile/build/outputs/apk/release/mobile-release.apk` |
| Dimensione | 59.085.523 byte |
| SHA-256 | `a5d879c6fa3888b9ca7b769a86d0ded44649f758a81ca45b58b8f85c4572336d` |
| Versione | `1.0.1` (versionCode `5`) |
| Firma | certificato ufficiale StreamNova, APK Signature Scheme v1+v2 |

- Asset GitHub **sostituito** sulla release `v1.0.1` del canale
  `miciotto-web/StreamNova-Mobile`, mantenendo lo stesso nome
  `StreamNova-Mobile-v1.0.1-universal.apk`.
- Digest dell'asset dopo la sostituzione:
  `sha256:a5d879c6fa3888b9ca7b769a86d0ded44649f758a81ca45b58b8f85c4572336d`.
- Note della release aggiornate con la sezione «Correzione build 1.0.1» e il nuovo SHA-256.
- **Nessun aumento di versione**: resta la 1.0.1 (build 5), così l'app installata viene
  sostituita e l'updater in-app non la propone come aggiornamento.

## 6. Note

- La correzione è confinata al modulo `:mobile`; la TV non è stata modificata.
- Nessun nuovo asset o dipendenza: riuso di `SpoilerBlurredImage`/`boxBlurArgb`.
- Screenshot e dump UI di verifica in `.artifacts/detail_blur_s22/` (non tracciati).

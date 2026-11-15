# Rapporto di fine lavoro — Ritorno automatico alla scheda Dettaglio a fine riproduzione

**Data:** 15 novembre 2026
**Branch:** `integration/streamnova-1.0.3-w1-w12`
**Build verificata:** release firmata, `versionCode 1100`, `versionName 1.1.0`
**Dispositivi di test:** emulatore Android TV AOSP (x86, API 30) + Fire TV Stick reale (AFTMA08C15, Fire OS / Android 11, adb di rete 192.168.1.2)
**Commit/push:** NON eseguiti su richiesta.

---

## 1. Difetto segnalato

Quando il player arriva alla fine della riproduzione si ferma e resta inattivo sull'ultimo
fotogramma finché l'utente non preme BACK sul telecomando per tornare alla scheda dettaglio.

Comportamento richiesto:

1. **Film**: a fine riproduzione il player deve tornare automaticamente alla scheda
   Dettaglio del film appena riprodotto.
2. **Serie TV**: se la riproduzione NON continua verso l'episodio successivo e si ferma a
   fine episodio, il player deve tornare automaticamente alla scheda Dettaglio della serie
   in riproduzione.

---

## 2. Analisi

In `PlayerScreen.kt` il listener di ExoPlayer (`onPlaybackStateChanged`) a `STATE_ENDED`
eseguiva soltanto:

- `currentSimklScrobbler.onEnded()` (scrobble);
- `playbackEnded = true`, usato dall'overlay "Prossimo Episodio".

Nessun percorso chiudeva il player: senza episodio successivo (film, ultima puntata) o con
l'overlay non idoneo (autoplay OFF, overlay chiuso) lo schermo restava fermo sul video
concluso, con sola via d'uscita il BACK.

La chiusura corretta esisteva già: è il ramo `CLOSE_PLAYER` del BACK
(`resetStreamState()` + `viewModel.closePlayer()`). `closePlayer()` riporta già al
Dettaglio quando la riproduzione è partita dalla scheda (origine DETAIL) e, per il resume
da "Continua a guardare", differisce la chiusura e apre proprio il Dettaglio del contenuto
appena riprodotto (`deferCloseForDetailReturn`/`completePlayerClose`). Il ritorno automatico
poteva quindi percorrere la stessa strada, senza nuove logiche di navigazione.

---

## 3. Correzione applicata

Nuovo effetto in `PlayerScreen.kt` (subito dopo l'effetto del countdown "Prossimo
Episodio"), chiavi `playbackEnded / isNextEpisodePromptVisible /
isNextEpisodeUnavailableVisible / isAutoplayBlocked`:

- a `STATE_ENDED` **senza avanzamento in corso**, dopo una finestra di tolleranza di
  1 500 ms (`PLAYER_END_AUTO_RETURN_GRACE_MS`) esegue `viewModel.resetStreamState()` +
  `viewModel.closePlayer()` — identico al BACK;
- alla scadenza della tolleranza **ri-verifica a stato vivo** (letture dei delegate Compose,
  non degli snapshot catturati), così il rientro non può scattare quando, nel frattempo,
  è partita una delle transizioni seguenti.

Guardie (all'ingresso e alla ri-verifica):

| Guardia | Motivo |
|---|---|
| `nextEpisodeCountdownSeconds > 0` | countdown "Prossimo Episodio" attivo: porterà al prossimo episodio senza uscire dal player |
| `isSwitchingEpisode` | transizione di episodio già avviata |
| `isAutoplayBlocked` | ricerca sorgente autoplay in corso |
| `isNextEpisodeUnavailableVisible` | card "Nessuna fonte disponibile" a video: l'utente ha l'azione manuale "Seleziona fonte" davanti |
| `playbackFailed` | sessione fallita: resta in carico la gestione errore esistente (Riprova / Sorgente video) |
| `isScrubbing \|\| isSeekSettling` | anteprima di scrub/seek aperta: la chiusura non deve abbatterla |

Il falso `ENDED` da stream vuoto/rotto resta escluso perché `playbackEnded` diventa vero
solo con posizione e durata valide (`> 0`), condizione già presente sull'handler.

Copertura dei casi:

| Scenario | Comportamento |
|---|---|
| Film a fine video | rientro automatico nel Dettaglio del film |
| Serie, prossimo episodio disponibile, autoplay ON | countdown → avanzamento automatico (invariato) |
| Serie, ultimo episodio (nessun next) | rientro automatico nel Dettaglio della serie |
| Serie, autoplay OFF (o overlay chiuso con BACK) | a fine video rientro automatico nel Dettaglio della serie |

---

## 4. File modificati

| File | Modifica |
|---|---|
| `app/src/main/java/com/example/ui/screens/PlayerScreen.kt` | +57 righe: costante `PLAYER_END_AUTO_RETURN_GRACE_MS` e `LaunchedEffect` di rientro automatico a fine riproduzione |
| `tools/Build-ReleaseSignedDpapi.ps1` | nuovo: build release firmata usando le credenziali DPAPI già preparate da `Import-ReleaseKey.ps1` (password mai mostrate né salvate), con verifica `apksigner` del certificato ufficiale |

---

## 5. Build e installazione su Fire TV reale

- La release è stata compilata con `assembleRelease` firmando con il keystore ufficiale
  `streamnova-release-v2.p12` (alias `upload`); `apksigner` conferma su tutti e 5 gli APK
  il certificato ufficiale SHA-256 `3175a7df6ea50a1be756c4a0b74c2623757128b620d28f40e678eed715fd2cd7`.
- Installazione su Fire TV Stick (modello `AFTMA08C15`, ABI `armeabi-v7a`, Fire OS Android
  11) via `adb connect 192.168.1.2:5555` + `adb install -r`:
  - firma identica alla versione già installata → upgrade accettato;
  - `versionCode 1100` invariato, `firstInstallTime` 11-11 **invariato**,
    `lastUpdateTime` aggiornato: **nessuna perdita dati** (profilo, preferiti e
    "Continua a guardare" conservati).

---

## 6. Test

### 6.1 Emulatore

Percorso provato fino a fine film (stream reale risolta dal provider HTTP, ~2h49m):
riproduzione, avanzamento a tasti MEDIA_FAST_FORWARD (10 s a pressione, verificati in
logcat `SeekSync`), avvicinamento alla fine. Il CDN della sorgente è diventato
irregolare sugli ultimi segmenti (stall + "Source error") e la sessione è stata
interrotta: dall'emulatore non è arrivata una verifica conclusiva.

### 6.2 Fire TV reale — test manuale (esito utente)

Installata la build firmata sopra, test manuale con il telecomando su tutti gli scenari:

| Test | Esito |
|---|---|
| Film a fine riproduzione → ritorno automatico al Dettaglio del film | ✅ |
| Serie con episodio successivo (autoplay ON) → overlay + countdown + avanzamento automatico | ✅ |
| Serie, ultimo episodio → ritorno automatico al Dettaglio della serie | ✅ |
| Serie con autoplay OFF, nessun "Guarda ora" premuto → ritorno automatico al Dettaglio della serie | ✅ |

**Esito complessivo: test verde passato al 100%** (riscontro diretto dell'utente sulla TV
reale). La build release non emette i log applicativi (strippati da R8): la verifica è
visiva sul comportamento.

---

## 7. Note e limiti

- Il rientro usa la stessa strada del BACK: con origine Dettaglio torna al Dettaglio; con
  origine "Continua a guardare" apre il Dettaglio del contenuto appena visto (chiusura
  differita esistente); con altre origini (es. sezione provider) torna alla schermata di
  provenienza, come il BACK manuale.
- Se a fine flusso compare la card di errore ("Errore di Riproduzione" / "Nessuna fonte
  disponibile"), il rientro automatico è volutamente inibito: la sessione è in errore o
  l'utente ha un'azione manuale a video; BACK resta sempre operativo.
- La tolleranza di 1,5 s evita rientri anticipati mentre un countdown/transizione sta
  partendo; rende anche meno brusco il passaggio all'ultimo fotogramma.
- Nessun commit né push: le modifiche restano in working tree. Il nuovo script
  `tools/Build-ReleaseSignedDpapi.ps1` riusa le credenziali DPAPI già presenti sul
  computer (`%LOCALAPPDATA%\StreamNovaSigning\release-cred.dpapi.json`) e non introduce
  alcuna password in chiaro.

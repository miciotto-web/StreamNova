# Verifica P0/P1 su Fire TV fisica — scrubbing con anteprima (25-10-2026)

## 1. Esito

**ENTRAMBI I BUG VERIFICATI RISOLTI SU DISPOSITIVO FISICO.** Script strumentato a 6 fasi
(`SeekPreviewRemoteScriptTest#scrubbingRemoteScript`) eseguito su Fire TV Stick AFTMA08C15
(mt8696, API 30) via `am instrument`: **OK (1 test), 97,7 s**, PID app 14186 stabile per
tutta la sessione (nessuna morte di processo, nessun riavvio).

| Gate di regressione | Esito |
|---|---|
| SIGABRT / JNI DETECTED ERROR / Fatal signal / ANR | **0 / 0 / 0 / 0** |
| Buffer crash (`crash_buffer.txt`) | vuoto |
| ImageReader creata (`nativeCreatePlanes`, `ImageReader pronta`, `planes:`) | **0 righe** — percorso GL usato, come preservato |
| PID app durante il test | 14186 invariato dall'inizio alla fine |

## 2. Cause radice dei due bug (riepilogo)

- **Bug 1 (scatti / frame finale incoerente andando avanti):** il log `frame:` della versione
  precedente cantava "pubblicato=true" per ogni candidato decodificato, senza verificare che
  fosse *più vicino all'intento corrente* di ciò che era a video: un risultato tardivo di una
  pressione vecchia sostituiva a video un frame migliore, e il frame finale poteva non
  corrispondere alla posizione mostrata dallo slider.
- **Bug 2 (frame congelato andando a ritroso):** il gate di pubblicazione era
  `decodedPosition >= displayedPosition`, cioè "più recente" = "PTS più grande". In scorrimento
  inverso ogni frame legittimo ha PTS minore di quello a video, quindi veniva scartato sempre e
  l'anteprima restava inchiodata sull'immagine del punto di partenza.

## 3. File corretti e razionale

- `app/src/main/java/com/example/ui/screens/player/SeekPreviewSource.kt`
  - `shouldDisplay()` (riga 589): criterio sostituito con **distanza dall'intento corrente**
    (`|candidato − intento| < |a video − intento|`), simmetrica nei due versi; a parità di
    distanza vince la richiesta più recente (`seq`). Le protezioni contro i risultati obsoleti
    restano integre, espresse nella dimensione giusta (distanza dal target) invece che nel verso.
  - `rejectionReason()` (riga 607): **delega a `shouldDisplay`** — decisione e spiegazione non
    possono più divergere (era esattamente il modo in cui il log diceva "pubblicato=true" mentre
    il frame non corrispondeva alla posizione).
  - `publishFrame()` (riga 778): unico punto in cui un frame finisce a video; `approximate`
    dichiarato quando PTS non autorevole o scarto oltre la tolleranza (500 ms);
    `requestedPositionMs` conservato per l'onestà dell'UI.
  - `settleLatestIntent()` (riga 815): un frame comparso in cache dopo la richiesta viene
    adottato per l'intento corrente (chiude il caso Loading senza fine).
- `app/src/main/java/com/example/ui/screens/SeekPreview.kt` (righe 87-108): UI onesta —
  frame in attesa = attenuato con banda di caricamento; badge "≈" + bordo attenuato per frame
  approssimato; timestamp sempre il PTS reale decodificato, mai la posizione richiesta
  riscritta addosso a un'immagine diversa.
- `app/src/androidTest/java/com/example/SeekPreviewRemoteScriptTest.kt`: riscritto in 6 fasi
  (A.1–A.6) che riproducono i due bug end-to-end su hardware reale, con screenshot per fase.
- `p0fix_run.ps1`: driver di verifica (proprietà device, nessun override manuale, logcat +
  screenrecord in background, strumentazione, pull artifact, dump logcat e buffer crash).

## 4. Numeri dal run su dispositivo (logcat, PID 14186)

### Fasi A.1–A.3 — avanti, a ritroso, alternanza
| richiesto (verso) | PTS decodificato | scarto | latenza | hash |
|---|---|---|---|---|
| 65233 (indietro) | 65250 | 17 ms | 297 ms | `0000c083d7bfff00` |
| 45700 (indietro) | 45708 | 8 ms | 702 ms | `00006cffd9bfff00` |
| 57250 (avanti) | 57250 | 0 ms* | 299 ms | `005a4ea7e713bd00` |
| 67250 (avanti) | 67250 | 0 ms* | 492 ms | `0000a783c3999900` |
| 77250 (avanti) | 77250 | 0 ms* | 260 ms | `000033ffcbf1ff00` |
| 87250 (avanti, raffica) | 87250 | — | 3363 ms | `00fffd84d9c01000` |

(*) scarto 0 ms dall'intento al momento della cattura; le righe `frame:` con
`richiesto=57250 intento=67250` ecc. mostrano la **conflazione**: il frame apparteneva a una
pressione precedente ma era comunque più vicino all'intento attuale di ciò che era a video
(regola "vince il più vicino"), quindi pubblicato e marcato in UI (`render: ... stale=true`).

**Due–tre hash distinti in scorrimento inverso con PTS entro 20 ms dal richiesto**: il frame
a video cambia ANDANDO A RITROSO (prima restava congelato sul punto di partenza). Le righe
`render:` confermano che le bitmap sono arrivate davvero alla composizione dell'overlay.

### Fase A.4 — raffica 12× a ~250 ms e stop improvviso
- Richieste seq 11–23 confluite correttamente: dei 10 avanti rapidi, il worker ha completato
  solo l'ultimo decode in volo (87250 ms; 23 ricerche in 4 s impongono fetch di segmenti HLS,
  da qui i 3363 ms sotto carico contro i 260–700 ms a passo umano).
- Il frame tardivo 87250 è stato pubblicato perché **più vicino all'intento** di ciò che era
  a video, e l'UI l'ha marcato onestamente `stale=true` (attenuato + banda) col suo PTS reale:
  nessuna affermazione falsa sul punto finale.
- Il commit per silenzio (900 ms) ha chiuso la sessione sull'ultima posizione richiesta
  (177250 ms): il seek finale atterra dove vuole l'utente, non su un passo intermedio.

### Fasi A.5/A.6 — conferma OK e annullamento BACK
- OK: seek confermato, anteprima chiusa, riproduzione ripristinata (`s05_after_ok.png`).
- BACK: sessione chiusa senza seek (`s06_after_back.png`).

### Screenshot (hash percettivo 12×8, contenuto reale dello schermo)
12/12 hash distinti — tra cui i tre del backward `s02a/b/c` (luminanza media 95 → 76 → 66):
l'immagine visibile cambia col movimento in entrambi i versi.

## 5. Evidenza su dispositivo vs unit

- **Unit**: regole di accettazione PTS (gate estrattore) e gate di pubblicazione verificate in
  isolamento con decoder simulato; suite mirata verde (2 failure di baseline preesistenti,
  fuori dallo scope P0/P1).
- **Dispositivo**: le stesse regole reggono con decoder hardware `omx.video.avc` reale, HLS di
  rete e cadenza D-pad reale: PTS autorevoli entro 20 ms, latenze 260–700 ms a passo umano,
  3,4 s solo nella raffica patologica; zero crash nativi, zero ImageReader, PID stabile.

Il caso raffica resta l'unico con un frame "vecchio" a video: l'UI lo dichiara (attenuato +
banda + PTS reale) invece di fingere che sia quello del punto scelto — comportamento voluto e
verificato.

## 6. Questioni aperte

1. **Suite completa (964 test) mai girata interamente**: 2 failure di baseline preesistenti e
   test dipendenti dalla rete che si bloccano se eseguiti insieme; eseguite solo run mirati.
2. **Latenza sotto raffica**: con molte ricerche ravvicinate su HLS l'ultimo decode può superare
   1 s (misurato 3,4 s). L'UI è onesta, ma una prima immagine del punto finale può arrivare
   dopo il commit per silenzio; un prefetch reattivo al rilascio accorcerebbe il divario.
3. **Screenrecord `p0fix.mp4` non prodotto** (il processo screenrecord in background è morto
   prima del pull): non bloccante, le evidenze numeriche e i 12 screenshot coprono il caso.
4. **Vecchi screenshot del 24-10 non ripuliti** in `files/seekproof` prima del run: i nomi
   nuovi evitano ambiguità, ma vale la pena aggiungere la pulizia nel driver.

---
Artefatti: `p0fix_verify\` (logcat_full.txt, logcat_raw.txt, crash_buffer.txt, instrument.txt,
s00_player…s06_after_back.png), driver `p0fix_run.ps1`, analisi `p0fix_analyze.ps1` +
`p0fix_shothash.ps1`, questo report `p0fix_report.md`.

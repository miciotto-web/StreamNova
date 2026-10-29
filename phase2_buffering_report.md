# Fase 2 — Buffering continuo: diagnosi e correzioni (26-10-2026)

**Stato**: implementata e verificata sul PC (suite completa verde). Verifica su Fire TV **non
eseguita** (a carico dell'utente). Nessun commit, push, tag o release. Fase 1 intatta.

---

## 1. Causa (o cause) dimostrate

### C1 — Oscillazione delle rendition con riconfigurazione del decoder (causa principale)

**Meccanismo.** `AdaptiveTrackSelection` di Media3 rinvia un **aumento** di qualità finché il
buffer è più corto di `minDurationForQualityIncreaseMs`. Il port di NuvioTV
(`NuvioTrackSelector`) usava **2.000 ms** invece dei 10.000 ms di Media3: dopo ogni
svuotamento del buffer (downshift) bastavano 2 s per risalire. Su CDN lenti la selezione
ping-pongava: *up → svuotamento → down → up*.

**Perché si vede come “buffering continuo”.** Su questi flussi HLS ogni cambio di rendition è
**non adattivo**: il decoder video viene riconfigurato e, nei casi peggiori, rilasciato e
reinizializzato. Ogni cambio = micro-freeze + flush, quindi spinner e salti ripetuti anche
quando il video non si è mai fermato davvero.

**Evidenze**

| Evidenza | Dettaglio |
|---|---|
| Codice | `NuvioTrackSelector.kt`: `AdaptiveTrackSelection.Factory(2_000, …)`; Media3 stock = 10.000 (verificato nel bytecode del fork: `Factory()` → `10 000`, `25 000`, `25 000`, `0.7`) |
| Semantica verificata | `AdaptiveTrackSelection.updateSelectedTrack` → `minDurationForQualityIncreaseUs`; per un VOD è la costante nuda (nessun live edge) |
| Log Fire TV 25-10 (`p0fix_verify/logcat_full.txt`, PID 14186) | `DECODER VIDEO inputFormatChanged` ×8 in 97 s: 1080p → 720p → 1080p (rilascio decoder 09:22:28.9 + reinizializzazione 09:22:30.7 = ~1,7 s di fermo) → 720p → 480p → 1080p |
| Log Fire TV 24-10 (`hotfix_logcat_session.txt`, PID 6850) | Riproduzione **normale** (nessun test strumentato): 1080p → 720p → 480p in 30 s, con `MediaCodecLogger: Resolution change from 1920x1080 to 854x480 took = 5` |
| Diagnostica app | `CANDIDATE … support=FORMAT_HANDLED adaptive=SEAMLESS` ma il decoder reale viene riconfigurato: la promessa di adattività è solo nel selettore, non nel renderer |

### C2 — Il percorso HLS non riceveva la policy di errore dell’app

**Meccanismo.** `PlayerScreen` creava `HlsMediaSource.Factory(httpDataSourceFactory)` **senza**
`.setLoadErrorHandlingPolicy(...)`: la `PlayerLoadErrorHandlingPolicy` (scritta *proprio* per
l’HLS: esclusione della rendition su 404, sottotitoli mai fatali, scala di retry corta) era
applicata solo al percorso progressivo. Su HLS — la maggior parte delle sorgenti — restava la
policy di default di Media3 (3 tentativi con backoff) e poi l’errore fatale, che nell’app
significa ricostruzione del MediaItem + `prepare()` (ripartenza da zero del buffering) o
fallback di sorgente.

**Evidenze**: codice (`PlayerScreen.kt` righe 602-608 prima della correzione, unico
`setLoadErrorHandlingPolicy` su `mediaSourceFactory`, usato solo nel ramo non-HLS); commento
di intestazione della policy stessa (“handling HLS/DASH media segment 404 track exclusion”);
il ramo HLS è quello di default (`computeIsHlsStream` → `true` senza estensione).

### C3 — Overlay “Caricamento” senza isteresi (causa del sintomo *percepito*)

**Meccanismo.** L’overlay era la copia diretta di `state == STATE_BUFFERING`. ExoPlayer entra
in `STATE_BUFFERING` anche per transizioni brevissime che **non** fermano il video (cambio
rendition, istantanea di un seek). Con C1 attivo (7-8 cambi al minuto) lo spinner appariva e
spariva in continuazione: è il “buffering continuo” visto dall’utente. La funzione pensata per
sopprimerlo (`NuvioExoPlayerPerformanceHelper.shouldSuppressBufferingUi`) esisteva ma **non
era chiamata da nessuna parte**.

### C4 — “Buffer iniziale” era un’impostazione morta

**Meccanismo.** `InitialBufferOption` (Avvio Rapido 1,5 s / Standard 2,5 s / Stabile 3,5 s) è
esposta nella UI, persistita su DataStore e scrivibile dal ViewModel, ma **non arrivava mai al
player**: `buildLoadControl` usava le costanti hard-coded 2.500/5.000 ms. Chi vedeva il video
ripartire a singhiozzo poteva selezionare “Stabile” senza alcun effetto.

**Evidenze**: `grep` su tutte le occorrenze di `initialBuffer` → solo prefs/UI/ViewModel, zero
consumatori nel player; `BUFFER_FOR_PLAYBACK_MS` usato in due punti in
`NuvioExoPlayerPerformanceHelper`.

C1 + C3 spiegano il sintomo; C2 e C4 sono difetti dimostrati nella stessa area (retry/segmenti
e buffer iniziale) e chiudono due “leve” che l’utente non poteva usare.

---

## 2. File modificati

Modificati (main):

| File | Modifica |
|---|---|
| `app/src/main/java/com/example/ui/screens/player/NuvioTrackSelector.kt` | soglia di aumento qualità 2.000 → **10.000 ms** (stock Media3) + factory estratta e testabile |
| `app/src/main/java/com/example/ui/screens/player/NuvioExoPlayerPerformanceHelper.kt` | `buildLoadControl(…, bufferForPlaybackMsOverride)`; soglia di ripartenza = `max(5.000, override)`; log aggiornato |
| `app/src/main/java/com/example/ui/screens/PlayerScreen.kt` | una sola `PlayerLoadErrorHandlingPolicy` condivisa; LoadControl da `buildPlayerLoadControl(playbackSettings)`; `initialBuffer` tra le chiavi del player; overlay di caricamento con isteresi; “Stima banda” nell’overlay Stats for Nerds; import `HlsMediaSource` rimosso |
| `app/src/main/java/com/example/ui/screens/player/PlayerMediaSourceFactories.kt` **(nuovo)** | `resolvePlaybackMediaSourceFactory(...)`: il ramo HLS porta la policy dell’app |
| `app/src/main/java/com/example/ui/screens/player/PlayerLoadControlProvider.kt` **(nuovo)** | `buildPlayerLoadControl(settings)`: catena impostazioni → LoadControl |
| `app/src/main/java/com/example/ui/screens/player/PlayerBufferingOverlay.kt` **(nuovo)** | decisione dell’overlay con ritardo di rivelazione 600 ms |

Test nuovi:

| File | Test |
|---|---|
| `app/src/test/.../player/PlayerTrackAdaptationHysteresisTest.kt` | 7 |
| `app/src/test/.../player/PlayerInitialBufferSettingTest.kt` | 5 |
| `app/src/test/.../player/PlayerMediaSourceFactoriesTest.kt` | 2 |
| `app/src/test/.../player/PlayerBufferingOverlayTest.kt` | 5 |

Helper/log (non tracciati, come da convenzione del repo): `_tmp_test_filter_phase2.init.gradle`,
`_tmp_test_filter_phase2_withoutnew.init.gradle`, `_tmp_test_filter_phase2_redcheck.init.gradle`,
`phase2_*.log`.

---

## 3. Comportamento prima/dopo

| # | Prima | Dopo |
|---|---|---|
| C1 | Dopo un downshift bastavano 2 s di buffer per risalire di rendition → ping-pong con riconfigurazione del decoder (7-8 volte/minuto nei log) | La risalita richiede **10 s di buffer sano** (valore stock Media3): l’oscillazione si spegne. La selezione iniziale resta guidata dalla stima di banda → **la qualità massima (1080p/4K) resta raggiungibile**, solo non più “a rimbalzo” |
| C2 | Su HLS: sottotitoli esterni falliti / 404 di segmento / 403-404 di manifest gestiti dalla policy Media3 di default (3 retry + errore fatale) → ricarica completa o cambio sorgente non voluto | Su HLS valgono le regole dell’app: traccia sottotitoli scartata senza toccare il video, segmento 404 → esclusa **quella** rendition, 403/404 definitivi → fallback immediato all’altra sorgente, timeout con retry 750/1.500/3.000 ms |
| C3 | Lo spinner appariva a ogni `STATE_BUFFERING`, anche per transizioni di poche centinaia di ms | L’overlay appare solo se il caricamento dura ≥ 600 ms e sparisce subito alla ripresa; durante scrubbing e con errore visibile resta fuori schermo. Nessun errore viene nascosto (l’overlay di errore è separato) |
| C4 | “Buffer iniziale” senza effetto (sempre 2,5 s / ripartenza 5 s) | Avvio Rapido 1,5 s, Standard 2,5 s (default Media3 invariato), Stabile 3,5 s: il valore arriva al LoadControl e la ripartenza dopo stallo resta ≥ 5 s |
| Extra | Overlay Stats for Nerds senza stima di banda | Riga “Stima banda: X Mbps” (diagnostica opt-in): distingue “CDN/rete lento” da “il player cambia rendition a vuoto” |

Non modificati di proposito: buffer target/back buffer (valori Nuvio, nessuna evidenza di
difetto), timeout HTTP 15/20 s, `applyPreferredResolution` (vedi §6), sottotitoli sidecar
(§6), stima iniziale di banda 50 Mbps.

---

## 4. Test eseguiti e risultati esatti

Ambiente: PC Windows, Gradle 9.3.1 + AGP 9.1.1, JDK 17, Robolectric. Il filtro CLI `--tests`
è rotto in questo progetto → init script (l’unico modo per run mirati).

| Run | Comando | Esito |
|---|---|---|
| Classi nuove (mirato) | `.\gradlew.bat -I _tmp_test_filter_phase2.init.gradle :app:testDebugUnitTest --rerun` | **19 test, 0 failure, 0 skip** (7+5+2+5) |
| **Suite completa (albero congelato)** | `.\gradlew.bat :app:testDebugUnitTest --rerun` | **1340 test, 0 failure, 2 skip** — ripetuta 3 volte, identica |
| Baseline senza le classi nuove | `-I _tmp_test_filter_phase2_withoutnew.init.gradle` | 1321 test, 0 failure, 2 skip (= baseline Fase 1: nessuna regressione introdotta) |
| Classi di test nuove + vecchie in isolamento | `-I …_redcheck…` | 97 test, 0 failure |
| Compilazione APK | `.\gradlew.bat :app:assembleDebug` | BUILD SUCCESSFUL (5 split: arm64-v8a, armeabi-v7a, universal, x86, x86_64) |

I 2 skip sono i test di rete `@Ignore` di `NewProvidersTest` (preesistenti, non dichiarati
superati).

**Prova “rosso prima / verde dopo”** (le regressioni sono state reintrodotte a mano e poi
ripristinate):

| Fix rimosso temporaneamente | Test che diventano rossi |
|---|---|
| `setLoadErrorHandlingPolicy` sul ramo HLS | `PlayerMediaSourceFactoriesTest.ilRamoHlsUsaLaPolicyDiErroreDellApp` |
| `initialBuffer` non passato al LoadControl | `PlayerInitialBufferSettingTest.stabileRichiedeTreSecondiEMezzoPrimaDiPartire`, `…avvioRapidoPartePrimaMaNonSottoLaSuaSoglia` |
| Soglia vecchia (2.000 ms) | `PlayerTrackAdaptationHysteresisTest.ilValoreDiProduzioneEQuelloStockDiMedia3` (+ il test `conLaSogliaVecchiaDiNuvioIlBufferCortoFacevaRisalireSubito` **dimostra dentro la suite** che con 2 s la risalita prematura avveniva, e `conLaSogliaDiProduzioneIlBufferCortoNonAutorizzaLaRisalita` che con 10 s non avviene) |

I test guidano le classi reali di Media3 (`AdaptiveTrackSelection`, `DefaultLoadControl`),
non copie delle loro regole.

**Emulatore locale: NON eseguito.** Motivo documentato: (1) questa AVD va in SIGSEGV ogni pochi
minuti con `swiftshader_indirect` e con `angle_indirect` (storico di questo progetto);
(2) il difetto dipende da CDN/rete reale, che l’emulatore non riproduce in modo
significativo — un esito verde non sarebbe prova, un esito rosso non sarebbe diagnosi. La
verifica hardware è nella checklist per l’utente.

---

## 5. Problemi preesistenti o ambientali

1. **Robolectric: SDK misto.** Il primo run completo con le classi nuove ha prodotto **25
   failure** (`FileSystemAlreadyExistsException`, `UnsatisfiedLinkError … SQLiteConnection.nativeOpen`,
   cascate `UncaughtExceptionsBeforeTest`) in classi **non** toccate (Room/TMDB/SeekPreview/
   LiveChannel/…). Rieseguite in isolamento: **97/97 verdi**; esclusi i nuovi test: suite
   **1321/0/2 verde**. Causa ambientale preesistente (mix di classi `@Config(sdk=[34])` con
   classi a SDK di default), **non** una regressione. Mitigazione applicata: 3 delle 4 classi
   nuove sono JVM pure, ne resta 1 sola Robolectric → suite completa di nuovo verde.
   → Consiglio per una fase futura: uniformare `@Config(sdk = [34])` su tutte le classi
   AndroidJUnit4 (15 classi oggi senza).
2. **Due test di rete** `@Ignore` (preesistenti): non dichiarati superati.
3. **`shouldSuppressBufferingUi` e `applyNetworkOptimizations`** in
   `NuvioExoPlayerPerformanceHelper` sono codice morto (nessun chiamante): il primo è stato
   reso superfluo dalla nuova isteresi, il secondo (pool OkHttp condiviso, HTTP/2) resta non
   collegato. Nessuna evidenza che servano: non toccati.
4. **Carico non-player all’avvio** (non affrontato in Fase 2): nei log del device il refresh
   catalogo esegue 18 checkpoint (`MediaRepository`), ognuno legge l’intero catalogo da Room
   (~5.488 righe) e riscrive il delta, in ~75 s; e ogni titolo di dettaglio viene richiesto
   **23 volte** a TMDB. È I/O di rete/CPU, non riproduzione: se durante i test TV si apre il
   player subito dopo l’avvio, tenerlo presente come concorrenza (nessuna azione intrapresa,
   fuori perimetro).

---

## 6. Rischi residui (dichiarati, non nascosti)

1. **Il difetto di fondo può essere la sorgente.** Su una rendition 4.5 Mbps con una
   connessione che regge 2 Mbps il player *deve* scendere di qualità: le correzioni
   impediscono l’oscillazione, non aumentano la banda. La riga “Stima banda” dell’overlay
   Stats for Nerds serve a distinguere i due casi sul campo.
2. **`applyPreferredResolution` blocca l’adattività quando si sceglie una risoluzione**
   (`setMinVideoSize` = `setMaxVideoSize` + `setForceHighestSupportedBitrate(true)`, e per il
   4K `forceHighestSupportedBitrate` senza vincoli = sempre il bitrate più alto). È una scelta
   di prodotto (parità Nuvio, qualità richiesta dall’utente) e **non è stata toccata**: con
   “Risoluzione preferita” diversa da AUTO e banda insufficiente il player non può scendere →
   buffering. Da decidere in una prossima fase (es. trattare la preferenza come *tetto*
   invece che come *vincolo*).
3. **Sottotitoli esterni ignorati sui flussi HLS.** `HlsMediaSource.Factory.createMediaSource`
   non gestisce `MediaItem.SubtitleConfiguration` (verificato anche sul **sorgente stock
   Media3 1.5.1**: nessun `MergingMediaSource`; solo `DefaultMediaSourceFactory` lo fa). Il
   commento dell’app che assume il contrario è errato: su HLS le tracce sidecar
   Stremio/OpenSubtitles non vengono caricate. Fuori perimetro (Fase 2 = buffering): richiede
   una decisione (usare `DefaultMediaSourceFactory` per l’HLS o comporre il merge a mano).
4. **Diagnostica fuorviante.** La riga `Player STATE_READY -> activeTrack: 854x480…` stampa la
   **prima** traccia selezionata del gruppo adattivo, cioè sempre la rendition più bassa, non
   quella in riproduzione: usarla come prova nei test TV porta a conclusioni sbagliate.
   Affidarsi a `videoFormat` / `DECODER VIDEO inputFormatChanged`, o alla nuova riga “Stima
   banda”.
5. **Timeout HTTP 15/20 s** (Media3 stock: 8 s di read): una lettura in stallo tiene lo
   spinner fino a 20 s prima che parta la scala di retry. Nessuna evidenza che sia stato
   colpito (nessun `SocketTimeoutException` nei log disponibili) → **non modificato**; è la
   prima leva da provare se i test TV mostrano attese lunghe e isolate.
6. La correzione C1 è una mitigazione *client*: se la sorgente continuerà a flappare, la
   selezione resterà più bassa più a lungo (scelta voluta: meglio 720p stabile che 1080p a
   scatti).

---

## 7. Checklist per i test manuali sulla TV (a carico dell’utente)

Preparazione: logcat pulito (`adb logcat -c` dalla postazione dell’utente) e, se serve,
`logcat -s ExoPerformance:I StreamNova4KDiag:I PlayerScreen:I`.

1. **Playback normale, “Risoluzione preferita” = Auto** — riprodurre un titolo con sorgente
   HLS reale per ≥ 5 minuti.
   - `logcat | grep "DECODER VIDEO inputFormatChanged"` → **molti meno cambi** che in passato
     (prima: 7-8 al minuto); nessun `DECODER VIDEO rilasciato` a ripetizione.
   - Con overlay Stats for Nerds attivo: la stima di banda deve essere **≥ bitrate video**
     quando si vede 1080p.
2. **Spinner** — guardare un titolo per qualche minuto: il cerchio di caricamento **non deve
    lampeggiare** nei micro-freeze del cambio rendition; deve invece comparire (dopo ~0,6 s)
    su uno stallo reale, per esempio pausando il router per 10 s.
3. **Buffer iniziale** — Impostazioni → Avanzate → “Buffer iniziale” → **Stabile (3,5 s)**,
   poi avviare un titolo: nella logcat deve comparire
   `ExoPerformance: buildLoadControl: … bufferDurationsMs=30000/60000/3500/5000`.
   Tornare a **Standard** → `…/2500/5000`.
4. **Nessuna regressione nelle leve esistenti**: pausa/riprendi, seek avanti/indietro e
   scrubbing con anteprima, cambio lingua audio, sottotitoli embedded, cambio sorgente dal
   player, passaggio al prossimo episodio, uscita dal player e rientro, riproduzione 4K dove
   la sorgente la offre.
5. **Errori di sorgente (C2)** — con una sorgente scaduta/protetta: il passaggio
   all’alternativa deve avvenire **subito** (non dopo ~7 s di tentativi) e senza schermata di
   errore se un’altra sorgente è disponibile.
6. **Sorgente con una rendition rotta (facoltativo)** — se capita un 404 su un segmento: la
   riproduzione deve continuare sulle altre rendition invece di interrompersi.
7. **Segnalazione**: se il buffering resta, annotare bitrate video, stima di banda (overlay) e
   frequenza dei `inputFormatChanged` → distingue “rete insufficiente” da “selezione instabile”.

---

## 8. Vincoli rispettati

- Nessun accesso alla Fire TV fisica: nessun `adb`, installazione, avvio, input, logcat,
  screenshot o modifica su dispositivo.
- Nessun commit/push/tag/release/upload; Fase 1 (e tutte le sue modifiche non tracciate)
  intatta; tree di Fase 2 non committato.
- Nessun buffer aumentato indiscriminatamente, nessun retry infinito, nessun errore nascosto:
  il default del buffer iniziale resta quello di Media3 (2,5 s) e i 403/404 definitivi
  falliscono **prima** di prima.
- Fase 3 non iniziata.

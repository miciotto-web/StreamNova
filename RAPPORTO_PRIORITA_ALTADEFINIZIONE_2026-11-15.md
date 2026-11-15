# Rapporto di fine lavoro — Priorità automatica Altadefinizione e selezione qualità video

**Data:** 15 novembre 2026
**Branch:** `integration/streamnova-1.0.3-w1-w12`
**Build verificata:** `:app:assembleDebug` → **BUILD SUCCESSFUL**
**Commit/push/tag:** NON eseguiti (come richiesto)

---

## 1. Analisi del sistema di selezione precedente

La scelta automatica viveva in due punti:

1. **`StreamManager.resolveFlow`** — interrogava i provider HTTP in parallelo
   (default: 3 slot concorrenti) e fondeva le sorgenti in un'unica lista
   ordinata per qualità. Il primo provider che rispondeva faceva partire il
   Fast-Start (1,2 s), anche a 720p.
2. **`StreamNovaViewModel.openStreamForMedia`** — riordinava le sorgenti con
   `TorBoxSourceOrdering` e sceglieva la prima riproducibile
   (`resolveAutoPickSource`), con una finestra Full-HD di 8 s per dare tempo ai
   provider più lenti.

Limiti rispetto alla nuova richiesta:

- **Altadefinizione non era prioritario**: VixSrc partiva prima nella lista dei
  provider e spesso vinceva con una 720p.
- **Altadefinizione restituiva una sola sorgente**: la master HLS veniva
  classificata con la qualità massima dichiarata, ma le singole varianti
  (`4K/1440p/1080p/720p`) non esistevano come sorgenti separate.
- **Nessuna verifica per-risoluzione**: una variante dichiarata 4K ma con
  manifest/segmenti inaccessibili non veniva distinta da una realmente
  riproducibile.
- **Nessun fallback deterministico** Altadefinizione → VixSrc → Streaming
  Community: i provider erano tutti in gara e vinceva il più veloce.

---

## 2. File modificati

### Nuovi

| File | Contenuto |
|---|---|
| `app/src/main/java/com/example/data/streaming/StreamResolution.kt` | Normalizzazione 4K/UHD/2160p, QHD/1440p, Full HD/FHD/1080p, HD/720p, 480p/SD; altezza convenzionale delle varianti widescreen (es. 1920x800 → 1080p); sconosciuta = 0. |
| `app/src/main/java/com/example/data/streaming/HlsPlaylist.kt` | Parser HLS: varianti `#EXT-X-STREAM-INF`, risoluzione relativa con propagazione del token, primo segmento, segmento di inizializzazione `#EXT-X-MAP`. |
| `app/src/main/java/com/example/data/streaming/AutoSourcePriority.kt` | Gerarchia provider (Altadefinizione → VixSrc → Streaming Community → altri) e ordinamento per risoluzione decrescente con sconosciuti in fondo. |
| `app/src/main/java/com/example/data/streaming/StreamSourceVerifier.kt` | Verifica HTTP di riproducibilità: manifest, playlist di variante, init e primo segmento; esiti `Playable` / `Unavailable` / `TransientFailure`. |
| `app/src/test/java/com/example/data/streaming/AutomaticProviderPriorityTest.kt` | Test obbligatori A–J. |
| `app/src/test/java/com/example/data/streaming/StreamResolutionTest.kt` | Denominazioni equivalenti, sconosciuti, ordinamento gerarchico. |
| `app/src/test/java/com/example/data/streaming/StreamSourceVerifierTest.kt` | Verifica manifest/segmenti, errori HTTP, HTML, rete. |
| `app/src/test/java/com/example/ui/viewmodel/AutomaticAltadefinizionePriorityViewModelTest.kt` | Integrazione ViewModel: Altadefinizione 720p batte VixSrc 4K e il player parte subito. |

### Modificati

| File | Modifica |
|---|---|
| `app/src/main/java/com/example/data/streaming/StreamManager.kt` | Nuova cascata automatica, verifica iniettata, refactor del tentativo provider (`runAttempt`), supporto 1440p nei punteggi. Percorso manuale/parallelo invariato. |
| `app/src/main/java/com/example/data/streaming/StreamModels.kt` | `SourceAvailability` sulla sorgente; `effectiveHeight`/`resolution` normalizzati; 1440p in `TorBoxSourceOrdering`. |
| `app/src/main/java/com/example/data/streaming/providers/AltadefinizioneProvider.kt` | La master HLS viene espansa in una sorgente per variante, ordinate 4K → 1440p → 1080p → 720p. |
| `app/src/main/java/com/example/data/streaming/providers/Vidxgo.kt` | Riconosce 1440p e delega il parsing delle varianti a `HlsPlaylist`. |
| `app/src/main/java/com/example/data/streaming/VixSrcProvider.kt` | Marca `Verified` la sorgente dopo aver scaricato e validato la playlist con gli stessi header del player. |
| `app/src/main/java/com/example/ui/viewmodel/StreamNovaViewModel.kt` | Attiva `automaticSelection` solo in riproduzione automatica; avvio immediato della sorgente già verificata dalla cascata. |
| `app/src/test/java/com/example/data/streaming/providers/AltadefinizioneProviderTest.kt` | Aggiornato alle varianti e aggiunto il caso multi-risoluzione. |

**Non modificati:** `mobile/`, UI del player, card, SIMKL, addon, cataloghi TMDB,
Parental Guide, OTA, sincronizzazione dispositivi, sistema di focus.
Le modifiche WIP preesistenti (`HomeScreen.kt`, `SubScreens.kt`) sono state
preservate senza alcun intervento.

---

## 3. Nuova gerarchia implementata

La cascata è attiva solo quando **tutte** queste condizioni sono vere:

- `automaticSelection = true` (riproduzione automatica, non dialog manuale);
- motore `HTTP_WEB`;
- nessun provider HTTP principale scelto manualmente;
- almeno un provider della gerarchia è configurato.

Ordine di valutazione:

1. **Altadefinizione** — sempre primo provider interrogato.
2. **VixSrc**.
3. **Streaming Community**.
4. Altri provider abilitati (ordine storico; gli sperimentali restano spenti
   se il relativo interruttore è OFF).

Per Altadefinizione le varianti vengono ordinate per risoluzione decrescente:

| Sorgenti Altadefinizione | Scelta |
|---|---|
| 4K, 1440p, 1080p, 720p | 4K |
| 1440p, 1080p, 720p | 1440p |
| 1080p, 720p | 1080p |
| 720p | 720p |
| sconosciuta | ultima, mai promossa |

Una 720p **riproducibile** di Altadefinizione batte una 4K di VixSrc: la
riproduzione automatica resta su Altadefinizione. VixSrc (e gli altri provider)
può essere interrogato **dopo** la selezione solo per popolare il selettore
sorgenti del player, senza mai sostituire il flusso avviato.

---

## 4. Modalità di verifica della riproducibilità

`HttpSourceVerifier` usa una sonda HTTP iniettabile, senza avviare player:

1. `GET` del manifest con **gli stessi header usati dal player** (Referer,
   Origin, User-Agent, Sec-Fetch-*).
2. Se è una master playlist, segue la variante dichiarata (o la più alta) fino
   a 3 livelli di annidamento.
3. Richiede una media playlist HLS valida (`#EXTM3U` + almeno un `#EXTINF`).
4. Sonda l’eventuale segmento di inizializzazione (`#EXT-X-MAP`) e il **primo
   segmento** con `Range: bytes=0-1023`.
5. Accetta 2xx; un 4xx è `Unavailable`; 408/425/429/5xx sono
   `TransientFailure`; HTML/JSON al posto del flusso è `Unavailable`.

Le richieste sono **sequenziali**: nessuna riproduzione multipla in parallelo,
nessun retry infinito. Il timeout è di 4 s per singola richiesta.

Stati nel modello:

- `Unknown` — nessuna verifica (le varianti Altadefinizione partono così);
- `Verified` — manifest/segmento raggiungibili (VixSrc lo è già dopo la sua
  estrazione; la cascata lo considera valido senza una seconda richiesta);
- `Unavailable` — risorsa non utilizzabile.

Una risoluzione **dichiarata** 4K ma con manifest non accessibile non viene
selezionata: si passa alla variante inferiore.

---

## 5. Gestione del fallback

```
Altadefinizione: tutte le varianti verificate in ordine decrescente
   ├─ prima Playable → riproduzione avviata con Altadefinizione
   │     └─ le fasce successive vengono comunque interrogate DOPO la scelta,
   │        solo per popolare il selettore sorgenti del player
   └─ nessuna Playable
        ↓
VixSrc (migliore qualità disponibile)
   ├─ Playable → riproduzione avviata con VixSrc
   │     └─ Streaming Community e gli altri provider vengono raccolti per il selettore
   └─ nessuna Playable
        ↓
Streaming Community
   ├─ Playable → riproduzione avviata con Streaming Community
   └─ nessuna Playable
        ↓
Altri provider abilitati (parallelo, comportamento storico)
        ↓
Nessuna sorgente → nessuna emissione → il ViewModel mostra l'errore/dialog
già previsto ("Nessuna sorgente disponibile per questo titolo")
```

Nessun intervento dell’utente. Ogni provider viene interrogato **una sola
volta** per ricerca.

Nota: Streaming Community è un’alternativa esplicita della gerarchia e viene
eseguita anche se l’interruttore “Provider sperimentali” è OFF, ma **solo**
dopo che Altadefinizione e VixSrc non hanno prodotto sorgenti riproducibili.
Poiché non gira mai in parallelo con Altadefinizione, non può duplicarne il
backend.

---

## 6. Test eseguiti e risultati

### Test obbligatori A–J — `AutomaticProviderPriorityTest`

| # | Scenario | Esito |
|---|---|---|
| A | Altadefinizione 4K valido | 4K selezionato; VixSrc solo per il selettore |
| B | 4K non valido, 1440p valido | 1440p selezionato |
| C | 4K e 1440p non validi, 1080p valido | 1080p selezionato |
| D | solo 720p valido | 720p selezionato |
| E | tutte le varianti Altadefinizione non valide | fallback VixSrc |
| F | VixSrc non valido | fallback Streaming Community |
| G | nessun provider disponibile | nessuna emissione, nessuna eccezione |
| H | Altadefinizione 720p valido + VixSrc 4K valido | 720p Altadefinizione |
| I | 4K dichiarato ma manifest non accessibile | non selezionato |
| J | selezione manuale | ordinamento storico, nessuna verifica |
| K | selettore del player dopo la scelta | VixSrc e Streaming Community presenti come alternative |

Tutti verdi.

### Altre verifiche automatiche

- `StreamResolutionTest` — 5 test su denominazioni, widescreen, sconosciuti,
  ordinamento gerarchico.
- `StreamSourceVerifierTest` — 9 test su manifest, varianti, segmenti, 403,
  503, HTML, file progressivi, errori di rete.
- `AltadefinizioneProviderTest` — varianti multiple ordinate 4K/1440p/1080p/720p;
  caso multi-variante aggiunto.
- `AutomaticAltadefinizionePriorityViewModelTest` — la 720p di Altadefinizione
  batte la 4K di VixSrc, il player parte senza attendere la finestra Full-HD e
  VixSrc compare comunque tra le sorgenti selezionabili dal player.

### Comandi e risultati

| Comando | Esito |
|---|---|
| `./gradlew :app:testDebugUnitTest` (suite completa) | **BUILD SUCCESSFUL** (10m 18s dopo l’aggiornamento del selettore) |
| `./gradlew :app:testDebugUnitTest --tests "com.example.data.streaming.*"` | **BUILD SUCCESSFUL** |
| `./gradlew :app:testDebugUnitTest --tests "com.example.ui.viewmodel.*"` | **BUILD SUCCESSFUL** (dopo il fix; una singola esecuzione parziale aveva mostrato un test flaky dei sottotitoli, passato in isolamento e nella suite completa) |
| `./gradlew :app:assembleDebug` | **BUILD SUCCESSFUL** (48s dopo l’aggiornamento del selettore) |

### Verifica manuale su rete reale

Con gli stessi header del player è stata verificata la catena reale vidxgo:

- token `GET /t/0111161` → master HLS `cdn.v1.media-836.d2b.you/hls/0111161/master.m3u8?...`;
- master con variante `1920x1038` → classificata **1080p**;
- playlist di variante → `#EXTM3U` con segmenti `.ts`;
- primo segmento con `Range: bytes=0-1023` → **HTTP 206**, 1024 byte.

La verifica conferma che il parser delle varianti e la sonda dei segmenti
funzionano sui manifest reali di Altadefinizione.

---

## 7. Limitazioni tecniche

- La verifica controlla **manifest + playlist di variante + primo segmento**
  (ed eventuale init): non scarica tutti i segmenti. È il compromesso tra
  attendibilità e tempo di selezione; la riproduzione successiva resta protetta
  dal fallback del player già esistente.
- Un errore **transitorio** durante la verifica non dichiara la sorgente
  inesistente: la si salta per la selezione corrente e resta disponibile nella
  selezione manuale. Non viene ritentata all’infinito.
- La cascata è **sequenziale tra provider**: se Altadefinizione è lento, l’avvio
  attende la sua valutazione. È intenzionale: è l’unico modo per garantire che
  una 4K/1440p/1080p realmente riproducibile non venga scavalcata da un
  provider alternativo più veloce. Non sono state introdotte attese arbitrarie.
- Il rinnovo del token vidxgo durante la riproduzione continua a funzionare
  perché le URL delle varianti restano sotto `/hls/{id}/...`, il percorso
  riscritto da `VidxgoTokenInterceptor` (confermato dal CDN reale).
- Altadefinizione ora espone **una sorgente per variante**: il selettore
  manuale mostra quindi più voci di qualità dello stesso provider. È la
  conseguenza funzionale necessaria per scegliere la risoluzione; la UI non è
  stata modificata.

---

## 8. Conferma: selezione manuale invariata

- `openStreamForMedia(..., manualSelection = true)` → `automaticSelection = false`
  → percorso storico: tutti i provider interrogati in parallelo, tutte le
  sorgenti emesse, dialog di scelta mostrato, **nessuna verifica** eseguita.
- Provider HTTP principale scelto nelle Impostazioni → `preferredProvider`
  valorizzato → cascata disattivata, comportamento preferito invariato
  (inclusa la modalità “solo provider scelto”).
- Motore Debrid/TorBox → invariato.
- Il test **J** verifica esplicitamente che in selezione manuale vinca
  l’ordinamento storico per qualità e che il verificatore non venga chiamato.
- Nessuna impostazione esistente è stata modificata; nessuna nuova dipendenza.

---

## 9. Esito build debug

```
./gradlew :app:assembleDebug
BUILD SUCCESSFUL in 2m 07s
```

Nessun errore di compilazione. La suite completa `:app:testDebugUnitTest` è
verde.

---

## 10. Gestione Git

- Nessun commit, push, tag o pubblicazione.
- Nessun file cancellato.
- Modifiche WIP preesistenti preservate:
  `app/src/main/java/com/example/ui/screens/HomeScreen.kt`,
  `app/src/main/java/com/example/ui/screens/SubScreens.kt`,
  report e screenshot già presenti.
- Nuovi file del lavoro lasciati non tracciati, in attesa di approvazione.

**In attesa dell’approvazione dell’utente prima di qualsiasi operazione Git.**

---

## 11. Aggiornamento — selettore sorgenti del player

Su segnalazione dell’utente, la cascata automatica non si ferma più dopo la
prima sorgente riproducibile: **dopo aver avviato la riproduzione** continua a
interrogare le fasce successive (VixSrc, Streaming Community, altri provider
abilitati) e aggiunge le loro sorgenti al catalogo del selettore interno al
player.

Comportamento risultante:

- la scelta automatica e la riproduzione restano quelle della gerarchia
  (Altadefinizione → VixSrc → Streaming Community → altri);
- le sorgenti alternative **non sostituiscono mai** il flusso già avviato: il
  ViewModel le usa solo per `sourceCandidates` / `availableSources`;
- il selettore del player si aggiorna progressivamente man mano che i provider
  rispondono (tipicamente VixSrc e Streaming Community entro pochi secondi);
- anche il **fallback automatico del player** (`PlayerSourceFallback`) ora ha
  alternative reali: se la sorgente principale ha un errore definitivo
  (403/404/manifest rotto), può passare a VixSrc/Streaming Community senza
  intervento dell’utente;
- ogni provider viene interrogato una sola volta per ricerca;
- la selezione manuale dal Dettaglio resta invariata.

Nuovo test: `K_ilSelettoreDelPlayerContieneIProviderAlternativi` in
`AutomaticProviderPriorityTest`, più l’asserzione corrispondente in
`AutomaticAltadefinizionePriorityViewModelTest`.

APK debug aggiornato installato sull’emulatore `emulator-5554`
(`StreamNova-x86-debug.apk`, installazione `-r` con dati preservati).

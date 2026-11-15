# Rapporto di fine lavoro — Selezione automatica provider: vince chi ha il contenuto in 1080p

**Data:** 14 novembre 2026
**Branch:** `integration/streamnova-1.0.3-w1-w12`
**Build verificata:** debug, `versionCode 1100`, `versionName 1.1.0` (APK installato sull'emulatore)
**Dispositivo di test:** emulatore Android TV AOSP (1920×1080, ABI x86), settings reali:
*Motore = Web Streaming Gratuito (HTTP Scraper)*, *Provider HTTP principale = Automatico*,
*Provider sperimentali = ON*, *"Risoluzione preferita" = Auto*
**Commit/push:** NON eseguiti su richiesta.

---

## 1. Richiesta

> Quando come provider HTTP principale è selezionato **Automatico** insieme a
> **Provider Sperimentali** viene comunque quasi sempre selezionato ViXsrc e spesso
> a 720p. Vorrei che vincesse sempre la selezione automatica il provider che ha
> disponibile quel contenuto **a 1080p**.

---

## 2. Analisi e cause individuate

L'auto-pick (scelta automatica della sorgente da riprodurre) vive in
`StreamNovaViewModel.openStreamForMedia` → `collectBlock`. Analizzando il percorso
sono emersi **due difetti, uno di progettazione e uno strutturale**:

### 2.1 Finestra di assestamento troppo corta (1,2 s)

Al primo arrivo di una sorgente riproducibile — quasi sempre il **Fast-Start di
VixSrc a 720p** — la sorgente veniva messa in attesa (`pendingAutoPick`) e il player
partiva dopo la finestra `AUTO_PICK_SETTLE_WINDOW_MS = 1_200 ms` se nel frattempo
non fosse arrivata una sorgente ≥ 1080p. I provider che hanno tipicamente il
contenuto in Full HD (Altadefinizione, CB01, StreamingCommunity, SuperEmbed…)
rispondono però **2–8 s** dopo l'avvio della ricerca: la finestra scadeva prima e la
riproduzione partiva in 720p. Una sorgente 1080p arrivata dopo veniva solo aggiunta
alla lista del selettore manuale, senza sostituire il flusso in corso (per scelta
progettuale: mai riavvii/re-buffering di uno stream già partito).

### 2.2 Bug strutturale: il collector restava bloccato per l'intera finestra

Scava più a fondo, il difetto era **peggiore** di quanto la sola finestra corta
spieghi. Il timer di assestamento veniva lanciato con `launch` **dentro**
`withContext(Dispatchers.Main) { ... }` nel blocco `collect`:

```kotlin
withContext(Dispatchers.Main) {          // scope A
  ...
  settleJob = launch {                   // figlio DI A
    delay(settleWindowMs)
    openAutoPickedSource(pendingAutoPick)
  }
}
```

`withContext` — per la struttura a coroutine — **non restituisce finché tutti i
figli lanciati nel suo scope non sono completati**. Conseguenza: dopo la prima
emissione il collector del flusso `resolveFlow` restava **sospeso dentro il
`withContext` per l'intera durata della finestra**, e le emissioni successive dei
provider più lenti (la 1080p!) restavano nel buffer del channel **senza essere mai
processate** prima che il player fosse già partito a 720p.

Il difetto è stato **riprodotto e isolato con un test minimo** (`channelFlow` +
`withContext(Main)` + timer figlio su dispatcher di tempo virtuale): la seconda
emissione arrivata a t=3 s veniva consegnata solo a t=8 s, dopo lo scatto del timer.
Nei test d'integrazione esistenti il difetto restava mascherato perché il
comportamento "vince la prima sorgente arrivata" era proprio quello codificato
nelle aspettative.

---

## 3. Correzioni apportate

Tutte in `app/src/main/java/com/example/ui/viewmodel/StreamNovaViewModel.kt`
(nessuna modifica al protocollo `StreamManager` né ai provider):

### 3.1 Nuova "Finestra Full-HD" della selezione automatica

* Nuova costante `AUTO_PICK_FULLHD_SETTLE_WINDOW_MS = 8_000 ms`: in modalità
  **Automatico** (HTTP) la selezione attende fino a 8 s che un provider produca una
  sorgente ≥ 1080p prima di ripiegare sulla migliore già raccolta.
* Una sorgente ≥ 1080p arrivata nella finestra **parte subito**
  (`canStartImmediately`), scavalcando il Fast-Start del provider più veloce.
* La ricerca che si conclude prima (tipico: 4–6 s con 8 provider) apre comunque
  subito il pending pick: **nessun avvio mai più lento della fine della ricerca**.

### 3.2 Scelta della finestra incapsulata in una funzione pura

`autoPickSettleWindowMs(mode, preferredHttpProviderId, preferredResolution)`:

| Configurazione | Finestra |
|---|---|
| Provider principale scelto manualmente | `PRIMARY_PROVIDER_SETTLE_WINDOW_MS` (1,5 s, invariata) |
| Automatico, motore HTTP, risoluzione preferita ≠ 720p | **`AUTO_PICK_FULLHD_SETTLE_WINDOW_MS` (8 s)** |
| Motore Debrid/TorBox o "Risoluzione preferita" = 720p | `AUTO_PICK_SETTLE_WINDOW_MS` (1,2 s, invariata) |

Motivazione delle eccezioni: con un provider scelto manualmente la scelta
dell'utente è prioritaria (finestra corta invariata); nel motore Debrid le sorgenti
cached non devono aspettare i provider HTTP; con risoluzione preferita 720p
attendere il 1080p non avrebbe senso.

### 3.3 Fix dello scope del timer (il difetto strutturale)

Il timer è ora figlio dello **scope del job di ricerca** (`streamScope = this`
catturato all'apertura di `streamJob`) e non più dello scope `withContext`:

```kotlin
settleJob = streamScope.launch { delay(settleWindowMs); openAutoPickedSource(...) }
```

Effetto: il collector torna libero subito dopo la prima emissione, le emissioni
successive vengono processate in tempo reale e una 1080p può scattare via
`canStartImmediately`. La cancellazione resta strutturata: il timer muore con
`streamJob` (annullamento ricerca/nuova ricerca) e viene comunque cancellato nel
post-collect.

---

## 4. Test unitari

### 4.1 Nuova classe `AutoPickFullHdWindowTest` (4 test, tutti verdi)

* `slowFullHdSourceBeatsTheFast720pFastStart` — provider 720p immediato + provider
  1080p a 3 s: a t=1,5 s il player **non** è partito (prima sarebbe già partito in
  720p); la sorgente 1080p arrivata nella finestra avvia il player e vince.
* `withoutFullHdSourcesPlaybackStartsWithTheBestAvailable` — senza sorgenti Full HD
  la riproduzione parte comunque con la migliore disponibile (mai spinner infinito).
* `autoHttpModeWithDefaultResolutionUsesTheFullHdWindow` e
  `fullHdWindowIsSkippedWhenItWouldBePointless` — contratti della funzione pura
  `autoPickSettleWindowMs` (Automatico→8 s; provider manuale→1,5 s; Debrid→1,2 s;
  risoluzione preferita 720p→1,2 s).

### 4.2 Aggiornamento di `PlayerSourceFallbackViewModelTest`

5 test sul **fallback del player** fallivano perché i fixture codificavano il
vecchio comportamento difettoso ("prima sorgente 720p veloce avviata, 1080p tardiva
ignorata"). I fixture sono stati aggiornati al nuovo contratto (prima sorgente
Full HD avviata subito, alternative in lista) **senza toccare le asserzioni sul
meccanismo di fallback** (switch automatico, sorgenti già tentate non ritentate,
emissioni tardive non sostituiscono il flusso attivo, scelta manuale autorevole):
tutti e 8 i test della classe sono verdi.

### 4.3 Suite completa

`:app:testDebugUnitTest` (intera suite, Robolectric) eseguita per intero:
**BUILD SUCCESSFUL, exit 0, nessun fallimento.**

---

## 5. Verifica su emulatore (scenari reali, provider reali)

Configurazione replicata dalla richiesta: *Automatico + Provider sperimentali ON*.
Log reali (`StreamManager`, `CW_TRACE`, `COMET_DIAG`):

### Scenario A — "Resident Evil" (2026): nessun provider ha il 1080p

```
Provider MultiEmbedProvider [ATTIVO] ... VixSrcProvider [ATTIVO] ... AltadefinizioneProvider [ATTIVO]
  ... TwoEmbed/SuperEmbed/StreamingCommunity/Cb01/Eurostreaming [ATTIVO]   (tutti e 8)
Flusso selezionato: Altadefinizione con qualità 720p   (t+0,6 s, Fast-Start)
Flusso selezionato: VixSrc con qualità 720p            (t+1,1 s)
DIAG-E resolveFlow COMPLETE total=5  (VixSrc=1, Altadefinizione=1, StreamingCommunity=1, Mixdrop=2)
STREAM_RESOLVED server=Altadefinizione quality=720p via=auto-pick   (t≈4 s, a fine ricerca)
```

Nessuna sorgente Full HD disponibile: la riproduzione parte a fine ricerca con la
**migliore disponibile** (Altadefinizione 720p, sorgente italiana, che per
"Privilegia tracce italiane" prevale sulla 720p di VixSrc). **Nessun blocco, nessun
spinner infinito.**

### Scenario B — "Il buono, il brutto, il cattivo": il 1080p esiste e vince

```
Flusso selezionato: VixSrc con qualità 720p           (t+0,8 s, Fast-Start)
Flusso selezionato: Altadefinizione con qualità 1080p (t+1,6 s)
STREAM_RESOLVED server=Altadefinizione quality=1080p via=auto-pick  (t+1,6 s)
DIAG-E resolveFlow COMPLETE total=3  (Altadefinizione, StreamingCommunity, VixSrc)
```

Il player **parte in 1080p**: la sorgente Full HD di Altadefinizione, arrivata
~0,8 s **dopo** il 720p di VixSrc, scavalca il Fast-Start. Con il codice precedente
la riproduzione sarebbe partita in 720p su VixSrc. Riproduzione avviata e verificata
visivamente (film in riproduzione, traccia italiana).

---

## 6. Comportamento risultante (sintesi)

| Situazione (Automatico + sperimentali) | Prima | Dopo |
|---|---|---|
| VixSrc 720p veloce, 1080p altrove 2–8 s | Partiva 720p su VixSrc | **Parte 1080p** sul provider che lo ha (arrivo ≤ 8 s o entro fine ricerca) |
| Solo sorgenti ≤ 720p | Partiva 720p a ~1,2 s | Parte con la migliore a **fine ricerca** (tip. 4–6 s) o a 8 s di cap |
| Prima sorgente già 1080p/4K | Partiva subito | Parte subito (invariato) |
| Provider principale manuale | Finestra 1,5 s | Invariato |
| Motore Debrid/TorBox | Finestra 1,2 s | Invariato |
| "Risoluzione preferita" = 720p | Finestra 1,2 s | Invariata (non si attende il 1080p) |

Nota sul compromesso temporale: per i titoli **senza** alcuna sorgente Full HD
l'avvio slitta di qualche secondo (fine ricerca entro il cap di 8 s, contro i
1,2 s precedenti) — è il prezzo richiesto per garantire che il 1080p, quando
esiste, vinca sempre. La preferenza italiana ("Privilegia tracce italiane") resta
superiore alla risoluzione nell'ordinamento: tra due sorgenti 720p italiane e
non-italiane l'ordine non cambia; una 720p italiana batte una 1080p non italiana
(impostazione utente esplicita, non modificata).

---

## 7. File toccati

| File | Tipo | Contenuto |
|---|---|---|
| `app/src/main/java/com/example/ui/viewmodel/StreamNovaViewModel.kt` | modifica | Finestra Full-HD, `autoPickSettleWindowMs`, fix scope timer |
| `app/src/test/java/com/example/ui/viewmodel/AutoPickFullHdWindowTest.kt` | nuovo | 4 test del nuovo comportamento |
| `app/src/test/java/com/example/ui/viewmodel/PlayerSourceFallbackViewModelTest.kt` | modifica | Fixture allineati al nuovo contratto (asserzioni fallback invariate) |

`StreamManager.kt` e i provider **non sono stati modificati** (il diff su
StreamManager è vuoto: serviva solo strumentazione diagnostica temporanea, rimossa).

---

## 8. Limiti e note

* Il cap di 8 s protegge da provider bloccati fino al timeout di ricerca (15 s):
  una 1080p che arrivasse **dopo** il cap o dopo l'avvio del player non sostituisce
  il flusso in corso (politica anti-rebuffering confermata, resta in lista per la
  scelta manuale).
* Il tempo di avvio per contenuti solo-720p cresce (vedi §6); se si volesse
  ridurre, il valore `AUTO_PICK_FULLHD_SETTLE_WINDOW_MS` è l'unico punto da
  tarare.
* Non è stato modificato nulla di localizzazione/UI: nessuna nuova stringa.

# StreamNova — Fase 3: Continua a guardare, ripresa Debrid, completamento serie

**Branch:** `feature/streamnova-mobile` (working tree con le modifiche Fase 2 preservate)
**Data:** 2026-10-29
**Stato:** fix applicati, test di regressione verdi, build debug TV e mobile verdi.

---

## 1. Esito sintetico

| Obiettivo | Esito | Evidenza |
|---|---|---|
| Ripresa dell'episodio corretto (S5E2 ≠ S1E1) | ✅ | `EpisodeResumeResolver` usato da TV e mobile; test resolver + ViewModel |
| Ripresa diretta della sorgente Debrid | ✅ | ri-sblocco `infoHash`/`fileIdx` su ripresa; fallback su link scaduto; test |
| Rimozione delle serie completate | ✅ | `SeriesCompletionPolicy` + persistenza Room + riconciliazione all'avvio; test |
| Test di regressione | ✅ | 37 nuovi test mirati; suite complete verdi |
| Build debug | ✅ | `:app:assembleDebug`, `:mobile:assembleDebug` |

---

## 2. Cause reali individuate

### 2.1 Episodio sbagliato in ripresa (S1E1 al posto di S5E2)

Le liste episodi locali delle serie di catalogo sono **generate e troncate** (max 3
stagioni, 5 episodi in S1, 4 nelle altre) oppure non ancora caricate da TMDB. Tre punti
ripiegavano sul primo episodio quando la coppia salvata (`lastWatchedSeason`,
`lastWatchedEpisode`) non era nella lista:

- `StreamNovaViewModel.openStreamForMedia` (`episodes.find { … } ?: episodes.firstOrNull()`);
- `StreamNovaViewModel.executeOpenPlayer` (stessa catena);
- `DetailScreen` TV e `MobileDetailScreen` (pulsante Riprendi).

Inoltre `MobileDetailViewModel` apriva sempre la **stagione 1**, quindi la lista visibile
era quella sbagliata e il fallback produceva S1E1. La posizione salvata era corretta in
Room: il difetto era nella risoluzione dell'episodio, non nelle etichette UI.

### 2.2 Sorgente Debrid non recuperata / link scaduto

`tryOpenReusedStream` riproduceva direttamente l'URL salvato. Un link CDN scaduto
falliva nel player e costringeva alla risoluzione completa (con possibile selezione
manuale), pur esistendo in cache l'identità del torrent (`infoHash`, `fileIdx`,
`addonName`, introdotta in Fase 2). Inoltre `handleReusedStreamFailure` invalidava
l'intera voce, perdendo l'identità Debrid ancora valida.

### 2.3 Nessuna rimozione delle serie concluse

«Continua a guardare» includeva qualunque titolo con `currentProgressMs > 0`: una serie
finita restava per sempre (salvo rimozione manuale). Non esisteva alcuna verifica
«ultimo episodio dell'ultima stagione + serie conclusa».

---

## 3. Fix applicati

### 3.1 Episodio corretto (`EpisodeResumeResolver`)

Nuovo resolver puro in `core/data`:

- l'episodio **esplicito** (click su una puntata) resta quello scelto, con i suoi
  metadati (titolo, durata);
- senza episodio esplicito, l'ultima visione salvata è autorevole: se la puntata non è
  nella lista locale viene **ricostruita con gli stessi numeri** (mai S1E1);
- il ripiego al primo episodio resta solo per i titoli **mai avviati**;
- stagione 0 (speciali TMDB) è gestita;
- la durata misurata del titolo viene usata solo per l'episodio dell'ultima visione,
  mai per una puntata diversa.

Usato da: `StreamNovaViewModel.openStreamForMedia`, `executeOpenPlayer`,
`DetailScreen` TV, `MobileDetailViewModel` (stagione iniziale = ultima vista),
`MobileDetailScreen`, `MobilePlayerViewModel`.

### 3.2 Ripresa diretta Debrid

In `tryOpenReusedStream`, quando la voce di cache appartiene alla modalità Debrid e
conserva `infoHash`:

- il link viene **rigenerato** con `TorBoxRepository.resolveInfoHash(infoHash, fileIdx,
  stagione, episodio)` (timeout 6 s), con stagione/episodio **espliciti**: in un torrent
  di stagione il file selezionato è quello della puntata richiesta;
- se lo sblocco riesce si avvia direttamente il nuovo URL, senza interrogare i provider
  e senza dialog di selezione;
- se fallisce si usa il link salvato; un eventuale errore del player attiva il fallback
  classico già esistente;
- `handleReusedStreamFailure` ora **conserva** la voce quando esiste l'identità Debrid
  (un nuovo tentativo può rigenerare il link) e rimuove dalla cache in-memory di TorBox
  solo il link fallito (`TorBoxRepository.invalidateResolvedLink`);
- la cache resta blindata su `(mediaId, stagione, episodio)` esatti: una voce di S5E1 non
  può essere riprodotta per S5E2.

### 3.3 Serie completate

Nuova politica pura `SeriesCompletionPolicy` + helper `WatchProgress`:

- la serie è conclusa **solo** se la puntata vista è l'ultima dell'ultima stagione
  (`seasonsCount` + `seasonEpisodeCounts` TMDB) e lo stato TMDB è terminale
  (`Ended`/`Canceled`);
- stagioni successive, episodi futuri, serie `Returning Series`, dati mancanti o
  incoerenti ⇒ la serie **resta** in «Continua a guardare»;
- il trigger usa la durata **misurata** dal player (ultimi 15 s); una durata stimata non
  è una prova;
- la rimozione azzera `currentProgressMs` in memoria e su Room (nuova query
  `clearContinueWatchingProgress`) per **tutte le schede dello stesso titolo** (id TMDB e
  id IMDb/Stremio): la card non riappare dopo il riavvio; preferiti, stagione/episodio
  visti e badge episodi restano intatti;
- all'avvio `loadFromCache()` riconcilia le righe persistite che hanno raggiunto
  **esattamente** la durata nota (`hasReachedEnd`): posizioni «quasi alla fine» stimate
  non eliminano nulla;
- se il completamento non è verificabile (nessuna chiave/rete/cache TMDB) non si rimuove
  nulla.

### 3.4 Stabilizzazione test (dipendenza indispensabile)

`AppSettingsRepository.resetForTesting()` non annullava il collector del DataStore: un
collector superstite poteva riscrivere i valori letti da disco sopra i default appena
ripristinati, rendendo i test delle preferenze dipendenti dal timing. Il collector ora
viene annullato a ogni reset/re-init (nessun effetto sul percorso di produzione, dove
`init` avviene una sola volta).

---

## 4. File modificati

**Nuovi (produzione)**
- `core/data/src/main/java/com/example/data/repository/EpisodeResumeResolver.kt`
- `core/data/src/main/java/com/example/data/repository/SeriesCompletionPolicy.kt`

**Modificati (produzione)**
- `core/model/src/main/java/com/example/data/model/MediaModels.kt` (`WatchProgress.isAtEnd`, `hasReachedEnd`)
- `core/data/src/main/java/com/example/data/local/CachedMediaDao.kt`
- `core/data/src/main/java/com/example/data/repository/MediaRepository.kt`
- `core/data/src/main/java/com/example/data/torbox/TorBoxRepository.kt`
- `core/data/src/main/java/com/example/data/prefs/AppSettingsRepository.kt`
- `app/src/main/java/com/example/ui/viewmodel/StreamNovaViewModel.kt`
- `app/src/main/java/com/example/ui/screens/DetailScreen.kt`
- `mobile/src/main/java/com/aistudio/streamnova/mobile/ui/MobileDetailViewModel.kt`
- `mobile/src/main/java/com/aistudio/streamnova/mobile/ui/MobilePlayerViewModel.kt`
- `mobile/src/main/java/com/aistudio/streamnova/mobile/ui/screens/DetailScreen.kt`
- `mobile/src/main/java/com/aistudio/streamnova/mobile/ui/screens/PlayerScreen.kt`

**Test nuovi**
- `core/data/src/test/java/com/example/data/repository/EpisodeResumeResolverTest.kt` (10)
- `core/data/src/test/java/com/example/data/repository/SeriesCompletionPolicyTest.kt` (12)
- `core/data/src/test/java/com/example/data/repository/MediaRepositorySeriesCompletionTest.kt` (8)
- `app/src/test/java/com/example/ui/viewmodel/ContinueWatchingResumeEpisodeTest.kt` (4)

**Test estesi**
- `core/data/src/test/java/com/example/data/model/WatchProgressTest.kt` (+3)

**Scratch (non prodotto)**
- `_tmp_test_filter_cw_phase3.init.gradle`, `_tmp_test_filter_prefs.init.gradle`

Nessun file non tracciato preesistente è stato rimosso; nessun reset/clean/commit.

---

## 5. Test eseguiti

### Test mirati (filtro `_tmp_test_filter_cw_phase3.init.gradle`)

| Classe | Test | Esito |
|---|---:|---|
| `EpisodeResumeResolverTest` | 10 | ✅ |
| `SeriesCompletionPolicyTest` | 12 | ✅ |
| `MediaRepositorySeriesCompletionTest` | 8 | ✅ |
| `WatchProgressTest` | 21 | ✅ |
| `ContinueWatchingResumeEpisodeTest` | 4 | ✅ |
| `ContinueWatchingSmartSwitchTest` (Fase 2) | 5 | ✅ |
| `PlayNextEpisodeResolutionTest` (Fase 2) | 18 | ✅ |
| `SimklSyncIntegrationTest` | 35 | ✅ |
| `SourceDialogMediaAlignmentTest` | 6 | ✅ |

### Suite complete (tutti i moduli)

| Modulo | Test | Fallimenti | Skipped |
|---|---:|---:|---:|
| `:core:model` | 5 | 0 | 0 |
| `:core:data` | 1065 | 0 | 12 |
| `:core:player` | 282 | 0 (1 flake preesistente, verde al rerun) | 0 |
| `:app` | 410 | 0 | 0 |
| `:mobile` | 25 | 0 | 0 |

Nota: `SimklScrobbleUploadTest.laFineDellaRiproduzioneChiudeLaSessioneAlCentoPerCento`
ha prodotto una `ConcurrentModificationException` in un helper del test (non toccato da
questa fase); il rerun isolato di `:core:player` è verde. Il test delle preferenze che ha
esposto la race del collector DataStore è ora stabile (`:core:data` verde).

### Build

| Comando | Esito |
|---|---|
| `:app:assembleDebug` | ✅ |
| `:mobile:assembleDebug` | ✅ |

---

## 6. Cosa è dimostrato dai test e cosa richiede prova pratica

**Dimostrato dai test (deterministici, senza rete/account reali)**
- una serie con ultima visione S5E2 riprende S5E2, non S1E1, anche con lista episodi
  troncata; l'episodio riprodotto e il progresso salvato coincidono;
- la voce Debrid salvata viene usata direttamente, con link rigenerato via
  `infoHash`/`fileIdx` e stagione/episodio corretti, senza ricerca provider;
- una voce di cache di un altro episodio non viene mai riprodotta;
- link non rigenerabile → link salvato → fallback alla risoluzione classica sulla stessa
  puntata; identità Debrid conservata;
- fine stagione ≠ fine serie; serie in corso, episodi futuri, metadati incompleti
  conservano la card;
- la serie conclusa sparisce da «Continua a guardare» e la posizione azzerata persiste
  su Room (riavvio simulato con `loadFromCache`);
- l'avanzamento automatico della Fase 2 resta verde (18 test).

**Richiede una prova pratica su device/account reale**
- ripresa reale da una serie TMDB con S5E2 su TV e telefono (la UI e i tempi di rete
  reali non sono coperti dai test);
- Debrid con account TorBox reale: link scaduto rigenerato dallo stesso torrent, torrent
  non più in cache, errore di rete durante lo sblocco;
- completamento di una serie `Ended` con ultimo episodio visto fino in fondo: card
  rimossa, assenza dopo riavvio, e serie `Returning Series` mantenuta;
- comportamento con TMDB non raggiungibile al momento del completamento (la card deve
  restare e sparire alla successiva riconciliazione online).

Non sono state toccate le funzionalità delle Fasi 4 e 5; le uniche modifiche fuori dal
perimetro strettamente «Continua a guardare» sono la stabilizzazione del reset delle
preferenze nei test e il riavvio da 0:00 di un episodio già concluso nel player mobile
(necessario per non far terminare istantaneamente una ripresa).

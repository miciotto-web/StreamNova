# Rapporto — Fix autoplay prossimo episodio al confine di stagione (modalità Debrid)

**Data:** 2026-11-20
**Branch:** `integration/streamnova-1.0.3-w1-w12`
**Stato:** fix applicato e verificato — **nessun commit né push eseguiti**, come richiesto.

---

## 1. Problema segnalato

In modalità Debrid, quando finisce l'ultima puntata di una stagione, l'autoplay deve
passare automaticamente alla prima puntata della stagione successiva **trovando la
stessa fonte** con cui sono stati riprodotti gli episodi della stagione precedente.
Invece la riproduzione si ferma con il messaggio che la fonte non viene trovata
(card «Nessuna fonte disponibile per il prossimo episodio»).

## 2. Analisi — causa radice

Il percorso dell'autoplay "prossimo episodio" in modalità Debrid è:

```
fine episodio → popup/countdown (PlayerScreen)
  → autoPlayNextEpisode → playNextEpisode            (StreamNovaViewModel)
    → openStreamForMedia(..., isNextEpisode = true)
      → FAST-PATH "stesso torrent": seasonPackEpisodeResolver(pinnedSource, season, epNumber)
        → DebridServiceRouter.resolveSeasonPackEpisode(hash, season, episode)
          → TorBox/RealDebrid/PremiumizeRepository.resolveSeasonPackEpisode
            → findEpisodeFile(files DELLO STESSO TORRENT, season, episode)
      → se null → CASO B: stato TERMINALE (SOURCE_UNAVAILABLE), nessuna ricerca
```

**Il difetto:** il fast-path cerca il file dell'episodio successivo **solo dentro lo
stesso torrent** con cui si sta riproducendo l'episodio corrente
(`app/src/main/java/com/example/data/torbox/TorBoxRepository.kt:557`,
`findEpisodeFile`). Un torrent "pacchetto di stagione" (es. `Show.S03.1080p`)
contiene **solo gli episodi della propria stagione**: alla prima puntata della
stagione successiva (S3E8 → S4E1) il file **non può esistere** in quel torrent.

Fino a questo fix, il fallimento del fast-path era trattato in modo **incondizionatamente
terminale** (`CASO B` in `StreamNovaViewModel.openStreamForMedia`, ~linea 5323): nessuna
interrogazione dei provider/addon, stato `SOURCE_UNAVAILABLE`, player bloccato sulla
card di fallimento con sola scelta manuale. Risultato: **al confine di stagione l'autoplay
falliva sempre** in modalità Debrid, esattamente il sintomo riportato.

La ricerca normale (invece) ha già tutto il necessario per il comportamento atteso:
`rankBySameSourceAffinity` ordina le sorgenti dando **massima precedenza allo stesso
addon** (+200) e alla firma di release simile (+60/+40/+30) — cioè "la stessa fonte" —
e `resolveAutoPickSource` auto-sblocca e avvia la sorgente riproducibile migliore.

Il comportamento terminale resta invece **corretto dentro la stessa stagione** (es.
episodio mancante in un pacchetto incompleto): lì il fallimento deve restare terminale
per evitare il loop storico di avanzamenti/ri-search (documentato nel commento CASO B e
codificato in `NextEpisodeAutoplayTerminalStateTest`).

## 3. Fix applicato

Tutte le modifiche sono in `app/src/main/java/com/example/ui/viewmodel/StreamNovaViewModel.kt`
(+ un nuovo file di test). Principio: **il fallimento del fast-path "stesso torrent" non è
più terminale SOLO quando la transizione attraversa il confine di stagione**; in quel caso
subentra la ricerca normale con affinità di fonte.

1. **`playNextEpisode`** — cattura la stagione dell'episodio appena concluso
   (`currentEpisode?.seasonNumber`, prima del cambio di stato) e la passa a
   `openStreamForMedia` tramite il nuovo parametro `previousSeason`.

2. **`openStreamForMedia`** — nuovo parametro opzionale `previousSeason: Int? = null`
   (documentato nel KDoc). Nel ramo di fallimento del fast-path:
   - `crossesSeasonBoundary = previousSeason != null && season != previousSeason`;
   - se **non** si attraversa il confine → `CASO B` terminale **invariato** (stesso
     stato, stessi log/trace, stesso messaggio);
   - se si attraversa → si prosegue con la ricerca normale: log
     `NextEpisodeDebrid` e trace `SAME_SOURCE_SEASON_BOUNDARY_FALLBACK`, stato resta
     `SEARCHING_SAME_SOURCE` (il player mostra lo spinner "Caricamento episodio
     successivo..."), le sorgenti vengono ordinate per affinità con la fonte corrente
     (`rankBySameSourceAffinity` — stesso addon in cima) e la migliore sorgente
     riproducibile viene auto-selezionata e avviata. Il timeout globale di 10 s
     (`NEXT_EPISODE_RESOLUTION_TIMEOUT_MS`) continua a proteggere la transizione.

3. **Coerenza della macchina a stati dell'autoplay** (prima il solo esito possibile
   raggiungendo la ricerca era IDLE/STARTED/SOURCE_UNAVAILABLE; con il fallback serviva
   gestire gli esiti "ricerca riuscita" e "serve la scelta manuale"):
   - `openAutoPickedSource`: quando il player parte con una sorgente trovata dalla
     ricerca e lo stato è ancora `SEARCHING_SAME_SOURCE` → promozione a **`STARTED`**
     (senza questa transizione il countdown per l'episodio *dopo* non si sarebbe mai
     riarmato: `blocksAutomaticAdvance` lo avrebbe bloccato);
   - tutti i punti di apertura del selettore sorgenti raggiunti durante il fallback
     (branch «senza identificatori», branch `shouldShowDialog`, Caso C senza sorgente
     avviabile nel collect, Caso C finale a fine raccolta) promuovono
     `SEARCHING_SAME_SOURCE` → **`MANUAL_SELECTION_REQUIRED`** tramite il nuovo helper
     `promoteSearchingSameSourceToManualSelection` (no-op in ogni altro caso):
     la chiusura del selettore senza scelta converge quindi nello stato terminale
     `SOURCE_UNAVAILABLE` già gestito da `dismissSourceDialog`, e la scelta manuale
     risporta lo stato a `IDLE` (comportamento esistente di `selectSource`).

4. **Documentazione aggiornata**: KDoc di `SAME_SOURCE_TIMEOUT_MS` e di
   `openStreamForMedia` ora descrivono l'eccezione del confine di stagione.

### Comportamento risultante

| Scenario | Prima | Dopo |
|---|---|---|
| S3E8 → S4E1, stesso addon ha il pacchetto S4 (cached) | Card «fonte non trovata», riproduzione ferma | Ricerca con affinità → parte il pacchetto S4 dello stesso addon, autoplay prosegue |
| S3E8 → S4E1, nessuna sorgente per S4E1 | Card di fallimento | Selettore manuale aperto sulla nuova puntata (stato `MANUAL_SELECTION_REQUIRED`); chiusura senza scelta → fallimento terminale `SOURCE_UNAVAILABLE` |
| Torrent MULTI-stagione (S2E1 presente nello stesso torrent) | Avvio diretto dal fast-path | **Invariato**: fast-path vince ancora, provider mai interrogati |
| Fallimento fast-path dentro la stessa stagione (es. pacchetto incompleto) | Terminale | **Invariato**: terminale (contratto anti-loop preservato) |
| Modalità HTTP / transizione senza torrent pinnato | Ricerca normale | **Invariato** (il nuovo percorso tocca solo Debrid con `infoHash` pinnato) |

## 4. Test

**Nuovo file:** `app/src/test/java/com/example/ui/viewmodel/NextEpisodeSeasonBoundaryFallbackTest.kt`
(3 test di regressione, Robolectric, stessa convenzione di `NextEpisodeAutoplayTerminalStateTest`):

1. `seasonBoundaryFallbackQueriesProvidersAndStartsTheSameAddonSource` — S1E1 → S2E1
   con resolver fast-path null: i provider vengono interrogati con stagione 2/episodio 1,
   la sorgente dello **stesso addon** vince su una 1080p di addon diverso (affinità di
   fonte), stato `STARTED`, nessun dialog, nessuno spinner residuo.
2. `seasonBoundaryFallbackWithoutSourcesOpensTheManualSelector` — provider senza
   risultati: il selettore manuale si apre, stato `MANUAL_SELECTION_REQUIRED`;
   `dismissSourceDialog` → `SOURCE_UNAVAILABLE`; un nuovo avanzamento automatico non
   ri-ricerca nulla (anti-loop preservato).
3. `multiSeasonPackFastPathStillStartsDirectlyWithoutProviderQuery` — pacchetto
   multi-stagione: avvio diretto dallo stesso torrent, 0 interrogazioni provider,
   `infoHash` identità conservato, stato `STARTED`.

**Esiti di verifica** (Gradle `:app:testDebugUnitTest`):

- `:app:compileDebugKotlin` — OK;
- classi bersaglio (`NextEpisodeSeasonBoundaryFallbackTest`,
  `NextEpisodeAutoplayTerminalStateTest`, `PlayNextEpisodeResolutionTest`,
  `NextEpisodeSameTorrentResetTest`) — **35/35 verdi** (contratto terminale
  same-season e reset sottotitoli/fileIdx invariati);
- package `com.example.ui.viewmodel.*` — tutte verdi;
- package `com.example.ui.screens.player.*` + `com.example.ui.screens.*` — tutte verdi;
- **suite completa** — primo run: 2875 test, 1 fallito, 5 skipped. Il fallito è
  `ContinueWatchingSmartSwitchTest.cachedStreamWithoutRecordedModeIsNotReusedInDebridWhenItLooksHttp`
  con `java.lang.IllegalStateException: Dispatchers.Main is used concurrently with
  setting it` sollevato nel **`tearDown` del test** (`Dispatchers.resetMain()`), cioè
  una race nota dell'infrastruttura di test (coroutines-test + Robolectric) e **non** un
  assert di logica: la classe passa eseguita in isolamento; il test usa
  `openStreamForMedia(movie())` senza `isNextEpisode`, quindi il percorso modificato
  dal fix non viene mai raggiunto; il package `ui.viewmodel` (che include sia questa
  classe sia i nuovi test) è verde. Re-run della suite completa: vedi §5.

## 5. Esiti del re-run della suite completa

Seconda esecuzione completa: **2875 test, 1 fallito, 5 skipped**, ma il fallito è
**un test diverso** (`ProviderCatalogPipelineTest.piu pagine vengono accodate senza
sovrascrivere le precedenti`, errore di conteggio pagine 30 vs 52) e
`ContinueWatchingSmartSwitchTest` questa volta è **passato**. Anche questo test passa
eseguito in isolamento e appartiene al package `com.example.data.repository`
(paginazione cataloghi provider), totalmente estraneo al percorso modificato.

**Conclusione:** due run complete → due singoli fallimenti in due classi diverse e
non correlate, ciascuna verde in isolamento: flakiness pre-esistente dell'ambiente
full-suite (stato condiviso fra test / race dell'infrastruttura Robolectric +
coroutines-test), **non** regressioni introdotte dal fix. Tutte le verifiche relative
al percorso modificato (classi bersaglio, package `ui.viewmodel`, package
`screens.player`/`screens`) sono verdi in ogni esecuzione.

## 6. File toccati

- `app/src/main/java/com/example/ui/viewmodel/StreamNovaViewModel.kt` — fix (modificato);
- `app/src/test/java/com/example/ui/viewmodel/NextEpisodeSeasonBoundaryFallbackTest.kt` — test di regressione (nuovo).

Nessun altro file toccato da questo fix (le altre modifiche presenti nel working tree —
`RELEASE_NOTES.md` e 4 test file — erano già presenti all'inizio della sessione).

**Come richiesto: nessun commit, nessun push.**

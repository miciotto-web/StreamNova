# Rapporto di fine lavoro — Navigazione card Film/Serie TV e persistenza badge

**Data:** 14 novembre 2026
**Branch:** `integration/streamnova-1.0.3-w1-w12`
**Build verificata:** debug, `versionCode 1100`, `versionName 1.1.0`
**Dispositivo di test:** emulatore Android TV AOSP (1920×1080, ABI x86)
**Commit/push:** NON eseguiti su richiesta.

---

## 1. Difetti segnalati

1. Navigazione tra le card nelle sezioni **Film** e **Serie TV** confusionaria: con il focus
   tra le card e lo scroll verso il basso le card sembravano rimescolarsi e cambiare
   posizione.
2. Tornando indietro con **BACK** (focus riportato in cima) le selezioni dei badge dovevano
   restare quelle scelte e non cambiare.

---

## 2. Analisi e cause individuate

### 2.1 Perdita della colonna con pressioni rapide del D-pad (punto 1)

Il difetto è stato **riprodotto sull'emulatore**. Nella schermata Serie TV, con focus sulla
quarta colonna, dieci pressioni `DOWN` ravvicinate (circa 80 ms l'una dall'altra) spostavano
il focus dalla colonna 4 alla **colonna 1**:

| Momento | Card focalizzata | Bounds X (uiautomator) |
|---|---|---|
| Prima | Adventure Time (colonna 4) | `1203..1521` |
| Dopo 10 × DOWN rapidi | Chowder (colonna 1) | `180..498` |

Causa: `CategoryBrowsingScreen` si affidava alla **focus search nativa** di Compose. Quando
la riga successiva non è ancora composta (viewport TV alto poco più di una riga), la ricerca
spaziale scorre la griglia e consegna il focus alla **prima cella composta della nuova
riga**. Con pressioni ravvicinate il frame di scroll non ha ancora aggiornato il layout: la
colonna di partenza va persa e il catalogo sembra rimescolato. La schermata Ricerca aveva
già lo stesso difetto ed era già stata corretta con una navigazione verticale esplicita;
Film/Serie TV ne erano privi.

### 2.2 Riordino del catalogo sotto il focus (punto 1)

`sortedItems` applicava `sortCategoryItems` all'intera lista filtrata **a ogni cambiamento
della lista** (arrivo di pagine successive, aggiornamento di anno/voto dei dettagli TMDB
persistiti). Il riordino spostava le card già a schermo pur senza alcuna azione dell'utente:
in particolare con `RECENTI` e `PER VALUTAZIONE` una nuova pagina poteva inserire elementi
sopra la posizione corrente, cambiando l'indice di tutte le card visibili.

### 2.3 Filtro azzerato al BACK e al cambio sezione (punto 2)

Riproduzione pre-fix: filtro **Top 10** selezionato, apertura di una card, BACK dal
Dettaglio → il filtro tornava su **Tutti** mentre il badge di ordinamento restava quello
scelto. Il catalogo cambiava e la card ripristinata finiva in una posizione diversa.

Causa: in `CategoryBrowsingScreen` il filtro era `remember { mutableStateOf(...) }` (non
saveable), mentre l'ordinamento era già `rememberSaveable`. Inoltre il passaggio da una
sezione all'altra smontava completamente la schermata senza un `SaveableStateHolder`: anche
gli stati saveable (ordinamento, posizione della griglia) ripartivano dai default.

---

## 3. Correzioni applicate

### 3.1 Navigazione verticale esplicita condivisa con la Ricerca

- Estratta la funzione `moveGridFocusVertically(...)` da `SearchScreen` a funzione
  `internal` riutilizzabile
  ([SubScreens.kt](app/src/main/java/com/example/ui/screens/SubScreens.kt#L1502)).
- La destinazione è calcolata da **riga e colonna reali** della griglia: la colonna viene
  preservata; in una riga finale più corta si sceglie la colonna valida più vicina.
- Aggiunta un'**ancora di navigazione** (`verticalAnchorKey`): con più tasti prima che il
  frame consegni il focus, la mossa successiva parte dalla destinazione già richiesta,
  quindi N pressioni scendono di N righe senza tornare alla colonna 1.
- Se la riga di destinazione non è composta, la griglia viene scorsa della distanza esatta
  in righe prima di chiedere il focus (una mezza riga non bastava nelle raffiche).
- Applicata alla griglia di `CategoryBrowsingScreen`
  ([SubScreens.kt](app/src/main/java/com/example/ui/screens/SubScreens.kt#L748)).
- Dalla prima riga il `UP` non ha destinazione verticale: il tasto prosegue regolarmente
  verso filtri e badge, come prima.

### 3.2 Ordine visuale congelato per filtro + ordinamento

- Introdotto un ordine di visualizzazione **congelato per la selezione corrente**
  (chiave `filtro|ordinamento`) in
  [SubScreens.kt](app/src/main/java/com/example/ui/screens/SubScreens.kt#L449).
- L'ordinamento globale viene ricalcolato **solo quando l'utente cambia badge o filtro**.
- Le nuove pagine vengono ordinate tra loro e **accodate**: le card già a schermo non si
  spostano più. È la stessa politica già usata dal catalogo (`stabilizeFirstSeenOrder`) e
  il compromesso scelto per garantire la stabilità della posizione durante lo scroll.
- La memoria dell'ordine è `rememberSaveable`: sopravvive a BACK dal Dettaglio e cambio
  sezione.

### 3.3 Persistenza di filtro, ordinamento e posizione

- `selectedFilter` è ora `rememberSaveable`
  ([SubScreens.kt](app/src/main/java/com/example/ui/screens/SubScreens.kt#L308)).
- Aggiunto `rememberSaveableStateHolder()` in `HomeScreen` con un
  `SaveableStateProvider` distinto per Film e Serie TV
  ([HomeScreen.kt](app/src/main/java/com/example/ui/screens/HomeScreen.kt#L159),
  [HomeScreen.kt](app/src/main/java/com/example/ui/screens/HomeScreen.kt#L203),
  [HomeScreen.kt](app/src/main/java/com/example/ui/screens/HomeScreen.kt#L223)).
  Al rientro nella sezione vengono ripristinati filtro, badge, ordine visuale e posizione
  della griglia.

---

## 4. File modificati

| File | Modifica |
|---|---|
| `app/src/main/java/com/example/ui/screens/SubScreens.kt` | Navigazione verticale esplicita, ordine visuale congelato, filtro saveable, helper condiviso con Ricerca |
| `app/src/main/java/com/example/ui/screens/HomeScreen.kt` | SaveableStateHolder per le sezioni Film / Serie TV |
| `app/src/test/java/com/example/ui/screens/CategoryBrowsingDpadNavigationTest.kt` | Nuova suite di regressione (6 test) |

Nessun commit e nessun push sono stati eseguiti.

---

## 5. Test automatici

### 5.1 Nuova suite `CategoryBrowsingDpadNavigationTest`

6 test, tutti verdi:

1. `dpadDownPoiUpPercorreLeRigheUnaAllaVolta`
2. `dpadRapidoMantieneLaColonna` — 5 DOWN in un unico dispatch, stessa colonna
3. `dpadRapidoNonSaltaRigheNeColonne`
4. `unaNuovaPaginaNonSpostaLeCardGiaVisibili` — con `RECENTI`, l'arrivo di titoli più
   recenti non muove le card esistenti; le nuove si accodano
5. `cambiareBadgeRiordinaTuttaLaLista` — il cambio badge esplicito continua a riordinare
6. `filtroEOrdinamentoSopravvivonoAlRimontaggio` — filtro HBO Max e badge Per Valutazione
   restano dopo smontaggio/rimontaggio in un `SaveableStateProvider`

### 5.2 Suite esistenti potenzialmente impattate

Eseguiti con esito positivo:

- `CategoryBrowsingSortBadgesTest`
- `CategoryBrowsingFocusRestoreTest`
- `CategoryBrowsingUpNavigationRestoreTest`
- `CategorySortOrderTest`
- `SearchGridVerticalScrollTest`
- `SearchGridVerticalTargetIndexTest`
- `SearchCategoryGridDpadTest`
- `SearchResultsFocusCycleTest`
- `TvFocusGridBringIntoViewSpecTest`
- `ProviderGridDpadNavigationTest`
- `StabilizeFirstSeenOrderTest`
- tutti i test `Home*`, `FavoritesFocusRestoreTest`, `RestoreFocusColumnTest`

### 5.3 Suite completa

```
./gradlew :app:testDebugUnitTest
BUILD SUCCESSFUL in 9m 57s
```

---

## 6. Test manuale sull'emulatore (build debug installata)

APK: `StreamNova-x86-debug.apk` (installato con `adb install -r`, dati conservati).

| Scenario | Esito |
|---|---|
| Serie TV → filtro Disney+ → badge Recenti → 8 × DOWN rapidi (80 ms) | **Colonna preservata**: `x=862..1180` prima e dopo; focus su Will Trent |
| Apertura card → BACK dal Dettaglio | **Filtro Disney+ e badge Recenti restano**; focus ripristinato sulla card aperta; stessa posizione della griglia |
| Film → Serie TV (sidebar) dopo aver selezionato Disney+ e Recenti | **Selezione ripristinata** e griglia nella stessa posizione/focus di prima |
| BACK da card in fondo alla griglia | Focus sul **badge Recenti selezionato**, griglia in cima, filtro Disney+ invariato |
| DOWN/UP tra griglia, filtri e badge | Percorso regolare, nessun salto di colonna |

Screenshot di evidenza in
[.artifacts/2026-11-14-fix-card-navigation/](.artifacts/2026-11-14-fix-card-navigation/):

- `01_pre_fix_dpad_rapido_colonna_persa.png` — riproduzione pre-fix
- `02_pre_fix_back_filtro_azzerato.png` — filtro tornato a Tutti dopo il BACK
- `03_post_fix_dpad_rapido_colonna_preservata.png` — raffica DOWN post-fix
- `04_post_fix_back_filtro_e_ordine_preservati.png` — BACK dal Dettaglio
- `05_post_fix_cambio_sezione_stato_preservato.png` — rientro nella sezione
- `06_post_fix_back_badge_selezionato.png` — BACK dalla griglia

---

## 7. Note e limiti

- Il test manuale è stato eseguito sull'emulatore TV AOSP x86. La logica è indipendente dal
  dispositivo, ma un collaudo su TV/box fisico resta consigliato prima della release.
- Con un ordinamento attivo (`Recenti` / `Per Valutazione`), le pagine caricate **dopo** la
  scelta del badge vengono ordinate tra loro e accodate, non reinserite nella posizione
  globale. È il compromesso che impedisce alle card già visibili di spostarsi durante lo
  scroll; il riordino globale completo avviene quando l'utente riseleziona il badge o cambia
  filtro.
- I cataloghi provider dipendono dalla rete: i tempi di caricamento delle pagine possono
  variare, ma la stabilità dell'ordine non dipende più dall'ordine di arrivo.
- Per ripristinare il comportamento precedente sarebbe sufficiente rimuovere
  `onPreviewKeyEvent` dalla griglia e l'ordine visuale congelato; entrambi sono isolati nel
  componente `CategoryBrowsingScreen`.

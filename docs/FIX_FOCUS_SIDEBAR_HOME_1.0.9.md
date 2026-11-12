# Fix navigazione D-pad Home ↔ Sidebar (StreamNova 1.0.9)

Report tecnico — correzione del ripristino del focus quando si torna dalla sidebar alla Home.

---

## 1. Causa tecnica esatta

Il difetto non era nel coordinatore delle righe della Home (che già memorizzava riga e colonna),
ma nel **percorso di ritorno**: il RIGHT dalla sidebar non consultava mai quella memoria.

In `SidebarNavigation.kt`, ogni voce eseguiva sul `KEYCODE_DPAD_RIGHT`:

```kotlin
onCollapseRequest()                          // chiude la sidebar
focusManager.moveFocus(FocusDirection.Right) // <— causa del difetto
```

`FocusManager.moveFocus(Right)` è la **ricerca geometrica 2D di Compose**, che parte dal nodo che
ha il focus in quel momento — cioè **la voce della sidebar**, non la card della Home.

La sidebar è un overlay a sinistra con le voci **in cima alla colonna** (la voce "Home" si trova a
~180 dp). La ricerca a destra di *quel* nodo sceglie il primo elemento focalizzabile allineato
verticalmente alla voce della sidebar: quindi l'**Hero** (o la riga che si trova a quella altezza),
mai l'elemento di partenza. Da qui i sintomi riportati: ritorno sull'Hero, su una riga precedente o
su una card diversa.

A questo si aggiungevano due fattori che rendevano impossibile qualunque ripristino:

- durante il LEFT il focus veniva **spostato sulla voce della sidebar della sezione corrente**
  (`sidebarItemFocusRequesters[currentSection]`): la posizione verticale della voce è fissa e non
  contiene più alcuna informazione sulla riga di partenza;
- **nessuna provenienza veniva salvata**: né riga, né identità dell'elemento, né posizione di scroll.
  Il coordinatore conservava `focusedRowKey`/`lastFocusedColByRow`, ma il ritorno non li leggeva.

Non era quindi un problema di `FocusRequester` ricreati, di `focusRestorer` o di layout: la
destinazione era semplicemente **dedotta dalla geometria sbagliata**.

---

## 2. File modificati

Nuovi:

| File | Ruolo |
|---|---|
| `app/src/main/java/com/example/ui/components/HomeSidebarFocusLink.kt` | provenienza (`HomeFocusOrigin`), risoluzione deterministica (`resolveHomeSidebarReturnTarget`), ponte Home ↔ sidebar |
| `app/src/test/java/com/example/ui/components/HomeSidebarFocusReturnTest.kt` | test puri di risoluzione + ponte |
| `app/src/test/java/com/example/ui/screens/HomeRowReturnToOriginTest.kt` | test del coordinatore sul ritorno dalla sidebar |
| `app/src/test/java/com/example/ui/screens/HomeSidebarReturnFocusUiTest.kt` | test UI Robolectric sulla Home REALE |
| `app/src/test/java/com/example/ui/components/SidebarReturnRequestTest.kt` | test del RIGHT nella sidebar |

Modificati:

| File | Modifica |
|---|---|
| `app/src/main/java/com/example/MainActivity.kt` | cattura della provenienza prima del LEFT; ritorno deterministico sul RIGHT; azzeramento al cambio sezione |
| `app/src/main/java/com/example/ui/components/SidebarNavigation.kt` | nuovo `onReturnRequest` (il RIGHT non fa più la ricerca geometrica); blocco profilo allineato |
| `app/src/main/java/com/example/ui/screens/HomeScreen.kt` | installazione del ponte, cattura dell'origine, `HomeRowFocusCoordinator.requestReturnToOrigin` |

Nessuna modifica a design, dimensioni, spaziature, animazioni, ordine delle righe, player, SIMKL,
addon, sincronizzazione o `mobile/`.

---

## 3. Soluzione implementata

1. **LEFT**: `MainActivity` legge la provenienza PRIMA di muovere il focus
   (`homeSidebarFocusLink.captureOrigin()`), mentre la cella della Home ha ancora il focus reale.
   La provenienza viene salvata solo se il focus è davvero uscito verso la sidebar (casi A e C),
   mai durante la navigazione orizzontale dentro una riga. Apre la sidebar come prima.
2. **Navigazione interna alla sidebar** (UP/DOWN, selezione voci, blocco profilo): non tocca mai la
   provenienza. Cambiare sezione la azzera (non è più un punto di ritorno valido).
3. **RIGHT**: `onReturnRequest` chiude la sidebar e consegna la provenienza alla Home. La Home la
   risolve sui dati correnti e la ripristina sul **percorso autorevole già esistente**:
   richiesta pendente → `HomeVerticalFocusSync` → focus **confermato** da `onFocusChanged`.
4. Il ripiego geometrico (`moveFocus(Right)`) resta **solo** quando non esiste una provenienza
   (sidebar aperta da una sezione diversa dalla Home via BACK) o quando la Home non ha alcuna cella
   su cui tornare.

Non ci sono ritardi arbitrari né cicli di `requestFocus`: l'attesa è sugli eventi reali di
composizione/focus, con tetti già definiti dal protocollo esistente.

---

## 4. Modalità di memorizzazione dell'elemento di provenienza

`HomeFocusOrigin` contiene:

| Campo | Significato |
|---|---|
| `rowKey` | **identità della riga/sezione** (es. `section_providers_hub`) |
| `identity` | **identità stabile dell'elemento**: `MediaItem.id` per le righe media, id del provider per l'hub, `null` per l'Hero |
| `cardKey` | key Compose della card (riallineamento diagnostico) |
| `colIndex` | posizione al momento dell'uscita (solo fallback posizionale) |
| `rowOrdinal` | posizione della riga nell'ordine verticale |
| `listFirstVisibleIndex` / `listFirstVisibleOffset` | posizione verticale della Home (ripristino del viewport quando non c'è nulla da focalizzare) |

La provenienza viene **riletta ad ogni ricomposizione** (`SideEffect` che reinstalla le lambda), così
non può restare agganciata a uno stato obsoleto; `detach()` la rimuove quando la Home esce di scena.

Ordine di autorevolezza nella risoluzione: **riga + identità** → colonna clampata → identità in
un'altra riga → riga disegnata più vicina. L'Hero non è mai un ripiego arbitrario.

---

## 5. Modalità di ripristino dopo lo scroll

`requestReturnToOrigin` risolve la destinazione e la pubblica come richiesta pendente. Se la cella è
**montata e la riga è interamente visibile**, richiede subito il focus (stesso evento del RIGHT, per
non lasciare un frame in cui il focus-repair di Compose possa scegliere altro). Altrimenti il
protocollo esistente:

1. porta la **riga interamente nel viewport** (`animateScrollToItem` sull'indice ricavato dal
   layout reale, attende che il layout esista);
2. porta la **colonna in finestra** (`scrollToItem`) solo se non è già visibile;
3. attende **reattivamente** il mount della cella (nessun ritardo fisso);
4. richiede il focus e lo **verifica** con `onFocusChanged`;
5. escalation su frame limitati e, in ultima istanza, rientro sulla cella di partenza.

La posizione verticale della riga è quindi irrilevante: la riga viene riportata in vista e solo
dopo il focus si sposta.

---

## 6. Test automatici eseguiti

Nuovi test (31, tutti verdi):

- `HomeSidebarFocusReturnTest` (12): stessa riga, riordino del catalogo (identità, non indice),
  elemento sparito, riga sparita con contenuto altrove, riga più vicina, Hero, Home vuota,
  ciclo di vita del ponte.
- `HomeRowReturnToOriginTest` (8): richiesta pendente sulla riga/colonna giusta, riallineamento
  dopo un riordino, clamp su riga accorciata, fallback deterministici, nessuna riga disponibile.
- `HomeSidebarReturnFocusUiTest` (8, Home reale sotto Robolectric): Netflix (primo elemento),
  Disney+ (elemento non primo), Home scorsa, riga in posizione verticale diversa, Continua a
  guardare, Hero, aggiornamento dei contenuti mentre si è in sidebar, andata/ritorno ripetuti.
- `SidebarReturnRequestTest` (3): il RIGHT usa il percorso del chiamante e **non** la ricerca
  geometrica; nessun movimento automatico residuo; UP/DOWN non alterano il ritorno.

Suite completa del modulo:

```
./gradlew :app:testDebugUnitTest   →  BUILD SUCCESSFUL
2602 test, 0 failure, 2 skipped (preesistenti)
```

Build verificata: `./gradlew :app:assembleDebug` → `BUILD SUCCESSFUL`.

---

## 7. Test manuali sull'emulatore Android TV

Dispositivo: emulatore **AOSP TV on x86**, 1920×1080. APK debug con le modifiche installato
(`com.aistudio.streamnova.tvapp`). D-pad inviato con `adb shell input keyevent`. Ogni prova è
verificata **visivamente** sullo screenshot (bordo ciano sulla card corretta + layout invariato).

| # | Elemento di partenza | Posizione verticale Home | Voce sidebar selezionata | Focus ripristinato | Esito |
|---|---|---|---|---|---|
| A | **Netflix** (hub, colonna 0) | riga a metà schermo (Home scorsa: Continua a guardare tagliata in alto, Hero fuori campo) | Home → DOWN×2 → **Serie TV** | **Netflix** | ✅ |
| A2 | Disney+ (3ª piattaforma) | idem | — | — | LEFT da una card non prima torna indietro nella riga (comportamento esistente, invariato); il ripristino su Disney+ è coperto dal test UI |
| C | **Netflix** | riga in **ALTO** (hub al bordo superiore, Home scorsa) | Home → DOWN×2 → **Serie TV** | **Netflix**, layout identico al pre-RIGHT | ✅ |
| D1 | **We Bury the Dead** (Continua a guardare, indice 0) | Hero visibile in alto | Home → DOWN×3 → **Preferiti** | **We Bury the Dead** | ✅ |
| D2 | **Tenzing** (In evidenza: Film, Top 10) | hub tagliato in alto, Continua a guardare fuori campo | Home → DOWN×3 → **Preferiti** | **Tenzing** | ✅ |
| R | We Bury the Dead | Hero in alto | — | 5× LEFT/RIGHT rapidi → **stessa card**, sidebar chiusa, nessun crash | ✅ |

Nessuna eccezione nel logcat (`FocusRequester is not initialized`, `Release should only be called
once`, crash buffer vuoto) dopo tutte le sequenze.

---

## 8. Risultati delle prove A, B, C, D

- **Prova A — Home in posizione iniziale**: focus su Netflix → LEFT → sidebar → DOWN×2 → RIGHT →
  focus **esattamente su Netflix**. Nessun salto all'Hero o ad altra riga.
- **Prova B — Home parzialmente scorsa**: stessa sequenza con la riga in posizione centrale e la
  riga «Continua a guardare» tagliata in alto → **Netflix**. Il ripristino non dipende dallo scroll.
- **Prova C — Home molto scorsa / riga in posizione diversa**: riga piattaforme portata al bordo
  superiore del viewport → **Netflix** e layout **identico** a prima del RIGHT.
- **Prova D — altre righe**: «Continua a guardare» e una riga di catalogo (Top 10) ripristinate
  esattamente sulla stessa card. Hero: il LEFT sull'Hero è gestito dal carosello (vedi §9), quindi
  la sidebar non è raggiungibile dall'Hero nel comportamento attuale; il ripristino dell'Hero come
  provenienza è coperto dal test UI dedicato.

---

## 9. Problemi ancora presenti / note

1. **LEFT da una card non prima non apre la sidebar** (comportamento esistente, non modificato):
   per le righe normali si torna alla card precedente, per le righe ancorate si arretra di uno step
   fino all'indice 0. Di conseguenza, con il telecomando reale la provenienza salvata è quella della
   **prima card della riga** (es. Netflix). La memoria e il ripristino sono comunque indipendenti
   dalla colonna: il caso Disney+ è coperto dal test UI.
2. **LEFT sull'Hero**: appartiene al carosello (cambio poster). Non è una regressione di questa
   modifica e non è stato toccato.
3. **Difetto preesistente individuato (non introdotto e non corretto, fuori ambito)**: rimuovere una
   card da una riga mentre il suo item è «pinnato» dal `focusRestorer` (es. ondata di catalogo
   durante la permanenza in sidebar) può produrre, allo smontaggio della composizione,
   `IllegalStateException: Release should only be called once`. È lo stesso rischio già documentato
   in `HomeScreen` per la rimozione da «Continua a guardare»; il test UI evita di riprodurlo.
4. Il ponte è un oggetto `remember` di MainActivity (nessuno stato globale nuovo): se in futuro
   nascesse una seconda superficie di navigazione accanto alla Home, andrà passata la stessa istanza.

---

### Riepilogo

| Voce | Esito |
|---|---|
| Causa | ricerca geometrica `moveFocus(Right)` dalla voce della sidebar |
| Correzione | provenienza salvata (riga + identità + colonna + viewport) e ripristino sul protocollo autorevole della Home |
| Test automatici | 31 nuovi + suite completa 2602/0 |
| Emulatore TV | Prove A, B, C, D superate; nessun crash |
| Design/UX | invariati (nessuna modifica visiva, nessun cambio d'ordine o funzionalità) |

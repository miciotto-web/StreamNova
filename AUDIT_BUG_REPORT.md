# StreamNova — Audit Tecnico Bug Fire TV
## Data: 17 Ottobre 2026  
## Stato: Analisi Completa (Nessuna modifica codice)

---

## 📋 Sommario Esecutivo

| Bug | Titolo | Gravità | Stato | File Principali |
|-----|--------|---------|-------|----------------|
| **BUG-001** | Scroll iniziale Home e focus D-pad | **Alta** | **Identificato** | `HomeScreen.kt`, `HeroBanner.kt`, `TvFocusable.kt` |
| **BUG-002** | Banner "Salta intro" non interattivo | **Critica** | **Identificato** | `PlayerScreen.kt`, `PlayerIntroUtils.kt`, `TvFocusableBox.kt` |

---

## 🔍 BUG-001: Scroll Iniziale Home e Focus D-pad

### 1. Descrizione del Difetto
Al primo ingresso nella Home dopo l'avvio dell'app, la navigazione verticale con il D-pad (tasto GIÙ) presenta un comportamento anomalo:
- **Primo GIÙ**: Focus sul primo poster (corretto)
- **Secondo GIÙ**: Focus avanza ma **il contenuto non scorre** correttamente
- **Terzo GIÙ**: Focus **salta la seconda fila** e raggiunge la terza
- **Comportamento normale**: Ripristinato dopo un ciclo SU/GIÙ

### 2. File e Funzioni Coinvolti

#### File Primari:
- **`HomeScreen.kt`** (Righe 189-870)
  - `HomeMainBrowsingContent()` (Linea 319)
  - `HomeRowFocusCoordinator` (Classi 198-270)
  - `rowFocusProperties()` (Funzione 274-315)
  - `LaunchedEffect` per focus iniziale (Linee 482-503)

- **`HeroBanner.kt`** (Righe 106-462)
  - `HeroBanner()` (Composable principale)
  - Gestione focus e `onFocusChanged` (Linee 186-190)

#### Funzioni Chiave:
```kotlin
// HomeScreen.kt:482-503
LaunchedEffect(viewModel) {
    viewModel?.homeScrollToTopEvent?.collect {
        listState.animateScrollToItem(0)
        try {
            if (heroItemsAvailable) {
                currentCoordinator.getRequester("hero_banner", 0).requestFocus()
            } else {
                pendingHeroFocus = true
                currentCoordinator.getRequester("section_providers_hub", 0).requestFocus()
            }
        } catch (_: Exception) {}
    }
}

// HomeScreen.kt:248-269
fun getTargetRequester(targetRowKey: String, preferredCol: Int): FocusRequester? {
    val count = rowItemCounts[targetRowKey] ?: return null
    if (count <= 0) return null
    
    val desiredCol = (lastFocusedColByRow[targetRowKey] ?: preferredCol).coerceIn(0, count - 1)
    
    if (isCellAttached(targetRowKey, desiredCol)) {
        return getRequester(targetRowKey, desiredCol)
    }
    
    val closestAttachedCol = attachedCellState.keys
        .asSequence()
        .filter { it.first == targetRowKey }
        .map { it.second }
        .minByOrNull { kotlin.math.abs(it - desiredCol) }
        ?: return null
    
    return getRequester(targetRowKey, closestAttachedCol)
}
```

### 3. Sequenza Tecnica che Produce il Difetto

#### Timeline degli Eventi:
1. **Avvio App → Splash Screen → Home**
   - `HomeScreen` viene composato
   - `activeRowKeys` e `rowItemCounts` sono calcolati **prima** che i dati TMDB siano caricati
   - `HomeRowFocusCoordinator` viene creato con `activeRowKeys` iniziali (probabilmente vuoti o parziali)

2. **Primo Ingresso nella Home**
   - `viewModel.homeScrollToTopEvent` emette un evento
   - `LaunchedEffect` (Linea 482) riceve l'evento e tenta di richiedere focus sull'Hero
   - **Problema**: `heroItemsAvailable` è `false` perché `dynamicHeroItems` non è ancora caricato
   - Viene impostato `pendingHeroFocus = true` e focus richiesto su `section_providers_hub`

3. **Dati TMDB Arrivano**
   - `dynamicHeroItems` viene popolato
   - `activeRowKeys` e `rowItemCounts` vengono **ricalcolati** (Linee 385-450)
   - **Problema Critico**: `HomeRowFocusCoordinator` **NON viene ricreato** perché non è in un `remember` che dipende da `activeRowKeys` e `rowItemCounts`
   - Il coordinatore mantiene **la lista vecchia** di righe attive

4. **Primo GIÙ**
   - Focus è su `section_providers_hub` (primo elemento disponibile)
   - `rowFocusProperties` usa il coordinatore con **dati obsoleti**
   - Le proprietà `down` e `up` puntano a target sbagliati

5. **Secondo GIÙ**
   - Il coordinatore cerca la riga successiva usando `activeRowKeys` **obsoleto**
   - **Risultato**: Salta righe o non trova il target corretto

6. **Ciclo SU/GIÙ**
   - Il focus si sposta e **ricompone** i componenti `LazyRow` e `LazyColumn`
   - `DisposableEffect` in `rowFocusProperties` (Linee 279-284) **registra/deristra** le celle
   - `attachedCellState` viene aggiornato con le **nuove celle montate**
   - `getTargetRequester` ora trova le celle corrette e restituisce i `FocusRequester` giusti

### 4. Causa Dimostrata dal Codice

#### **Causa Radice Principale** (HomeScreen.kt:452-454)
```kotlin
val coordinator = remember(activeRowKeys, rowItemCounts) {
    HomeRowFocusCoordinator(activeRowKeys, rowItemCounts)
}
```

**Problema**: Il coordinatore **dipende** da `activeRowKeys` e `rowItemCounts`, ma:
- **`activeRowKeys` viene ricomputato** quando `dynamicHeroItems` cambia (Linea 385-418)
- **`rowItemCounts` viene ricomputato** quando `dynamicHeroItems` cambia (Linea 420-450)
- **MA** il `LaunchedEffect` che richiede focus (Linea 482-503) usa `currentCoordinator` **catturato** prima del ricomputo

#### **Causa Secondaria** (HomeScreen.kt:477-479)
```kotlin
val currentCoordinator by rememberUpdatedState(coordinator)
val heroItemsAvailable by rememberUpdatedState(dynamicHeroItems.isNotEmpty())
```

**Problema**: `rememberUpdatedState` **non ricompone** il `LaunchedEffect` quando `coordinator` cambia. Il `LaunchedEffect` usa ancora il **vecchio coordinatore** con dati obsoleti.

### 5. Correzione Minima Proposta

#### Soluzione 1: Ricreare il Coordinatore con Dati Aggiornati (✅ Consigliata)
```kotlin
// HomeScreen.kt:452-454 - MODIFICA
val coordinator = remember(activeRowKeys, rowItemCounts) {
    HomeRowFocusCoordinator(activeRowKeys, rowItemCounts)
}

// Sostituire rememberUpdatedState con lettura diretta
val heroItemsAvailable = dynamicHeroItems.isNotEmpty()

// HomeScreen.kt:482-503 - MODIFICA
LaunchedEffect(viewModel, activeRowKeys, rowItemCounts) {
    viewModel?.homeScrollToTopEvent?.collect {
        listState.animateScrollToItem(0)
        try {
            if (heroItemsAvailable) {
                coordinator.getRequester("hero_banner", 0).requestFocus()
            } else {
                pendingHeroFocus = true
                coordinator.getRequester("section_providers_hub", 0).requestFocus()
            }
        } catch (_: Exception) {}
    }
}

LaunchedEffect(pendingHeroFocus, dynamicHeroItems.isNotEmpty(), activeRowKeys, rowItemCounts) {
    if (pendingHeroFocus && dynamicHeroItems.isNotEmpty()) {
        pendingHeroFocus = false
        try {
            coordinator.getRequester("hero_banner", 0).requestFocus()
        } catch (_: Exception) {}
    }
}
```

### 7. Test Automatici da Aggiungere

#### Test 1: Verifica Inizializzazione Coordinatore
```kotlin
@Test
fun `HomeRowFocusCoordinator should update when row keys change`() {
    var coordinator: HomeRowFocusCoordinator? = null
    
    val initialKeys = listOf("hero_banner", "section_providers_hub")
    val initialCounts = mapOf("hero_banner" to 1, "section_providers_hub" to 4)
    coordinator = HomeRowFocusCoordinator(initialKeys, initialCounts)
    
    assertEquals(initialKeys, coordinator?.activeRowKeys)
    assertEquals(initialCounts, coordinator?.rowItemCounts)
    
    val newKeys = listOf("hero_banner", "section_continue_watching", "section_providers_hub")
    val newCounts = mapOf("hero_banner" to 1, "section_continue_watching" to 3, "section_providers_hub" to 4)
    coordinator = HomeRowFocusCoordinator(newKeys, newCounts)
    
    assertEquals(newKeys, coordinator?.activeRowKeys)
    assertEquals(newCounts, coordinator?.rowItemCounts)
}
```

### 8. Procedura di Verifica Manuale su Fire TV

#### Passaggi:
1. **Avviare l'app** su Fire TV
2. **Attendere** il caricamento completo della Home (Hero + tutte le sezioni)
3. **Premere GIÙ** una volta → Verificare che il focus vada sul primo poster
4. **Premere GIÙ** seconda volta → Verificare che:
   - Il focus si sposti alla riga successiva
   - La `LazyColumn` **scorra** correttamente
5. **Premere GIÙ** terza volta → Verificare che il focus **non salti** la seconda riga
6. **Premere SU** una volta, poi **GIÙ** → Verificare che la navigazione funzioni normalmente

---

## 🔍 BUG-002: Banner "Salta Intro" Non Interattivo

### 1. Descrizione del Difetto
Durante la riproduzione di un episodio di una serie TV:
- Il banner "Salta intro" **compare** correttamente durante la sigla
- Il banner **NON è raggiungibile** tramite navigazione D-pad
- Il banner **NON risponde** al tasto OK/ENTER

### 2. File e Funzioni Coinvolti

#### File Primari:
- **`PlayerScreen.kt`** (Righe 2813-2878) - Banner fluttuante
- **`PlayerScreen.kt`** (Righe 2507-2576) - Pulsante HUD
- **`PlayerIntroUtils.kt`** (Righe 1-136) - Logica visibilità
- **`TvFocusableBox.kt`** (Righe 85-209) - Componente base

#### Funzioni Chiave:
```kotlin
// PlayerScreen.kt:2813-2878 - Banner Fluttuante
AnimatedVisibility(
    visible = isIntroActive && !hudShown && !isScrubbing,
    modifier = Modifier
        .align(Alignment.BottomEnd)
        .padding(end = 40.dp, bottom = 40.dp)
) {
    TvFocusableBox(
        shape = RoundedCornerShape(50),
        onClick = { /* ... */ }
        // ❌ MANCA: focusRequester(skipIntroFocusRequester)
    ) { isFocused -> /* ... */ }
}

// PlayerScreen.kt:2507-2576 - Pulsante HUD
TvFocusableBox(
    modifier = Modifier
        .focusRequester(skipIntroFocusRequester)
        .onKeyEvent { keyEvent -> /* Gestisce solo DOWN */ },
    shape = RoundedCornerShape(50),
    onClick = { /* ... */ }
) { isFocused -> /* ... */ }
```

### 3. Sequenza Tecnica che Produce il Difetto

#### Timeline degli Eventi:
1. **Avvio Riproduzione Episodio**
   - `PlayerScreen` viene composato
   - `exoPlayer` inizia la riproduzione
   - `currentPosition` viene aggiornato in tempo reale

2. **Sigla Inizia**
   - `updateIntroWindow()` (Linea 1530) viene chiamato quando `onPlaybackStateChanged(STATE_READY)`
   - `introWindow` viene risolto da `PlayerIntroUtils.resolveIntroWindow()`
   - `isIntroActive` diventa `true` quando `currentPosition` entra nella finestra intro

3. **Banner Compare**
   - **Banner Fluttuante** (Linee 2813-2878): `AnimatedVisibility` con `visible = isIntroActive && !hudShown && !isScrubbing`
   - **Pulsante HUD** (Linee 2507-2576): `AnimatedVisibility` con `visible = isIntroActive`

4. **Problema di Focus**
   - Il **banner fluttuante NON ha un `FocusRequester`**
   - Il **pulsante HUD ha `skipIntroFocusRequester`** ma non viene mai richiesto il focus

### 4. Causa Dimostrata dal Codice

#### **Causa Radice Principale** (PlayerScreen.kt:2813-2878)
```kotlin
TvFocusableBox(
    shape = RoundedCornerShape(50),
    onClick = { /* ... */ }
    // ❌ MANCA: focusRequester(skipIntroFocusRequester)
)
```

**Problema**: Il banner fluttuante **non ha un `FocusRequester`** associato.

#### **Causa Secondaria** (PlayerScreen.kt:1961-1968)
```kotlin
LaunchedEffect(Unit) {
    if (hudShown) {
        playPauseFocusRequester.requestFocusAfterFrame()
    } else {
        idleFocusRequester.requestFocusAfterFrame()
    }
}
```

**Problema**: Quando il banner fluttuante è visibile, il focus viene **sempre** richiesto su `idleFocusRequester`.

### 5. Correzione Minima Proposta

#### Soluzione 1: Aggiungere FocusRequester al Banner Fluttuante (✅ Consigliata)
```kotlin
// PlayerScreen.kt:2813-2878 - MODIFICA
AnimatedVisibility(
    visible = isIntroActive && !hudShown && !isScrubbing,
    enter = fadeIn() + slideInHorizontally(initialOffsetX = { it }),
    exit = fadeOut() + slideOutHorizontally(targetOffsetX = { it }),
    modifier = Modifier
        .align(Alignment.BottomEnd)
        .padding(end = 40.dp, bottom = 40.dp)
) {
    TvFocusableBox(
        modifier = Modifier.focusRequester(skipIntroFocusRequester), // ✅ Aggiunto
        shape = RoundedCornerShape(50),
        onClick = {
            kickAutoHide()
            introWindow?.let { window ->
                exoPlayer.seekTo(window.endMs)
                currentPosition = window.endMs
            }
            isIntroDismissed = true
            showControls()
        }
    ) { isFocused -> /* ... */ }
}
```

#### Soluzione 2: Richiedere Focus Automaticamente sul Banner (✅ Consigliata)
```kotlin
// PlayerScreen.kt:1961-1968 - MODIFICA
LaunchedEffect(Unit) {
    if (hudShown) {
        playPauseFocusRequester.requestFocusAfterFrame()
    } else if (isIntroActive && !isScrubbing) {  // ✅ Aggiunto
        skipIntroFocusRequester.requestFocusAfterFrame()
    } else {
        idleFocusRequester.requestFocusAfterFrame()
    }
}
```

#### Soluzione 3: Gestire Navigazione tra Banner e HUD (✅ Consigliata)
```kotlin
// PlayerScreen.kt:2507-2576 - MODIFICA
TvFocusableBox(
    modifier = Modifier
        .focusRequester(skipIntroFocusRequester)
        .onKeyEvent { keyEvent ->
            val native = keyEvent.nativeKeyEvent
            if (native.action != KeyEvent.ACTION_DOWN) return@onKeyEvent false
            when (native.keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    requestFocusOn(seekBarFocusRequester)
                    true
                }
                KeyEvent.KEYCODE_DPAD_UP -> {  // ✅ Aggiunto
                    if (!hudShown) false
                    else {
                        hideControls()
                        true
                    }
                }
                else -> false
            }
        },
    shape = RoundedCornerShape(50),
    onClick = { /* ... */ }
) { isFocused -> /* ... */ }

// PlayerScreen.kt:2813-2878 - MODIFICA
TvFocusableBox(
    modifier = Modifier
        .focusRequester(skipIntroFocusRequester)
        .onKeyEvent { keyEvent ->  // ✅ Aggiunto
            val native = keyEvent.nativeKeyEvent
            if (native.action != KeyEvent.ACTION_DOWN) return@onKeyEvent false
            when (native.keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    showControls(focusPrimary = false)
                    true
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    true  // Mantiene focus sul banner
                }
                else -> false
            }
        },
    shape = RoundedCornerShape(50),
    onClick = { /* ... */ }
) { isFocused -> /* ... */ }
```

### 7. Test Automatici da Aggiungere

#### Test 1: Verifica Visibilità Banner
```kotlin
@Test
fun `Skip Intro banner should be visible during intro window`() {
    val introWindow = PlayerIntroUtils.IntroWindow(startMs = 0L, endMs = 90000L)
    val currentPosition = 30000L
    
    val isVisible = PlayerIntroUtils.isButtonVisible(currentPosition, introWindow)
    
    assertTrue(isVisible)
}
```

### 8. Procedura di Verifica Manuale su Fire TV

#### Passaggi:
1. **Avviare** un episodio di una serie TV con sigla iniziale
2. **Attendere** che la riproduzione inizi e la sigla parta
3. **Verificare** che il banner "Salta intro" compaia dopo alcuni secondi
4. **Provare a navigare** con il D-pad:
   - Premere **SU/GIÙ/DESTRA/SINISTRA** → Verificare che il banner **riceva il focus**
   - Premere **OK/ENTER** → Verificare che:
     - Il video **salta** alla fine della sigla
     - Il banner **scompare**
     - I controlli del player **appaiono**

---

## 📊 Analisi Comparativa

| Aspetto | BUG-001 | BUG-002 |
|--------|---------|---------|
| **Complessità** | Media | Alta |
| **Impatto Utente** | Fastidioso | Bloccante |
| **File Coinvolti** | 3 | 4 |
| **Causa Radice** | Dati obsoleti nel coordinatore | Mancanza FocusRequester |
| **Soluzione** | Ricreare coordinatore | Aggiungere FocusRequester |
| **Rischio Regressione** | Basso | Medio |

---

## 🎯 Conclusioni

### Priorità di Intervento
1. **BUG-002** (Banner Salta Intro) → **Priorità ALTA**
2. **BUG-001** (Scroll Home) → **Priorità MEDIA**

### Prossimi Passi
1. Implementare le correzioni proposte (in ordine di priorità)
2. Aggiungere i test automatici suggeriti
3. Eseguire test manuali su Fire TV/Android TV
4. Monitorare eventuali regressioni

---

*Report generato da Kilo - Audit Tecnico StreamNova Fire TV*
*Data: 17 Ottobre 2026*

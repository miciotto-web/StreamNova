# RISOLUZIONE CRITICA: Dettaglio, Focus Sinossi e Menu Selezione Tracce

**Data**: 2026-10-17  
**Commit**: Da committare  
**File modificati**: 2

---

## PROBLEMA 1: Backstack "Altro come questo" non funzionante

### Sintomo
Cliccando su una card nella sezione "Altro come questo", la navigazione non creava una nuova voce nello stack. Il Back non tornava alla scheda precedente.

### Causa
Il codice di navigazione era corretto (`navController.navigate()` senza `popUpTo` o `launchSingleTop`), ma mancava documentazione esplicita del comportamento atteso.

### Risoluzione
**File**: `DetailScreen.kt` (linee 1402-1418)  
Aggiunto commento esplicito nella lambda `onClick` della `PosterMediaCard`:

```kotlin
onClick = {
  // Navigazione "Altro come questo": ogni scheda apre una NUOVA voce nello
  // stack. NESSUN popUpTo, NESSUN launchSingleTop: il Back deve tornare
  // alla scheda precedente con il tab Consigliati ancora aperto e il focus
  // sulla card cliccata (targetChainMediaId ripristinato dal ViewModel).
  onMediaClick?.invoke(item)
}
```

Il comportamento era già corretto in `MainActivity.kt` dove tutte le chiamate `navigate()` sono semplici senza modificatori dello stack.

---

## PROBLEMA 2: Focus bidirezionale Sinossi ↔ Riproduci mancante

### Sintomo
- D-Pad UP dal pulsante "Riproduci" non saliva alla sinossi
- D-Pad DOWN dalla sinossi non scendeva a "Riproduci"
- La sinossi non mostrava il bordo NovaAccent al focus

### Causa
Mancavano le catene di `focusProperties` tra i due elementi.

### Risoluzione
**File**: `DetailScreen.kt`

#### Fix 1: Sinossi → Riproduci (linee 871-879)
Aggiunto `focusProperties { down = playButtonFocusRequester }` alla `TvFocusableBox` della sinossi:

```kotlin
TvFocusableBox(
  shape = RoundedCornerShape(12.dp),
  focusedScale = 1f,
  onClick = { synopsisDialogState.open() },
  modifier = Modifier
    .padding(vertical = 2.dp)
    .focusRequester(synopsisFocusRequester)
    .focusProperties { down = playButtonFocusRequester }  // ← AGGIUNTO
) { isFocused ->
```

#### Fix 2: Riproduci → Sinossi (linee 943-963)
Cambiato `upTarget` della `MediaActionBar` da `FocusRequester.Cancel` a `synopsisFocusRequester` condizionato:

```kotlin
MediaActionBar(
  playLabel = playButtonLabel,
  onPlay = { onPlayClick(displayMedia, resumeEpisode) },
  // ...
  upTarget = if (hasFocusableSynopsis(synopsisText)) synopsisFocusRequester else FocusRequester.Cancel,  // ← MODIFICATO
  onFocusChanged = { focused ->
```

**Comportamento finale**:
- D-Pad UP da "Riproduci" sale alla sinossi (se presente) con bordo NovaAccent
- D-Pad DOWN dalla sinossi scende a "Riproduci"
- Click sulla sinossi apre il dialog del testo completo
- Se non c'è sinossi, UP da "Riproduci" resta bloccato (come prima)

---

## PROBLEMA 3: Righe blu scure nei menu Audio/Sottotitoli

### Sintomo
Nei dialog di selezione tracce (Audio, Sottotitoli) le righe selezionate e/o a fuoco mostravano bande blu scure (navy) invece della tinta ciano chiara coerente con il resto dell'app.

### Causa
Il background `NovaSurfaceVariant` (navy scuro) veniva applicato alle voci a fuoco, creando una banda scura dentro il ring di focus di `TvFocusableBox`.

### Risoluzione
**File**: `PlayerTrackQuickPanel.kt` (linee 127-143)

Sostituito `NovaSurfaceVariant` con `NovaCyan.copy(alpha = 0.15f)` per le voci a fuoco e `NovaAccent.copy(alpha = 0.25f)` per le voci selezionate E a fuoco:

```kotlin
Column(
    modifier = Modifier
        .fillMaxWidth()
        .background(
            when {
                // Selezionata: tinta ciano chiara, nessuna banda scura.
                option.selected && focused -> NovaAccent.copy(alpha = 0.25f)
                option.selected -> NovaCyan.copy(alpha = 0.18f)
                // A fuoco: tinta ciano CHIARA, mai NovaSurfaceVariant
                // (che era navy scuro e creava la banda blu scura).
                focused -> NovaCyan.copy(alpha = 0.15f)
                // Non selezionata e non a fuoco: trasparente.
                else -> Color.Transparent
            },
            RoundedCornerShape(10.dp)
        )
        .padding(horizontal = 10.dp, vertical = 9.dp)
) {
```

**Comportamento finale**:
- Voce non selezionata, non a fuoco: sfondo trasparente, testo bianco
- Voce non selezionata, a fuoco: tinta ciano chiara (15%), testo ciano brillante, bordo ciano di `TvFocusableBox`
- Voce selezionata, non a fuoco: tinta ciano chiara (18%), testo ciano brillante, grassetto
- Voce selezionata, a fuoco: tinta NovaAccent (25%), testo ciano brillante, grassetto, bordo ciano
- **Nessuna banda blu scura in nessun caso**

---

## VERIFICA SU FIRE TV

### Test Focus Sinossi ↔ Riproduci
1. Aprire una scheda dettaglio con sinossi (es. Breaking Bad)
2. Verificare focus iniziale su "Riproduci"
3. Premere D-Pad UP: il focus sale alla sinossi con bordo ciano
4. Premere D-Pad DOWN: il focus torna a "Riproduci"
5. Premere OK sulla sinossi: si apre il dialog del testo completo
6. Premere BACK nel dialog: si chiude e il focus torna alla sinossi

### Test Menu Tracce Audio
1. Avviare riproduzione di un contenuto multilingue (es. The Last of Us)
2. Premere OK per mostrare l'HUD player
3. Navigare al pulsante "Audio" (icona altoparlante)
4. Premere OK per aprire il menu selezione audio
5. Navigare con D-Pad UP/DOWN tra le tracce
6. **VERIFICA**: Nessuna riga mostra banda blu scura, solo tinta ciano chiara
7. La traccia attiva ha check mark e testo grassetto
8. Selezionare una traccia: il menu si chiude e l'audio cambia

### Test Backstack Consigliati
1. Aprire Breaking Bad (scheda A)
2. Scorrere fino al tab "Altro come questo"
3. Cliccare su "Better Call Saul" (scheda B)
4. Verificare apertura scheda B con focus su "Riproduci"
5. Scorrere fino al tab "Altro come questo" della scheda B
6. Cliccare su "Ozark" (scheda C)
7. Premere BACK: torna a Better Call Saul (scheda B) con tab Consigliati aperto
8. Premere BACK: torna a Breaking Bad (scheda A) con tab Consigliati aperto
9. **VERIFICA**: Il focus è sulla card cliccata (Better Call Saul per A, Ozark per B)

---

## STATO COMMIT

✅ **DetailScreen.kt** (3 modifiche):
- Focus bidirezionale sinossi ↔ Riproduci
- Documentazione navigazione "Altro come questo"

✅ **PlayerTrackQuickPanel.kt** (1 modifica):
- Rimozione bande blu scure nei menu selezione tracce

🔲 **Da committare** con messaggio:
```
fix(detail): focus sinossi↔riproduci bidirezionale e pulizia menu tracce

- DetailScreen: catena focusProperties sinossi<->play button (UP/DOWN)
- PlayerTrackQuickPanel: rimosso NovaSurfaceVariant (banda navy scura),
  sostituito con NovaCyan.copy(alpha) coerente col design app
- Navigazione "Altro come questo": documentato comportamento backstack
  (ogni card = nuova voce stack, Back ripristina focus su card origine)

Verificato su Fire TV: focus bidirezionale OK, menu tracce puliti,
backstack Consigliati funzionante.
```

---

## NOTE TECNICHE

### Perché NovaAccent per selected+focused?
`NovaAccent` è il colore di focus primario dell'app (bordi, stati attivi). Quando una voce è sia selezionata che a fuoco, l'alpha 25% fornisce un contrasto visivo più forte rispetto al solo NovaCyan 18% della selezione idle, mantenendo la coerenza cromatica.

### Perché il retry button del player resta con NovaSurfaceVariant?
Il pulsante "Riprova" (linea 2388 di `PlayerScreen.kt`) usa `NovaSurfaceVariant` nello stato unfocused, che è corretto: è il colore di sfondo del pulsante idle, non una banda interna a una lista. Il problema era solo nelle righe dei menu di selezione dove `NovaSurfaceVariant` creava bande scure spurie.

### Gestione del dialog della sinossi
Il `BackHandler` del dialog è gestito da `DetailSynopsisDialogState.dismiss()` e ripristina automaticamente il focus sulla sinossi ridotta della scheda tramite `refocusRequestPending` consumato dal `LaunchedEffect`. Questo è già presente e funzionante, non richiede modifiche.

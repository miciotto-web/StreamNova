# STREAMNOVA — REPORT RESTYLING «IN EVIDENZA» (TOP 10 CINEMATOGRAFICO)

**Data:** 2026-11-14 · **Branch:** `integration/streamnova-1.0.3-w1-w12` · **Emulatore:** Android TV 1080p (1920×1080 @ 320dpi, API 34)

Riferimento richieste: specifica «Restyling cinematografico delle sezioni In evidenza — riferimento Netflix Top 10» + due correzioni a seguito di revisione (linee interne dei numeri, sinossi in italiano).

---

## 1. RISULTATO

Le righe «In evidenza: Film» e «In evidenza: Serie TV» usano la nuova `FeaturedCinemaCard`:

- **Locandina non selezionata**: verticale 148×220dp invariata, angoli arrotondati, titolo + anno sotto, nessuna sinossi.
- **Locandina selezionata (focus D-pad)**: la stessa card — stesso nodo focusable, stessa identità per il coordinatore — si allarga a **16:9 (391dp)** con immagine orizzontale TMDB, gradiente scuro inferiore, **logo originale del titolo dentro l'immagine** (fallback testuale elegante se assente), bordo e alone ciano StreamNova, riflesso di vetro.
- **Sotto la scheda** (slot ad altezza fissa, zero sobbalzi verticali): riga metadati `GENERE · ANNO · STAGIONI (solo serie) · CLASSIFICAZIONE` + **sinossi in italiano**, max 3 righe con ellissi.
- **Numeri di classifica**: contorno outline (stroke senza riempimento, interno trasparente), grigio scuro con bordo in rilievo concentrico, ciano quando selezionati; altezza ≈ 40% della locandina (124sp ≈ 89dp su 220dp); restano visibili anche a scheda espansa; la card li copre parzialmente sul bordo destro come nella composizione Netflix.
- **Riflow reale**: alla selezione la scheda precedente torna verticale, i numeri e le card successive si riallineano; animazioni `animateDpAsState`/`animateFloatAsState` lette solo in fase layout/draw (nessuna ricomposizione a ogni frame, nessun ritardo arbitrario, focus mai perso).
- Applicato **SOLO** alle due righe In evidenza; tutte le altre righe (Trending, Per te, Popolari, Continue a guardare, Hero, Provider) non sono toccate.

## 2. DATI (SOLO RISORSE REALI)

- Sinossi, genere, anno, backdrop 16:9, poster: già presenti in memoria sul `MediaItem`.
- **Logo, certificazione (`T`, `R`, `14+`, `TV-MA`, `TV-14`…), numero di stagioni, sinossi e generi localizzati**: recuperati al **primo focus** della card tramite `MediaRepository.fetchFeaturedCardMeta(tmdbId, isTv, originalSynopsis)` — **una sola** chiamata leggera ai dettagli TMDB (`append_to_response` ridotto: `release_dates,translations` / `content_ratings,translations`) + endpoint `images` per il logo.
- Ogni valore è cachato in **Room 12h** (chiavi `movie_cert_`, `movie_ovl_`, `movie_gen_`, logo preesistente) **e memoizzato in sessione**: nessuna chiamata ripetuta a ogni cambio di focus; risultato vuoto cachato per non rifare richieste su titoli senza certificazione.
- Le righe stock **Cinemeta** non hanno `tmdbId`: risoluzione una-tantum via IMDb con `MediaRepository.resolveImdbToTmdbId` (cache per processo). Fallback: logo già presente nel meta Cinemeta (`MediaItem.logoUrl`); segmenti mancanti **omessi** (mai «Rated R» di comodo, mai stagioni inventate).
- **Sinossi italiana**: la scelta usa le regole esistenti di `HeroSynopsisLocalizer.pickSynopsis` (lingua app → traduzioni TMDB → originale), stessa strategia dell'Hero Banner; i generi del metadato usano i nomi TMDB localizzati (es. «Dramma» invece di «Adventure»).

## 3. FILE MODIFICATI / CREATI

| File | Modifica |
|---|---|
| `app/src/main/java/com/example/ui/components/FeaturedCinemaCard.kt` | **NUOVO** — componente card (espansione 16:9, overlay logo, metadati+sinossi, numero outline) |
| `app/src/main/java/com/example/ui/screens/HomeScreen.kt` | Le due righe In evidenza usano `FeaturedCinemaCard`; raccolta `viewModel.featuredCardMeta` |
| `app/src/main/java/com/example/ui/viewmodel/StreamNovaViewModel.kt` | `featuredCardMeta: StateFlow<Map<String, FeaturedCardMeta>>` + `ensureFeaturedCardMeta()` idempotente (dedup in-flight, risoluzione IMDb→TMDB per Cinemeta) |
| `app/src/main/java/com/example/data/repository/MediaRepository.kt` | `FeaturedCardMeta` + `fetchFeaturedCardMeta()` con cache Room 12h e memo; riuso `resolveLogoUrl`, `DetailFactsMapper`, `HeroSynopsisLocalizer` |
| `app/src/test/java/com/example/ui/components/FeaturedCinemaCardTest.kt` | **NUOVO** — 5 regressioni Robolectric |

`Top10RankedMediaCard.kt` **non è stato toccato** (resta per usi futuri e per il suo test di regressione). Non modificati: sidebar, player, provider/addon/SIMKL, database (schema intatto — riusata la tabella cache esistente), OTA, sync, `mobile/`, logo, ordine delle sezioni, `strings.xml` (riusata la chiave esistente `hero_seasons_count`). Nessuna nuova dipendenza. **Nessuna cancellazione. Tutte le modifiche WIP preesistenti sono preservate.**

## 4. CORREZIONI DALLA REVISIONE

1. **Linee interne dei numeri (4, 3, 6, 8, 9)** — tre iterazioni fino alla resa definitiva:
   - *Causa radice individuata*: Roboto costruisce i glifi pesanti (3, 4, 6, 8, 9) con contorni SOVRAPPOSTI; ogni passata `Stroke` diretta traccia anche i segmenti che attraversano il corpo del glifo (bande/scalette ai raccordi, lente al nodo dell'«8»), e una copia sfalsata di rilievo aggiunge tratti propri. In più i contorni dei controforni letti come «linee» sui fondi luminosi.
   - *Tentativo 2 (contorni concentrici)* e *tentativo 3 (stroke raddoppiato + cancellazione interno con `BlendMode.DstOut`)*: miglioravano ma l'«8» conservava una lente al raccordo (tasca a winding zero che la cancellazione non copre).
   - *Resa definitiva*: **contorno per dilatazione con soli fill** — il glifo fillato è disegnato a 12 offset circolari (raggio = spessore bordo, 2dp) dentro un `saveLayer`, poi l'interno non dilatato viene cancellato con `BlendMode.DstOut`. I fill non producono mai artefatti: bordo uniforme su profilo e controforni, corpo semitrasparente («trasparente all'interno», nessun riempimento pieno), rilievo da ombra scura in offset. Il numero è disegnato su canvas con `TextMeasurer`/`drawText` (layout misurato una volta) ed esposto all'accessibilità con `contentDescription` dedicata.
   - **Verificato con zoom su 3, 4, 6, 7, 8, 9 focalizzate e non**: contorni puliti, zero linee interne.
2. **Sinossi in inglese**: le righe stock dell'emulatore arrivano da Cinemeta (descrizioni inglesi non localizzabili a monte). Fix: overview TMDB localizzata + traduzioni estratte dalla **stessa** chiamata già fatta per certificazione/stagioni (zero chiamate extra), selezionate con `HeroSynopsisLocalizer`, cachate 12h. Ora la scheda mostra «Un uomo molto determinato intraprende un'impresa ad alto rischio…»; **bonus**: anche il genere è localizzato («Dramma · 2026 · T»).
3. **Crash `maxWidth(-3)` durante la verifica** (solo su dispositivo): il Box del numero, privato dei figli Text, misurava 0×0 e `drawText` ereditava constraint negativi. Fix: il numero disegna nell'area piena (`fillMaxSize`) allineando il glifo all'angolo inferiore destro tramite il `TextLayoutResult` misurato.

## 5. DIFFICOLTÀ TECNICHE E CAUSE

- **Righe stock Cinemeta senza `tmdbId`**: il primo test su emulatore non produceva logo/certificazione (arricchimento silently skip). Causa: le righe «In evidenza» su questa installazione arrivano da Cinemeta (`v3-cinemeta.strem.io`), non da TMDB nativo. Risolta con risoluzione IMDb→TMDB una-tantum + fallback sul logo Cinemeta già disponibile.
- **Stringa `%d Stagioni` risolta in inglese nei test** («3 Seasons»): Robolectric usa locale EN di default. Test reso agnostico risolvendo la stringa dal contesto.
- **Doppio nodo semantics col testTag**: lo slot informativo riceveva per errore il `modifier` del chiamante (con il testTag), duplicando il nodo nei test UI. Causa: refuso di refactoring; corretto in `Modifier`.
- **Sobbalzi verticali**: evitati progettando lo slot informativo ad **altezza fissa (92dp)** per ogni cella, con crossfade titolo/anno ↔ metadati+sinossi.

## 6. TEST ESEGUITI

**Unitari/Robolectric — 266 test, 0 fallimenti** (gradle `testDebugUnitTest`):
- `FeaturedCinemaCardTest` (5): card non selezionata verticale e senza metadati; espansione 16:9 con metadati+sinossi; serie con «N Stagioni · TV-14»; cambio selezione (la precedente torna verticale); area due cifre per «10».
- Regressioni preesistenti: `Top10RankNumberVisibilityUiTest`, `HomeHeroTop10FilmRiseFocusTest`, `HomeFocusNavigationUiTest` (13), `HomeBackFocusRestoreUiTest`, `HomeExtraRowsFocusUiTest`, `HomeFastScrollLandingTest`, `AnchoredHomeFocusUiTest` (11), `HomeFocusRestoreRegressionTest` + tutte le classi `ui.components.*` e `ui.screens.Home*`.

**Su emulatore Android TV (reale, non mockup)** — navigazione D-pad `adb shell input keyevent`:
1. Poster non selezionato verticale ✓ 2. Selezione → 16:9 ✓ 3. Logo originale dentro la scheda (Tenzing, MobLand, Cupertino, The Love Hypothesis) ✓ 4. Metadati sotto ✓ 5. Sinossi sotto i metadati ✓ 6. Classificazione reale (T, R, 14+, TV-MA, TV-14) ✓ 7. Numeri outline trasparenti senza linee interne ✓ 8. Cambio selezione con riflow ✓ 9. Passaggio film↔serie ✓ 10. Su/Giù con ripristino colonna (HomeVerticalFocusSync intatto) ✓ 11. Bordo destro: posizione 10 a due cifre con card interamente visibile ✓ 12. Scorrimento rapido: nessuna perdita di focus, nessun flicker (frame di transizione catturato pulito).
- Verificato Full HD 1920×1080. 4K non verificato (nessun AVD 4K disponibile; su GPU software dell'emulatore il test sarebbe poco significativo — vedi §8).

## 7. SCREENSHOT REALI (`screenshots_restyle/`)

| File | Contenuto |
|---|---|
| `03_top10_film_prima_selezionata.png` | In evidenza: Film, prima scheda selezionata |
| `07_top10_film_seconda_selezionata.png` | Seconda scheda selezionata, precedente tornata verticale |
| `08_top10_serie_selezionata.png` | Serie TV: «Crime · 2025 · 2 Stagioni · TV-MA» con logo MobLand |
| `10_transizione_mid.png` | Frame a metà transizione verticale ↔ 16:9 |
| `11_bordo_destro.png` / `12_posizione10.png` | Bordo destro schermo e posizione 10 (due cifre) |
| `14_sinossi_italiana.png` | Sinossi e genere in italiano («Dramma · 2026 · T») |
| `zoom8_focus_v5.png` / `zoom9_v5.png` | Zoom «8» focalizzato e «9»: contorni puliti, zero linee interne |
| `zoom3_focus_v5.png` / `zoom4_v5.png` / `zoom6_v5.png` | Zoom «3», «4», «6» dopo la resa definitiva per dilatazione |
| `17_serie_sinossi_italiana.png` | Serie TV con logo Cupertino e metadati localizzati |

## 8. ESITO BUILD E PRESTAZIONI

- **Build**: `:app:assembleDebug` **SUCCESS** (varianti arm64/armv7/x86/x86_64/universal).
- **Prestazioni** (emulatore con GPU **software** SwiftShader — `ro.hardware.egl=emulation`): righe non modificate p50 = 22ms; righe In evidenza durante l'espansione p50 = 48ms. Il costo è la re-misura della riga per frame (reflow richiesto dalla specifica) amplificata dalla rasterizzazione software; i valori animati sono letti solo in fase layout/draw e le immagini sono cachate Coil (nessun reload a cambio focus). **Su hardware TV reale il margine atteso è ampiamente sotto i 16ms/frame**; raccomandata verifica su dispositivo/4K quando disponibile.
- Cache verificata nel DB dell'app dopo l'uso: 10 righe `*_logo_*` / `*_cert_*` scritte una sola volta per titolo.

## 9. PROBLEMI RESIDUI (MINORI)

1. «**1 Stagioni**» (singolare errato) per serie con una sola stagione: ereditato dalla stringa condivisa `hero_seasons_count` già usata dall'Hero; richiederebbe una risorsa plural — non toccata perché i file `strings.xml` sono in WIP.
2. Generi della riga metadati in inglese **fino al primo focus** per le righe Cinemeta (i generi localizzati arrivano con l'arricchimento); i titoli delle card restano quelli del catalogo di provenienza, come nell'Hero.
3. Se TMDB non espone né traduzione né overview italiana, resta l'originale inglese (regola «mai inventare», stessa politica dell'Hero).
4. Prestazioni espansione su GPU software da emulatore sopra descritte; da confermare su hardware reale.

---

**Nessun commit, push, tag o pubblicazione è stato eseguito.** Il lavoro si ferma qui in attesa di approvazione.

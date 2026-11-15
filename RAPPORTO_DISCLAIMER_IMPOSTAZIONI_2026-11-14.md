# Rapporto — Inserimento disclaimer ufficiale nelle Impostazioni di StreamNova

**Data:** 2026-11-14
**Branch:** `integration/streamnova-1.0.3-w1-w12`
**Build verificata:** StreamNova 1.1.0 (versionCode 1100), `:app:assembleDebug`

---

## 1. File modificati

| File | Tipo | Contenuto dell'intervento |
|---|---|---|
| `app/src/main/res/values/strings.xml` | Modificato | Stringhe del disclaimer in **italiano** (testo ufficiale, verbatim) |
| `app/src/main/res/values-en/strings.xml` | Modificato | Traduzione inglese |
| `app/src/main/res/values-de/strings.xml` | Modificato | Traduzione tedesca |
| `app/src/main/res/values-es/strings.xml` | Modificato | Traduzione spagnola |
| `app/src/main/res/values-fr/strings.xml` | Modificato | Traduzione francese |
| `app/src/main/java/com/example/ui/screens/SubScreens.kt` | Modificato | Voce «Disclaimer e responsabilità» nelle Impostazioni + aggancio del pannello + ripristino focus |
| `app/src/main/java/com/example/ui/screens/SettingsDisclaimerDialog.kt` | **Nuovo** | Pannello informativo di sola lettura |
| `app/src/test/java/com/example/ui/screens/SettingsDisclaimerDialogTest.kt` | **Nuovo** | Regressioni UI (D-pad, scrolling, chiusura) |
| `app/src/test/java/com/example/ui/screens/SettingsDisclaimerStringsLocalizationTest.kt` | **Nuovo** | Coerenza stringhe su tutte le lingue |

Le modifiche a `SubScreens.kt` sono 6 blocchi chirurgici (stato del dialog, focus
requester di ritorno, `LaunchedEffect` di ripristino, sezione Impostazioni, invocazione
del pannello). Nessuna riga di altre schermate toccata. Le modifiche WIP preesistenti
(`HomeScreen.kt`, `CategoryBrowsingDpadNavigationTest.kt`, documenti in root e
`docs/telegram/`) sono state preservate integralmente.

## 2. Posizione della nuova voce nelle Impostazioni

`Impostazioni` → sezione **«Informazioni legali»** → riga **«Disclaimer e
responsabilità»** (icona `Gavel`, chevron, identico stile `SettingsRow` delle altre
voci). La sezione è inserita **tra la card statica «Informazioni» e «Riconoscimenti»**.

Nota di posizione: la sezione «Informazioni» esistente è una card di sola lettura che
per design è un **unico target del D-pad** (commento di progetto in `SubScreens.kt`);
inserirvi una riga interattiva l'avrebbe alterata. La voce è quindi una sezione dedicata
adiacente, nella stessa area, secondo l'alternativa prevista dalla richiesta
(«creare una voce dedicata nella posizione più coerente»). Il titolo di sezione riusa
la chiave esistente `settings_legal_info` (presente ma finora inutilizzata in tutte le
5 localizzazioni).

Navigazione: la riga è raggiungibile con il D-pad come le altre sezioni; OK apre il
pannello; BACK o «Chiudi» chiudono e il focus torna sulla riga che l'ha aperto
(ripristino esplicito, stesso schema dei popup chiave API TorBox/OpenSubtitles).

## 3. Schermata implementata

`SettingsDisclaimerDialog` (`SettingsDisclaimerDialog.kt`): pannello modale informativo
di **sola lettura**, seguente il design system esistente:

- **Contenitore**: `Dialog` nativo su `NovaSurface`, bordo ciano `NovaCyan` 1.5dp, angoli
  20dp, larghezza 640dp — stessa cornice di `TrackSelectionDialog`/`UpdateResultDialog`.
- **Intestazione**: titolo del documento in maiuscolo («DISCLAIMER E LIMITAZIONE DI
  RESPONSABILITÀ») + icona di chiusura rapida per click/touch (non focalizzabile col
  D-pad, per non far atterrare il focus sull'icona).
- **Gerarchia visiva**: paragrafi in `NovaTextSecondary` 15sp (interlinea 23sp); le
  frasi evidenziate del testo ufficiale (markup `<b>` nelle risorse) renderizzate in
  `NovaTextPrimary` SemiBold via conversione `Spanned → AnnotatedString`; paragrafo
  finale interamente in evidenza.
- **Scrolling verticale**: area testo con `heightIn(max = 380.dp)` + `verticalScroll`;
  il blocco è l'unico target D-pad del corpo e consuma SU/GIÙ solo finché c'è contenuto
  da scorrere; ai bordi il tasto torna al grafo del pannello (pattern collaudato di
  `UpdateChangelogBlock`), quindi «Chiudi» è raggiungibile arrivati in fondo.
- **Focus**: iniziale sul testo (via `requestFocusWhenAttached`, 3 tentativi su frame
  successivi), sempre visibile con bordo ciano `TvFocusableBox`.
- **Azione Indietro**: pulsante «Chiudi» (`TvActionButton` primario, come nei dialog
  profili) + BACK di sistema che chiude via `onDismissRequest`.
- **Isolamento**: finestra `Dialog` reale — mentre è aperta, la finestra Impostazioni
  non riceve D-pad/BACK; `ModalOverlayState` disattiva la gesture LEFT dell'Activity.
- **Nessuna conferma obbligatoria all'avvio**: il pannello si apre solo dalla voce
  delle Impostazioni. Nessuno stato, nessuna preferenza, nessun side effect su
  sorgenti/provider/player/sincronizzazione.

## 4. Lingue aggiornate

Tutte le 5 localizzazioni del progetto:

| Locale | Voce | Titolo pannello |
|---|---|---|
| `values` (it, default) | Disclaimer e responsabilità | Testo ufficiale italiano, verbatim |
| `values-en` | Disclaimer and responsibility | Disclaimer and limitation of liability |
| `values-de` | Haftungsausschluss und Verantwortung | Haftungsausschluss und Haftungsbeschränkung |
| `values-es` | Aviso legal y responsabilidad | Aviso legal y limitación de responsabilidad |
| `values-fr` | Avertissement et responsabilité | Avertissement et limitation de responsabilité |

Le traduzioni sono fedeli e professionali e **non eliminano né attenuano** le clausole
su pirateria, responsabilità esclusiva dell'utente e diritti d'autore (verificato dal
test `nessunaLinguaAttenuaLeClausoleSuPirateriaOResponsabilita`, che controlla i
concetti chiave in ogni lingua). Nessun testo tradotto è stato inserito nei componenti
Kotlin: tutti i testi vivono nei file XML di risorse, con i segmenti in evidenza
marcati `<b>` (coerentemente bilanciati in ogni lingua, verificato da test).

## 5. Test eseguiti e risultati

### Compilazione
- `:app:compileDebugKotlin` — **OK** (solo warning preesistenti, nessuno nuovo).
- `:app:assembleDebug` — **OK**, 5 APK generati (vedi §7).

### Test delle componenti interessate (49 test, 0 failure, 0 error)

| Classe | Test | Esito |
|---|---|---|
| `SettingsDisclaimerDialogTest` (nuovo) | 6 | **Tutti passati** |
| `SettingsDisclaimerStringsLocalizationTest` (nuovo) | 5 | **Tutti passati** |
| `SettingsContinueWatchingSectionTest` | 8 | Tutti passati |
| `UpdatePromptDialogTest` | 8 | Tutti passati |
| `TrackSelectionDialogFocusTest` | 5 | Tutti passati |
| `OpenSubtitlesStringsLocalizationTest` | 3 | Tutti passati |
| `SettingsOpenSubtitlesSectionTest` | 3 | Tutti passati |
| `SettingsHomeSectionTest` | 3 | Tutti passati |
| `SettingsSwitchUniformityTest` | 3 | Tutti passati |
| `SettingsSubtitleOpacityRowTest` | 2 | Tutti passati |
| `SettingsTmdbGenreFallbackRowTest` | 2 | Tutti passati |
| `SettingsSimklAccountRowsTest` | 1 | Tutti passati |

Copertura delle nuove regressioni (`SettingsDisclaimerDialogTest`, Robolectric + Compose,
locale `it-w1280dp-h720dp-tvdpi`):

1. **laVoceNelleInformazioniLegaliantiApreIlPannelloConIlTestoUfficiale** — la sezione
   «INFORMAZIONI LEGALI» esiste; OK sulla riga apre il pannello con titolo, tutti i 7
   paragrafi (frasi chiave del testo ufficiale) e «Chiudi».
2. **ilFocusInizialeParteDalTesto** — all'apertura il focus è sull'area di lettura.
3. **suDalBordoSuperioreRestaSullaLettura** — SU in cima al documento non fa fuggire il
   focus (l'icona di chiusura non è un target D-pad).
4. **loScorrimentoEsaurisceIlDocumentoEDelegaAChiudi** — GIÙ ripetuto copre l'intero
   documento; al fondo il tasto è rilasciato e il focus passa a «Chiudi».
5. **ilPulsanteChiudiRiportaAlleImpostazioni** — percorso reale del telecomando
   (GIÙ fino in fondo + OK su «Chiudi»): pannello chiuso, focus di nuovo sulla riga.
6. **ilTastoBACKChiudeIlPannelloERiportaAlleImpostazioni** — BACK di sistema (dispatcher
   del Dialog, come sul dispositivo) chiude il pannello e riporta alle Impostazioni
   con il focus sulla riga.

Copertura delle stringhe (`SettingsDisclaimerStringsLocalizationTest`):
chiavi presenti e non vuote in tutte le lingue; testo italiano ufficiale senza
abbreviazioni (frasi chiave verbatim); nessuna lingua attenua pirateria/responsabilità
/diritti; nessun placeholder di formato; markup `<b>` bilanciato.

### Suite completa
`:app:testDebugUnitTest` (intera suite) — **BUILD SUCCESSFUL**: 320 classi di test,
**2675 test, 0 failure, 0 error, 3 skipped** (10m 19s). Nessun test preesistente
rotturato dalle modifiche.

### Verifica D-pad / scrolling / BACK (da test + implementazione)
- D-pad: la riga è un `SettingsRow` standard (stessa `TvFocusableBox` di tutte le voci);
  apertura con OK verificata dai test 1, 5 e 6.
- Scrolling fino all'ultima riga: verificato dal test 4 (16 pressioni GIÙ coprono il
  documento; il passaggio di focus a «Chiudi» al fondo prova il raggiungimento del
  bordo inferiore) — il contenuto è composto per intero (`verticalScroll` non-lazy),
  quindi nessun testo tagliato o sovrapposto.
- BACK alle impostazioni: verificato dal test 6, con ripristino del focus sulla riga.
- Leggibilità Full HD/4K: pannello ~530dp di altezza su viewport TV 720dp (1080p) e
  oltre; larghezza 640dp; nessun elemento sfora (stessa impostazione dei dialog esistenti).

## 6. Conferma: funzionalità estranee non modificate

- **Player** — nessuna modifica (nessun file toccato).
- **Sorgenti video / provider / addon / integrazioni** — nessuna modifica.
- **SIMKL** — nessuna modifica (`SyncSettings.kt`, repository e sync manager intatti).
- **Sincronizzazione tra dispositivi** — nessuna modifica.
- **OTA / sistema di aggiornamento** — nessuna modifica (`UpdateDialogs.kt` intatto).
- **Database Room e migrazioni** — nessuna modifica.
- **`mobile/`** — nessuna modifica.
- **Branding / logo** — nessuna modifica.
- **Nessuna nuova dipendenza** — sono stati riusati solo componenti e API già presenti
  nel progetto (`TvFocusableBox`, `TvActionButton`, `Dialog`, `verticalScroll`,
  `ModalOverlayState`, `requestFocusWhenAttached`, `Gavel` già importata).
- **Nessuna schermata o conferma obbligatoria all'avvio** — il pannello esiste solo
  come voce di Impostazioni; splash e avvio app non toccati.
- Le sezioni Impostazioni esistenti non hanno cambiato comportamento: tutti i loro test
  restano verdi (§5).

## 7. Percorso dell'APK debug generato

`D:\VSCODE\Progetti\StreamNova_integration\app\build\outputs\apk\debug\`

| Artefatto | Dimensione |
|---|---|
| `StreamNova-arm64-v8a-debug.apk` | ~44 MB |
| `StreamNova-armeabi-v7a-debug.apk` | ~43 MB |
| `StreamNova-x86-debug.apk` | ~44 MB |
| `StreamNova-x86_64-debug.apk` | ~44 MB |
| `StreamNova-universal-debug.apk` | ~76 MB |

versionName **1.1.0**, versionCode **1100**.

## 8. Operazioni Git

**Nessun commit, push, tag o pubblicazione eseguita.** Tutte le modifiche restano WIP
nell'area di lavoro; le modifiche WIP preesistenti sono state preservate. In attesa di
approvazione prima di qualsiasi operazione Git.

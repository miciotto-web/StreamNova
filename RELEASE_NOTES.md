# StreamNova V1.2.2 — Build 1202

StreamNova 1.2.2 è la release stabile successiva alla 1.2.1. Il `versionName` passa a
`1.2.2` e il `versionCode` a `1202`: l'aggiornamento in-app viene offerto a tutte le
installazioni 1.2.1 e precedenti, con la stessa chiave di distribuzione ufficiale.

## Novità principali

### Backup e ripristino affidabili + Google Drive personale (OAuth Device Flow)

- **Esporta/Ripristina senza crash**: su dispositivi senza document provider (es. Fire TV
  senza DocumentsUI) le due azioni non fanno più crashare l'app con
  `ActivityNotFoundException`; `launch()` protetti e fallback in-app con scrittura
  atomica e lista dei backup locali.
- **Backup sul Google Drive personale di ogni utente** via OAuth 2.0 Device Authorization
  Grant (client «TVs and Limited Input devices», `client_secret` sui token endpoint come
  richiesto da Google): collegamento con codice a 8 caratteri, refresh token cifrato con
  Keystore, access token mantenuto solo in memoria.
- **BackupManager**: envelope unica con validazione round-trip, export su file atomico
  `StreamNova_Backup_YYYY-MM-DD_HH-mm-ss.json`, cartella in-app «StreamNova Backups»
  come fallback senza SAF.
- **Google Drive REST v3** via OkHttp (nessuna nuova dipendenza): repository per-profilo
  con cartella isolata per account, retry dell'upload e anti-duplicato.
- **UI D-pad friendly**: dialog destinazione/sorgente Locale/Drive, liste, anteprima e
  conferma del ripristino, disconnessione account.
- Verificato su Fire TV reale: device flow, export/import/restore su Drive, nessun crash
  (report `STREAMNOVA_GOOGLE_DRIVE_OAUTH_FINAL_VERIFICATION_REPORT.md`); 43 test unitari
  nuovi/aggiornati.

### Scena di caricamento cinematografica unificata (Debrid + HTTP)

- Stessa scena per i due percorsi di caricamento del flusso: immagine del titolo a tutto
  schermo (backdrop, fallback sul poster), logo originale pulsante e dicitura di
  caricamento, ricavati **solo dai metadati del MediaItem** della sessione, nessuna
  richiesta di rete aggiuntiva.
- In modalità HTTP diretta la visibilità dello schermo di caricamento è decisa dal solo
  `StreamingEngineMode`.
- `StreamStatusOverlay` rimosso; localizzazione IT/EN/DE/ES/FR.

### Integrazione W12: sorgenti intelligenti, health addon e diagnostica

- **Selezione sorgenti intelligente** (`SmartSourceSelection`): classificatore delle
  sorgenti Stremio HTTP e normalizzatore delle richieste.
- **Health-check degli addon**: monitor «Stato addon» con report strutturato.
- **Diagnostica di riproduzione**: inspector e report HLS consultabili in-app.
- **Nuove schermate** «Non so cosa guardare» e «Nuovi episodi»
  (`LibraryDiscoveryScreens`).
- Completata l'integrazione del ramo W12: ricerca, discovery, library, sottotitoli,
  backup, storage, config sync e sezioni di manutenzione in Impostazioni.

### Gestione focus della sidebar deterministica

- OK su una voce di sezione chiude la sidebar ed entra nel contenuto con target
  deterministico; su sezione vuota il focus resta sulla voce.
- LEFT dal contenuto con sidebar aperta torna sulla **voce della sezione corrente**
  (prima atterrava su una voce arbitraria, es. «Preferiti»).
- RIGHT da una voce senza provenienza entra nel contenuto della sezione invece di
  atterrare sulla voce «Home» della sidebar stessa.
- OK su «Home» riporta il focus sull'Hero via protocollo dell'ancora.
- Verificato su emulatore (scenari V1–V15); test unitari mirati 34/34.

### Correzioni

- **Crash all'avvio della build firmata W12** (`Missing field in s5` /
  `NoSuchFieldException: HEALTHY`): le regole default di R8 rinominavano i
  campi-costante degli enum letti via reflection da Moshi
  (`AddonHealthReportJsonAdapter`). Nuova regola
  `-keepclassmembers enum com.example.** { <fields>; }` package-wide, che protegge anche
  gli enum già serializzati di `SyncPayload` e `BackupManager` e ogni futuro enum
  serializzato. Verificato su Fire TV reale (avvio pulito, 0 FATAL EXCEPTION).
- **Card QR di configurazione remota**: spostata subito sotto il pulsante «Aggiungi»,
  sopra il monitor «Stato addon». La card non è focusabile quindi il bring-into-view non
  la centrava mai: se il monitor la spingeva sotto il fold restava tagliata a metà e il
  telefono non riusciva più a inquadrarla.

## Versione e compatibilità

- App version: `1.2.2`
- Version code: `1202`
- Application ID: `com.aistudio.streamnova.tvapp`
- Artifacts: APK separati per `armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`, più l'APK universale
- Firma: stessa chiave di distribuzione ufficiale delle release precedenti (certificato
  SHA-256 `3175a7df…`), aggiornamento in place senza disinstallare
- Canale: stabile, non prerelease

# StreamNova V1.2.1 — Build 1201

StreamNova 1.2.1 è la release stabile successiva alla 1.2.0. Il `versionName` passa a
`1.2.1` e il `versionCode` a `1201`: l'aggiornamento in-app viene offerto a tutte le
installazioni 1.2.0 e precedenti, con la stessa chiave di distribuzione ufficiale.

## Novità principali

### Debrid multi-servizio: TorBox, Real-Debrid e Premiumize

- Nuovo selettore del servizio Debrid in **Impostazioni → Motore di riproduzione**, con
  chiave API, verifica account e toggle **Instant Debrid** dedicati a ciascun servizio.
- **Real-Debrid**: verifica della cache con `instantAvailability`, `addMagnet`, selezione
  del file dell'episodio e `unrestrict/link` verso il CDN diretto.
- **Premiumize**: verifica con `cache/check`, `transfer/create`, `item/details` e
  `stream_link` diretto.
- `DebridServiceRouter` instrada risoluzione automatica, ripresa da «Continua a guardare»
  e fast-path episodio sul servizio selezionato, con cache link per hash+episodio e
  single-flight senza deadlock.
- Card sorgenti e overlay di caricamento mostrano il branding del servizio attivo. In
  modalità HTTP diretta nessun provider Debrid viene interrogato.

### Nuovi provider HTTP: AnimeWorld e GuardaPlay

- **AnimeWorld** con ricerca/parsing API, MP4 diretto, Streamtape, ranking ITA/SUB ITA ed
  esclusione di sequel/spin-off, accanto ad AnimeSaturn.
- **GuardaPlay** per i film: ricerca WordPress, player trembed, Uqload/loadm/VixSrc skip e
  URL media diretti.
- Nuovi estrattori **Uqload, Streamtape, Voe e Doodstream** nel `HosterResolver`, con
  `CookieJar` condiviso tra player ed estrattori (sessione Doodstream domain-scoped).
- `JsPacked` con parsing lineare per evitare StackOverflow sui payload packed reali.

### Diagnostica HLS 1.2.0

- **Sintesi della playlist**: tipo, renditions, codec, durata e risoluzione dichiarata.
- **Sessione diagnostica per riproduzione** basata sul listener Media3, con pannello
  tecnico consultabile: eventi HTTP, load error, buffering, codec e provider.
- Soglie di risoluzione unificate con **1440p distinto dal 4K** e riepilogo osservato dal
  verifier allegato alle sorgenti.
- Audit indipendenti 1.2.0 inclusi nel repository; correzioni A-01–A-04 verificate.

### VixSrc multi-audio e qualità automatica

- Dai master con più tracce audio vengono esposte le varianti **VixSrc ITA** e
  **VixSrc SUB ITA**, con lingua audio, sottotitoli italiani e attivazione sottotitoli
  trasmessi al player tramite `StreamSource`.
- Se il resolver lascia una sorgente su qualità **Auto** ma la verifica HLS osserva una
  risoluzione, la qualità effettiva e l'altezza verificata vengono promosse sulla sorgente
  selezionata: una 1080p verificata non resta più sotto una 720p di un provider successivo.

### Altadefinizione: titoli nuovi e fallback ID

- Per i titoli nuovi senza IMDb l'archivio usa `tm{tmdbId}`: il player prova l'IMDb e, solo
  su **400/404**, ricade sull'ID TMDB. Rate-limit e errori CDN non vengono mascherati.
- La ricerca nel catalogo riprova con il **solo titolo** quando la query con anno restituisce
  un item diverso (es. titoli omonimi o anno non ancora allineato).
- Il token vidxgo rinnova **solo `t` ed `e`**, preservando host, path proxy, `b` e `p` della
  richiesta; rinnovo su deadline locale e backoff di 10 s dopo un refresh fallito.

### Popup di conferma uscita

- Il BACK sulla Home non chiude più l'app al doppio tocco: compare un popup con **Esci** e
  **Annulla**.
- Focus iniziale su **Annulla**; il tasto BACK annulla; **Esci** chiude l'Activity con
  `finishAndRemoveTask` (stesso comportamento di prima).
- Il popup vive in una finestra `Dialog` separata e blocca i tasti destinati alla Home.

### Cataloghi, addon e correzioni

- **TMDB gestore nativo predefinito**: righe stock Home e paginazione Film/Serie alimentate
  dagli endpoint TMDB; rimosso Cinemeta e il relativo special-casing. Con addon catalogo
  attivi (Xperience, Easy Catalog) la risposta dell'addon è definitiva, senza supplemento
  TMDB.
- **Categoria Christmas** al posto di News, con discover TMDB per keyword; sinossi con
  fallback italiano → inglese tramite `translations`.
- **Hero Banner** con logo originale del titolo TMDB (priorità it → en, cache Room 12h) e
  fallback testuale.
- **Addon**: pulsante «Aggiorna» su ogni card per riscaricare il manifest senza
  reinstallare; logo ufficiale del manifest o asset incluso; install resiliente a
  «Chain validation failed» con anchor GTS e diagnosi dell'orologio di sistema.
- **Card addon strette**: pillole azione in `FlowRow`, margine minimo dallo switch e
  nessuna sovrapposizione con la pillola «Attivo».
- **StreamingCommunity**: dominio canonico `army` come primo mirror.

## Versione e compatibilità

- App version: `1.2.1`
- Version code: `1201`
- Application ID: `com.aistudio.streamnova.tvapp`
- Artifacts: APK separati per `armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`, più l'APK universale
- Firma: stessa chiave di distribuzione ufficiale delle release precedenti (certificato
  SHA-256 `3175a7df…`), aggiornamento in place senza disinstallare
- Canale: stabile, non prerelease

# StreamNova V1.2.0 — Build 1200

StreamNova 1.2.0 segna il passaggio dalla fase beta alla **fase stabile**: è pubblicata come release stabile (non prerelease), con la stessa chiave di distribuzione ufficiale delle versioni precedenti. Il `versionName` passa a `1.2.0` e il `versionCode` a `1200`, così l'aggiornamento in-app viene offerto a tutte le installazioni 1.0.x e 1.1.0.

## Novità principali

### Hub Studios in Home

- **Nuova riga «Studios»** sotto le schede dei provider: le card ufficiali di 19 case di produzione, con logo originale e gradiente del brand.
- **Scheda Studio dedicata** con badge filtro: Tutti, Ultimi Film, Film, Ultime Serie TV, Serie TV, Top 10, Azione, Fantascienza, Dramma, Commedia, Animazione, Horror, Fantasy.
- **Cataloghi Xperience quando l'addon è attivo**: le schede usano i cataloghi `studio_*` dell'addon, inclusi i brand non elencati nel manifest `/home` tramite target sintetici sul `baseUrl`. Senza addon, o per i brand non coperti, si usa il discover TMDB `with_companies`.
- **Refresh dei manifest addon all'avvio** con retry: i nuovi cataloghi sono visibili senza reinstallare l'app.
- **Filtri genere** con extra `genre` dell'addon e verifica client-side sui meta; paginazione con skip per target ed esaurimento su «nessun id nuovo».
- **Focus TV**: BACK dalla scheda Studio torna sulla card di origine, BACK su una card della fila riporta alla prima card e il focus viene ripristinato al rientro dal player.

### Priorità automatica Altadefinizione e qualità reale

- **Gerarchia automatica**: Altadefinizione → VixSrc → Streaming Community → altri provider abilitati, con la risoluzione più alta **realmente riproducibile** prima (4K/UHD 2160p → 1440p/QHD → 1080p/FHD → 720p/HD; risoluzione sconosciuta mai promossa).
- **Master HLS espansa in varianti**: Altadefinizione restituisce una sorgente per variante, ordinate per risoluzione decrescente.
- **Verifica di riproducibilità** di manifest, playlist di variante, segmento di inizializzazione e primo segmento, in sequenza: una variante dichiarata 4K ma inaccessibile non viene scelta al posto di una 1080p funzionante.
- **Finestra Full HD dell'auto-pick portata a 8 secondi** e correzione del blocco strutturale che teneva fermo il collector per tutta la finestra: la sorgente 1080p arrivata dopo il Fast-Start 720p viene ora processata e avviata subito.
- **Fallback deterministico** con alternative reali su errore definitivo; la selezione manuale (dialog e provider preferito) e il motore Debrid restano invariati.

### Restyling cinematografico «In evidenza»

- **`FeaturedCinemaCard`**: la locandina selezionata si espande a 16:9 con immagine TMDB, gradiente inferiore, logo originale del titolo dentro la scheda, bordo e alone ciano; le altre restano verticali con riflow animato e senza perdita di focus.
- **Slot informativo a altezza fissa** sotto la card: `GENERE · ANNO · STAGIONI · CLASSIFICAZIONE` + sinossi in italiano (max 3 righe), senza sobbalzi verticali.
- **Numeri di classifica outline** per dilatazione con soli fill: nessuna linea interna sui glifi Roboto a contorni sovrapposti (3, 4, 6, 8, 9), ciano quando selezionati.
- **Arricchimento al primo focus** con cache Room 12h e memo di sessione (logo, certificazione, stagioni, overview e generi localizzati); risoluzione IMDb→TMDB per le righe stock Cinemeta e fallback sul logo già presente nell'item.
- Applicato **solo** alle due righe «In evidenza: Film» e «In evidenza: Serie TV».

### Scheda Dettaglio: Franchise - Collezione

- **Nuova sezione dopo «Creatore e Cast»** con il nome della collezione TMDB e i poster in formato 16:9, solo per i film (TMDB non espone collezioni per le serie).
- **Card con logo originale del titolo**, titolo e anno, focus D-pad e click che apre il dettaglio del film.
- **Ordinamento cronologico** per data di uscita, deduplica e filtro delle parti fantasma TMDB; cache Room della collezione con TTL 12h.
- **Loghi corretti**: scartati gli SVG non renderizzabili da Coil, preferiti i logo-titolo wide (aspect ratio 1.8–8) in italiano e poi inglese; cache loghi bumpata a `*_logo_v2_` per non riusare URL vecchi.

### Profili utente

- **Avatar-immagine dei personaggi** nel selettore profilo: 39 asset a 384px in 5 categorie (Cinema Fantasy, The Simpsons, Disney, Cartoon Classic, Black Mirror).
- **Scelta esclusiva** emoji/immagine: selezionare l'una deseleziona l'altra; il badge profilo mostra l'immagine quando presente, poi emoji o iniziale.
- **Pannello ristrutturato** con sezioni in area scorrevole e pulsanti «Chiudi»/«Applica» sempre visibili anche su TV con poco spazio verticale.
- **D-pad**: righe annidate avvolte in `TvFocusRow` per non bloccare lo scroll orizzontale e focus iniziale sull'avatar già scelto.

### Impostazioni e note legali

- **Nuova sezione «Informazioni legali»** con la riga «Disclaimer e responsabilità» che apre un pannello informativo di sola lettura.
- Testo ufficiale con frasi in evidenza, scrolling con SU/GIU e chiusura con BACK o «Chiudi» con ripristino del focus sulla riga di apertura.
- Stringhe localizzate in italiano, inglese, tedesco, spagnolo e francese.

### Player e navigazione TV

- **Ritorno automatico al Dettaglio a fine riproduzione**: film, ultima puntata di una serie ed episodi con autoplay OFF o overlay chiuso non restano più fermi sull'ultimo fotogramma. Il rientro usa la stessa via del BACK, con guardie su countdown «Prossimo Episodio», cambio episodio, ricerca sorgente autoplay, card «Nessuna fonte disponibile», sessione fallita e scrub/seek in corso.
- **Scroll animato in discesa** nelle griglie di Cerca e categorie: il pre-scroll rivela solo la riga destinazione con la stessa curva del bring-into-view e il focus viene richiesto in parallelo, senza salti istantanei e senza perdere righe nelle raffiche di pressioni.

## Versione e compatibilità

- App version: `1.2.0`
- Version code: `1200`
- Application ID: `com.aistudio.streamnova.tvapp`
- Artifacts: APK separati per `armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`, più l'APK universale
- Firma: stessa chiave di distribuzione ufficiale delle release precedenti (certificato SHA-256 `3175a7df…`), aggiornamento in place senza disinstallare
- Canale: stabile, non prerelease

## Verifica

- Suite unitaria completa eseguita sullo stato di questa release: **2.737 test, 0 fallimenti, 3 saltati** (i tre test di rete, saltati per progetto).
- Compilazione Release con R8/minificazione e risorse ottimizzate: 5 APK (quattro split ABI + Universal).
- Ogni APK è stato verificato con `apksigner` e `aapt2`: firma valida con il certificato SHA-256 `3175a7df6ea50a1be756c4a0b74c2623757128b620d28f40e678eed715fd2cd7` (la stessa chiave di distribuzione delle release precedenti), `versionName=1.2.0`, `versionCode=1200` e ABI dichiarate corrette su ogni split.

### SHA-256

```
198f4b20c14ece637498e0ab8b785ae14979de62bcda1175299e80027e82dedd  StreamNova-arm64-v8a-release.apk
6a93c06e896a8644ba9377d4532cbbac484664c82c7852f8d0db2ada98bab238  StreamNova-armeabi-v7a-release.apk
de3c36108ceabef9cf8e4fd992f27c319bf7817b398a123cb18394ea693f7d48  StreamNova-universal-release.apk
89b718b66314dfe5e0a3a76f4cd13fd517700d6c9836d6b3486a2c9a3e71487f  StreamNova-x86_64-release.apk
defc31cb695235bb2b2624842a8fd68122c8657b7a96e77edd837a2ea262614f  StreamNova-x86-release.apk
```

---

# StreamNova V1.1.0 — Build 1100

StreamNova 1.1.0 porta il motore HTTP alla versione successiva: nuovi provider verificati, la scelta del provider principale, il rinnovo automatico dei token HLS e le correzioni TV/D-pad rimaste in coda dopo la 1.0.10. Il `versionName` cresce (`1.1.0`) e il `versionCode` passa a `1100`, così l'aggiornamento in-app viene offerto a tutte le installazioni.

## Novità principali

### Nuovi provider HTTP

- **Altadefinizione (nuovo provider).** Risolve i flussi tramite il player vidxgo: usa l'IMDb ID come chiave primaria (`tt0133093` → `0133093`, con zero-padding a 7 cifre) e, se manca, ricade sulla ricerca per titolo/anno dell'API pubblica del catalogo. Film: `/t/{id}`; serie: `/t/{id}/{stagione}/{episodio}`. La master HLS viene letta per ricavare la qualità massima; un errore in questa lettura non invalida la sorgente (resta «Auto»).
- **VidxgoTokenInterceptor (rinnovo token HLS).** I flussi Altadefinizione usano un token che scade dopo circa 180 secondi: l'interceptor lo rinnova pochi istanti prima della scadenza e, su un 403 del CDN, forza un refresh e ritenta una sola volta. Agisce esclusivamente sugli host del CDN vidxgo (`*.d2b.you`): nessun'altra sorgente viene toccata. Un errore di refresh non interrompe la riproduzione.
- **StreamingCommunity.** Il player attuale usa lo stesso backend vidxgo: la pagina HTML risponde 403 (gate anti-bot), mentre l'endpoint JSON pubblico `/t/{id}` restituisce la master HLS. Il provider ora usa questo percorso, lo stesso già verificato per Altadefinizione.
- **CB01 / CB01 Uno.** Aggiornati i domini: `cb01one.uno` è il canonico, con `cb01uno.uno`, `cb01one.com` e `cb01.stream` come alias di fallback. I domini storici `cb01uno.top` e `cb01uno.skin` sono stati rimossi.
- **Regole R8 per i payload vidxgo.** Aggiunte le keep rules Moshi per `TokenPayload`, `ArchivePayload` e `ArchiveItem`: senza di esse R8 poteva svuotare le classi e far fallire il parsing solo nella build di release minificata.

### Scelta del provider HTTP

- **Provider HTTP principale.** Nuova voce in Impostazioni → Motore di Riproduzione (visibile solo in modalità HTTP): si può lasciare «Automatico» oppure scegliere uno dei provider (MultiEmbed, VixSrc, Altadefinizione, 2Embed, SuperEmbed, StreamingCommunity, CB01, Eurostreaming). La scelta è persistita e sopravvive al riavvio.
- **Solo provider scelto.** Con un provider principale selezionato è attivo per default: nessun fallback automatico sugli altri provider. Se il provider non risponde, la riproduzione non parte. L'opzione è disattivabile per riabilitare il fallback.
- **Provider sperimentali.** Nuovo interruttore (default **disattivato**): in modalità automatica restano attivi solo i provider verificati (VixSrc e Altadefinizione). Attivandolo vengono eseguiti anche 2Embed, CB01, StreamingCommunity, MultiEmbed, SuperEmbed ed Eurostreaming. Il provider scelto manualmente viene sempre eseguito, anche se classificato come sperimentale.
- **Ordinamento delle sorgenti.** Le sorgenti del provider preferito restano in cima sia nel fast-start sia nel selettore manuale, prima dei criteri di cache/lingua/qualità. La deduplica resta invariata: lo stesso URL restituito da due provider è una sola sorgente.
- **Avvio e fallback.** Il fast-start attende il provider preferito fino a 3 secondi prima di ripiegare sulla migliore sorgente disponibile; l'auto-pick usa una finestra dedicata di 1,5 secondi e avvia subito una sorgente del provider scelto. La preferenza vale solo nel motore HTTP (in Debrid l'unico provider è TorBox).
- **Robustezza.** Un id provider sconosciuto o non più attivo viene ignorato e la ricerca torna automatica, senza errori. Etichette localizzate in italiano, inglese, tedesco, spagnolo e francese.

### TV, D-pad e interfaccia

- **Badge di ordinamento Film/Serie TV.** Nuovi badge «Recenti», «Per Valutazione» e «Popolari» nelle schermate Film e Serie TV: riordinano client-side le schede già caricate (la paginazione resta sulla lista originale) e la scelta sopravvive al ritorno dal Dettaglio. Il BACK dalla griglia riporta il focus sul badge di ordinamento selezionato, con conferma reale del focus.
- **Fix sobbalzo delle griglie.** La griglia di `CategoryBrowsingScreen` usa `TvFocusGridBringIntoView`: la riga in ingresso viene centrata e LEFT/RIGHT nella stessa riga non muovono più il catalogo in verticale (eliminato il sobbalzo di circa 8 dp per pressione).
- **Fix crash D-pad nel selettore avatar.** Il DOWN dai chip categoria puntava alla prima emoji della categoria: se quella riga non era composta, il focus search terminava con `IllegalStateException: FocusRequester is not initialized`. I chip ora puntano a un requester agganciato alla riga emoji visibile, sempre composta.

### Altri fix

- **OpenSubtitles.** Una scrittura asincrona già in volo non può più atterrare su disco dopo la rimozione della chiave API: il job precedente viene annullato e lo store catturato al momento della chiamata.
- **Selettore sorgenti.** Il provider preferito viene passato anche al dialog di selezione, così l'ordine resta coerente con l'auto-pick.

## Versione e compatibilità

- App version: `1.1.0`
- Version code: `1100`
- Application ID: `com.aistudio.streamnova.tvapp`
- Artifacts: APK separati per `armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`, più l'APK universale
- Firma: stessa chiave di distribuzione ufficiale delle release precedenti (certificato SHA-256 `3175a7df…`), aggiornamento in place senza disinstallare

## Verifica

- Suite unitaria completa eseguita sullo stato di questa release: **2.658 test, 0 fallimenti, 3 saltati** (i tre test di rete, saltati per progetto). Include i nuovi test su Altadefinizione/Vidxgo, sul rinnovo del token, sulla scelta del provider, sui badge di ordinamento e sul crash D-pad degli avatar.
- Compilazione Release con R8/minificazione e risorse ottimizzate: 5 APK (quattro split ABI + Universal).
- Ogni APK è stato verificato con `apksigner` e `aapt2`: firma valida con il certificato SHA-256 `3175a7df6ea50a1be756c4a0b74c2623757128b620d28f40e678eed715fd2cd7` (la stessa chiave di distribuzione delle release precedenti), `versionName=1.1.0`, `versionCode=1100` e ABI dichiarate corrette su ogni split.

### SHA-256

```
054589d13d44e85da079b04e6538778f2cfff206b789b1e0053eb2e5ab357bc4  StreamNova-arm64-v8a-release.apk
e5b1849b526584efbd34cac8eb870642fd9bdefea1eb82ebf6e3539b5c335427  StreamNova-armeabi-v7a-release.apk
ddf6a64c1b64d25c7a463d54c5dd6f6fdbca0669f00ee4d58a5e283047272e2f  StreamNova-universal-release.apk
ec139397680bccbd7812a5b4e5f751a9f7c059014473ef52de730ae196fd090b  StreamNova-x86_64-release.apk
ecb085ef4f2a4c8838270ccc1f5fb29d15e3c42b6d2dca297ba7f732fb433705  StreamNova-x86-release.apk
```

---

# StreamNova V1.0.2 - Official Stable Release

StreamNova V1.0.2 follows V1.0.1 and closes the update problem that left installations on 1.0.0 stuck: the updater shipped inside 1.0.0 could not tell the architecture of a release apart — it simply took the first APK of the release (or any file literally named `app-release.apk`), and on the published 1.0.1 release that first file is the 64-bit ARM build, which a 32-bit-only device such as a Fire TV Stick rejects with `INSTALL_FAILED_NO_MATCHING_ABIS`. No player, catalog, provider or extractor behaviour is touched.

## What's new in 1.0.2

- **The update check never changes the architecture of the installed app.** The selection still only considers ABIs the system declares it can execute, but it now puts the ABIs matching the running process first (`Process.is64Bit()` together with `Build.SUPPORTED_32_BIT_ABIS` / `SUPPORTED_64_BIT_ABIS`, in the order the system declares them). On a 64-bit device where StreamNova is installed as a 32-bit app, the system lists `arm64-v8a` first and the 1.0.1 updater would have offered the 64-bit file, silently switching the app's native architecture; 1.0.2 keeps the update on the architecture the app already runs. On a 32-bit-only device the result is identical to 1.0.1. A file that does not declare its architecture is still never selected.
- **`app-release.apk`: a universal APK, so installations still on 1.0.0 can update at all.** The 1.0.2 release also publishes a single APK that carries every ABI under the exact name the 1.0.0 updater prefers. Those installations — which cannot download the fixed updater, because the file they would receive cannot be installed — now receive one file that works on every device: on a 32-bit Fire TV it installs the 32-bit libraries, on a 64-bit box the 64-bit ones. Updaters from 1.0.1 onwards ignore that file (it declares no architecture) and keep using the split that matches their own ABI. Nothing changes for devices that are already on 1.0.1 or newer.
- Version bump: `versionName` `1.0.2`, `versionCode` `1004`.

## Version and compatibility

- App version: `1.0.2`
- Version code: `1004`
- Application ID: `com.aistudio.streamnova.tvapp`
- Artifacts: separate APKs for `armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`, plus the universal `app-release.apk` for installations still on 1.0.0
- All artifacts are signed with the same dedicated distribution key used for the previous stable releases, so 1.0.0 and 1.0.1 installations update in place without uninstalling.

## Verification

Automated verification on this source state:

- The complete unit test suite was re-run in full on 2026-10-25 and passed: 1,312 tests, 0 failures, 2 skipped (two network-dependent provider checks, skipped by design). 16 of those tests cover the ABI selection introduced here, including the counter-proof: with the pre-1.0.1 rule the same release data yields the 64-bit file for a 32-bit installation.
- All five Release APKs were checked with `apksigner` (certificate `3175a7df…`, the distribution key of 1.0.0 and 1.0.1) and with `aapt2` (`com.aistudio.streamnova.tvapp`, `versionCode 1004`, `versionName 1.0.2`; each split declares exactly its own ABI, the universal all four). SHA-256 of every file is published alongside the release.
- The universal APK was compared against the four splits: it contains the same ten native libraries per ABI as the corresponding split, for all four ABIs.

On-device verification, Fire TV Stick 4K Max 2nd gen (`AFTMA08C15` / `karat`, Fire OS 8.1.8.5, Android 11) — a device that is 32-bit only (`ro.product.cpu.abilist=armeabi-v7a,armeabi`, `ro.product.cpu.abilist64` empty, `ro.zygote=zygote32`):

- The `arm64-v8a` split is rejected by the platform exactly as expected: `INSTALL_FAILED_NO_MATCHING_ABIS: Failed to extract native libraries, res=-113`.
- The officially signed 1.0.0 already installed on that device was updated in place to 1.0.2 twice: with the `armeabi-v7a` split and with the universal `app-release.apk`. Both installs succeeded without uninstalling (`firstInstallTime` unchanged across every step), the app kept `primaryCpuAbi=armeabi-v7a`, and it launched normally afterwards.

What is **not** verified yet, stated explicitly: the in-app flow (check → download → install) driven by a published release. It requires the 1.0.2 release to be live on GitHub; the Fire TV is left with the official 1.0.0 installed precisely so that this end-to-end test can be run as the first step after publication.

---

# StreamNova V1.0.1 - Official Stable Release

StreamNova V1.0.1 follows the V1.0 stable release and ships one focused change to the in-app update flow, together with the version bump. No player, catalog, provider or extractor behaviour is touched.

## What's new in 1.0.1

- **The update check selects the APK by device ABI.** The release assets are matched against the ABIs declared by the device — `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64` — in the order the system itself declares them, so a 64-bit ARM device receives the 64-bit ARM file and a 32-bit ARM device the 32-bit one. The order in which GitHub lists the assets no longer affects the result, and `app-release.apk` is no longer preferred blindly: a file that does not declare its architecture is never chosen.
- **A release without an installable APK is reported explicitly instead of being offered for download.** When the latest release carries no APK, only APKs for other architectures, or two assets declaring the same ABI, the update is not proposed and Settings explains it with a new localized message ("This release does not contain an APK installable on this device", added in Italian, English, German, Spanish and French). No download is started and no file is written to the cache.
- **The asset validated by the check is the asset that gets downloaded.** The selection happens once, during the check; the download reuses that validated asset instead of selecting again, and the declared size and SHA-256 of that same file are what the integrity verification enforces. Dismissing the update result also invalidates the asset, so a download can never start from a check that has already been discarded.
- Version bump: `versionName` `1.0.1`, `versionCode` `1003`.

## Version and compatibility

- App version: `1.0.1`
- Version code: `1003`
- Application ID: `com.aistudio.streamnova.tvapp`
- Targets Android TV and Fire TV Stick; separate APKs are built for `armeabi-v7a`, `arm64-v8a`, `x86`, and `x86_64`.
- All four APKs of this release are signed with the dedicated distribution key already used for the previous stable release.

## Verification

The complete automated unit test suite was re-run in full on this source state on 2026-10-25 and passed: 1,296 tests, 0 failures, and 2 skipped (two network-dependent provider checks, skipped by design). The four Release APKs were then rebuilt from this source state: they are signed with the distribution key and declare `versionName` `1.0.1` and `versionCode` `1003`, with the ABI of each file matching the name of the file.

Limits of this verification, stated explicitly: the update flow (check, asset selection, download) is covered by automated tests that run against a local HTTP server, not against a release published on GitHub, and no on-device verification of 1.0.1 has been performed. On-device verification of the update flow on a real device therefore remains open.

## Signing

This release uses a dedicated distribution signing key. Keep the keystore and its password in separate secure backups. Losing the signing key can prevent compatible updates to installations signed with that key.

---

# StreamNova V1.0 - Official Stable Release (previous release)

StreamNova V1.0 is the first stable release for Android TV and Fire TV Stick following the beta phase.

## Highlights

- Improved Android TV D-pad navigation, focus restoration after details and player screens, and fast row scrolling.
- Catalog, film and series details, synopsis localization, and Top 10 navigation improvements.
- Streaming engine, providers, extractors, source resolution, diagnostics, and player fallback improvements.
- Stremio catalog selection and live-channel support improvements.
- GitHub Releases update flow with version comparison, SHA-256 and size integrity checks, download progress, and explicit user confirmation before installation.
- Updated translations and expanded automated test coverage.

## Version and compatibility

- App version: `1.0.0`
- Version code: `1002`
- Application ID: `com.aistudio.streamnova.tvapp`
- Targets Android TV and Fire TV Stick; separate APKs are built for `armeabi-v7a`, `arm64-v8a`, `x86`, and `x86_64`.
- Fire TV 32-bit ARM artifact: `StreamNova V1.0.apk` (`armeabi-v7a`).

## Verification

The complete automated unit test suite was re-run in full on this source state and passed: 1,271 tests, 0 failures, and 2 skipped (two network-dependent provider checks, skipped by design). On-device functional verification on Fire TV was then completed with a positive outcome, and no signed Release APK has been rebuilt from this source state.

## Seek preview on the timeline (player scrubbing)

Pressing ◀ / ▶ with the controls hidden now shows a video frame preview above the timeline, YouTube-style: a 16:9 thumbnail with cyan accent, the timestamp of the *proposed* position ("1:24 / 43:10"), a discreet loading sheen while the frame is fetched, and an elegant placeholder when the frame cannot be extracted. The overlay is decorative (no focus, no key handling), so the D-pad flow, the delayed seek commit and the BACK cancellation behave exactly as before: no seek is issued per press, only on confirmation.

### How frames are obtained

1. **Frames already provided by the source** (sprite sheets / preview metadata): none of the providers used by the app deliver them, so there was nothing to reuse.
2. **HTTP range extraction from a second decoder**: rejected after inspection. StreamNova plays many sources through the NuvioEngine proxy (TorBox / debrid) or through hoster links with session cookies and signed query strings; those endpoints do not guarantee random-access HTTP Range, and a separate decoder failing there would have to download the file from the beginning — explicitly forbidden.
3. **Chosen: a decoder of its own** (`PlayerSeekPreviewExtractor`). The extractor builds a second `ExoPlayer` on the **same** `MediaSource.Factory` and `DataSource.Factory` as the main player, so it inherits URL, HTTP headers, cookies, cross-protocol redirects, proxy, HLS/DASH and DRM exactly as the stream being played, and it renders off-screen, where the frame is read with an `ImageReader` (`YUV_420_888`) on platforms declared safe or through the GL path `SeekPreviewGlFrameGrabber` — an off-screen `SurfaceTexture` read with `glReadPixels` from an FBO — where they are not. The main player is never seeked, paused or re-prepared by the preview: the seek of the scrubbing stays the single one issued on confirmation, and the playback path gains no extra buffering. Each published frame carries the position the decoder actually reached, read from the renderer's presentation time (`VideoFrameMetadataListener`), never the requested one. The dedicated decoder is warmed up when the scrubbing opens, because its first `prepare()` was measured at about 5.7 s on the Fire TV (`mt8696`) and paying that cost inside the first capture kept the worker busy for ten presses.

### Known limitation

Reading the planes of an `Image` can abort the process inside `libmedia_jni` — a CheckJNI abort that no `catch` can intercept, observed on the Fire TV Stick 4K Max 2nd gen (`mt8696`) — so on platforms declared incompatible the extractor never creates the `ImageReader` at all and takes the GL path instead, where the buffer never leaves the GPU and no `Image`, hence no plane, is ever created. Where even the GL path cannot initialise, the source declares itself unavailable, the preview shows the placeholder and the timestamp, and scrubbing continues to work normally.

The second decoder opens a second connection to the source. Playback is paused while scrubbing, so there is no contention for bandwidth, but a hoster that limits concurrent sessions — or a DRM server — can refuse it, and the preview then honestly reports that no frame is available. There is no fallback that shows a frame of a different position: the timestamp always matches the frame on screen, and a frame the decoder lands on before the requested instant is flagged as an approximation ("≈" badge, dimmed border) instead of being labelled with the requested time.

Retrieval is driven from the player's application thread, as Media3 requires, and it delegates every expensive step — bitmap conversion, frame fingerprint, diagnostic dumps — to background threads, so the preview's cost on the player thread stays limited to the calls Media3 requires. An LRU cache capped at 24 MB avoids repeated requests, consecutive positions are deduplicated, the two adjacent steps are prefetched only while no primary request is pending, positions that are no longer current are skipped, the capture in flight is cancelled when the scrubbing closes or the source changes, and the cache and overlay are reset on episode/source change and on screen disposal.

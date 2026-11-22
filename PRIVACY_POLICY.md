---
layout: default
title: Privacy Policy
permalink: /privacy/
---

# Privacy Policy di StreamNova

**Ultimo aggiornamento: 22 novembre 2026**

Questa informativa descrive, in modo concreto e verificabile, quali dati tratta StreamNova, dove vengono conservati, quando lasciano il dispositivo e come possono essere cancellati. È stata redatta a partire dalle funzionalità realmente implementate nell'applicazione.

## 1. Chi siamo

StreamNova è un'applicazione per **Android TV e Amazon Fire TV** sviluppata e mantenuta dallo sviluppatore che pubblica, con l'account **miciotto-web**, il repository ufficiale:

- Repository: [https://github.com/miciotto-web/StreamNova](https://github.com/miciotto-web/StreamNova)
- Contatti e richieste: la sezione **Issues** del repository ufficiale, all'indirizzo [https://github.com/miciotto-web/StreamNova/issues](https://github.com/miciotto-web/StreamNova/issues)

Lo sviluppatore **non gestisce alcun proprio server di raccolta dati**: non esiste un backend StreamNova, non esiste un account "StreamNova" e l'app non trasmette nulla a sistemi di proprietà dello sviluppatore.

## 2. Che cos'è StreamNova

StreamNova è un media center che consente di organizzare e riprodurre contenuti tramite cataloghi e sorgenti di terze parti, integrando in particolare:

- cataloghi e metadati forniti da TMDB (The Movie Database);
- addon dell'ecosistema Stremio, predefiniti o configurati dall'utente;
- servizi "debrid" opzionali (TorBox, Real-Debrid, Premiumize) con le chiavi API dell'utente;
- sincronizzazione della visione con Simkl, tramite account collegato facoltativo;
- ricerca sottotitoli tramite OpenSubtitles;
- sorgenti di catalogo e streaming integrate o configurabili;
- **backup e ripristino della configurazione su Google Drive personale dell'utente** (facoltativo);
- sincronizzazione della configurazione tra dispositivi StreamNova sulla stessa rete locale (facoltativa).

StreamNova **non ospita, non possiede e non controlla** i contenuti raggiungibili tramite sorgenti di terze parti: opera come contenitore e interfaccia tecnologica. L'utente è l'unico responsabile delle sorgenti e dei servizi ai quali decide di accedere.

## 3. Principio fondamentale: nessuna telemetria

StreamNova **non include sistemi di analytics, telemetria, pubblicità, tracciamento o report di crash**. L'app non invia alcun dato automaticamente allo sviluppatore. I log dell'applicazione restano sul dispositivo (logcat di Android, consultabile solo dal dispositivo stesso o collegandolo a un computer).

## 4. Dati conservati localmente sul dispositivo

Tutti i dati d'uso di StreamNova sono conservati **solo sul dispositivo**, nello spazio privato dell'applicazione:

- **Impostazioni generali e di riproduzione** (lingua, autoplay, sottotitoli, buffering, controllo parentale, ecc.): l'hash SHA-256 del PIN del controllo parentale è conservato localmente; il PIN in chiaro non viene mai salvato né trasferito.
- **Libreria**: preferiti, "Continua a guardare", progressi di visione di film ed episodi, metadati del catalogo (titoli, sinossi, immagini in cache).
- **Profili utente**: profili multipli locali con avatar e preferenze.
- **Chiavi API di servizi configurati dall'utente** (debrid, OpenSubtitles, ecc.): conservate cifrate, con chiave protetta da Android Keystore.
- **Credenziali degli account collegati** (Google Drive, Simkl): conservate cifrate, come descritto nelle sezioni 6 e 8.
- **Backup locali**: i file di backup generati manualmente (formato JSON, estensione `.streamnova.json`) vengono salvati dove l'utente sceglie (es. memoria del dispositivo o USB); l'app ne conserva copie solo nel proprio spazio privato.

La disinstallazione dell'app rimuove questi dati dal dispositivo. Alcuni dati (preferiti, progressi, impostazioni) possono inoltre essere cancellati direttamente dalle funzioni dell'app.

## 5. Quando i dati lasciano il dispositivo

I dati lasciano il dispositivo **solo** in questi casi, tutti legati a funzionalità attivate dall'utente:

1. **Backup su Google Drive** (facoltativo) — sezione 6.
2. **Sincronizzazione tra dispositivi sulla rete locale** (facoltativa) — sezione 7.
3. **Utilizzo di servizi di terze parti** per cataloghi, metadati, stream, sottotitoli, tracking (sezioni 8 e 9).
4. **Controllo degli aggiornamenti** — sezione 10.

Nessuno di questi flussi comporta la trasmissione di dati allo sviluppatore di StreamNova.

## 6. Backup su Google Drive (facoltativo)

### 6.1 Collegamento opzionale dell'account

Il collegamento a Google Drive è **completamente facoltativo**: tutte le funzioni dell'app, tranne il backup su Drive, funzionano senza alcun account Google. L'utente collega il **proprio** account Google dall'app, tramite il flusso standard "Device Authorization Grant" di Google (RFC 8628): l'app mostra un codice e l'utente lo conferma su [https://google.com/device](https://google.com/device) da qualsiasi dispositivo, accedendo con il proprio account.

Ogni profilo StreamNova può collegare un account; i token di un profilo sono isolati da quelli degli altri profili.

### 6.2 Autorizzazioni richieste (scope)

StreamNova richiede a Google **solo** queste autorizzazioni, principi di minimo privilegio:

| Scope | A cosa serve |
|---|---|
| `https://www.googleapis.com/auth/drive.file` | Creare e gestire **solo** i file e le cartelle creati da StreamNova stessa |
| `openid` e `https://www.googleapis.com/auth/userinfo.email` | Identificare quale account è stato collegato |

Con lo scope `drive.file`, StreamNova **non ha accesso al resto del Drive**: non può leggere, elencare o modificare documenti, foto o altri file creati dall'utente o da altre applicazioni. Può operare esclusivamente sulla cartella "StreamNova Backups" e sui file di backup che crea lei stessa.

Le informazioni dell'account Google trattate dall'app sono limitate a: **l'indirizzo e-mail** e **l'identificatore tecnico stabile dell'account** (`sub` dell'id token). Servono unicamente per mostrare in impostazioni quale account è collegato e per garantire che i backup di account diversi non vengano mai mescolati (ad esempio se l'utente collega prima un account e poi un altro). L'e-mail non viene trasmessa ad alcun servizio diverso da Google né mostrata oltre alle schermate di configurazione.

### 6.3 Cosa viene caricato e dove

I backup sono file **JSON** caricati, tramite le API ufficiali di Google Drive, in una cartella denominata **"StreamNova Backups"** situata **nel Google Drive personale dell'utente**. **Lo sviluppatore non ha un Drive centralizzato e non riceve mai una copia dei backup**: il trasferimento avviene direttamente dal dispositivo dell'utente ai server di Google.

Il contenuto del backup è la stessa configurazione visibile nell'app: impostazioni generali e di riproduzione, addon installati (URL e stato), preferiti, "Continua a guardare", progressi degli episodi. Inoltre:

- l'intestazione del backup include la **versione dell'app, la data di esportazione e il modello del dispositivo** (es. il nome commerciale del TV);
- le **credenziali** (chiavi API dei servizi configurati, credenziali Simkl) sono **escluse per impostazione predefinita**: vengono incluse solo se l'utente seleziona esplicitamente l'opzione dedicata in fase di creazione del backup;
- il PIN del controllo parentale non viene mai incluso in chiaro (al più il suo hash, solo se le credenziali vengono incluse esplicitamente).

### 6.4 Token e loro protezione

- Il **refresh token** Google è conservato **solo sul dispositivo, cifrato** con una chiave protetta da Android Keystore (non esportabile).
- L'**access token** vive **solo in memoria** durante la sessione e viene rinnovato quando serve; non viene mai scritto su disco.
- Nessun token viene scritto nei log dell'app.

### 6.5 Gestione, conservazione ed eliminazione

- I backup restano nel Drive dell'utente **finché l'utente non li elimina**: possono essere gestiti o cancellati in qualsiasi momento da [Google Drive](https://drive.google.com) (cartella "StreamNova Backups"), come qualsiasi altro file personale.
- La **disconnessione dell'account** dall'app revoca il token di accesso su Google (l'app perde ogni capacità di operare sul Drive) e cancella dal dispositivo ogni dato del collegamento (token, e-mail, riferimenti alla cartella). I backup già presenti nel Drive restano di proprietà dell'utente: viene rimossa solo la capacità dell'app di accedervi.
- L'utente può revocare l'accesso di StreamNova in qualsiasi momento anche dalle impostazioni di sicurezza del proprio account Google (sezione "App con accesso all'account" / [https://myaccount.google.com/permissions](https://myaccount.google.com/permissions)).
- Se l'utente elimina la cartella "StreamNova Backups" dal Drive, l'app lo rileva e ricrea la cartella al prossimo backup, senza accedere ad altri contenuti.

### 6.6 Trasferimento

Il trasferimento dei backup avviene su **HTTPS** verso gli endpoint ufficiali di Google (`googleapis.com`). I dati in transito sono protetti dalla crittografia di trasporto e, a valle, sono trattati da Google nei termini del proprio servizio Drive, sotto il controllo dell'account dell'utente.

## 7. Sincronizzazione tra dispositivi (rete locale)

La funzione di sincronizzazione della configurazione tra due dispositivi StreamNova avviene **esclusivamente sulla rete locale** (Wi-Fi), tramite annuncio mDNS e connessione diretta tra i dispositivi. **Nessun dato transita da server esterni.** Un trasferimento parte solo dopo **approvazione esplicita dell'utente sul dispositivo ricevente**: senza l'approvazione, nessun dato lascia il dispositivo sorgente.

## 8. Account Simkl (facoltativo)

Se l'utente collega un account Simkl (anch'esso facoltativo, tramite il flusso ufficiale di Simkl), l'app invia a Simkl i dati di visione necessari alla sincronizzazione: titoli, episodi e timestamp di ciò che si guarda (avvio/pausa/fine, "visto", rimozione dalla cronologia) e legge elenchi e impostazioni dell'account. Il token Simkl è conservato cifrato sul dispositivo e revocato alla disconnessione. Le operazioni seguono le API ufficiali di Simkl; i dati sono trattati da Simkl secondo la propria privacy policy. Se l'account non viene collegato, nulla viene inviato a Simkl.

## 9. Servizi di terze parti usati per cataloghi, stream e sottotitoli

Per funzionare come media center, l'app contatta servizi esterni. Le richieste sono effettuate **dal dispositivo dell'utente** e i server di destinazione vedono, come per qualsiasi navigazione web, l'indirizzo IP e i metadati standard della connessione, oltre ai parametri della richiesta:

- **TMDB** (metadati, immagini, cataloghi): richieste basate su titoli, identificativi dei contenuti e parametri di catalogo. Nessun dato personale dell'utente è incluso.
- **Addon Stremio** (predefiniti o aggiunti dall'utente con il proprio URL): l'app chiede cataloghi, stream e metadati; le richieste includono i titoli o gli identificativi dei contenuti ricercati e i parametri previsti dal protocollo addon.
- **Servizi debrid** (TorBox, Real-Debrid, Premiumize — solo se l'utente configura la propria chiave API): l'app invia hash di torrent/magnet o link da risolvere, associati alla chiave API dell'utente, per verificare la disponibilità e ottenere URL diretti.
- **OpenSubtitles** (con la chiave configurata): richieste di ricerca sottotitoli basate su titolo/episodio.
- **Sorgenti di catalogo e streaming integrate o configurate**: l'app contatta le sorgenti selezionate per recuperare elenchi, pagine e flussi riproducibili.

Ognuno di questi servizi è titolare del proprio trattamento: si invita a consultare le relative informative. StreamNova **non vende, non cede e non condivide** dati personali con questi servizi oltre a quanto sopra descritto.

## 10. Controllo degli aggiornamenti

La verifica degli aggiornamenti interroga l'API pubblica di GitHub (`api.github.com`, repository ufficiali) per conoscere l'ultima release disponibile. La richiesta non contiene dati personali; GitHub riceve come da prassi l'indirizzo IP del dispositivo. L'installazione degli aggiornamenti avviene solo se l'utente la conferma.

## 11. Sicurezza

- Token e chiavi API sono conservati cifrati con chiavi non esportabili di Android Keystore.
- Tutto il traffico verso i servizi descritti usa HTTPS.
- I backup sono file JSON leggibili: chi ha accesso fisico o logico al Drive dell'utente (o al file esportato localmente) ne può leggere il contenuto; per questo le credenziali sono escluse di default.
- Nessun sistema può essere dichiarato infallibile: la sicurezza complessiva dipende anche dalla protezione del dispositivo (blocco schermo, sorgenti di installazione affidabili) e della propria password Google.

## 12. Conservazione ed eliminazione — riepilogo

| Dato | Dove | Eliminazione |
|---|---|---|
| Impostazioni, libreria, profili | Dispositivo (spazio privato app) | Dalla UI dell'app o disinstallando l'app |
| Token Google / Simkl, chiavi API | Dispositivo, cifrati | Disconnessione dell'account / rimozione della chiave / disinstallazione |
| Backup in locale | Percorso scelto dall'utente | Cancellazione del file da parte dell'utente |
| Backup su Google Drive | Drive personale dell'utente | Cancellazione dalla cartella "StreamNova Backups" su Google Drive |
| Copie presso lo sviluppatore | **Non esistono** | — |

## 13. Diritti dell'utente

Poiché StreamNova conserva i dati **sul dispositivo dell'utente** e non opera server di raccolta, l'accesso, la correzione e la cancellazione dei dati sono sotto il controllo diretto dell'utente: tramite le funzioni dell'app, la disconnessione degli account, la cancellazione dei file di backup (locali o su Drive) e la disinstallazione. Per i dati trattati da terze parti (Google, Simkl, TMDB, debrid, OpenSubtitles) valgono i diritti previsti dai rispettivi servizi, esercitabili direttamente presso ciascun fornitore.

Per qualsiasi richiesta relativa a questa informativa è possibile contattare lo sviluppatore tramite le Issues del repository: [https://github.com/miciotto-web/StreamNova/issues](https://github.com/miciotto-web/StreamNova/issues).

## 14. Minori

StreamNova non si rivolge deliberatamente a minori e **non raccoglie alcun dato presso soggetti identificabili**: tutti i dati d'uso restano sul dispositivo o negli account di terze parti dell'utente. Il controllo parentale integrato consente di limitare i contenuti visibili sul dispositivo.

## 15. Modifiche a questa informativa

Se le funzionalità di StreamNova dovessero cambiare il trattamento dei dati qui descritto, questa pagina verrà aggiornata con la data di revisione nella parte superiore. Le versioni precedenti restano consultabili nella cronologia del repository pubblico.

# StreamNova Mobile

Versione Android per smartphone e tablet del progetto StreamNova. Il modulo Mobile vive nello stesso repository della versione Android TV, ma mantiene un'applicazione e un'esperienza adattate ai dispositivi touch.

## Stato

- Implementazione delle schermate principali, cataloghi, ricerca, dettagli e impostazioni.
- Riproduzione Media3 con selezione manuale delle sorgenti in modalità Debrid.
- Supporto smartphone e tablet con layout adattivo.
- Orientamento: smartphone in portrait fuori dal player e landscape durante la riproduzione; tablet in landscape.
- Branding e design system condivisi con StreamNova TV.

## Modulo

` :mobile ` (Gradle module: `mobile`)

Il codice specifico dell'app si trova in `mobile/src/main`. I modelli, dati e componenti condivisi sono nei moduli `core/`.

## Build e test

Da PowerShell, nella root del repository:

```powershell
.\gradlew.bat :mobile:assembleDebug
.\gradlew.bat :mobile:testDebugUnitTest
```

APK debug:

```text
mobile/build/outputs/apk/debug/mobile-debug.apk
```

## Configurazione locale

Le chiavi e gli identificativi di servizio devono essere configurati localmente tramite `local.properties` o le variabili d'ambiente previste dal progetto. Non inserire segreti o credenziali nel repository.

## Versione TV

La versione Android TV rimane nel modulo `app/`. Le modifiche e le istruzioni specifiche Mobile sono documentate qui, senza sostituire la documentazione TV della root.

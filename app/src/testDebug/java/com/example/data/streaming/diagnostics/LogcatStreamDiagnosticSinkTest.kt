package com.example.data.streaming.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Formato delle righe del sink Logcat di debug: chiavi stabili, campi
 * opzionali resi come `-` e nessuna possibilita' di iniettare righe o di
 * falsificare il contesto della ricerca tramite il messaggio del tentativo.
 */
class LogcatStreamDiagnosticSinkTest {

  @Test
  fun formatoCompletoContieneTutteLeChiaviRichieste() {
    val line = LogcatStreamDiagnosticSink.format(
      StreamAttempt(
        providerId = "Cb01Provider",
        mirrorId = "cb01uno.top",
        extractorId = "MaxStreamExtractor",
        outcome = StreamAttemptOutcome.SUCCESS,
        durationMs = 812,
        searchId = 12,
        queueMs = 0,
        runMs = 800
      )
    )

    assertEquals(
      "search=12 provider=Cb01Provider mirror=cb01uno.top extractor=MaxStreamExtractor " +
        "outcome=SUCCESS queueMs=0 runMs=800",
      line
    )
  }

  @Test
  fun campiOpzionaliAssentiNonInventanoValori() {
    val line = LogcatStreamDiagnosticSink.format(
      StreamAttempt(
        providerId = "VixSrcProvider",
        outcome = StreamAttemptOutcome.TIMEOUT,
        durationMs = 15_000
      )
    )

    assertTrue(line.contains("search=-"))
    assertTrue(line.contains("mirror=-"))
    assertTrue(line.contains("extractor=-"))
    assertTrue(line.contains("queueMs=-"))
    // Senza runMs esplicito si usa la durata totale: nessuna metrica inventata.
    assertTrue(line.contains("runMs=15000"))
  }

  @Test
  fun messaggioNonPuoIniettareRigheNeFalsificareLaRicerca() {
    val line = LogcatStreamDiagnosticSink.format(
      StreamAttempt(
        providerId = "Probe",
        outcome = StreamAttemptOutcome.HTTP_ERROR,
        durationMs = 5,
        searchId = 7,
        message = "riga1\nsearch=99 provider=Altro\rriga2"
      )
    )

    assertFalse("nessuna nuova riga nel log", line.contains('\n') || line.contains('\r'))
    assertTrue(line.startsWith("search=7 provider=Probe"))
    assertFalse("il searchId iniettato non deve comparire", line.contains("search=99"))
    // Il testo originale resta leggibile come messaggio.
    assertTrue(line.contains("msg=riga1 provider=Altro riga2"))
  }
}

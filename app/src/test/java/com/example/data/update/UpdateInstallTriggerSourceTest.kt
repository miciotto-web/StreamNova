package com.example.data.update

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Contratto di sorgente sul flusso di installazione: l'installer di sistema
 * deve essere aperto SOLO da un gestore esplicito di click dell'utente
 * ("Installa") e mai da un effetto automatico legato al completamento del
 * download.
 *
 * Il flusso vive in due posti — il dialog delle Impostazioni e il popup del
 * controllo automatico di avvio, che condivide il blocco download/installa — e il
 * contratto vale per ENTRAMBI: le sorgenti vengono lette tutte, e ogni chiamata
 * all'installer trovata deve rispettarlo.
 *
 * La verifica è su sorgente perché `ApkInstaller` è un object di sistema:
 * una dipendenza iniettabile per un test di UI non aggiungerebbe garanzie
 * reali sul comportamento osservato dall'utente.
 */
class UpdateInstallTriggerSourceTest {

  /** Sorgenti che possono avviare l'installer: Impostazioni e popup di avvio. */
  private fun installerSources(): List<File> =
    listOf("SubScreens.kt", "UpdateDialogs.kt")
      .mapNotNull { name ->
        listOf(
          File("src/main/java/com/example/ui/screens/$name"),
          File("app/src/main/java/com/example/ui/screens/$name")
        ).firstOrNull { it.isFile }
      }

  @Test
  fun installerIsOnlyLaunchedFromAnExplicitClickHandler() {
    val sources = installerSources()
    assumeTrue("Sorgenti dei dialog non raggiungibili", sources.isNotEmpty())

    val launchMarker = "ApkInstaller.launch("
    var occurrences = 0
    sources.forEach { source ->
      val text = source.readText()
      var index = text.indexOf(launchMarker)
      while (index >= 0) {
        occurrences++
        val before = text.substring(0, index)
        val lastClick = before.lastIndexOf("onClick")
        val lastEffect = before.lastIndexOf("LaunchedEffect(")
        val lastButton = before.lastIndexOf("UpdateDialogButton(")
        assertTrue(
          "ApkInstaller.launch deve stare in un onClick di un pulsante (${source.name})",
          lastClick >= 0 && lastClick > lastEffect
        )
        assertTrue(
          "ApkInstaller.launch deve stare nel pulsante Installa (${source.name})",
          lastButton >= 0 && lastButton > lastEffect && lastClick > lastButton
        )
        index = text.indexOf(launchMarker, index + 1)
      }
    }

    assertTrue("Il pulsante Installa deve esistere", occurrences >= 1)
  }

  @Test
  fun noAutomaticInstallerLaunchedWhenTheDownloadCompletes() {
    val sources = installerSources()
    assumeTrue("Sorgenti dei dialog non raggiungibili", sources.isNotEmpty())

    sources.forEach { source ->
      val text = source.readText()
      // Nessun effetto automatico deve menzionare l'installer o gli stati di
      // download: il download completato mostra solo il pulsante Installa.
      val effectBlocks = extractLaunchedEffectBlocks(text)
      effectBlocks.forEach { block ->
        assertTrue(
          "Un LaunchedEffect non deve avviare l'installer o osservare il download (${source.name})",
          !block.contains("ApkInstaller") && !block.contains("downloadState")
        )
      }
      // Il vecchio avvio automatico passava dal path del file completato.
      assertTrue(
        "Nessun riferimento residuo a launchedApkPath (${source.name})",
        !text.contains("launchedApkPath")
      )
    }
  }

  private fun extractLaunchedEffectBlocks(text: String): List<String> {
    val blocks = mutableListOf<String>()
    var searchFrom = 0
    while (true) {
      val start = text.indexOf("LaunchedEffect(", searchFrom)
      if (start < 0) break
      val openBrace = text.indexOf('{', start)
      if (openBrace < 0) break
      var depth = 0
      var end = openBrace
      while (end < text.length) {
        when (text[end]) {
          '{' -> depth++
          '}' -> {
            depth--
            if (depth == 0) break
          }
        }
        end++
      }
      if (end >= text.length) break
      blocks += text.substring(openBrace, end + 1)
      searchFrom = end + 1
    }
    return blocks
  }
}

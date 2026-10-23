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
 * La verifica è su sorgente perché `ApkInstaller` è un object di sistema:
 * una dipendenza iniettabile per un test di UI non aggiungerebbe garanzie
 * reali sul comportamento osservato dall'utente.
 */
class UpdateInstallTriggerSourceTest {

  private fun settingsSource(): File? =
    listOf(
      File("src/main/java/com/example/ui/screens/SubScreens.kt"),
      File("app/src/main/java/com/example/ui/screens/SubScreens.kt")
    ).firstOrNull { it.isFile }

  @Test
  fun installerIsOnlyLaunchedFromAnExplicitClickHandler() {
    val source = settingsSource()
    assumeTrue("Sorgente Impostazioni non raggiungibile", source != null)
    val text = source!!.readText()

    val launchMarker = "ApkInstaller.launch("
    var index = text.indexOf(launchMarker)
    var occurrences = 0
    while (index >= 0) {
      occurrences++
      val before = text.substring(0, index)
      val lastClick = before.lastIndexOf("onClick")
      val lastEffect = before.lastIndexOf("LaunchedEffect(")
      val lastButton = before.lastIndexOf("UpdateDialogButton(")
      assertTrue(
        "ApkInstaller.launch deve stare in un onClick di un pulsante",
        lastClick >= 0 && lastClick > lastEffect
      )
      assertTrue(
        "ApkInstaller.launch deve stare nel pulsante Installa",
        lastButton >= 0 && lastButton > lastEffect && lastClick > lastButton
      )
      index = text.indexOf(launchMarker, index + 1)
    }

    assertTrue("Il pulsante Installa deve esistere", occurrences >= 1)
  }

  @Test
  fun noAutomaticInstallerLaunchedWhenTheDownloadCompletes() {
    val source = settingsSource()
    assumeTrue("Sorgente Impostazioni non raggiungibile", source != null)
    val text = source!!.readText()

    // Nessun effetto automatico deve menzionare l'installer o gli stati di
    // download: il download completato mostra solo il pulsante Installa.
    val effectBlocks = extractLaunchedEffectBlocks(text)
    effectBlocks.forEach { block ->
      assertTrue(
        "Un LaunchedEffect non deve avviare l'installer o osservare il download",
        !block.contains("ApkInstaller") && !block.contains("downloadState")
      )
    }
    // Il vecchio avvio automatico passava dal path del file completato.
    assertTrue(
      "Nessun riferimento residuo a launchedApkPath",
      !text.contains("launchedApkPath")
    )
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

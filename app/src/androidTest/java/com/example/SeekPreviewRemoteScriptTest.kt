package com.example

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Script di collaudo su device reale per l'anteprima video durante lo scrubbing.
 *
 * NON è un test funzionale (nessuna asserzione): emula il telecomando con la cadenza di un
 * utente reale (~500 ms tra una pressione e l'altra, sotto la soglia di commit-per-silenzio
 * di 900 ms) e salva screenshot nei momenti in cui l'overlay di anteprima deve mostrare un
 * fotogramma. Serve perché `adb shell input keyevent` su Fire TV costa ~2 s per iniezione:
 * con quella latenza ogni pressione diventa una sessione separata e la cattura non può
 * completare. `sendKeyDownUpSync` inietta via InputManager con latenza trascurabile,
 * esattamente come un tasto del telecomando.
 *
 * Sequenza (il player deve già essere in riproduzione con HUD nascosto):
 * 1. A.1 — 12 pressioni AVANTI con tre scatti nella stessa sessione: i fotogrammi devono
 *    comparire e cambiare durante la corsa (s01a/s01b/s01c);
 * 2. A.2 — 10 pressioni INDIETRO con scatti intermedi: nuove immagini verso il passato, non il
 *    fotogramma del punto di partenza ripetuto (s02a/s02b/s02c);
 * 3. A.3 — alternanza avanti/indietro senza chiudere l'overlay: ogni inversione deve produrre
 *    un fotogramma nuovo (s03);
 * 4. A.4 + A.5 — movimento rapido a 250 ms e arresto improvviso, poi osservazione del frame
 *    finale entro la finestra prima del commit-per-silenzio (s04a/s04b);
 * 5. A.6a — OK: conferma la seek e chiude l'overlay (s05);
 * 6. A.6b — BACK: annullamento senza seek (s06).
 *
 * Gli screenshot finiscono nella directory interna dell'app (`files/seekproof/`), da
 * recuperare con `run-as`; i log `SeekPreview`/`SeekPreviewSrc`/`ScrubSeek` completano le
 * prove. Dal 25-10-2026 il controller emette una riga `frame:` per ogni candidato pubblicato
 * o scartato, con posizione richiesta, PTS decodificato, posizione mostrata, scarto
 * dall'intento, latenza, hash, direzione, cache hit/miss e motivo dell'eventuale scarto: è
 * l'evidenza numerica richiesta dal piano di verifica, e sostituisce il confronto a occhio.
 */
@RunWith(AndroidJUnit4::class)
class SeekPreviewRemoteScriptTest {

  @Test
  fun scrubbingRemoteScript() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    // L'iniezione di eventi verso un'altra applicazione richiede INJECT_EVENTS: la
    // identità shell (che la possiede) viene adottata solo per lo script di collaudo.
    instrumentation.uiAutomation.adoptShellPermissionIdentity(
      // Costante @hide non esposta dallo SDK pubblico: si passa il nome letterale.
      "android.permission.INJECT_EVENTS"
    )
    val outDir = File(instrumentation.targetContext.filesDir, "seekproof").apply { mkdirs() }

    fun press(keyCode: Int, pauseMs: Long = 500L) {
      instrumentation.sendKeyDownUpSync(keyCode)
      SystemClock.sleep(pauseMs)
    }

    fun shot(name: String) {
      val bitmap: Bitmap? = instrumentation.uiAutomation.takeScreenshot()
      if (bitmap != null) {
        FileOutputStream(File(outDir, name)).use { out ->
          bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        bitmap.recycle()
      }
    }

    /** Vero finché siamo sul launcher di StreamNova (la riga "Continua a guardare" esiste). */
    fun appRoot(): android.view.accessibility.AccessibilityNodeInfo? {
      val windows = instrumentation.uiAutomation.getWindows()
      val fromWindow = windows?.firstOrNull { window ->
        window.root?.packageName?.toString()?.contains("streamnova") == true
      }?.root
      return fromWindow ?: instrumentation.uiAutomation.rootInActiveWindow
    }

    fun onHome(): Boolean {
      val root = appRoot()
      android.util.Log.i("RemoteScript", "onHome: root=${root != null} pkg=${root?.packageName}")
      return root?.findAccessibilityNodeInfosByText("Continua a guardare")?.isNotEmpty() == true
    }

    /** Primi testi visibili nell'albero di accessibilità (diagnosi). */
    fun dumpTexts(tag: String) {
      val root = appRoot() ?: return
      val texts = mutableListOf<String>()
      fun walk(node: android.view.accessibility.AccessibilityNodeInfo, depth: Int) {
        if (depth > 25 || texts.size >= 25) return
        node.text?.takeIf { it.isNotEmpty() }?.let { texts.add(it.toString()) }
        for (i in 0 until node.childCount) {
          val child = node.getChild(i) ?: continue
          walk(child, depth + 1)
          child.recycle()
        }
      }
      walk(root, 0)
      android.util.Log.i("RemoteScript", "$tag testi=${texts.joinToString(" | ")}")
    }

    /** Porta il focus sulla card "Continua a guardare" (Fight Club) via semantica di accessibilità. */
    fun focusContinueWatchingCard(): Boolean {
      val root = appRoot() ?: return false
      val badge = root.findAccessibilityNodeInfosByText("Fight Club")?.firstOrNull()
        ?: return false
      var node: android.view.accessibility.AccessibilityNodeInfo? = badge
      while (node != null && !node.isFocusable) node = node.parent
      val done = node?.performAction(
        android.view.accessibility.AccessibilityNodeInfo.ACTION_FOCUS
      ) == true
      val where = android.graphics.Rect()
      node?.getBoundsInScreen(where)
      android.util.Log.i("RemoteScript", "focusCard: nodo=$where azione=$done")
      return done
    }

    /** Ripiego tattile: tocco il centro della card "Continua a guardare". */
    fun tapContinueWatchingCard() {
      val root = appRoot() ?: return
      val badge = root.findAccessibilityNodeInfosByText("Fight Club")?.firstOrNull() ?: return
      var node: android.view.accessibility.AccessibilityNodeInfo? = badge
      val where = android.graphics.Rect()
      while (node != null) {
        node.getBoundsInScreen(where)
        if (where.width() >= 300) break
        node = node.parent
      }
      if (node == null || where.width() < 300) return
      android.util.Log.i("RemoteScript", "tapCard: bounds=$where")
      val downTime = android.os.SystemClock.uptimeMillis()
      val x = where.centerX().toFloat()
      val y = where.centerY().toFloat()
      instrumentation.uiAutomation.injectInputEvent(
        android.view.MotionEvent.obtain(downTime, downTime, android.view.MotionEvent.ACTION_DOWN, x, y, 0),
        true
      )
      instrumentation.uiAutomation.injectInputEvent(
        android.view.MotionEvent.obtain(downTime, downTime + 80, android.view.MotionEvent.ACTION_UP, x, y, 0),
        true
      )
    }

    // `am instrument` crea il processo dell'app ma NON avvia alcuna Activity: si lancia
    // esplicitamente il launcher di StreamNova, poi si apre il contenuto di
    // "Continua a guardare" (Fight Club, stream già risolto) e si attende la riproduzione.
    val launchIntent = instrumentation.targetContext.packageManager
      .getLaunchIntentForPackage(instrumentation.targetContext.packageName)
    if (launchIntent != null) {
      launchIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
      instrumentation.targetContext.startActivity(launchIntent)
    }
    // Attesa del rendering della riga "Continua a guardare" (max ~20 s).
    SystemClock.sleep(5_000)
    var waited = 0
    while (!onHome() && waited < 20) {
      SystemClock.sleep(1_000)
      waited++
    }
    dumpTexts("albero")
    if (focusContinueWatchingCard()) {
      press(KeyEvent.KEYCODE_DPAD_CENTER, 14_000L)
    } else {
      // Ripiego: navigazione con i tasti.
      press(KeyEvent.KEYCODE_DPAD_DOWN, 1_500L)
      press(KeyEvent.KEYCODE_DPAD_CENTER, 14_000L)
    }
    if (onHome()) {
      // Ripiego tattile sul centro della card.
      tapContinueWatchingCard()
      SystemClock.sleep(14_000L)
    }
    android.util.Log.i("RemoteScript", "dopo navigazione onHome=${onHome()}")
    shot("s00_player.png")

    // FASE 1 (A.1): avanti. Una sola sessione (cadenza < 900 ms): la miniatura deve mostrare
    // fotogrammi reali e cambiarli durante le pressioni, prima di qualunque conferma. Si scatta
    // tre volte nella stessa sessione per poter confrontare i contenuti: hash diversi e PTS
    // crescenti sono la prova che la corsa si vede davvero, non un frame ripetuto.
    repeat(4) { press(KeyEvent.KEYCODE_DPAD_RIGHT) }
    press(KeyEvent.KEYCODE_DPAD_RIGHT, 0L) // mantiene la sessione viva durante lo scatto
    shot("s01a_forward.png")
    repeat(4) { press(KeyEvent.KEYCODE_DPAD_RIGHT) }
    press(KeyEvent.KEYCODE_DPAD_RIGHT, 0L)
    shot("s01b_forward.png")
    repeat(4) { press(KeyEvent.KEYCODE_DPAD_RIGHT) }
    press(KeyEvent.KEYCODE_DPAD_RIGHT, 0L)
    shot("s01c_forward.png")

    // FASE 2 (A.2): indietro. Nuove immagini verso il passato. È la sequenza che prima della
    // correzione restava bloccata sul fotogramma del punto di partenza: gli screenshot intermedi
    // servono a dimostrare che la scena cambia DURANTE la corsa inversa, non solo per effetto
    // della cache ereditata dalla fase avanti.
    repeat(3) { press(KeyEvent.KEYCODE_DPAD_LEFT) }
    press(KeyEvent.KEYCODE_DPAD_LEFT, 0L)
    shot("s02a_backward.png")
    repeat(3) { press(KeyEvent.KEYCODE_DPAD_LEFT) }
    press(KeyEvent.KEYCODE_DPAD_LEFT, 0L)
    shot("s02b_backward.png")
    repeat(4) { press(KeyEvent.KEYCODE_DPAD_LEFT) }
    press(KeyEvent.KEYCODE_DPAD_LEFT, 0L)
    shot("s02c_backward.png")

    // FASE 3 (A.3): alternanza avanti/indietro SENZA chiudere l'overlay. Le inversioni sono il
    // caso in cui un gate legato al verso dei PTS si rompe: qui si verifica che ogni inversione
    // produca comunque un fotogramma nuovo.
    for (keyCode in intArrayOf(
      KeyEvent.KEYCODE_DPAD_RIGHT,
      KeyEvent.KEYCODE_DPAD_RIGHT,
      KeyEvent.KEYCODE_DPAD_LEFT,
      KeyEvent.KEYCODE_DPAD_LEFT,
      KeyEvent.KEYCODE_DPAD_LEFT,
      KeyEvent.KEYCODE_DPAD_RIGHT,
      KeyEvent.KEYCODE_DPAD_RIGHT
    )) {
      press(keyCode)
    }
    press(KeyEvent.KEYCODE_DPAD_RIGHT, 0L)
    shot("s03_alternating.png")

    // FASE 4 (A.4 + A.5): movimento rapido poi arresto improvviso, e si osserva cosa resta a
    // video. Con cadenza 250 ms — sotto il costo di un seek su mt8696 — le richieste si
    // accumulano e la conflazione ne scarta molte: l'ultimo fotogramma deve essere coerente con
    // l'ULTIMA posizione, non con una tappa intermedia della corsa.
    repeat(12) { press(KeyEvent.KEYCODE_DPAD_RIGHT, 250L) }
    press(KeyEvent.KEYCODE_DPAD_RIGHT, 0L)
    shot("s04a_fast_stop.png")
    // A.5: oltre COMMIT_DELAY_MS (900 ms) la sessione si conferma da sola e l'overlay si
    // chiude, quindi il frame finale va osservato entro quella finestra: si riapre la sessione
    // con un passo minimo e si riscatta subito, così il confronto è sulla stessa posizione.
    press(KeyEvent.KEYCODE_DPAD_LEFT)
    press(KeyEvent.KEYCODE_DPAD_LEFT, 0L)
    shot("s04b_after_wait.png")

    // FASE 5 (A.6a): OK conferma la seek e chiude l'overlay.
    press(KeyEvent.KEYCODE_DPAD_CENTER, 1_500L)
    shot("s05_after_ok.png")

    // FASE 6 (A.6b): BACK annulla lo scrubbing senza seek.
    repeat(4) { press(KeyEvent.KEYCODE_DPAD_RIGHT) }
    press(KeyEvent.KEYCODE_BACK, 1_500L)
    shot("s06_after_back.png")
  }
}

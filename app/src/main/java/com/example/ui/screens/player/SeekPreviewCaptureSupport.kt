package com.example.ui.screens.player

import android.os.Build
import java.io.File
import java.util.Locale
import kotlin.math.abs

/**
 * Stabilisce se la cattura dei fotogrammi di anteprima (decoder dedicato + [android.media.ImageReader]
 * in YUV_420_888) è utilizzabile sulla piattaforma corrente.
 *
 * **Perché serve una verifica di piattaforma e non basta un `try/catch`.** Le plane di una
 * `Image` non sono dati Java: `Image.getPlanes()` finisce in `ImageReader$SurfaceImage.nativeCreatePlanes`
 * → `Image_createSurfacePlanes` dentro `libmedia_jni.so`, che mappa il buffer grafico e passa
 * ogni puntatore a `NewDirectByteBuffer`. Se il buffer consegnato dal decoder non è mappabile
 * come YUV (il codec scrive un formato proprietario/tiled), il puntatore è nullo mentre la
 * capacità non lo è, e ART **termina il processo** (abort di CheckJNI, non un'eccezione):
 *
 * ```
 * Abort message: 'JNI DETECTED ERROR IN APPLICATION: non-zero capacity for nullptr pointer: 1
 *     in call to NewDirectByteBuffer
 *     from android.media.ImageReader$SurfaceImage$SurfacePlane[]
 *          android.media.ImageReader$SurfaceImage.nativeCreatePlanes(int, int)'
 * ```
 *
 * Non esiste alcun modo di interrogare l'`Image` *prima* di chiedere le plane (larghezza,
 * altezza e formato sono coerenti con la ImageReader configurata) e nessun `catch` può
 * sopravvivere a un `abort()`: l'unica difesa possibile è **non creare affatto** la
 * ImageReader su una piattaforma che si comporta così.
 *
 * Piattaforma nota non compatibile — tombstone del **2026-10-24** su Fire TV Stick 4K Max 2ª
 * generazione (`AFTMA08C15` / `karat`, **Fire OS 8.1.8.5** = Android 11 / API 30,
 * `armeabi-v7a`, SoC **mt8696**), crash alla prima pressione di scrubbing. Non è stato
 * verificato se il difetto riguardi tutto Fire OS o quel SoC: l'euristica spegne la cattura
 * su entrambe le firme, perché il modo di sbagliare è un processo terminato mentre il costo
 * dell'astensione è solo l'anteprima mancante (che resta annunciata dal placeholder).
 *
 * Cosa succede quando la piattaforma è dichiarata NON compatibile: questa classe decide solo
 * la sicurezza del percorso **ImageReader**. A partire dalla correzione P0 del 2026-10-24
 * l'estrattore NON disattiva più l'anteprima: ricade sul percorso GL
 * ([SeekPreviewGlFrameGrabber], SurfaceTexture + `glReadPixels`), che non tocca `Image` né le
 * sue plane e quindi non può riprodurre l'abort. Solo l'override `seekpreview.force-off`
 * disattiva del tutto l'estrazione (placeholder), e `seekpreview.force-gl` forza il percorso
 * GL anche dove la ImageReader sarebbe sicura (diagnosi del nuovo percorso). Lo scrubbing —
 * posizione proposta, conferma con OK, annullamento con BACK, pausa del player principale — è
 * quello di [PlayerScrubController] e non dipende da questa classe.
 */
internal object SeekPreviewCaptureSupport {

  /** File nella directory privata dell'app che forza la cattura ACCESA (verifica su device). */
  const val FORCE_ON_FILE = "seekpreview.force-on"

  /** File nella directory privata dell'app che forza la cattura SPENTA (verifica su device). */
  const val FORCE_OFF_FILE = "seekpreview.force-off"

  /** File che forza il percorso GL (SurfaceTexture) anche dove la ImageReader è sicura. */
  const val FORCE_GL_FILE = "seekpreview.force-gl"

  /** Vero se l'override manuale impone il percorso GL. */
  fun isGlForced(filesDir: File?): Boolean =
    filesDir != null && File(filesDir, FORCE_GL_FILE).exists()

  /** Produttori le cui implementazioni di ImageReader/codec non sono state verificate sicure. */
  private val UNSUPPORTED_MANUFACTURERS = setOf("amazon")

  /** SoC con il difetto osservato (mt8696 = Fire TV Stick 4K Max 2ª gen). */
  private val UNSUPPORTED_CHIPSETS = setOf("mt8696")

  /**
   * Decisione operativa: prima l'override manuale, poi la firma della piattaforma.
   *
   * @param filesDir directory privata dell'app (`Context.filesDir`); null nei test o se non
   *   disponibile, nel qual caso decide solo la piattaforma.
   */
  fun isCaptureSupported(filesDir: File?): Boolean {
    overrideFrom(filesDir)?.let { return it }
    return isCaptureSupportedByPlatform(
      manufacturer = Build.MANUFACTURER,
      board = Build.BOARD,
      hardware = Build.HARDWARE
    )
  }

  /**
   * Override manuale letto dalla directory privata dell'app, per la verifica su device senza
   * ricompilare: `true` = cattura forzata accesa, `false` = forzata spenta, `null` = nessun
   * override (decide la piattaforma).
   *
   * Lo spegnimento vince sull'accensione: con entrambi i file presenti non si deve poter
   * riaccendere una cattura che il test manuale ha appena dichiarato problematica.
   */
  fun overrideFrom(filesDir: File?): Boolean? {
    if (filesDir == null) return null
    if (File(filesDir, FORCE_OFF_FILE).exists()) return false
    if (File(filesDir, FORCE_ON_FILE).exists()) return true
    return null
  }

  /**
   * Decisione pura sulla firma della piattaforma: nessun accesso a `Context`, a file o a
   * stato globale, così è verificabile nei test JVM con i valori reali dei device osservati.
   */
  fun isCaptureSupportedByPlatform(
    manufacturer: String?,
    board: String?,
    hardware: String?
  ): Boolean {
    val manufacturerToken = manufacturer.normalized()
    if (manufacturerToken != null && manufacturerToken in UNSUPPORTED_MANUFACTURERS) return false
    return listOf(board, hardware)
      .map { it.normalized() }
      .none { it != null && it in UNSUPPORTED_CHIPSETS }
  }

  private fun String?.normalized(): String? =
    this?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
}

/**
 * Gate del PTS: decide se il frame presentato dal renderer appartiene al seek appena emesso.
 *
 * Vive qui (logica pura, nessuna dipendenza da Android) perché è il punto esatto in cui si
 * nascondeva il fotogramma fisso dello scrubbing inverso: il controllo precedente era un
 * confronto di vicinanza alla sola posizione richiesta, con una tolleranza di 10 s pari a un
 * passo intero di scrubbing. Il frame del punto di partenza rientrava sempre in quella finestra
 * e veniva accettato come se fosse quello nuovo — in avanti come all'indietro.
 */
internal object SeekPreviewSeekGate {

  /**
   * Finestra di atterraggio del seek (ms): scarto massimo fra PTS del frame presentato e
   * posizione richiesta perché il frame possa essere "quello del seek".
   *
   * Un decimo dei 10 s precedenti: copre la durata di pochi frame e l'arrotondamento dei PTS,
   * non un passo di scrubbing né un cambio di scena.
   */
  const val SEEK_LANDING_TOLERANCE_MS = 1_500L

  /** Entro questo scarto il frame è comunque il target: inutile confrontarlo con la partenza. */
  const val NEAR_TARGET_TOLERANCE_MS = 400L

  /** Il frame deve stare almeno così più vicino al target che al punto di partenza. */
  const val MIN_SEEK_PROGRESS_MS = 400L

  /**
   * Vero se [ptsMs] appartiene al seek da [preSeekMs] a [requestedMs], e non al punto di partenza.
   *
   * Le due condizioni sono simmetriche rispetto al verso: nessuna delle due parla di "maggiore"
   * o "minore", quindi un seek inverso è trattato esattamente come uno diretto. È questo che
   * mancava prima, e che rendeva impossibile qualsiasi aggiornamento all'indietro.
   */
  fun belongsToSeek(ptsMs: Long, requestedMs: Long, preSeekMs: Long): Boolean {
    val toTarget = abs(ptsMs - requestedMs)
    if (toTarget > SEEK_LANDING_TOLERANCE_MS) return false
    val toPreSeek = abs(ptsMs - preSeekMs)
    return toTarget <= NEAR_TARGET_TOLERANCE_MS || toTarget + MIN_SEEK_PROGRESS_MS < toPreSeek
  }
}

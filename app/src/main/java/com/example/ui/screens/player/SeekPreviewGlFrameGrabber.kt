package com.example.ui.screens.player

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Cattura fotogrammi via **OpenGL**: il decoder dedicato renderizza su una [SurfaceTexture]
 * fuori schermo e il frame viene letto con `glReadPixels` da un FBO.
 *
 * **Perché esiste questo percorso.** La cattura via [android.media.ImageReader] (YUV_420_888)
 * richiede di leggere le plane dell'`Image`: su Fire OS 8 (Fire TV Stick 4K Max 2ª gen,
 * `AFTMA08C15` / `mt8696`) quella lettura termina il processo dentro `libmedia_jni` — un
 * `abort()` di CheckJNI che nessun `catch` può intercettare (vedi [SeekPreviewCaptureSupport]).
 * Qui il buffer non lascia mai il compositor/GPU: nessuna `Image`, nessuna plane, nessun
 * puntatore nativo mappato in Java. Il percorso GL è quindi l'unico modo di mostrare
 * fotogrammi reali su quella piattaforma senza riprodurre il crash.
 *
 * Architettura invariata rispetto al percorso ImageReader: la [Surface] restituita da
 * [surface] viene collegata al secondo `ExoPlayer` dell'estrattore, che vi renderizza
 * normalmente; il PTS del frame si legge dal timestamp del buffer
 * ([GrabResult.timestampMs]) e, in parallelo, da `VideoFrameMetadataListener`.
 *
 * Threading: tutto lo stato GL vive sul thread [HandlerThread] interno, con un contesto
 * EGL pbuffer dedicato tenuto corrente su quel thread. [prepare] e [grab] sono sospesi e
 * vengono eseguiti lì dentro; [release] è idempotente e può essere chiamato da qualunque
 * thread. Se l'inizializzazione GL fallisce la classe lo dichiara ([prepare] restituisce
 * false) e l'anteprima mostra il placeholder: nessun crash, nessun blocco del player.
 */
internal class SeekPreviewGlFrameGrabber {

  /** Fotogrammi arrivati dal decoder: il consumer della SurfaceTexture (qualsiasi thread). */
  val frameSignal = AtomicInteger(0)

  /**
   * Buffer ancora da consumare nella coda della SurfaceTexture.
   *
   * Contatore separato da [frameSignal]: quest'ultimo viene azzerato a ogni seek per marcare
   * l'inizio della attesa, quindi non dice quanti buffer siano fisicamente in coda. È quello
   * che [drainPendingBuffers] deve scaricare prima di un seek.
   */
  private val queuedFrames = AtomicInteger(0)

  private var glThread: HandlerThread? = null
  private var glHandler: Handler? = null

  @Volatile
  private var ready = false

  @Volatile
  private var failed = false

  @Volatile
  private var outputSurface: Surface? = null

  // Stato GL: accessibile solo dal thread GL.
  private var eglDisplay: EGLDisplay? = null
  private var eglContext: EGLContext? = null
  private var eglSurface: EGLSurface? = null
  private var surfaceTexture: SurfaceTexture? = null
  private var oesTextureId = 0
  private var fboTextureId = 0
  private var fboId = 0
  private var program = 0
  private var aPosition = -1
  private var aTexCoord = -1
  private var uTexMatrix = -1
  private var uTexture = -1
  private var vertexBuffer: FloatBuffer? = null
  private var texCoordBuffer: FloatBuffer? = null
  private var pixelBuffer: ByteBuffer? = null
  private var texMatrix = FloatArray(16)
  private var outWidth = 0
  private var outHeight = 0

  init {
    val thread = HandlerThread("seek-preview-gl").apply { start() }
    glThread = thread
    glHandler = Handler(thread.looper)
  }

  /**
   * Crea contesto EGL, texture OES della [SurfaceTexture] e FBO di uscita
   * `outWidth` x `outHeight`. Idempotente: una seconda chiamata a buon fine è un no-op.
   *
   * @param videoWidth larghezza dei buffer del decoder (solo hint per la SurfaceTexture).
   * @param videoHeight altezza dei buffer del decoder (solo hint per la SurfaceTexture).
   * @return true se il percorso GL è utilizzabile; false se la piattaforma non lo consente
   *   (in quel caso la classe è da considerarsi definitivamente non disponibile).
   */
  suspend fun prepare(
    videoWidth: Int,
    videoHeight: Int,
    outWidth: Int,
    outHeight: Int
  ): Boolean {
    if (ready) return true
    if (failed) return false
    return runOnGl {
      try {
        initGl(videoWidth, videoHeight, outWidth, outHeight)
        ready = true
        Log.i(
          TAG,
          "GL grabber pronto: decoder ${videoWidth}x$videoHeight -> bitmap ${outWidth}x$outHeight " +
            "(EGL ${EGL14.eglQueryString(eglDisplay, EGL14.EGL_VERSION)})"
        )
        true
      } catch (error: Throwable) {
        failed = true
        Log.e(TAG, "GL grabber non inizializzabile: anteprima senza fotogrammi (nessun crash)", error)
        releaseGl()
        false
      }
    }
  }

  /** Superficie di rendering per il decoder dedicato; null se [prepare] non è riuscita. */
  fun surface(): Surface? = outputSurface

  /** Esito di una cattura: bitmap già convertita e posizione reale del buffer decodificato. */
  class GrabResult(val bitmap: Bitmap, val timestampMs: Long)

  /**
   * Aggiorna la texture sul frame più recente del decoder e ne legge il contenuto.
   *
   * @return il fotogramma con il suo timestamp di presentazione (ms), oppure null se la
   *   cattura non è possibile (grabber non pronto o già rilasciato).
   */
  suspend fun grab(): GrabResult? {
    if (!ready || failed) return null
    return try {
      runOnGl<GrabResult?> {
        val texture = surfaceTexture ?: return@runOnGl null
        texture.updateTexImage()
        // Il buffer consumato dalla cattura non è più in coda: aggiornare il contatore evita
        // che un drain successivo al try di un altro seek scarichi frame che non esistono.
        queuedFrames.decrementAndGet()
        texture.getTransformMatrix(texMatrix)
        drawFrame()
        val buffer = pixelBuffer ?: return@runOnGl null
        buffer.clear()
        GLES20.glReadPixels(0, 0, outWidth, outHeight, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)
        checkGlError("glReadPixels")
        buffer.rewind()
        // glReadPixels restituisce le righe dal basso (convenzione GL): il bitmap va
        // ribaltato verticalmente per mostrare il fotogramma nell'orientamento del video.
        val raw = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
        raw.copyPixelsFromBuffer(buffer)
        val flip = Matrix().apply { postScale(1f, -1f) }
        val bitmap = Bitmap.createBitmap(raw, 0, 0, outWidth, outHeight, flip, true)
        raw.recycle()
        GrabResult(bitmap, texture.timestamp / 1_000_000L)
      }
    } catch (error: Throwable) {
      if (error is kotlin.coroutines.cancellation.CancellationException) throw error
      Log.e(TAG, "cattura GL fallita", error)
      null
    }
  }

  /**
   * Scarica i buffer già presentati dal decoder, senza restituirli a nessuno.
   *
   * Da chiamare **prima** di un seek. I buffer accodati prima del seek contengono le immagini
   * del punto di partenza: la SurfaceTexture li restituirebbe comunque al `updateTexImage()`
   * successivo, e il fotogramma vecchio finirebbe catturato dopo il seek con addosso il
   * timestamp della posizione nuova. Azzerare solo il contatore del segnale — come faceva la
   * versione precedente — non li rimuove dalla coda, ed è il motivo per cui invertendo i pixel
   * non cambiavano mai.
   *
   * @return numero di buffer scaricati.
   */
  suspend fun drainPendingBuffers(maxBuffers: Int = 8): Int {
    if (!ready || failed) return 0
    return try {
      runOnGl {
        val texture = surfaceTexture ?: return@runOnGl 0
        var drained = 0
        while (drained < maxBuffers && queuedFrames.get() > 0) {
          queuedFrames.decrementAndGet()
          val consumed = runCatching { texture.updateTexImage() }.isSuccess
          if (!consumed) break
          drained++
        }
        drained
      }
    } catch (error: Throwable) {
      if (error is kotlin.coroutines.cancellation.CancellationException) throw error
      Log.w(TAG, "scarico buffer pre-seek fallito", error)
      0
    }
  }

  /** Rilascia contesto GL, superfici e thread. Idempotente, chiamabile da ogni thread. */
  fun release() {
    val handler = glHandler ?: return
    val thread = glThread ?: return
    glHandler = null
    glThread = null
    ready = false
    handler.post {
      releaseGl()
      thread.quitSafely()
    }
  }

  // ---------------------------------------------------------------------------
  // Esecuzione sul thread GL
  // ---------------------------------------------------------------------------

  private suspend fun <T> runOnGl(block: () -> T): T =
    suspendCancellableCoroutine { continuation ->
      val handler = glHandler
      val posted = handler?.post {
        if (!continuation.isActive) return@post
        try {
          continuation.resume(block())
        } catch (error: Throwable) {
          continuation.resumeWithException(error)
        }
      } ?: false
      if (!posted) {
        continuation.resumeWithException(IllegalStateException("thread GL chiuso"))
      }
    }

  private fun initGl(videoWidth: Int, videoHeight: Int, outW: Int, outH: Int) {
    outWidth = outW
    outHeight = outH

    val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    if (display == EGL14.EGL_NO_DISPLAY) throw IllegalStateException("EGL display non disponibile")
    val version = IntArray(2)
    if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
      throw IllegalStateException("EGL inizializzazione fallita")
    }
    eglDisplay = display

    val configAttribs = intArrayOf(
      EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
      EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
      EGL14.EGL_RED_SIZE, 8,
      EGL14.EGL_GREEN_SIZE, 8,
      EGL14.EGL_BLUE_SIZE, 8,
      EGL14.EGL_ALPHA_SIZE, 8,
      EGL14.EGL_NONE
    )
    val configs = arrayOfNulls<EGLConfig>(1)
    val numConfigs = IntArray(1)
    if (!EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfigs, 0) ||
      numConfigs[0] <= 0
    ) {
      throw IllegalStateException("Nessuna config EGL compatibile")
    }
    val config = configs[0]!!

    val context = EGL14.eglCreateContext(
      display,
      config,
      EGL14.EGL_NO_CONTEXT,
      intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
      0
    )
    if (context == null || context == EGL14.EGL_NO_CONTEXT) {
      throw IllegalStateException("EGL context non creabile")
    }
    eglContext = context

    val pbuffer = EGL14.eglCreatePbufferSurface(
      display,
      config,
      intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE),
      0
    )
    if (pbuffer == null || pbuffer == EGL14.EGL_NO_SURFACE) {
      throw IllegalStateException("Pbuffer EGL non creabile")
    }
    eglSurface = pbuffer
    if (!EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)) {
      throw IllegalStateException("eglMakeCurrent fallita")
    }

    val textures = IntArray(2)
    GLES20.glGenTextures(2, textures, 0)
    oesTextureId = textures[0]
    fboTextureId = textures[1]
    checkGlError("glGenTextures")

    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

    val texture = SurfaceTexture(oesTextureId)
    texture.setDefaultBufferSize(videoWidth.coerceAtLeast(2), videoHeight.coerceAtLeast(2))
    texture.setOnFrameAvailableListener(
      {
        val count = frameSignal.incrementAndGet()
        queuedFrames.incrementAndGet()
        Log.i(TAG, "frame disponibile dalla SurfaceTexture (segnale=$count)")
      },
      glHandler
    )
    surfaceTexture = texture
    outputSurface = Surface(texture)

    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTextureId)
    GLES20.glTexImage2D(
      GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, outWidth, outHeight, 0,
      GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
    )
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    checkGlError("fbo texture")

    val framebuffers = IntArray(1)
    GLES20.glGenFramebuffers(1, framebuffers, 0)
    fboId = framebuffers[0]
    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
    GLES20.glFramebufferTexture2D(
      GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fboTextureId, 0
    )
    val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
    if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
      throw IllegalStateException("FBO incompleto: 0x${Integer.toHexString(status)}")
    }

    program = buildProgram()
    GLES20.glUseProgram(program)
    aPosition = GLES20.glGetAttribLocation(program, "aPosition")
    aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
    uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")
    uTexture = GLES20.glGetUniformLocation(program, "sTexture")

    vertexBuffer = floatBufferOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    texCoordBuffer = floatBufferOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
    pixelBuffer = ByteBuffer.allocateDirect(outWidth * outHeight * 4).order(ByteOrder.nativeOrder())

    GLES20.glViewport(0, 0, outWidth, outHeight)
    checkGlError("initGl")
  }

  /** Disegna la texture OES corrente nel FBO di uscita, applicando la matrice SurfaceTexture. */
  private fun drawFrame() {
    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
    GLES20.glUseProgram(program)
    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
    GLES20.glUniform1i(uTexture, 0)
    GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)
    GLES20.glEnableVertexAttribArray(aPosition)
    GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
    GLES20.glEnableVertexAttribArray(aTexCoord)
    GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)
    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    GLES20.glDisableVertexAttribArray(aPosition)
    GLES20.glDisableVertexAttribArray(aTexCoord)
    checkGlError("drawFrame")
  }

  private fun buildProgram(): Int {
    val vertexShader = compileShader(
      GLES20.GL_VERTEX_SHADER,
      """
      attribute vec4 aPosition;
      attribute vec4 aTexCoord;
      uniform mat4 uTexMatrix;
      varying vec2 vTexCoord;
      void main() {
        gl_Position = aPosition;
        vTexCoord = (uTexMatrix * aTexCoord).xy;
      }
      """.trimIndent()
    )
    val fragmentShader = compileShader(
      GLES20.GL_FRAGMENT_SHADER,
      """
      #extension GL_OES_EGL_image_external : require
      precision mediump float;
      varying vec2 vTexCoord;
      uniform samplerExternalOES sTexture;
      void main() {
        gl_FragColor = texture2D(sTexture, vTexCoord);
      }
      """.trimIndent()
    )
    val program = GLES20.glCreateProgram()
    if (program == 0) throw IllegalStateException("Programma GL non creabile")
    GLES20.glAttachShader(program, vertexShader)
    GLES20.glAttachShader(program, fragmentShader)
    GLES20.glLinkProgram(program)
    val linked = IntArray(1)
    GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
    if (linked[0] == 0) {
      val log = GLES20.glGetProgramInfoLog(program)
      GLES20.glDeleteProgram(program)
      throw IllegalStateException("Link del programma GL fallito: $log")
    }
    GLES20.glDeleteShader(vertexShader)
    GLES20.glDeleteShader(fragmentShader)
    return program
  }

  private fun compileShader(type: Int, source: String): Int {
    val shader = GLES20.glCreateShader(type)
    if (shader == 0) throw IllegalStateException("Shader GL non creabile (type=$type)")
    GLES20.glShaderSource(shader, source)
    GLES20.glCompileShader(shader)
    val compiled = IntArray(1)
    GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
    if (compiled[0] == 0) {
      val log = GLES20.glGetShaderInfoLog(shader)
      GLES20.glDeleteShader(shader)
      throw IllegalStateException("Compilazione shader fallita: $log")
    }
    return shader
  }

  private fun floatBufferOf(vararg values: Float): FloatBuffer =
    ByteBuffer.allocateDirect(values.size * 4)
      .order(ByteOrder.nativeOrder())
      .asFloatBuffer()
      .apply { put(values); rewind() }

  private fun checkGlError(op: String) {
    val error = GLES20.glGetError()
    if (error != GLES20.GL_NO_ERROR) {
      throw IllegalStateException("$op: errore GL 0x${Integer.toHexString(error)}")
    }
  }

  private fun releaseGl() {
    runCatching {
      surfaceTexture?.setOnFrameAvailableListener(null)
      surfaceTexture?.release()
      surfaceTexture = null
      outputSurface?.release()
      outputSurface = null
      if (program != 0) GLES20.glDeleteProgram(program)
      program = 0
      val textures = intArrayOf(oesTextureId, fboTextureId)
      GLES20.glDeleteTextures(2, textures, 0)
      oesTextureId = 0
      fboTextureId = 0
      if (fboId != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(fboId), 0)
      fboId = 0
      val display = eglDisplay
      if (display != null) {
        EGL14.eglMakeCurrent(
          display,
          EGL14.EGL_NO_SURFACE,
          EGL14.EGL_NO_SURFACE,
          EGL14.EGL_NO_CONTEXT
        )
        eglSurface?.let { EGL14.eglDestroySurface(display, it) }
        eglContext?.let { EGL14.eglDestroyContext(display, it) }
        EGL14.eglTerminate(display)
      }
      eglDisplay = null
      eglContext = null
      eglSurface = null
      pixelBuffer = null
    }.onFailure { Log.w(TAG, "rilascio GL incompleto", it) }
  }

  private companion object {
    const val TAG = "SeekPreviewSrc"
  }
}

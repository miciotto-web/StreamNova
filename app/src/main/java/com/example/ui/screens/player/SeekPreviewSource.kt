package com.example.ui.screens.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Impronta compatta del contenuto visivo di una bitmap (average hash 8x8).
 *
 * Serve alla verifica oggettiva che fotogrammi pubblicati per posizioni diverse siano
 * contenuti REALMENTE diversi: due hash uguali per posizioni lontane indicano che il
 * renderer non ha presentato un nuovo frame (fotogramma vecchio etichettato con la
 * posizione nuova). È tracciamento, non logica funzionale.
 */
internal object SeekPreviewFrameProbe {

  fun contentHash(bitmap: Bitmap): String {
    return runCatching {
      val small = Bitmap.createScaledBitmap(bitmap, 8, 8, true)
      val gray = LongArray(64)
      for (i in 0 until 64) {
        val px = small.getPixel(i % 8, i / 8)
        gray[i] = (((px shr 16) and 0xFF) * 30L + ((px shr 8) and 0xFF) * 59L + (px and 0xFF) * 11L) / 100L
      }
      var sum = 0L
      for (g in gray) sum += g
      val avg = sum / 64L
      var value = 0L
      for (i in 0 until 64) {
        if (gray[i] >= avg) value = value or (1L shl i)
      }
      small.recycle()
      java.lang.String.format("%016x", value)
    }.getOrDefault("unavailable")
  }
}

/**
 * Stato dell'anteprima video pubblicato dal [SeekPreviewController].
 *
 * Un solo elemento descrive sempre la posizione *migliore disponibile*:
 *
 * - `Loading(positionMs)`: la posizione richiesta non ha (ancora) un fotogramma. Se
 *   `frame` è valorizzato si tratta di un refresh discreto (posizione nuova in arrivo,
 *   fotogramma precedente ancora a video); se è null non c'è nulla da mostrare.
 * - `Frame(requestedPositionMs, frame)`: [frame] corrisponde a `positionMs`.
 * - `Unavailable(positionMs)`: fonte non ricercabile, fonte assente, errore di rete o
 *   nessun fotogramma estraibile. La ricerca continua a funzionare normalmente.
 *
 * In nessun caso viene mostrato un fotogramma di una posizione diversa da quella
 * scritta accanto: al cambio sorgente lo stato viene azzerato.
 *
 * **Approssimazione vs risultato definitivo.** Durante il movimento un frame lontano dalla
 * posizione voluta può essere mostrato, ma [Frame.approximate] lo dichiara e l'UI lo disegna
 * in modo diverso: solo [Frame.isDefinitive] vuol dire "questo è il fotogramma del punto che
 * hai scelto". La correzione P1 del 2026-10-25 introduce [Frame.requestedPositionMs] proprio
 * per non perdere l'informazione di *cosa era stato chiesto* quando il frame è stato scelto:
 * senza di essa un'approssimazione mostrata a metà corsa restava indistinguibile dall'esito
 * della posizione finale.
 */
internal sealed interface SeekPreviewState {

  val positionMs: Long

  data class Loading(
    override val positionMs: Long,
    val frame: SeekPreviewFrame? = null
  ) : SeekPreviewState

  /**
   * Fotogramma attualmente a video.
   *
   * @param positionMs posizione REALE del frame mostrato: il PTS di presentazione quando
   *   [frame] ha un PTS autorevole ([SeekPreviewFrame.ptsAuthoritative]), altrimenti la
   *   migliore associazione disponibile (e in tal caso [approximate] è true).
   * @param requestedPositionMs posizione che l'utente aveva chiesto quando questo frame è
   *   stato messo a video.
   * @param approximate true quando il frame NON è il risultato definitivo della richiesta:
   *   PTS non confermato dal decoder o scarto oltre la tolleranza di coincidenza.
   */
  data class Frame(
    override val positionMs: Long,
    val frame: SeekPreviewFrame,
    val requestedPositionMs: Long = positionMs,
    val approximate: Boolean = !frame.ptsAuthoritative
  ) : SeekPreviewState {

    /** Scarto assoluto tra PTS mostrato e posizione richiesta dall'utente. */
    val driftMs: Long get() = kotlin.math.abs(positionMs - requestedPositionMs)

    /**
     * Vero solo se il frame rappresenta davvero la posizione richiesta: niente PTS approssimativo
     * e scarto entro la tolleranza di coincidenza. È l'unica condizione che autorizza l'UI a
     * presentare il fotogramma come "il punto che hai scelto".
     */
    val isDefinitive: Boolean
      get() = !approximate && driftMs <= SeekPreviewController.FRAME_MATCH_TOLERANCE_MS
  }

  data class Unavailable(
    override val positionMs: Long
  ) : SeekPreviewState
}

/**
 * Fotogramma decodificato per l'anteprima, con la posizione della sorgente a cui appartiene.
 *
 * @param positionMs posizione del frame: il **PTS reale** di presentazione quando
 *   [ptsAuthoritative] è true; la posizione a cui il frame è stato associato quando è false
 *   (decoder che non ha confermato un PTS coerente con il seek).
 * @param ptsAuthoritative false quando il PTS non proviene dal `VideoFrameMetadataListener`
 *   del renderer: il frame è comunque reale e mostrabile, ma non si può dichiarare che
 *   appartenga a [positionMs], quindi l'overlay lo segnala come approssimazione.
 * @param contentHash impronta del contenuto, calcolata **una sola volta** qui. Prima veniva
 *   ricalcolata tre volte per fotogramma (estrattore, controller e composizione Compose sul
 *   main thread), ognuna con `createScaledBitmap` più 64 `getPixel`: lavoro puro sprecato sul
 *   percorso critico dello scrubbing, dove ogni millisecondo è un frame in meno.
 */
internal class SeekPreviewFrame(
  val bitmap: Bitmap,
  val positionMs: Long,
  val fromCache: Boolean = false,
  val ptsAuthoritative: Boolean = true,
  contentHash: String? = null
) {

  val contentHash: String = contentHash ?: SeekPreviewFrameProbe.contentHash(bitmap)

  /** Copia restituita dalla cache: stesso contenuto e stesso hash, marcata come riuso. */
  internal fun asCacheHit(): SeekPreviewFrame =
    SeekPreviewFrame(bitmap, positionMs, fromCache = true, ptsAuthoritative, contentHash)
}

/**
 * Sorgente dei fotogrammi di anteprima. Non conosce il player, l'interfaccia né la cache:
 * riceve una posizione e restituisce un fotogramma.
 *
 * Il controller garantisce il rispetto del contratto "uno alla volta, annullabile":
 * [load] viene invocata solo da una coroutine cancellabile e deve restituire null
 * (o lanciare) appena il lavoro non è più utile. Una implementazione senza supporto
 * (stream live, sorgente HTTP non rand-accessibile, ...) restituisce subito null.
 */
internal interface SeekPreviewSource {

  /** Vero solo se la sorgente può fornire fotogrammi per il contenuto corrente. */
  val isAvailable: Boolean

  /** Recupera il fotogramma corrispondente alla posizione (millisecondi dall'inizio). */
  suspend fun load(positionMs: Long): SeekPreviewFrame?

  /**
   * Scalda la sorgente (decoder, superficie GL) senza cercare nessuna posizione.
   *
   * Serve perché il primo `load` di una sessione include il `prepare()` del secondo player:
   * 5.7 s misurati sulla Fire TV mt8696, durante i quali il worker è occupato e le pressioni
   * dell'utente si accumulano. Scaldare all'apertura dello scrubbing sposta quel costo fuori
   * dalla prima cattura, così il worker torna libero e l'aggiornamento resta continuo.
   *
   * Idempotente e non bloccante: le sorgenti che non hanno nulla da scaldare non fanno nulla.
   */
  suspend fun prewarm() {}

  /** Rilascia decoder, range HTTP e thread della sorgente. Idempotente. */
  fun release()
}

/**
 * Registro delle sorgenti di anteprima per contenuto.
 *
 * PlayerScreen usa il registro perché il controller lo crea quando le frecce aprono lo
 * scrubbing, mentre l'URL e le richieste HTTP della sorgente attiva sono altro stato della
 * composizione. Il registro tiene l'unico riferimento vivo alla sorgente ed evita che una
 * ricomposizione (o un nuovo controller) ne crei una seconda.
 */
internal object SeekPreviewRegistry {

  @Volatile
  private var current: SeekPreviewSource? = null

  fun source(): SeekPreviewSource? = current

  fun install(source: SeekPreviewSource) {
    val previous = current
    current = source
    if (previous !== source) {
      runCatching { previous?.release() }
        .onFailure { Log.w(TAG, "Rilascio della sorgente precedente fallito", it) }
    }
  }

  fun clear() {
    val previous = current
    current = null
    runCatching { previous?.release() }
      .onFailure { Log.w(TAG, "Rilascio della sorgente fallito", it) }
  }

  private const val TAG = "SeekPreview"
}

/**
 * Cache LRU di fotogrammi per la sessione di riproduzione.
 *
 * Ogni voce pesa quanto la sua bitmap (più il byte array decodificato, già liberato):
 * il tetto di byte impedisce di tenere in RAM un numero imprevedibile di frame. Le
 * pressioni ravvicinate del telecomando riusano lo stesso fotogramma invece di
 * richiederlo di nuovo.
 */
internal class SeekPreviewCache(
  private val maxBytes: Int = DEFAULT_MAX_BYTES,
  private val sizeOf: (SeekPreviewFrame) -> Int = { it.bitmap.byteCount }
) {

  private val entries = LinkedHashMap<Long, SeekPreviewFrame>(16, 0.75f, true)
  private var totalBytes = 0

  @Synchronized
  fun get(key: Long): SeekPreviewFrame? {
    val hit = entries[key] ?: return null
    return hit.asCacheHit()
  }

  /**
   * Fotogramma la cui posizione decodificata dista al massimo [toleranceMs] da [positionMs].
   *
   * La chiave della cache è la posizione REALE del frame (timestamp di presentazione), non
   * quella richiesta: un decoder può atterrare su un istante leggermente diverso (o su un
   * keyframe precedente). La ricerca per prossimità evita di riseekare per pochi
   * millisecondi, senza mai spacciare per "posizione richiesta" un frame lontano.
   */
  @Synchronized
  fun getWithin(positionMs: Long, toleranceMs: Long): SeekPreviewFrame? {
    if (toleranceMs < 0L) return null
    var best: SeekPreviewFrame? = null
    var bestDelta = Long.MAX_VALUE
    for ((key, frame) in entries) {
      val delta = kotlin.math.abs(key - positionMs)
      if (delta <= toleranceMs && delta < bestDelta) {
        best = frame
        bestDelta = delta
      }
    }
    return best?.asCacheHit()
  }

  @Synchronized
  fun put(key: Long, frame: SeekPreviewFrame) {
    if (maxBytes <= 0) return
    val size = sizeOf(frame)
    if (size > maxBytes) return
    val previous = entries.put(key, frame)
    if (previous != null) totalBytes -= sizeOf(previous)
    totalBytes += size
    trimToBudget()
  }

  @Synchronized
  fun clear() {
    entries.clear()
    totalBytes = 0
  }

  @Synchronized
  fun sizeBytes(): Int = totalBytes

  @Synchronized
  fun size(): Int = entries.size

  private fun trimToBudget() {
    val iterator = entries.entries.iterator()
    while (totalBytes > maxBytes && iterator.hasNext()) {
      val removed = iterator.next().value
      totalBytes -= sizeOf(removed)
      iterator.remove()
    }
  }

  companion object {
    /** Cappa di memoria della cache: ~12 frame 480x270, abbondanti per prefetch e ritorni. */
    const val DEFAULT_MAX_BYTES = 24 * 1024 * 1024
  }
}

/**
 * Coordina l'anteprima durante lo scrubbing: cache, conflation, prefetch, annullamento.
 *
 * Modello di esecuzione ("worker con conflation", revisione anti-blocco):
 * - [show] NON annulla mai la cattura in volo: deposita la posizione in una mailbox
 *   (conflazione: vale sempre solo l'ULTIMA posizione richiesta) e sveglia il worker;
 * - il worker esegue una cattura alla volta; alla fine di ognuna pubblica il fotogramma
 *   OTTENUTO se e solo se avvicina l'intento corrente rispetto a quello già a video (vedi
 *   [shouldDisplay]: la misura è la distanza dalla posizione che l'utente vuole adesso, non il
 *   verso dei PTS) e poi prende dalla mailbox la posizione più recente, saltando le intermedie
 *   obsolete;
 * - ogni cattura e ogni prefetch terminano con [settleLatestIntent], così il fotogramma della
 *   posizione corrente arriva a video anche se prodotto da un'altra richiesta: senza questo
 *   passaggio l'overlay restava su `Loading` con l'immagine vecchia quando l'utente si fermava;
 * - il prefetch delle posizioni adiacenti avviene SOLO quando nessuna posizione primaria
 *   è in attesa: non può mai bloccare o ritardare l'aggiornamento al punto più recente;
 * - HIDE, cambio sorgente e release annullano il worker e quindi la cattura in volo:
 *   la fine dello scrubbing resta l'unica cosa autorizzata a interrompere il decoder.
 *
 * Garanzie conservate:
 * - foto già in cache: pubblicata subito, senza toccare la sorgente (e l'inversione di
 *   direzione — avanti poi indietro — riusa il fotogramma già catturato);
 * - richiesta duplicata sulla stessa posizione: nessuna seconda cattura;
 * - [hide] annulla il lavoro pendente; la cache resta (tornare indietro è immediato);
 * - [setSource] azzera stato e cache: niente fotogrammi di un episodio precedente;
 * - nessuna pubblicazione dopo la chiusura (il worker cancellato non pubblica);
 * - nessun risultato obsoleto sovrascrive un fotogramma più vicino all'intento corrente, in
 *   nessuno dei due versi.
 *
 * Threading: il worker gira sul [dispatcher] passato (di default lo **stesso thread del
 * player**, cioè il main thread). Le sorgenti basate sul player DEBSONO accedere
 * all'ExoPlayer dal suo application thread (Media3 lancia `IllegalStateException: Player
 * is accessed on the wrong thread`), quindi il thread di esecuzione non è un dettaglio
 * interno: è un contratto. Le sorgenti che eseguono lavoro pesante (rete, decodifica di
 * byte) spostano esplicitamente il propio lavoro su [Dispatchers.IO] con `withContext`;
 * cache e pubblicazione dello stato restano sul thread del worker, dove show/hide
 * (anch'essi sul main) non creano contesa.
 */
internal class SeekPreviewController(
  private val scope: CoroutineScope,
  private val cache: SeekPreviewCache = SeekPreviewCache(),
  private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
  private val sourceProvider: () -> SeekPreviewSource? = { SeekPreviewRegistry.source() },
  private val onError: (Throwable) -> Unit = {
    Log.w("SeekPreview", "Anteprima non disponibile, si continua senza fotogramma", it)
  }
) {

  /** Contenuto per cui la sorgente corrente è valida; null quando nessuna fonte è installata. */
  private var sourceKey: String? = null

  /** Sorgente attiva: la stessa istanza del registro finché non viene sostituita. */
  private var source: SeekPreviewSource? = null

  /**
   * Worker attivo: uno solo finché l'overlay è aperto; processa la mailbox.
   *
   * Si tocca solo dentro [lifecycleLock]: vedere [startWorker] per il motivo (una richiesta
   * depositata nella mailbox mentre il worker si sta chiudendo andava persa).
   */
  private var worker: Job? = null

  /** Job per il completamento protetto della cattura finale prima del dismiss. */
  private var dismissJob: Job? = null

  /** Job per il prefetch speculativo: cancellato all'arrivo di ogni nuova richiesta primaria. */
  private var prefetchJob: Job? = null

  /** Protegge [worker] e la decisione di avviarlo:CAS-none, solo mutual exclusion. */
  private val lifecycleLock = Any()

  /**
   * Richiesta primaria confiata al worker.
   *
   * @param seq numero progressivo della pressione: identifica univocamente l'intento a cui una
   *   cattura appartiene, indipendentemente dal verso dello scrubbing.
   * @param direction verso dello scrubbing al momento della richiesta (
   *   [PlayerScrub.FORWARD] / [PlayerScrub.REWIND] / 0). Serve solo per la diagnostica: la
   *   logica di pubblicazione NON deve più niente al verso, ed è proprio il contrario di ciò
   *   che causava il blocco del fotogramma durante lo scrubbing inverso.
   */
  private data class PreviewRequest(
    val positionMs: Long,
    val prefetch: List<Long>,
    val seq: Long,
    val direction: Int
  )

  /** Mailbox conflata dell'ultima richiesta primaria; null = nessuna in attesa. */
  private val mailbox = MutableStateFlow<PreviewRequest?>(null)

  /** Contatore delle pressioni: ogni richiesta riceve un `seq` strettamente crescente. */
  private val seqCounter = AtomicLong(0L)

  /**
   * Ultima richiesta espressa dall'utente: il **target corrente** a cui ogni fotogramma
   * candidato va confrontato. È questo, e non l'ordine dei PTS, a decidere cosa va a video.
   */
  @Volatile
  private var latestRequest: PreviewRequest? = null

  /**
   * Fotogramma attualmente a video, con la posizione reale (PTS) e il `seq` della richiesta che
   * lo ha prodotto. null quando a video non c'è nessun fotogramma.
   */
  private class OnScreen(val decodedMs: Long, val authoritative: Boolean, val seq: Long)

  @Volatile
  private var onScreen: OnScreen? = null

  /**
   * Vero finché l'overlay è aperto: oltre a proteggere le pubblicazioni di stato, serve
   * al worker per fermare il loop (e il prefetch) alla chiusura.
   */
  @Volatile
  private var overlayVisible: Boolean = false

  private val mutableState = MutableStateFlow<SeekPreviewState?>(null)

  /** Stato da disegnare; null quando l'anteprima è chiusa o non c'è nulla da mostrare. */
  val state: StateFlow<SeekPreviewState?> = mutableState.asStateFlow()

  /** Vero quando una cattura è in volo (indipendentemente dalla posizione). */
  val isLoading: Boolean
    get() = synchronized(lifecycleLock) { worker }?.isActive == true

  /**
   * Collega (o scollega) la sorgente per [key]. Richiamarla con la stessa chiave e la
   * stessa istanza di sorgente è un no-op: non azzera stato né cache. Con chiave o
   * sorgente diversa annulla il lavoro, svuota la cache e chiude l'overlay: nessun
   * fotogramma del contenuto precedente può sopravvivere al cambio di episodio o fonte.
   */
  fun setSource(key: String?, seekable: Boolean) {
    val nextSource = if (seekable) sourceProvider() else null
    if (key == sourceKey && nextSource === source) return
    cancelWork()
    cache.clear()
    mutableState.value = null
    // Cambio di episodio o fonte: niente intento corrente e nessun frame a video, altrimenti
    // il gate di pubblicazione confronterebbe i fotogrammi nuovi con quelli del contenuto
    // precedente.
    latestRequest = null
    onScreen = null
    sourceKey = key
    source = nextSource
  }

  /**
   * Scalda il decoder dedicato senza cercare nessuna posizione. Da chiamare all'apertura dello
   * scrubbing: il `prepare()` del secondo player (5.7 s misurati su mt8696) non finisce più
   * dentro la prima cattura, dove teneva bloccato il worker per dieci pressioni.
   */
  fun warmUp() {
    val activeSource = source ?: return
    if (!activeSource.isAvailable) return
    scope.launch(dispatcher) {
      runCatching { activeSource.prewarm() }
        .onFailure { Log.d(TAG, "warm-up anteprima incompleto: ${it.message}") }
    }
  }

  /**
   * Richiede l'anteprima per [positionMs]; [prefetch] sono le posizioni adiacenti da
   * preparare per le pressioni successive (in ordine di priorità).
   *
   * La richiesta NON interrompe la cattura in volo (vedi KDoc della classe): aggiorna lo
   * stato mostrato — l'ultimo fotogramma disponibile resta a video, attenuato — deposita
   * la posizione nella mailbox e il worker la raccoglie appena libera.
   *
   * Su fonte non ricercabile o non disponibile pubblica subito `Unavailable`, così
   * l'overlay mostra il placeholder invece di un fotogramma sbagliato.
   */
  fun show(positionMs: Long, prefetch: List<Long> = emptyList()) {
    dismissJob?.cancel()
    dismissJob = null
    prefetchJob?.cancel()
    prefetchJob = null
    val safePosition = positionMs.coerceAtLeast(0L)
    overlayVisible = true

    // Intento corrente: registra la pressione PRIMA di qualsiasi altra cosa, perché è il
    // riferimento con cui ogni fotogramma (compresi quelli delle catture già in volo) verrà
    // confrontato. Il verso si ricava dallo spostamento rispetto alla pressione precedente:
    // serve alla diagnostica e non partecipa alle decisioni di pubblicazione.
    val previous = latestRequest
    val direction = when {
      previous == null -> 0
      safePosition > previous.positionMs -> PlayerScrub.FORWARD
      safePosition < previous.positionMs -> PlayerScrub.REWIND
      else -> 0
    }
    val request = PreviewRequest(
      positionMs = safePosition,
      prefetch = prefetch,
      seq = seqCounter.incrementAndGet(),
      direction = direction
    )
    latestRequest = request
    Log.i(
      TAG,
      "richiesta preview pos=${safePosition}ms seq=${request.seq} " +
        "verso=${directionLabel(direction)} prefetch=$prefetch"
    )

    val activeSource = source
    if (activeSource == null || !activeSource.isAvailable) {
      cancelWork()
      Log.w(
        TAG,
        "anteprima non richiedibile: sorgente=${if (activeSource == null) "assente" else "non disponibile"} " +
          "pos=${safePosition}ms"
      )
      mutableState.value = SeekPreviewState.Unavailable(safePosition)
      return
    }

    // Cache-hit immediata: pubblicata con lo stesso gate delle catture, così un riutilizzo non
    // può mai sovrascrivere un fotogramma più vicino all'intento corrente.
    val cachedFrame = cache.get(safePosition)
      ?: cache.getWithin(safePosition, FRAME_MATCH_TOLERANCE_MS)
    if (cachedFrame != null) {
      publishFrame(request, cachedFrame, elapsedMs = 0L, fromCache = true)
    } else {
      // Nessun fotogramma per la nuova posizione: il precedente (se esiste) resta a
      // video, attenuato, finché la cattura non pubblica quello nuovo.
      mutableState.value = SeekPreviewState.Loading(safePosition, currentFrameOrNull())
    }
    // Conflazione: solo l'ultima richiesta resta depositata; le intermedie spariscono.
    mailbox.value = request
    startWorker()
  }

  /**
   * Conclude la sessione proteggendo la cattura del frame finale:
   * non cancella il worker se sta decodificando l'ultima posizione richiesta o se è in mailbox.
   * Quando il frame finale viene pubblicato (o alla scadenza di [fallbackTimeoutMs]), nasconde
   * l'anteprima e invoca [onDismissed].
   */
  fun finishAndDismiss(
    targetPositionMs: Long,
    fallbackTimeoutMs: Long = 2000L,
    holdDurationMs: Long = 650L,
    onDismissed: () -> Unit = {}
  ) {
    dismissJob?.cancel()
    prefetchJob?.cancel()
    prefetchJob = null
    dismissJob = scope.launch(dispatcher) {
      val alreadyPublished = onScreen != null && abs(onScreen!!.decodedMs - targetPositionMs) <= FRAME_MATCH_TOLERANCE_MS
      if (alreadyPublished) {
        delay(holdDurationMs)
        hide()
        onDismissed()
        return@launch
      }

      val deadline = SystemClock.elapsedRealtime() + fallbackTimeoutMs
      while (overlayVisible && SystemClock.elapsedRealtime() < deadline) {
        val currentOnScreen = onScreen
        if (currentOnScreen != null && abs(currentOnScreen.decodedMs - targetPositionMs) <= FRAME_MATCH_TOLERANCE_MS) {
          delay(holdDurationMs)
          break
        }
        if (worker?.isActive != true && mailbox.value == null) {
          break
        }
        delay(50L)
      }
      hide()
      onDismissed()
    }
  }

  /**
   * Chiude l'anteprima: l'overlay sparisce subito e **la cattura in volo viene annullata**.
   *
   * La cancellazione è l'unica scelta corretta a fine scrubbing: conferma e BACK fanno
   * entrambi ripartire riproduzione e posizione dal loro target, e lasciare in vita la
   * cattura significa lasciarla continuare a spostare il player principale. La cattura
   * annullata NON esegue il proprio ripristino (la sorgente lo evita su cancellazione):
   * la posizione finale la decide chi conclude lo scrubbing (seek di conferma o ritorno
   * all'origine di BACK).
   */
  fun hide() {
    dismissJob?.cancel()
    dismissJob = null
    prefetchJob?.cancel()
    prefetchJob = null
    val lastShownPosition = mutableState.value?.positionMs
    Log.i(
      TAG,
      "anteprima chiusa (fine scrubbing: conferma o BACK, ultima pos=$lastShownPosition ms); " +
        "cattura in volo annullata"
    )
    overlayVisible = false
    cancelWork()
    mutableState.value = null
    latestRequest = null
    onScreen = null
  }

  /** Rilascio definitivo (distruzione della schermata): nessun job e nessuna referenza. */
  fun release() {
    dismissJob?.cancel()
    dismissJob = null
    prefetchJob?.cancel()
    prefetchJob = null
    overlayVisible = false
    cancelWork()
    mutableState.value = null
    source = null
    sourceKey = null
    latestRequest = null
    onScreen = null
    cache.clear()
  }

  /** Fotogramma attualmente a video (Frame, o Loading che lo conserva); null se non ce n'è. */
  private fun currentFrameOrNull(): SeekPreviewFrame? {
    val current = mutableState.value ?: return null
    return when (current) {
      is SeekPreviewState.Frame -> current.frame
      is SeekPreviewState.Loading -> current.frame
      is SeekPreviewState.Unavailable -> null
    }
  }

  /**
   * True se il candidato va messo a video al posto del fotogramma corrente.
   *
   * **Criterio: vicinanza all'intento corrente, non monotonia dei PTS.** La versione precedente
   * chiedeva `decodedPosition >= displayedPosition`, cioè interpretava "più recente" come
   * "PTS più grande": funziona solo andando avanti. Allo scrubbing inverso ogni fotogramma
   * legittimo ha PTS minore di quello a video, quindi veniva sempre scartato e l'anteprima
   * restava fissa sull'immagine del punto di partenza — il Bug 2.
   *
   * Qui il confronto è con la posizione che l'utente vuole *adesso*:
   * - nessun frame a video → si pubblica (qualcosa di reale è meglio del placeholder);
   * - il candidato è più vicino all'intento di quello a video → si pubblica, in qualunque verso
   *   lo scrubbing stia andando;
   * - distanza identica → vince la richiesta più recente (`seq`), così un risultato tardivo di
   *   una pressione vecchia non sostituisce un frame altrettanto vicino già mostrato.
   *
   * Le protezioni contro i risultati obsoleti restano quindi integre (anzi: ora funzionano
   * anche all'indietro), semplicemente espressi nella dimensione giusta — la distanza dal
   * target — invece che nel verso dei PTS.
   */
  private fun shouldDisplay(request: PreviewRequest, candidateDecodedMs: Long): Boolean {
    if (!overlayVisible) return false
    val current = onScreen ?: return true
    val intent = latestRequest?.positionMs ?: request.positionMs
    val candidateDrift = abs(candidateDecodedMs - intent)
    val displayedDrift = abs(current.decodedMs - intent)
    if (candidateDrift != displayedDrift) return candidateDrift < displayedDrift
    return request.seq >= current.seq
  }

  /**
   * Motivo del rifiuto, solo per la diagnostica; null se il frame va a video.
   *
   * Delega a [shouldDisplay] invece di ripetere il confronto: due copie della stessa regola è
   * esattamente il modo in cui il log ha continuato a dire "pubblicato=true" per mesi mentre il
   * fotogramma non corrispondeva alla posizione. La decisione e la sua spiegazione non possono
   * divergere.
   */
  private fun rejectionReason(request: PreviewRequest, candidateDecodedMs: Long): String? {
    if (shouldDisplay(request, candidateDecodedMs)) return null
    if (!overlayVisible) return "overlay chiuso"
    val current = onScreen ?: return null
    val intent = latestRequest?.positionMs ?: request.positionMs
    val candidateDrift = abs(candidateDecodedMs - intent)
    val displayedDrift = abs(current.decodedMs - intent)
    val distinguishing =
      if (candidateDrift > displayedDrift) {
        "piu lontano: frame=$candidateDecodedMs (scarto=${candidateDrift}ms) contro " +
          "a video=${current.decodedMs} (scarto=${displayedDrift}ms), intento=${intent}ms"
      } else {
        "distanza identica (${candidateDrift}ms dallintento ${intent}ms) ma richiesta meno " +
          "recente: seq=${request.seq} contro seq=${current.seq}"
      }
    return "scartato per non regredire: $distinguishing"
  }

  private fun directionLabel(direction: Int): String = when (direction) {
    PlayerScrub.FORWARD -> "avanti"
    PlayerScrub.REWIND -> "indietro"
    else -> "fermo"
  }

  private fun cancelWork() {
    synchronized(lifecycleLock) {
      prefetchJob?.cancel()
      prefetchJob = null
      mailbox.value = null
      worker?.cancel()
      worker = null
    }
  }

  /**
   * Avvia il worker se non c'è già.
   *
   * La versione precedente faceva `if (worker != null) return` e il worker si azzerava nel
   * `finally`: una richiesta depositata nella mailbox nell'attimo in cui il worker stava
   * uscendo dal loop restava **senza nessuno a leggerla**, e l'overlay si fermava su
   * `Loading` col fotogramma vecchio — uno dei modi in cui la posizione finale restava
   * sbagliata. Ora azzeramento e riavvio sono nella stessa sezione critica: il completamento
   * del worker rilancia se c'è ancora lavoro, quindi il wakeup non si perde mai.
   */
  private fun startWorker() {
    synchronized(lifecycleLock) {
      if (worker?.isActive == true) return
      val job = scope.launch(dispatcher) {
        while (overlayVisible) {
          val request = mailbox.firstOrNull { it != null } ?: break
          mailbox.value = null
          if (!overlayVisible) break
          runCapture(request)
        }
        // Uscita dal loop: il riavvio eventuale spetta a invokeOnCompletion, che lo fa nella
        // stessa sezione critica in cui azzera [worker]. Così una richiesta arrivata mentre
        // il worker stava uscendo non resta senza nessuno a leggerla.
      }
      worker = job
      job.invokeOnCompletion {
        synchronized(lifecycleLock) {
          if (worker === job) {
            worker = null
            if (overlayVisible && mailbox.value != null) startWorker()
          }
        }
      }
    }
  }

  /**
   * Una cattura: posizione primaria (con pubblicazione) e poi, solo se nessuna posizione
   * più recente è in attesa, il prefetch delle adiacenti.
   *
   * Due differenze sostanziali rispetto alla versione precedente:
   * - il ramo **cache-hit pubblica** (prima si limitava a rientrare): il frame della posizione
   *   corrente poteva arrivare dal prefetch mentre una cattura era in volo e non veniva mai
   *   messo a video, lasciando l'overlay su `Loading` con l'immagine vecchia — il fotogramma
   *   "sbagliato" che resta quando l'utente si ferma;
   * - ogni pubblicazione passa da [publishFrame], quindi il criterio è uno solo per cache,
   *   cattura e prefetch, ed è indipendente dal verso dello scrubbing.
   */
  private suspend fun runCapture(request: PreviewRequest) {
    val activeSource = source ?: return
    val position = request.positionMs

    val cached = cache.get(position) ?: cache.getWithin(position, FRAME_MATCH_TOLERANCE_MS)
    if (cached != null) {
      publishFrame(request, cached, elapsedMs = 0L, fromCache = true)
    } else {
      if (overlayVisible) {
        mutableState.value = SeekPreviewState.Loading(position, currentFrameOrNull())
      }
      val startedAt = System.currentTimeMillis()
      val loaded = try {
        activeSource.load(position)
      } catch (cancelled: CancellationException) {
        Log.i(TAG, "richiesta annullata pos=${position}ms seq=${request.seq}")
        throw cancelled
      } catch (error: Throwable) {
        Log.e(TAG, "errore sorgente pos=${position}ms", error)
        onError(error)
        null
      }
      val elapsed = System.currentTimeMillis() - startedAt
      if (loaded != null) {
        // In cache SEMPRE, anche se il gate lo scarta: è materiale utile per le pressioni
        // successive e per il ritorno su una posizione già visitata.
        cache.put(loaded.positionMs, loaded)
        publishFrame(request, loaded, elapsedMs = elapsed, fromCache = false)
      } else if (mailbox.value == null && request.seq == latestRequest?.seq) {
        // Nessun fotogramma e nessuna posizione più recente in coda: il placeholder
        // "Anteprima non disponibile" è allora lo stato onesto da mostrare.
        Log.w(TAG, "nessun fotogramma per pos=$position (${elapsed}ms)")
        if (overlayVisible) {
          mutableState.value = SeekPreviewState.Unavailable(position)
        }
      } else {
        Log.w(TAG, "nessun fotogramma per pos=$position (${elapsed}ms, richiesta superata)")
      }
    }

    // Il frame dell'intento corrente può essere arrivato da un'altra strada (prefetch della
    // pressione precedente, cattura superata): lo adottiamo prima di passare al prefetch.
    settleLatestIntent()

    if (mailbox.value != null || !overlayVisible || dismissJob != null) return
    prefetchJob?.cancel()
    prefetchJob = scope.launch(dispatcher) {
      // Breve pausa: dà priorità assoluta a pressioni D-Pad ravvicinate (es. 200ms)
      delay(250L)
      if (mailbox.value != null || !overlayVisible || dismissJob != null) return@launch
      for (candidate in request.prefetch) {
        if (candidate == position) continue
        // Una nuova posizione primaria ha priorità ASSOLUTA: interrompe subito il prefetch
        if (mailbox.value != null || !overlayVisible || dismissJob != null) break
        if (cache.get(candidate) != null) continue
        val startedAt = System.currentTimeMillis()
        val loaded = try {
          activeSource.load(candidate)
        } catch (cancelled: CancellationException) {
          Log.i(TAG, "prefetch annullato per priorità richiesta primaria pos=${candidate}ms")
          throw cancelled
        } catch (error: Throwable) {
          Log.e(TAG, "errore sorgente prefetch pos=${candidate}ms", error)
          onError(error)
          null
        }
        val elapsed = System.currentTimeMillis() - startedAt
        if (loaded != null) {
          Log.i(
            TAG,
            "prefetch pronto pos=$candidate (${elapsed}ms, pts=${loaded.positionMs}ms, " +
              "ptsAutorevole=${loaded.ptsAuthoritative}, " +
              "bitmap=${loaded.bitmap.width}x${loaded.bitmap.height}, hash=${loaded.contentHash})"
          )
          cache.put(loaded.positionMs, loaded)
        } else {
          Log.i(TAG, "prefetch senza fotogramma pos=$candidate (${elapsed}ms)")
        }
        settleLatestIntent()
      }
    }
  }

  /**
   * Unico punto in cui un fotogramma finisce a video.
   *
   * Applica il gate [shouldDisplay] — distanza dall'intento corrente, non verso dei PTS — e
   * classifica il risultato: [SeekPreviewState.Frame.isDefinitive] solo quando il PTS è
   * autorevole e coincide con la posizione voluta; altrimenti `approximate`, cioè immagine
   * reale mostrata durante il movimento, segnalata come tale e MAI da prendere per l'esito del
   * punto finale. La posizione accanto al fotogramma è sempre quella reale del frame, mai la
   * posizione richiesta riscritta addosso a un'immagine diversa.
   */
  private fun publishFrame(
    request: PreviewRequest,
    frame: SeekPreviewFrame,
    elapsedMs: Long,
    fromCache: Boolean
  ) {
    val decoded = frame.positionMs
    val intent = latestRequest?.positionMs ?: request.positionMs
    val reason = rejectionReason(request, decoded)
    Log.i(
      TAG,
      "frame: richiesto=${request.positionMs}ms seq=${request.seq} " +
        "verso=${directionLabel(request.direction)} pts=$decoded " +
        "ptsAutorevole=${frame.ptsAuthoritative} mostrato=$decoded intento=${intent}ms " +
        "scarto=${abs(decoded - intent)}ms " +
        "bitmap=${frame.bitmap.width}x${frame.bitmap.height} hash=${frame.contentHash} " +
        "cache=${if (fromCache) "hit" else "miss"} decodifica=${elapsedMs}ms " +
        (if (reason == null) "pubblicato=si" else "pubblicato=no perche=$reason")
    )
    Log.i(
      "SeekSync",
      "[SeekSync] PREVIEW_FRAME: requested=${request.positionMs}ms pts=$decoded " +
        "authoritative=${frame.ptsAuthoritative} drift=${abs(decoded - intent)}ms " +
        (if (reason == null) "published=true" else "published=false reason=$reason")
    )
    if (reason != null) return
    mutableState.value = SeekPreviewState.Frame(
      positionMs = decoded,
      frame = frame,
      requestedPositionMs = intent,
      approximate =
        !frame.ptsAuthoritative || abs(decoded - intent) > FRAME_MATCH_TOLERANCE_MS
    )
    onScreen = OnScreen(decoded, frame.ptsAuthoritative, request.seq)
  }

  /**
   * Adotta il fotogramma migliore già disponibile per l'intento corrente, senza nuove catture.
   *
   * Chiude il caso — normalissimo con prefetch e conflation insieme — in cui il frame della
   * posizione richiesta compare in cache *dopo* che [show] l'aveva cercata invano: nessuno lo
   * pubblicava più e l'utente si fermava su un'immagine di un'altra scena.
   */
  private fun settleLatestIntent() {
    val request = latestRequest ?: return
    if (!overlayVisible) return
    val best =
      cache.get(request.positionMs)
        ?: cache.getWithin(request.positionMs, FRAME_MATCH_TOLERANCE_MS)
        ?: return
    if (onScreen?.decodedMs == best.positionMs) return
    publishFrame(request, best, elapsedMs = 0L, fromCache = true)
  }

  companion object {
    private const val TAG = "SeekPreview"

    /**
     * Scarto massimo tra posizione richiesta e posizione decodificata per considerare un
     * frame già in cache "quello giusto". Copre la durata di un frame e l'arrotondamento
     * dei PTS, non un'intera scena: oltre questa soglia si preferisce una nuova cattura.
     */
    const val FRAME_MATCH_TOLERANCE_MS = 500L

    /** Contenuto e fonte per la decodifica: (chiave del contenuto, url della sorgente). */
    fun sourceKeyOf(mediaKey: String?, url: String?): String? {
      if (url.isNullOrBlank()) return null
      return "$mediaKey\u0000$url"
    }
  }
}

/**
 * Decodifica un fotogramma JPEG/PNG dal byte array prodotto dalla sorgente.
 *
 * Limita la scala già in decodifica (inSampleSize) per non allocare immagini a piena
 * risoluzione per una miniatura da poche centinaia di pixel, e non tiene il byte array
 * dopo la decodifica. Eseguita nel dispatcher IO del controller, mai sul main thread.
 */
internal object SeekPreviewDecoder {

  const val MAX_WIDTH_PX = 640

  fun decode(jpegBytes: ByteArray, positionMs: Long, targetWidthPx: Int = MAX_WIDTH_PX): SeekPreviewFrame? {
    if (jpegBytes.isEmpty()) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val options = BitmapFactory.Options().apply {
      inSampleSize = sampleSizeFor(bounds.outWidth, targetWidthPx)
      inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, options) ?: return null
    return SeekPreviewFrame(bitmap = bitmap, positionMs = positionMs)
  }

  /** Fattore di campionamento potenza di due che tiene la larghezza entro il target. */
  fun sampleSizeFor(sourceWidth: Int, targetWidthPx: Int): Int {
    if (sourceWidth <= 0 || targetWidthPx <= 0 || sourceWidth <= targetWidthPx) return 1
    var sample = 1
    while (sourceWidth / (sample * 2) >= targetWidthPx) sample *= 2
    return sample
  }

  /** Risolve il contatore di fotogrammi delle risorse Android (`R.integer` in AAR Media3). */
  fun rawResourceId(context: Context, name: String): Int {
    return runCatching { context.resources.getIdentifier(name, "raw", context.packageName) }
      .getOrDefault(0)
  }
}

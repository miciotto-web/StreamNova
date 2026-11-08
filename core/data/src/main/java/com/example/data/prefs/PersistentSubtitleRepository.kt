package com.example.data.prefs

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.data.model.Episode
import com.example.data.model.MediaItem
import com.example.domain.model.Subtitle
import java.io.File
import java.io.FileFilter
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val Context.persistentSubtitlesDataStore: DataStore<Preferences> by preferencesDataStore(
  name = "persistent_subtitles"
)

/**
 * Repository per la selezione persistente dei sottotitoli OpenSubtitles.
 *
 * Consente di:
 * - Salvare la selezione di un sottotitolo per un contenuto specifico (film o episodio)
 * - Ripristinare automaticamente il sottotitolo selezionato alla riapertura dello stesso contenuto
 * - Mantenere i file scaricati in modo permanente nella directory filesDir/online_subtitles
 *
 * La selezione persiste attraverso:
 * - Chiusura e riapertura del player
 * - Riavvio completo dell'app
 * - Episodi differenti con selezioni indipendenti
 *
 * Se l'utente seleziona una traccia nativa oppure "Nessuno", la selezione OpenSubtitles
 * salvata per quel contenuto viene rimossa.
 */
object PersistentSubtitleRepository : PersistentSubtitleStore {

  private const val TAG = "PersistentSubtitleRepo"
  private const val SUBTITLES_DIR = "online_subtitles"

  private val KEY_SELECTIONS_JSON = stringPreferencesKey("selections_json")

  private var dataStore: DataStore<Preferences>? = null
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  @Volatile
  private var appContext: Context? = null

  private val _selectionsMap = MutableStateFlow<Map<String, PersistentSubtitleSelection>>(emptyMap())
  val selectionsMap: StateFlow<Map<String, PersistentSubtitleSelection>> = _selectionsMap.asStateFlow()

  /**
   * Chiavi con una rimozione in corso.
   *
   * La rimozione dal DataStore è asincrona: senza questo tombstone una riapertura
   * immediata dello stesso contenuto potrebbe leggere il vecchio record ancora
   * presente su disco e "resuscitare" la selezione appena cancellata.
   */
  private val pendingRemovals = ConcurrentHashMap.newKeySet<String>()

  fun init(context: Context) {
    // Il contesto viene aggiornato anche se il DataStore è già inizializzato: in un
    // processo reale è sempre lo stesso Application, nei test Robolectric cambia.
    appContext = context.applicationContext
    if (dataStore != null) return
    val store = context.applicationContext.persistentSubtitlesDataStore
    dataStore = store

    scope.launch {
      store.data.collect { prefs ->
        _selectionsMap.value = PersistentSubtitleCodec.decode(prefs[KEY_SELECTIONS_JSON])
          .filterKeys { it !in pendingRemovals }
      }
    }
  }

  /**
   * Directory permanente per i file dei sottotitoli OpenSubtitles.
   */
  override fun subtitlesDir(): File? {
    val ctx = appContext ?: return null
    return File(ctx.filesDir, SUBTITLES_DIR).also { it.mkdirs() }
  }

  /**
   * Salva la selezione di un sottotitolo per un contenuto.
   *
   * @param mediaItem Il media in riproduzione
   * @param episode L'episodio corrente (null per i film)
   * @param subtitle Il sottotitolo selezionato dall'utente
   * @param localFile Il file locale del sottotitolo scaricato
   */
  override fun saveSelection(
    mediaItem: MediaItem,
    episode: Episode?,
    subtitle: Subtitle,
    localFile: File?
  ) {
    val selection = buildSelection(mediaItem, episode, subtitle, localFile) ?: return
    val contentKey = selection.contentId
    pendingRemovals.remove(contentKey)

    // Aggiornamento immediato della cache in memoria: se l'utente riapre subito lo
    // stesso contenuto, la selezione è già disponibile senza attendere il round-trip
    // asincrono del DataStore.
    _selectionsMap.update { it + (contentKey to selection) }

    scope.launch { writeSelection(selection) }

    Log.i(
      TAG,
      "Salvata selezione persistente: $contentKey → fileId=${subtitle.fileId}, " +
        "lang=${subtitle.lang}, localPath=${localFile?.absolutePath}"
    )
  }

  /**
   * Variante `suspend` usata dai test: applica la scrittura prima di restituire.
   */
  internal suspend fun saveSelectionAndAwait(
    mediaItem: MediaItem,
    episode: Episode?,
    subtitle: Subtitle,
    localFile: File?
  ) {
    val selection = buildSelection(mediaItem, episode, subtitle, localFile) ?: return
    pendingRemovals.remove(selection.contentId)
    _selectionsMap.update { it + (selection.contentId to selection) }
    writeSelection(selection)
  }

  private fun buildSelection(
    mediaItem: MediaItem,
    episode: Episode?,
    subtitle: Subtitle,
    localFile: File?
  ): PersistentSubtitleSelection? {
    val fileId = subtitle.fileId ?: return null
    val contentKey = SubtitleContentKey.forContent(mediaItem.id, episode)
    return PersistentSubtitleSelection(
      contentId = contentKey,
      fileId = fileId,
      lang = subtitle.lang,
      label = subtitle.label ?: subtitle.getDisplayLanguage(),
      localFilePath = localFile?.absolutePath,
      timestamp = System.currentTimeMillis()
    )
  }

  private suspend fun writeSelection(selection: PersistentSubtitleSelection) {
    dataStore?.edit { prefs ->
      val current = PersistentSubtitleCodec.decode(prefs[KEY_SELECTIONS_JSON]).toMutableMap()
      current[selection.contentId] = selection
      prefs[KEY_SELECTIONS_JSON] = PersistentSubtitleCodec.encode(current)
    }
  }

  /**
   * Recupera la selezione salvata per un contenuto, se presente.
   *
   * @param mediaItem Il media in riproduzione
   * @param episode L'episodio corrente (null per i film)
   * @return La selezione salvata, oppure null se non presente
   */
  override fun getSelection(
    mediaItem: MediaItem,
    episode: Episode?
  ): PersistentSubtitleSelection? {
    val contentKey = SubtitleContentKey.forContent(mediaItem.id, episode)
    if (contentKey in pendingRemovals) return null
    return _selectionsMap.value[contentKey]
  }

  /**
   * Come [getSelection], ma legge il DataStore se la cache in memoria non contiene ancora
   * il dato (riapertura del player immediatamente dopo l'avvio dell'app).
   */
  override suspend fun getSelectionAwait(
    mediaItem: MediaItem,
    episode: Episode?
  ): PersistentSubtitleSelection? {
    val contentKey = SubtitleContentKey.forContent(mediaItem.id, episode)
    if (contentKey in pendingRemovals) return null
    _selectionsMap.value[contentKey]?.let { return it }

    val prefs = try {
      dataStore?.data?.firstOrNull()
    } catch (e: Exception) {
      Log.w(TAG, "Lettura DataStore fallita: ${e.javaClass.simpleName}")
      null
    } ?: return null

    // La rimozione potrebbe essere iniziata durante la lettura: il tombstone vince
    // sul dato letto dal disco.
    if (contentKey in pendingRemovals) return null

    val selection = PersistentSubtitleCodec.decode(prefs[KEY_SELECTIONS_JSON])[contentKey]
    if (selection != null) {
      _selectionsMap.update { it + (contentKey to selection) }
    }
    return selection
  }

  /**
   * Verifica se esiste una selezione salvata per il contenuto specificato.
   *
   * @param mediaItem Il media in riproduzione
   * @param episode L'episodio corrente (null per i film)
   * @return true se esiste una selezione salvata
   */
  fun hasSelection(mediaItem: MediaItem, episode: Episode?): Boolean {
    return getSelection(mediaItem, episode) != null
  }

  /**
   * Verifica se il file locale della selezione salvata è ancora disponibile.
   *
   * @param selection La selezione da verificare
   * @return true se il file esiste ancora su disco
   */
  fun isLocalFileAvailable(selection: PersistentSubtitleSelection): Boolean {
    return getLocalFile(selection) != null
  }

  /**
   * Restituisce il file locale se ancora disponibile.
   *
   * @param selection La selezione da verificare
   * @return Il file se esiste, oppure null
   */
  override fun getLocalFile(selection: PersistentSubtitleSelection): File? {
    val path = selection.localFilePath ?: return null
    val file = File(path)
    return if (file.exists() && file.isFile) file else null
  }

  /**
   * Costruisce un sottotitolo Subtitle dalla selezione persistente.
   * Il sottotitolo avrà:
   * - id = contentKey (per identificazione)
   * - fileId = quello salvato (per re-download se necessario)
   * - url = percorso file locale (se disponibile) o vuoto (da risolvere al download)
   * - lang = lingua salvata
   * - label = etichetta salvata
   */
  override fun toSubtitle(selection: PersistentSubtitleSelection): Subtitle {
    val localFile = getLocalFile(selection)
    return Subtitle(
      id = selection.contentId,
      url = localFile?.let { "file://${it.absolutePath}" } ?: "",
      lang = selection.lang,
      addonName = "OpenSubtitles",
      label = selection.label,
      fileId = selection.fileId
    )
  }

  /**
   * Rimuove la selezione salvata per un contenuto.
   * Chiamato quando l'utente seleziona una traccia nativa oppure "Nessuno".
   *
   * @param mediaItem Il media in riproduzione
   * @param episode L'episodio corrente (null per i film)
   */
  override fun clearSelection(mediaItem: MediaItem, episode: Episode?) {
    val contentKey = SubtitleContentKey.forContent(mediaItem.id, episode)
    val expectedTimestamp = _selectionsMap.value[contentKey]?.timestamp

    pendingRemovals.add(contentKey)
    _selectionsMap.update { it - contentKey }

    scope.launch {
      try {
        removeSelection(contentKey, expectedTimestamp)
      } finally {
        pendingRemovals.remove(contentKey)
      }
    }

    Log.i(TAG, "Rimossa selezione persistente: $contentKey")
  }

  /**
   * Variante `suspend` usata dai test: applica la rimozione prima di restituire.
   */
  internal suspend fun clearSelectionAndAwait(mediaItem: MediaItem, episode: Episode?) {
    val contentKey = SubtitleContentKey.forContent(mediaItem.id, episode)
    val expectedTimestamp = _selectionsMap.value[contentKey]?.timestamp
    pendingRemovals.add(contentKey)
    _selectionsMap.update { it - contentKey }
    try {
      removeSelection(contentKey, expectedTimestamp)
    } finally {
      pendingRemovals.remove(contentKey)
    }
  }

  /**
   * Rimuove il record dal DataStore.
   *
   * @param expectedTimestamp timestamp della selezione che l'utente ha scartato.
   *   Se nel frattempo un nuovo salvataggio ha prodotto un timestamp diverso, la
   *   rimozione non deve cancellare la scelta più recente.
   */
  private suspend fun removeSelection(contentKey: String, expectedTimestamp: Long?) {
    dataStore?.edit { prefs ->
      val current = PersistentSubtitleCodec.decode(prefs[KEY_SELECTIONS_JSON]).toMutableMap()
      val existing = current[contentKey] ?: return@edit
      if (expectedTimestamp != null && existing.timestamp != expectedTimestamp) {
        return@edit
      }
      current.remove(contentKey)
      prefs[KEY_SELECTIONS_JSON] = PersistentSubtitleCodec.encode(current)
    }
  }

  /**
   * Rimuove il file locale di una selezione (senza rimuovere la selezione stessa).
   * Chiamato quando il file è stato eliminato ma vogliamo mantenere i metadati per un eventuale re-download.
   *
   * @param selection La selezione da aggiornare
   */
  fun clearLocalFile(selection: PersistentSubtitleSelection) {
    _selectionsMap.update { current ->
      val existing = current[selection.contentId] ?: return@update current
      current + (selection.contentId to existing.copy(localFilePath = null))
    }

    scope.launch {
      dataStore?.edit { prefs ->
        val current = PersistentSubtitleCodec.decode(prefs[KEY_SELECTIONS_JSON]).toMutableMap()
        val existing = current[selection.contentId] ?: return@edit
        current[selection.contentId] = existing.copy(localFilePath = null)
        prefs[KEY_SELECTIONS_JSON] = PersistentSubtitleCodec.encode(current)
      }
    }

    // Elimina il file fisico se esiste
    selection.localFilePath?.let { path ->
      runCatching {
        val file = File(path)
        if (file.exists()) {
          file.delete()
          Log.i(TAG, "Eliminato file locale: $path")
        }
      }
    }
  }

  /**
   * Aggiorna il percorso locale di una selezione esistente.
   *
   * @param selection La selezione da aggiornare
   * @param localFile Il nuovo file locale
   */
  fun updateLocalFile(selection: PersistentSubtitleSelection, localFile: File) {
    _selectionsMap.update { current ->
      val existing = current[selection.contentId] ?: return@update current
      current + (selection.contentId to existing.copy(localFilePath = localFile.absolutePath))
    }

    scope.launch {
      dataStore?.edit { prefs ->
        val current = PersistentSubtitleCodec.decode(prefs[KEY_SELECTIONS_JSON]).toMutableMap()
        val existing = current[selection.contentId] ?: return@edit
        current[selection.contentId] = existing.copy(localFilePath = localFile.absolutePath)
        prefs[KEY_SELECTIONS_JSON] = PersistentSubtitleCodec.encode(current)
      }
    }
  }

  /**
   * Pulisce i file locali orfani (non referenziati da nessuna selezione).
   */
  fun cleanupOrphanedFiles() {
    val dir = subtitlesDir() ?: return
    if (!dir.exists()) return

    val validPaths = _selectionsMap.value.values.mapNotNull { it.localFilePath }.toSet()

    dir.listFiles(object : FileFilter {
      override fun accept(file: File): Boolean {
        return file.isFile && file.absolutePath !in validPaths
      }
    })?.forEach { file ->
      runCatching {
        if (file.exists()) {
          file.delete()
          Log.i(TAG, "Pulizia file orfano: ${file.absolutePath}")
        }
      }
    }
  }

  /**
   * Rilegge le selezioni dal disco nella cache in memoria.
   *
   * Usato dai test per simulare un riavvio completo dell'app senza ricreare il processo.
   */
  internal suspend fun reloadFromDiskForTests() {
    val prefs = dataStore?.data?.firstOrNull() ?: return
    _selectionsMap.value = PersistentSubtitleCodec.decode(prefs[KEY_SELECTIONS_JSON])
  }

  /**
   * Attende che le scritture DataStore accodate siano state applicate.
   *
   * DataStore serializza le transazioni: un `edit` vuoto accodato dopo i salvataggi
   * completa solo quando anche quelli sono stati persistiti. Usato dai test per
   * simulare un riavvio senza dipendere da sleep arbitrari.
   */
  internal suspend fun awaitPendingWritesForTests() {
    dataStore?.edit { }
  }

  /** Svuota solo la cache in memoria (i dati su disco restano). */
  internal fun resetInMemoryForTests() {
    _selectionsMap.value = emptyMap()
  }
}

package com.example.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.BuildConfig
import com.example.data.remote.simkl.SimklAccountState
import com.example.data.remote.simkl.SimklApi
import com.example.data.remote.simkl.SimklApiClient
import com.example.data.remote.simkl.SimklPinState
import com.example.data.prefs.AppSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

private val Context.simklDataStore by preferencesDataStore(name = "simkl_preferences")

object SimklRepository {
  private val KEY_ACCESS_TOKEN = stringPreferencesKey("access_token")
  private val KEY_TOKEN_EXPIRES_AT = stringPreferencesKey("token_expires_at")
  private val KEY_USERNAME = stringPreferencesKey("username")
  private val KEY_ACCOUNT_ID = androidx.datastore.preferences.core.longPreferencesKey("account_id")
  private val KEY_ACCOUNT_TYPE = stringPreferencesKey("account_type")
  private val accountMutable = MutableStateFlow(SimklAccountState())
  val accountState: StateFlow<SimklAccountState> = accountMutable.asStateFlow()

  private var appContext: Context? = null
  private var api: SimklApi = SimklApiClient.api
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val watchedEpisodesCache = ConcurrentHashMap<String, CacheEntry>()
  private val watchedRevisionMutable = MutableStateFlow(0L)
  val watchedRevision: StateFlow<Long> = watchedRevisionMutable.asStateFlow()
  private val cacheTtlMs = 5 * 60 * 1000L
  private data class CacheEntry(val expiresAt: Long, val episodes: Set<Int>)

  @Synchronized
  fun init(context: Context, simklApi: SimklApi = SimklApiClient.api) {
    val applicationContext = context.applicationContext
    if (appContext === applicationContext) return
    appContext = applicationContext
    api = simklApi
    scope.launch {
      val store = applicationContext.simklDataStore
      accountMutable.value = store.data.first().toAccountState()
      store.data.collect { accountMutable.value = it.toAccountState() }
    }
  }

  private fun androidx.datastore.preferences.core.Preferences.toAccountState() = SimklAccountState(
    accessToken = this[KEY_ACCESS_TOKEN],
    username = this[KEY_USERNAME],
    accountId = this[KEY_ACCOUNT_ID],
    accountType = this[KEY_ACCOUNT_TYPE],
    tokenExpiresAt = this[KEY_TOKEN_EXPIRES_AT]
  )

  fun hasClientId(): Boolean = getClientId().isNotBlank()

  private fun getClientId(): String = System.getenv("SIMKL_CLIENT_ID")?.trim()?.takeIf { it.isNotBlank() }
    ?: AppSettingsRepository.simklClientId.value?.trim()?.takeIf { it.isNotBlank() }
    ?: ""

  suspend fun requestDeviceCode(): SimklPinState = withContext(Dispatchers.IO) {
    check(hasClientId()) { "Configura SIMKL_CLIENT_ID per abilitare l'accesso SIMKL." }
    val response = api.requestPin(getClientId())
    check(response.isSuccessful) { "SIMKL non ha generato il codice (${response.code()})." }
    val pin = requireNotNull(response.body()) { "Risposta PIN SIMKL vuota." }
    SimklPinState(pin.userCode, pin.verificationUri, pin.deviceCode, pin.expiresIn, pin.interval.coerceAtLeast(5L))
  }

  suspend fun pollDeviceCode(pin: SimklPinState): SimklAccountState = withContext(Dispatchers.IO) {
    val deadline = System.currentTimeMillis() + pin.expiresIn * 1_000L
    while (System.currentTimeMillis() < deadline) {
      delay(pin.interval.coerceAtLeast(5L) * 1_000L)
      val response = api.pollPin(pin.userCode, getClientId())
      val body = response.body()
      val token = body?.accessToken
      if (!token.isNullOrBlank()) return@withContext saveToken(token, body.expiresAt)
      // Codice originale consumato/scaduto: SIMKL risponde con la shape di creazione
      // di un NUOVO PIN (device_code sempre presente o user_code diverso). Per docs
      // bisogna fermarsi subito e chiedere all'utente un nuovo codice.
      if (body?.deviceCode != null ||
        (!body?.userCode.isNullOrBlank() && body?.userCode != pin.userCode)
      ) {
        error("Codice SIMKL non più valido. Richiedine uno nuovo.")
      }
      if (body?.result.equals("KO", ignoreCase = true)) {
        if (body?.message?.contains("pending", true) == true) continue
        error(body?.message ?: "Autorizzazione SIMKL rifiutata.")
      }
      if (!response.isSuccessful) error("Autorizzazione SIMKL non riuscita (${response.code()}).")
    }
    error("Codice SIMKL scaduto. Richiedine uno nuovo.")
  }

  private suspend fun saveToken(token: String, expiresAt: String?): SimklAccountState {
    val settings = runCatching {
      api.getUserSettings(getClientId(), "Bearer $token")
        .takeIf { it.isSuccessful }?.body()
    }.getOrNull()
    val state = SimklAccountState(
      accessToken = token,
      username = settings?.user?.name,
      accountId = settings?.account?.id,
      accountType = settings?.account?.type,
      tokenExpiresAt = expiresAt
    )
    appContext?.simklDataStore?.edit { prefs ->
      prefs[KEY_ACCESS_TOKEN] = token
      expiresAt?.let { prefs[KEY_TOKEN_EXPIRES_AT] = it } ?: prefs.remove(KEY_TOKEN_EXPIRES_AT)
      state.username?.let { prefs[KEY_USERNAME] = it } ?: prefs.remove(KEY_USERNAME)
      state.accountId?.let { prefs[KEY_ACCOUNT_ID] = it } ?: prefs.remove(KEY_ACCOUNT_ID)
      state.accountType?.let { prefs[KEY_ACCOUNT_TYPE] = it } ?: prefs.remove(KEY_ACCOUNT_TYPE)
    }
    accountMutable.value = state
    invalidateWatchedCache()
    return state
  }

  suspend fun signOut() {
    appContext?.simklDataStore?.edit { prefs ->
      prefs.remove(KEY_ACCESS_TOKEN)
      prefs.remove(KEY_TOKEN_EXPIRES_AT)
      prefs.remove(KEY_USERNAME)
      prefs.remove(KEY_ACCOUNT_ID)
      prefs.remove(KEY_ACCOUNT_TYPE)
    }
    accountMutable.value = SimklAccountState()
    invalidateWatchedCache()
  }

  private fun invalidateWatchedCache() {
    watchedEpisodesCache.clear()
    watchedRevisionMutable.value += 1L
  }

  suspend fun startScrobble(media: SimklMediaPayload, progress: Double) = withContext(Dispatchers.IO) {
    val token = accountMutable.value.accessToken ?: return@withContext
    val response = api.startScrobble(getClientId(), "Bearer $token", media.toPayload(progress))
    check(response.isSuccessful) { "Scrobble SIMKL start fallito (${response.code()})." }
  }

  suspend fun pauseScrobble(media: SimklMediaPayload, progress: Double) = withContext(Dispatchers.IO) {
    val token = accountMutable.value.accessToken ?: return@withContext
    val response = api.pauseScrobble(getClientId(), "Bearer $token", media.toPayload(progress))
    check(response.isSuccessful) { "Scrobble SIMKL pause fallito (${response.code()})." }
  }

  suspend fun stopScrobble(media: SimklMediaPayload, progress: Double) = withContext(Dispatchers.IO) {
    val token = accountMutable.value.accessToken ?: return@withContext
    val response = api.stopScrobble(getClientId(), "Bearer $token", media.toPayload(progress))
    check(response.isSuccessful) { "Scrobble SIMKL stop fallito (${response.code()})." }
    if (progress >= 80.0) invalidateWatchedCache()
  }

  suspend fun markWatched(media: SimklMediaPayload) = withContext(Dispatchers.IO) {
    val token = accountMutable.value.accessToken ?: return@withContext
    val item = media.toHistoryItem()
    val payload = if (media.episode == null) mapOf("movies" to listOf(item)) else mapOf("shows" to listOf(item))
    val response = api.addHistory(getClientId(), "Bearer $token", payload)
    check(response.isSuccessful) { "Salvataggio cronologia SIMKL fallito (${response.code()})." }
    invalidateWatchedCache()
  }

  suspend fun getWatchedEpisodes(seriesTmdbId: Int, season: Int): Set<Int> = withContext(Dispatchers.IO) {
    val token = accountMutable.value.accessToken ?: return@withContext emptySet()
    val key = "${accountMutable.value.accountId}:$seriesTmdbId:$season"
    watchedEpisodesCache[key]?.takeIf { it.expiresAt > System.currentTimeMillis() }?.let {
      return@withContext it.episodes
    }
    val response = api.getWatchedShows(getClientId(), "Bearer $token")
    if (!response.isSuccessful) return@withContext emptySet()
    val episodes = parseWatchedEpisodes(response.body() ?: return@withContext emptySet(), seriesTmdbId, season)
    watchedEpisodesCache[key] = CacheEntry(System.currentTimeMillis() + cacheTtlMs, episodes)
    episodes
  }

  internal fun parseWatchedEpisodes(body: Map<String, Any>, tmdbId: Int, season: Int): Set<Int> {
    val shows = body["shows"] as? List<*> ?: body["tv_shows"] as? List<*> ?: emptyList<Any>()
    return shows.asSequence().mapNotNull { it as? Map<*, *> }
      .filter { entry ->
        val show = entry["show"] as? Map<*, *> ?: entry
        val ids = show["ids"] as? Map<*, *>
        ids?.get("tmdb")?.toString()?.toIntOrNull() == tmdbId
      }
      .flatMap { entry ->
        (entry["seasons"] as? List<*> ?: emptyList<Any>()).asSequence()
          .mapNotNull { it as? Map<*, *> }
          .filter { (it["number"] as? Number)?.toInt() == season }
          .flatMap { it["episodes"] as? List<*> ?: emptyList<Any>() }
          .mapNotNull { it as? Map<*, *> }
          .filter { it["watched_at"] != null && !it["watched_at"].toString().equals("null", true) }
          .mapNotNull { (it["number"] as? Number)?.toInt() }
      }.toSet()
  }
}

data class SimklMediaPayload(
  val title: String,
  val year: Int?,
  val tmdbId: Int?,
  val imdbId: String?,
  val episode: SimklEpisodePayload? = null
) {
  fun toPayload(progress: Double): Map<String, Any> {
    val ids = buildMap<String, String> {
      tmdbId?.takeIf { it > 0 }?.let { put("tmdb", it.toString()) }
      imdbId?.takeIf { it.isNotBlank() }?.let { put("imdb", it) }
    }
    val item = buildMap<String, Any> {
      put("title", title)
      year?.takeIf { it > 0 }?.let { put("year", it) }
      put("ids", ids)
    }
    return buildMap {
      put("progress", progress.coerceIn(0.0, 100.0))
      if (episode == null) put("movie", item) else {
        put("show", item)
        put("episode", mapOf("season" to episode.season, "episode" to episode.number))
      }
    }
  }

  fun toHistoryItem(): Map<String, Any> {
    val payload = toPayload(100.0)
    return if (episode == null) payload.getValue("movie") as Map<String, Any>
    else buildMap {
      put("show", payload.getValue("show"))
      put("seasons", listOf(mapOf("number" to episode.season, "episodes" to listOf(mapOf("number" to episode.number)))))
    }
  }
}

data class SimklEpisodePayload(val season: Int, val number: Int)

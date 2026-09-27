package com.example

import android.app.Application
import com.example.data.local.StreamNovaDatabase
import com.example.data.repository.MediaRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class StreamNovaApplication : Application() {

  private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  override fun onCreate() {
    super.onCreate()
    // Initialize Room Database
    val db = StreamNovaDatabase.init(this)

    // Initialize MediaRepository with Room database
    MediaRepository.init(this, db)

    // Preload cached media immediately on background thread
    appScope.launch {
      MediaRepository.loadFromCache()
    }
  }
}

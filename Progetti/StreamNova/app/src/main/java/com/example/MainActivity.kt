package com.example

import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.example.data.repository.MediaRepository
import com.example.ui.components.SidebarNavigation
import com.example.ui.screens.DetailScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.PlayerScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.NovaBackground
import com.example.ui.viewmodel.ScreenState
import com.example.ui.viewmodel.SidebarSection
import com.example.ui.viewmodel.StreamNovaViewModel

class MainActivity : ComponentActivity() {
  private val viewModel: StreamNovaViewModel by viewModels()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // Carica il catalogo persistito PRIMA di setContent, cosi' la prima composizione
    // della Home usa gia' lo stato salvato (evita il flicker sui preloaded).
    MediaRepository.initPersistence(applicationContext)
    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    enableEdgeToEdge()
    setContent {
      MyApplicationTheme {
        StreamNovaApp(viewModel = viewModel)
      }
    }
  }
}

@Composable
fun StreamNovaApp(viewModel: StreamNovaViewModel) {
  val screenState by viewModel.screenState.collectAsState()
  val currentSection by viewModel.currentSection.collectAsState()
  val selectedMedia by viewModel.selectedMedia.collectAsState()
  val allMedia by viewModel.allMedia.collectAsState()

  // D-Pad and system back button handler
  BackHandler(enabled = true) {
    when (screenState) {
      ScreenState.PLAYER -> viewModel.closePlayer()
      ScreenState.DETAIL -> viewModel.backToBrowsing()
      ScreenState.BROWSING -> {
        if (currentSection != SidebarSection.HOME) {
          viewModel.setSection(SidebarSection.HOME)
        }
      }
    }
  }

  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(NovaBackground)
  ) {
    when (screenState) {
      ScreenState.PLAYER -> {
        PlayerScreen(viewModel = viewModel)
      }
      ScreenState.DETAIL -> {
        selectedMedia?.let { media ->
          DetailScreen(
            media = media,
            allMedia = allMedia,
            onBackClick = { viewModel.backToBrowsing() },
            onPlayClick = { m, ep -> viewModel.openPlayer(m, ep) },
            onToggleFavorite = { viewModel.toggleFavorite(it) }
          )
        } ?: run {
          viewModel.backToBrowsing()
        }
      }
      ScreenState.BROWSING -> {
        Row(modifier = Modifier.fillMaxSize()) {
          // Left Sidebar Menu
          SidebarNavigation(
            currentSection = currentSection,
            onSectionSelected = { viewModel.setSection(it) }
          )

          // Main Screen Content
          HomeScreen(
            viewModel = viewModel,
            modifier = Modifier.weight(1f)
          )
        }
      }
    }
  }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
  Text(text = "Hello $name!", modifier = modifier)
}

@Preview(name = "TV 16:9 1080p", device = "id:tv_1080p", showBackground = true)
@Preview(name = "TV 16:9 720p", device = "id:tv_720p", showBackground = true)
@Composable
fun StreamNovaTv16NinePreview() {
  MyApplicationTheme {
    Row(modifier = Modifier.fillMaxSize().background(NovaBackground)) {
      SidebarNavigation(
        currentSection = SidebarSection.HOME,
        onSectionSelected = {}
      )
      HomeScreen(
        viewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
        modifier = Modifier.weight(1f)
      )
    }
  }
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
  MyApplicationTheme { Greeting("StreamNova TV") }
}

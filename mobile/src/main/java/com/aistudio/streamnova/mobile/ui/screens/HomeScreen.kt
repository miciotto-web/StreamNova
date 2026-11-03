package com.aistudio.streamnova.mobile.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aistudio.streamnova.mobile.R
import com.aistudio.streamnova.mobile.ui.LocalWindowWidthExpanded
import com.aistudio.streamnova.mobile.ui.MobileCatalogViewModel
import com.aistudio.streamnova.mobile.ui.MobileHomeSectionKind
import com.aistudio.streamnova.mobile.ui.components.MobileEmptyState
import com.aistudio.streamnova.mobile.ui.components.MobileHeroCarousel
import com.aistudio.streamnova.mobile.ui.components.MobileLoadingState
import com.aistudio.streamnova.mobile.ui.components.MobileProviderCard
import com.aistudio.streamnova.mobile.ui.components.MobileRowStyle
import com.aistudio.streamnova.mobile.ui.components.MobileSectionRow
import com.aistudio.streamnova.mobile.ui.components.MobileTopBar
import com.aistudio.streamnova.mobile.ui.homeSections
import com.example.data.model.MediaItem
import com.example.ui.components.ProviderConstants
import com.example.ui.components.StreamingProvider
import com.example.ui.theme.NovaCyan
import com.example.ui.theme.NovaCyanBright
import com.example.ui.theme.NovaSurface
import com.example.ui.theme.NovaTextPrimary
import com.example.ui.theme.NovaTextSecondary

/**
 * Home mobile completa, adattata dalla Home TV 1.0.4.
 *
 * Ordine e contenuti vengono da [com.example.data.repository.HomeCatalogs]:
 * Hero dinamica, Continua a guardare, hub provider, Top 10 Film/Serie,
 * Novità Film/Serie, For You Film/Serie, Popolari Film/Serie e sezioni
 * catalogo aggiuntive. Nessun titolo, sezione o provider viene inventato:
 * gli stati loading/error/empty usano solo dati reali.
 */
@Composable
fun HomeScreen(
  viewModel: MobileCatalogViewModel,
  onMediaClick: (MediaItem) -> Unit,
  onPlayMedia: (MediaItem) -> Unit,
  onProviderClick: (StreamingProvider) -> Unit
) {
  val state by viewModel.catalog.collectAsStateWithLifecycle()
  val expanded = LocalWindowWidthExpanded.current
  var contextMenuMedia by remember { mutableStateOf<MediaItem?>(null) }

  BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
    val isLandscape = maxWidth > maxHeight
    // In landscape la Hero non deve occupare l'intera altezza disponibile:
    // le righe restano raggiungibili con uno scroll breve e nessun contenuto
    // viene tagliato dalle safe area.
    val heroHeight = when {
      isLandscape -> (maxHeight * 0.62f).coerceIn(220.dp, 320.dp)
      expanded -> 420.dp
      else -> 330.dp
    }

    LazyColumn(
      modifier = Modifier.fillMaxSize(),
      contentPadding = PaddingValues(bottom = 28.dp)
    ) {
      // Fase 8.4.1: la voce Impostazioni vive solo nella barra inferiore, quindi
      // la top bar della Home non espone più l'icona duplicata né il suo spazio.
      item(key = "topbar") {
        MobileTopBar(
          title = null,
          onSettings = null
        )
      }

      if (state.isRefreshing && state.hasHomeContent) {
        item(key = "refreshing") {
          LinearProgressIndicator(
            modifier = Modifier
              .fillMaxWidth()
              .height(2.dp),
            color = NovaCyan,
            trackColor = NovaSurface
          )
        }
      }

      when {
        state.isLoading && !state.hasHomeContent -> {
          item(key = "loading") {
            MobileLoadingState(message = stringResource(R.string.catalog_loading))
          }
        }

        state.error != null && !state.hasHomeContent -> {
          item(key = "error") {
            MobileEmptyState(
              title = stringResource(R.string.catalog_error_title),
              body = stringResource(R.string.catalog_error_body),
              actionLabel = stringResource(R.string.action_retry),
              onAction = viewModel::retry
            )
          }
        }

        !state.hasHomeContent -> {
          item(key = "empty") {
            MobileEmptyState(
              title = stringResource(R.string.catalog_error_title),
              body = stringResource(R.string.catalog_empty),
              actionLabel = stringResource(R.string.action_retry),
              onAction = viewModel::retry
            )
          }
        }

        else -> {
          state.homeSections().forEach { section ->
            when (section.kind) {
              MobileHomeSectionKind.HERO -> item(key = "hero") {
                MobileHeroCarousel(
                  items = section.items,
                  onItemClick = onMediaClick,
                  expanded = expanded,
                  compact = isLandscape,
                  height = heroHeight
                )
              }

              MobileHomeSectionKind.CONTINUE_WATCHING -> item(key = "row_${section.key}") {
                MobileSectionRow(
                  title = stringResource(R.string.home_continue),
                  badge = stringResource(
                    R.string.home_badge_in_progress,
                    section.items.size
                  ),
                  items = section.items,
                  onMediaClick = onMediaClick,
                  style = MobileRowStyle.CONTINUE,
                  onContinueClick = onPlayMedia,
                  onContinueLongClick = { contextMenuMedia = it }
                )
              }

              MobileHomeSectionKind.PROVIDERS -> item(key = "row_${section.key}") {
                MobileProviderHub(
                  title = stringResource(R.string.home_section_streaming_platforms),
                  badge = stringResource(R.string.home_badge_select_provider),
                  onProviderClick = onProviderClick
                )
              }

              MobileHomeSectionKind.TOP_10_ROW,
              MobileHomeSectionKind.POSTER_ROW,
              MobileHomeSectionKind.EXTRA_ROW -> item(key = "row_${section.key}") {
                MobileSectionRow(
                  title = section.title
                    ?: section.titleRes?.let { stringResource(it) }
                    .orEmpty(),
                  badge = section.badge
                    ?: section.badgeRes?.let { stringResource(it) },
                  items = section.items,
                  onMediaClick = onMediaClick,
                  style = if (section.kind == MobileHomeSectionKind.TOP_10_ROW) {
                    MobileRowStyle.TOP_10
                  } else {
                    MobileRowStyle.POSTER
                  }
                )
              }
            }
          }
        }
      }
    }
  }

  contextMenuMedia?.let { media ->
    ContinueWatchingActionsDialog(
      media = media,
      onPlay = {
        contextMenuMedia = null
        onPlayMedia(media)
      },
      onDetails = {
        contextMenuMedia = null
        onMediaClick(media)
      },
      onRemove = {
        contextMenuMedia = null
        viewModel.removeFromContinueWatching(media.id)
      },
      onDismiss = { contextMenuMedia = null }
    )
  }
}

/** Hub provider: stesse card, stesso ordine e stesse etichette della Home TV. */
@Composable
private fun MobileProviderHub(
  title: String,
  badge: String,
  onProviderClick: (StreamingProvider) -> Unit
) {
  Column(modifier = Modifier.fillMaxWidth()) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
      Text(
        text = title,
        color = NovaTextPrimary,
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold
      )
      Text(
        text = "• $badge",
        color = NovaCyanBright,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )
    }
    LazyRow(
      contentPadding = PaddingValues(horizontal = 16.dp),
      horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
      items(ProviderConstants.ALL, key = { it.id }) { provider ->
        MobileProviderCard(
          provider = provider,
          onClick = { onProviderClick(provider) }
        )
      }
    }
    Spacer(modifier = Modifier.height(14.dp))
  }
}

/**
 * Azioni long-press di «Continua a guardare», equivalenti al menu contestuale
 * TV: Guarda, Dettagli, Rimuovi da Continua a guardare.
 */
@Composable
private fun ContinueWatchingActionsDialog(
  media: MediaItem,
  onPlay: () -> Unit,
  onDetails: () -> Unit,
  onRemove: () -> Unit,
  onDismiss: () -> Unit
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    containerColor = NovaSurface,
    title = {
      Text(
        text = media.title,
        color = NovaTextPrimary,
        fontWeight = FontWeight.Bold,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
      )
    },
    text = {
      Column(modifier = Modifier.fillMaxWidth()) {
        DialogAction(text = stringResource(R.string.action_play), onClick = onPlay)
        DialogAction(text = stringResource(R.string.action_details), onClick = onDetails)
        DialogAction(
          text = stringResource(R.string.action_remove_continue_watching),
          onClick = onRemove
        )
      }
    },
    confirmButton = {},
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text(text = stringResource(R.string.action_cancel), color = NovaTextSecondary)
      }
    }
  )
}

@Composable
private fun DialogAction(text: String, onClick: () -> Unit) {
  Text(
    text = text,
    color = NovaTextPrimary,
    fontSize = 16.sp,
    fontWeight = FontWeight.Medium,
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .padding(vertical = 12.dp)
  )
}

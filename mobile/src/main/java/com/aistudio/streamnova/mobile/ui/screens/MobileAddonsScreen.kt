package com.aistudio.streamnova.mobile.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aistudio.streamnova.mobile.R
import com.aistudio.streamnova.mobile.ui.LocalWindowWidthExpanded
import com.aistudio.streamnova.mobile.ui.MobileAddonsViewModel
import com.aistudio.streamnova.mobile.ui.components.MobileAddonAddButton
import com.aistudio.streamnova.mobile.ui.components.MobileAddonCard
import com.aistudio.streamnova.mobile.ui.components.MobileAddonFeedbackBanner
import com.aistudio.streamnova.mobile.ui.components.MobileAddonRemoveDialog
import com.aistudio.streamnova.mobile.ui.components.MobileAddonsHeader
import com.aistudio.streamnova.mobile.ui.components.MobileEmptyState
import com.aistudio.streamnova.mobile.ui.components.MobileTextInputDialog
import com.example.data.stremio.InstalledAddon
import com.example.ui.components.ProviderConstants
import com.example.ui.theme.NovaBackground
import kotlinx.coroutines.delay

/**
 * Schermata Add-on mobile: la gestione della sezione "Addon Stremio" della TV
 * 1.0.4, adattata al touch.
 *
 * Porta gli stessi contenuti reali della TV — elenco installati, installazione
 * da URL, stato Attivo/Disattivo, rimozione, diagnostica dei cataloghi
 * dichiarati e associazioni Provider — con due differenze touch dichiarate:
 * la rimozione chiede conferma e ogni card ha l'azione "Aggiorna" (ri-fetch del
 * manifest dallo stesso URL salvato). Nessun dato viene inventato: gli esiti
 * mostrati sono quelli effettivi del repository condiviso.
 */
@Composable
fun MobileAddonsScreen(
  onBack: () -> Unit,
  viewModel: MobileAddonsViewModel = viewModel()
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  val expanded = LocalWindowWidthExpanded.current
  var showAddDialog by remember { mutableStateOf(false) }
  var removeCandidate by remember { mutableStateOf<InstalledAddon?>(null) }

  // Nomi reali dei provider StreamNova per le righe "Associazioni Provider",
  // come la TV.
  val providerNames = remember { ProviderConstants.ALL.associate { it.id to it.name } }

  BackHandler(onBack = onBack)

  // Il feedback (ok/errore) resta visibile per qualche secondo e poi svanisce,
  // come la schermata TV.
  LaunchedEffect(state.feedback) {
    if (state.feedback != null) {
      delay(4_000)
      viewModel.clearFeedback()
    }
  }

  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(NovaBackground)
  ) {
    LazyColumn(
      modifier = Modifier
        .align(Alignment.TopCenter)
        .widthIn(max = 1000.dp)
        .fillMaxSize(),
      contentPadding = PaddingValues(
        start = if (expanded) 32.dp else 12.dp,
        end = if (expanded) 32.dp else 12.dp,
        bottom = 48.dp
      ),
      verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
      item(key = "header") {
        MobileAddonsHeader(onBack = onBack)
      }

      item(key = "add") {
        MobileAddonAddButton(
          isInstalling = state.isInstalling,
          onClick = {
            if (!state.isInstalling) showAddDialog = true
          }
        )
      }

      state.feedback?.let { feedback ->
        item(key = "feedback") {
          MobileAddonFeedbackBanner(feedback = feedback)
        }
      }

      if (state.addons.isEmpty()) {
        item(key = "empty") {
          MobileEmptyState(
            title = stringResource(R.string.addons_empty_title),
            body = stringResource(R.string.addons_empty_desc)
          )
        }
      } else {
        items(state.addons, key = { it.installId }) { addon ->
          MobileAddonCard(
            addon = addon,
            proposals = viewModel.bindingProposals(addon),
            providerNames = providerNames,
            isUpdating = state.updatingInstallId == addon.installId,
            onToggleEnabled = { enabled -> viewModel.setEnabled(addon, enabled) },
            onUpdate = { viewModel.update(addon) },
            onRemoveRequest = { removeCandidate = addon },
            onConfirmBinding = { key, providerId -> viewModel.confirmBinding(key, providerId) },
            onRemoveBinding = { key, providerId -> viewModel.removeBinding(key, providerId) }
          )
        }
      }
    }
  }

  if (showAddDialog) {
    MobileTextInputDialog(
      title = stringResource(R.string.addons_dialog_title),
      hint = "https://torrentio.strem.fun/manifest.json",
      initialText = "",
      confirmLabel = stringResource(R.string.addons_dialog_install),
      onConfirm = { url ->
        showAddDialog = false
        viewModel.install(url)
      },
      onDismiss = { showAddDialog = false }
    )
  }

  removeCandidate?.let { addon ->
    MobileAddonRemoveDialog(
      addonName = addon.manifest.displayTitle,
      onConfirm = {
        viewModel.remove(addon)
        removeCandidate = null
      },
      onDismiss = { removeCandidate = null }
    )
  }
}

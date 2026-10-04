package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.example.R
import com.example.data.stremio.InstalledAddon
import com.example.ui.components.TvFocusBringIntoView
import com.example.ui.components.TvFocusableBox
import com.example.ui.components.TextInputDialog
import com.example.ui.theme.NovaBackground
import com.example.ui.theme.NovaCardBg
import com.example.ui.theme.NovaCardBgFocused
import com.example.ui.theme.NovaCyan
import com.example.ui.theme.NovaCyanBright
import com.example.ui.theme.NovaDivider
import com.example.ui.theme.NovaGreen
import com.example.ui.theme.NovaRed
import com.example.ui.theme.NovaTextMuted
import com.example.ui.theme.NovaTextPrimary
import com.example.ui.theme.NovaTextSecondary
import com.example.ui.viewmodel.StreamNovaViewModel
import kotlinx.coroutines.delay

/**
 * Schermata **Addon Stremio** (voce di menu laterale "Addon").
 *
 * Layout TV (Android TV / D-pad):
 *  - in alto il pulsante **"Aggiungi Addon"** che apre un dialog per
 *    incollare/inserire l'URL del manifest (istanze di Torrentio ecc.);
 *  - sotto l'elenco degli addon installati in card con **titolo, versione e
 *    descrizione**, switch di **abilitazione** e pulsante **"Rimuovi"**.
 *
 * Ogni elemento è focusabile con [TvFocusableBox]: la navigazione D-pad è
 * gestita in verticale (lista) e in orizzontale (toggle / rimuovi).
 */
@Composable
fun AddonsScreen(
  viewModel: StreamNovaViewModel,
  modifier: Modifier = Modifier
) {
  val addons by viewModel.installedAddons.collectAsState()
  val isInstalling by viewModel.addonInstalling.collectAsState()
  val message by viewModel.addonMessage.collectAsState()

  var showAddDialog by remember { mutableStateOf(false) }

  // Il feedback (ok/errore) resta visibile per qualche secondo e poi svanisce.
  LaunchedEffect(message) {
    if (message != null) {
      delay(4000)
      viewModel.clearAddonMessage()
    }
  }

  TvFocusBringIntoView {
    LazyColumn(
      modifier = modifier
        .fillMaxSize()
        .background(NovaBackground)
        .padding(start = 32.dp, top = 24.dp, end = 32.dp),
      contentPadding = PaddingValues(bottom = 48.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
      // ── Intestazione ──────────────────────────────────────────────────
      item(key = "addons_header") {
        Column {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
              imageVector = Icons.Default.Extension,
              contentDescription = null,
              tint = NovaCyanBright,
              modifier = Modifier.size(26.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
              text = stringResource(R.string.addons_title),
              color = NovaTextPrimary,
              fontSize = 24.sp,
              fontWeight = FontWeight.Bold
            )
          }
          Text(
            text = stringResource(R.string.addons_subtitle),
            color = NovaTextSecondary,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 4.dp)
          )
        }
      }

      // ── Aggiungi Addon ────────────────────────────────────────────────
      item(key = "addons_add_button") {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
          TvFocusableBox(
            shape = RoundedCornerShape(50),
            focusedScale = 1.04f,
            onClick = { if (!isInstalling) showAddDialog = true },
            modifier = Modifier.weight(1f, fill = false)
          ) { isFocused ->
            Row(
              modifier = Modifier
                .background(
                  if (isFocused) NovaCyanBright else NovaCyan.copy(alpha = 0.16f),
                  RoundedCornerShape(50)
                )
                .border(
                  1.dp,
                  if (isFocused) NovaCyanBright else NovaCyan,
                  RoundedCornerShape(50)
                )
                .padding(horizontal = 22.dp, vertical = 12.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
              if (isInstalling) {
                CircularProgressIndicator(
                  modifier = Modifier.size(16.dp),
                  color = if (isFocused) NovaBackground else NovaCyanBright,
                  strokeWidth = 2.dp
                )
              } else {
                Icon(
                  imageVector = Icons.Default.Add,
                  contentDescription = null,
                  tint = if (isFocused) NovaBackground else NovaCyanBright,
                  modifier = Modifier.size(18.dp)
                )
              }
              Text(
                text = if (isInstalling) stringResource(R.string.addons_installing) else stringResource(R.string.addons_add_button),
                color = if (isFocused) NovaBackground else NovaCyanBright,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
              )
            }
          }

          if (message != null) {
            Text(
              text = message ?: "",
              color = if (message!!.contains("fallita", ignoreCase = true)) NovaRed else NovaGreen,
              fontSize = 13.sp,
              fontWeight = FontWeight.SemiBold,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.weight(1f)
            )
          }
        }
      }

      // ── Stato vuoto ───────────────────────────────────────────────────
      if (addons.isEmpty()) {
        item(key = "addons_empty") {
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .padding(top = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
          ) {
            Icon(
              imageVector = Icons.Default.Info,
              contentDescription = null,
              tint = NovaTextMuted,
              modifier = Modifier.size(36.dp)
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
              text = stringResource(R.string.addons_empty_title),
              color = NovaTextPrimary,
              fontSize = 17.sp,
              fontWeight = FontWeight.SemiBold
            )
            Text(
              text = stringResource(R.string.addons_empty_desc),
              color = NovaTextMuted,
              fontSize = 13.sp,
              modifier = Modifier.padding(top = 6.dp)
            )
          }
        }
      }

      // ── Lista addon installati ────────────────────────────────────────
      items(addons, key = { it.baseUrl }) { addon ->
        AddonCard(
          addon = addon,
          onToggleEnabled = { enabled -> viewModel.setAddonEnabled(addon.id, enabled) },
          onRemove = { viewModel.removeAddon(addon.id) }
        )
      }
    }
  }

  if (showAddDialog) {
    TextInputDialog(
      title = stringResource(R.string.addons_dialog_title),
      hint = "https://torrentio.strem.fun/manifest.json",
      confirmLabel = stringResource(R.string.addons_dialog_install),
      onConfirm = { url ->
        showAddDialog = false
        viewModel.installAddon(url)
      },
      onDismiss = { showAddDialog = false }
    )
  }
}

/**
 * Card di un addon installato: titolo + versione + descrizione, switch
 * di abilitazione e pulsante "Rimuovi".
 */
@Composable
private fun AddonCard(
  addon: InstalledAddon,
  onToggleEnabled: (Boolean) -> Unit,
  onRemove: () -> Unit
) {
  val manifest = addon.manifest

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .background(NovaCardBg, RoundedCornerShape(14.dp))
      .border(1.dp, NovaDivider, RoundedCornerShape(14.dp))
      .padding(16.dp)
  ) {
    Row(verticalAlignment = Alignment.Top) {
      Icon(
        imageVector = Icons.Default.Extension,
        contentDescription = null,
        tint = if (addon.isEnabled) NovaCyanBright else NovaTextMuted,
        modifier = Modifier.size(28.dp)
      )
      Spacer(modifier = Modifier.width(14.dp))
      Column(modifier = Modifier.weight(1f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
            text = manifest.displayTitle,
            color = if (addon.isEnabled) NovaTextPrimary else NovaTextSecondary,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
          if (!manifest.version.isNullOrBlank()) {
            Spacer(modifier = Modifier.width(10.dp))
            Text(
              text = "v${manifest.version}",
              color = NovaCyanBright,
              fontSize = 12.sp,
              fontWeight = FontWeight.SemiBold,
              modifier = Modifier
                .background(NovaCyan.copy(alpha = 0.14f), RoundedCornerShape(50))
                .padding(horizontal = 8.dp, vertical = 2.dp)
            )
          }
        }
        if (!manifest.description.isNullOrBlank()) {
          Spacer(modifier = Modifier.height(6.dp))
          Text(
            text = manifest.description,
            color = NovaTextMuted,
            fontSize = 13.sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
          )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
          text = addon.baseUrl,
          color = NovaTextSecondary,
          fontSize = 11.sp,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
      }
    }

    Spacer(modifier = Modifier.height(14.dp))

    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween
    ) {
      // Switch di abilitazione (focusabile col D-pad).
      AddonSwitch(
        checked = addon.isEnabled,
        onCheckedChange = onToggleEnabled
      )

      // Rimuovi
      TvFocusableBox(
        shape = RoundedCornerShape(50),
        focusedScale = 1.05f,
        onClick = onRemove
      ) { isFocused ->
        Row(
          modifier = Modifier
            .background(
              if (isFocused) NovaCyan.copy(alpha = 0.22f) else NovaCardBgFocused,
              RoundedCornerShape(50)
            )
            .border(
              1.dp,
              if (isFocused) NovaCyanBright else NovaDivider,
              RoundedCornerShape(50)
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          Icon(
            imageVector = Icons.Default.Delete,
            contentDescription = stringResource(R.string.action_remove),
            tint = if (isFocused) NovaCyanBright else NovaTextSecondary,
            modifier = Modifier.size(16.dp)
          )
          Text(
            text = stringResource(R.string.action_remove),
            color = if (isFocused) NovaCyanBright else NovaTextSecondary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
          )
        }
      }
    }
  }
}

/**
 * Switch stilizzato ON/OFF per TV: tutta la pillola è focusabile e premibile
 * con il tasto OK del telecomando.
 */
@Composable
private fun AddonSwitch(
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit
) {
  TvFocusableBox(
    shape = RoundedCornerShape(50),
    focusedScale = 1.05f,
    onClick = { onCheckedChange(!checked) }
  ) { isFocused ->
    Row(
      modifier = Modifier
        .background(
          if (checked) NovaCyan.copy(alpha = 0.28f) else NovaCardBgFocused,
          RoundedCornerShape(50)
        )
        .border(
          1.5.dp,
          when {
            isFocused -> NovaCyanBright
            checked -> NovaCyan
            else -> NovaDivider
          },
          RoundedCornerShape(50)
        )
        .padding(horizontal = 12.dp, vertical = 7.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
      // Capsula con knob a sinistra/destra in base allo stato.
      Box(
        modifier = Modifier
          .width(40.dp)
          .height(20.dp)
          .background(
            if (checked) NovaCyan else NovaDivider,
            RoundedCornerShape(50)
          )
          .padding(2.dp)
      ) {
        Box(
          modifier = Modifier
            .align(if (checked) Alignment.CenterEnd else Alignment.CenterStart)
            .size(16.dp)
            .background(NovaTextPrimary, CircleShape)
        )
      }
      Text(
        text = if (checked) stringResource(R.string.addons_switch_active) else stringResource(R.string.addons_switch_inactive),
        color = when {
          isFocused -> NovaCyanBright
          checked -> NovaTextPrimary
          else -> NovaTextMuted
        },
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold
      )
    }
  }
}

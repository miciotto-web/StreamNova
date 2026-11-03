package com.aistudio.streamnova.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistudio.streamnova.mobile.R
import com.aistudio.streamnova.mobile.ui.MobileAddonFeedback
import com.aistudio.streamnova.mobile.ui.addonVersionLabel
import com.aistudio.streamnova.mobile.ui.catalogCapabilityTokens
import com.aistudio.streamnova.mobile.ui.messageRes
import com.example.data.stremio.InstalledAddon
import com.example.data.stremio.RESOURCE_CATALOG
import com.example.data.stremio.StremioCatalogDefinition
import com.example.data.stremio.provider.CatalogKey
import com.example.data.stremio.provider.ProviderBindingProposalStatus
import com.example.data.stremio.provider.ProviderBindingProposalView
import com.example.ui.theme.NovaBackground
import com.example.ui.theme.NovaCardBg
import com.example.ui.theme.NovaCyan
import com.example.ui.theme.NovaCyanBright
import com.example.ui.theme.NovaDivider
import com.example.ui.theme.NovaGreen
import com.example.ui.theme.NovaRed
import com.example.ui.theme.NovaSurfaceVariant
import com.example.ui.theme.NovaTextMuted
import com.example.ui.theme.NovaTextPrimary
import com.example.ui.theme.NovaTextSecondary

/** Intestazione della schermata Add-on: back, icona, titolo e sottotitolo TV. */
@Composable
fun MobileAddonsHeader(onBack: () -> Unit) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(top = 8.dp, bottom = 2.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    IconButton(onClick = onBack) {
      Icon(
        Icons.Default.ArrowBack,
        contentDescription = stringResource(R.string.action_back),
        tint = NovaTextPrimary
      )
    }
    Column {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
          imageVector = Icons.Default.Extension,
          contentDescription = null,
          tint = NovaCyanBright,
          modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
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
        fontSize = 12.sp
      )
    }
  }
}

/** Pulsante principale "Aggiungi Addon" con spinner durante l'installazione. */
@Composable
fun MobileAddonAddButton(
  isInstalling: Boolean,
  onClick: () -> Unit
) {
  Button(
    onClick = { if (!isInstalling) onClick() },
    enabled = !isInstalling,
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(50),
    colors = ButtonDefaults.buttonColors(
      containerColor = NovaCyan.copy(alpha = 0.16f),
      contentColor = NovaCyanBright,
      disabledContainerColor = NovaCyan.copy(alpha = 0.10f),
      disabledContentColor = NovaCyan.copy(alpha = 0.6f)
    )
  ) {
    if (isInstalling) {
      CircularProgressIndicator(
        modifier = Modifier.size(16.dp),
        color = NovaCyanBright,
        strokeWidth = 2.dp
      )
    } else {
      Icon(
        imageVector = Icons.Default.Add,
        contentDescription = null,
        modifier = Modifier.size(18.dp)
      )
    }
    Spacer(modifier = Modifier.width(8.dp))
    Text(
      text = if (isInstalling) {
        stringResource(R.string.addons_installing)
      } else {
        stringResource(R.string.addons_add_button)
      },
      fontWeight = FontWeight.Bold
    )
  }
}

/** Esito dell'ultima operazione: verde per successo, rosso per errore. */
@Composable
fun MobileAddonFeedbackBanner(feedback: MobileAddonFeedback) {
  val isError = feedback is MobileAddonFeedback.Failed
  val text = when (feedback) {
    is MobileAddonFeedback.Installed ->
      stringResource(R.string.addons_feedback_installed, feedback.addonName)

    is MobileAddonFeedback.Updated ->
      stringResource(R.string.addons_feedback_updated, feedback.addonName)

    MobileAddonFeedback.Removed -> stringResource(R.string.addons_feedback_removed)
    is MobileAddonFeedback.Failed -> stringResource(feedback.error.messageRes())
  }
  Text(
    text = text,
    color = if (isError) NovaRed else NovaGreen,
    fontSize = 13.sp,
    fontWeight = FontWeight.SemiBold,
    modifier = Modifier
      .fillMaxWidth()
      .background(
        (if (isError) NovaRed else NovaGreen).copy(alpha = 0.10f),
        RoundedCornerShape(10.dp)
      )
      .padding(horizontal = 12.dp, vertical = 10.dp)
  )
}

/**
 * Card di un addon installato: dati reali del manifest, switch Attivo/Disattivo
 * e azioni touch Aggiorna / Rimuovi / Cataloghi / Associazioni Provider.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MobileAddonCard(
  addon: InstalledAddon,
  proposals: List<ProviderBindingProposalView>,
  providerNames: Map<String, String>,
  isUpdating: Boolean,
  onToggleEnabled: (Boolean) -> Unit,
  onUpdate: () -> Unit,
  onRemoveRequest: () -> Unit,
  onConfirmBinding: (CatalogKey, String) -> Unit,
  onRemoveBinding: (CatalogKey, String) -> Unit
) {
  val manifest = addon.manifest
  var isCatalogsExpanded by rememberSaveable(addon.installId) { mutableStateOf(false) }
  var isBindingsExpanded by rememberSaveable(addon.installId) { mutableStateOf(false) }
  val catalogs = manifest.validCatalogs()
  val idPrefixes = manifest.idPrefixesForResource(RESOURCE_CATALOG) ?: manifest.idPrefixes
  val prefixesText = idPrefixes?.joinToString(", ")

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
        modifier = Modifier.size(26.dp)
      )
      Spacer(modifier = Modifier.width(12.dp))
      Column(modifier = Modifier.weight(1f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
            text = manifest.displayTitle,
            color = if (addon.isEnabled) NovaTextPrimary else NovaTextSecondary,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
          )
          addonVersionLabel(manifest.version)?.let { label ->
            Spacer(modifier = Modifier.width(10.dp))
            Text(
              text = label,
              color = NovaCyanBright,
              fontSize = 12.sp,
              fontWeight = FontWeight.SemiBold,
              modifier = Modifier
                .background(NovaCyan.copy(alpha = 0.14f), RoundedCornerShape(50))
                .padding(horizontal = 8.dp, vertical = 2.dp)
            )
          }
        }
        manifest.description?.takeIf { it.isNotBlank() }?.let { description ->
          Spacer(modifier = Modifier.height(6.dp))
          Text(
            text = description,
            color = NovaTextMuted,
            fontSize = 13.sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
          )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
          text = stringResource(R.string.addons_label_id, addon.id),
          color = NovaTextMuted,
          fontSize = 11.sp,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
          text = stringResource(R.string.addons_label_url, addon.baseUrl),
          color = NovaTextSecondary,
          fontSize = 11.sp,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
      }
    }

    Spacer(modifier = Modifier.height(12.dp))

    Row(verticalAlignment = Alignment.CenterVertically) {
      Switch(
        checked = addon.isEnabled,
        onCheckedChange = onToggleEnabled,
        colors = SwitchDefaults.colors(
          checkedThumbColor = NovaBackground,
          checkedTrackColor = NovaCyanBright,
          uncheckedThumbColor = NovaTextMuted,
          uncheckedTrackColor = NovaDivider
        )
      )
      Spacer(modifier = Modifier.width(10.dp))
      Text(
        text = if (addon.isEnabled) {
          stringResource(R.string.addons_switch_active)
        } else {
          stringResource(R.string.addons_switch_inactive)
        },
        color = if (addon.isEnabled) NovaCyanBright else NovaTextMuted,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold
      )
      Spacer(modifier = Modifier.weight(1f))
      if (isUpdating) {
        CircularProgressIndicator(
          modifier = Modifier.size(18.dp),
          color = NovaCyanBright,
          strokeWidth = 2.dp
        )
      }
    }

    Spacer(modifier = Modifier.height(12.dp))

    FlowRow(
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      MobileAddonActionPill(
        label = stringResource(R.string.addons_update_action),
        icon = Icons.Default.Refresh,
        enabled = !isUpdating,
        onClick = onUpdate
      )
      MobileAddonActionPill(
        label = stringResource(R.string.action_remove),
        icon = Icons.Default.Delete,
        onClick = onRemoveRequest
      )
      MobileAddonActionPill(
        label = stringResource(R.string.addons_catalogs_section, catalogs.size),
        icon = Icons.Default.Info,
        onClick = { isCatalogsExpanded = !isCatalogsExpanded }
      )
      if (proposals.isNotEmpty()) {
        MobileAddonActionPill(
          label = stringResource(R.string.addons_bindings_section, proposals.size),
          icon = Icons.Default.Check,
          onClick = { isBindingsExpanded = !isBindingsExpanded }
        )
      }
    }

    if (isCatalogsExpanded) {
      Spacer(modifier = Modifier.height(12.dp))
      MobileAddonCatalogDiagnostics(catalogs = catalogs, prefixesText = prefixesText)
    }

    if (isBindingsExpanded && proposals.isNotEmpty()) {
      Spacer(modifier = Modifier.height(12.dp))
      MobileAddonBindingSection(
        proposals = proposals,
        providerNames = providerNames,
        onConfirm = onConfirmBinding,
        onRemove = onRemoveBinding
      )
    }
  }
}

/** Pillola azione touch della card. */
@Composable
private fun MobileAddonActionPill(
  label: String,
  icon: ImageVector,
  enabled: Boolean = true,
  onClick: () -> Unit
) {
  Row(
    modifier = Modifier
      .background(NovaBackground.copy(alpha = 0.55f), RoundedCornerShape(50))
      .border(1.dp, NovaDivider, RoundedCornerShape(50))
      .clickable(enabled = enabled, onClick = onClick)
      .padding(horizontal = 14.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(6.dp)
  ) {
    Icon(
      imageVector = icon,
      contentDescription = null,
      tint = if (enabled) NovaTextSecondary else NovaTextMuted,
      modifier = Modifier.size(15.dp)
    )
    Text(
      text = label,
      color = if (enabled) NovaTextSecondary else NovaTextMuted,
      fontSize = 13.sp,
      fontWeight = FontWeight.SemiBold
    )
  }
}

/**
 * Diagnostica dei cataloghi dichiarati dal manifest: id, tipo, idPrefixes ed
 * extra realmente supportati. Nessun valore viene inventato.
 */
@Composable
private fun MobileAddonCatalogDiagnostics(
  catalogs: List<StremioCatalogDefinition>,
  prefixesText: String?
) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .background(NovaBackground.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
      .border(1.dp, NovaDivider.copy(alpha = 0.7f), RoundedCornerShape(10.dp))
      .padding(12.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp)
  ) {
    Text(
      text = stringResource(R.string.addons_catalogs_header, catalogs.size),
      color = NovaCyanBright,
      fontSize = 13.sp,
      fontWeight = FontWeight.Bold
    )
    if (catalogs.isEmpty()) {
      Text(
        text = stringResource(R.string.addons_catalogs_empty),
        color = NovaTextMuted,
        fontSize = 12.sp
      )
    } else {
      catalogs.forEach { catalog -> MobileAddonCatalogRow(catalog, prefixesText) }
    }
  }
}

/** Singola riga catalogo della diagnostica. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MobileAddonCatalogRow(
  catalog: StremioCatalogDefinition,
  prefixesText: String?
) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .background(NovaCardBg.copy(alpha = 0.75f), RoundedCornerShape(8.dp))
      .border(1.dp, NovaDivider, RoundedCornerShape(8.dp))
      .padding(10.dp),
    verticalArrangement = Arrangement.spacedBy(3.dp)
  ) {
    Text(
      text = catalog.displayTitle,
      color = NovaTextPrimary,
      fontSize = 14.sp,
      fontWeight = FontWeight.Bold
    )
    Text(
      text = stringResource(R.string.addons_label_id, catalog.effectiveId),
      color = NovaCyan,
      fontSize = 12.sp,
      fontWeight = FontWeight.SemiBold
    )
    Text(
      text = stringResource(R.string.addons_catalog_type, catalog.effectiveType),
      color = NovaTextSecondary,
      fontSize = 12.sp
    )
    if (!prefixesText.isNullOrBlank()) {
      Text(
        text = stringResource(R.string.addons_catalog_prefixes, prefixesText),
        color = NovaTextMuted,
        fontSize = 12.sp
      )
    }
    val tokens = catalogCapabilityTokens(catalog)
    if (tokens.isNotEmpty()) {
      FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        tokens.forEach { token ->
          Text(
            text = "✓ $token",
            color = NovaGreen,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
              .background(NovaGreen.copy(alpha = 0.10f), RoundedCornerShape(4.dp))
              .border(0.5.dp, NovaGreen.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
              .padding(horizontal = 6.dp, vertical = 2.dp)
          )
        }
      }
    }
  }
}

/**
 * Sezione "Associazioni Provider": le proposte euristiche raggruppate per
 * provider, con le stesse azioni della TV (Conferma/Sostituisci, Rimuovi).
 */
@Composable
private fun MobileAddonBindingSection(
  proposals: List<ProviderBindingProposalView>,
  providerNames: Map<String, String>,
  onConfirm: (CatalogKey, String) -> Unit,
  onRemove: (CatalogKey, String) -> Unit
) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .background(NovaBackground.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
      .border(1.dp, NovaDivider.copy(alpha = 0.7f), RoundedCornerShape(10.dp))
      .padding(12.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp)
  ) {
    Text(
      text = stringResource(R.string.addons_bindings_header, proposals.size),
      color = NovaCyanBright,
      fontSize = 13.sp,
      fontWeight = FontWeight.Bold
    )
    proposals.forEach { view ->
      MobileAddonBindingRow(
        view = view,
        providerName = providerNames[view.providerId] ?: view.providerId,
        onConfirm = onConfirm,
        onRemove = onRemove
      )
    }
  }
}

/** Riga di una proposta: catalogo, provider suggerito e azione reale. */
@Composable
private fun MobileAddonBindingRow(
  view: ProviderBindingProposalView,
  providerName: String,
  onConfirm: (CatalogKey, String) -> Unit,
  onRemove: (CatalogKey, String) -> Unit
) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .background(NovaCardBg.copy(alpha = 0.75f), RoundedCornerShape(8.dp))
      .border(1.dp, NovaDivider, RoundedCornerShape(8.dp))
      .padding(10.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp)
  ) {
    Text(
      text = view.catalogTitle,
      color = NovaTextPrimary,
      fontSize = 13.sp,
      fontWeight = FontWeight.SemiBold,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis
    )
    when (view.status) {
      ProviderBindingProposalStatus.USER,
      ProviderBindingProposalStatus.REGISTRY -> Text(
        text = stringResource(R.string.addons_binding_associated, providerName),
        color = NovaGreen,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium
      )

      ProviderBindingProposalStatus.PROPOSED -> Text(
        text = providerName,
        color = NovaTextSecondary,
        fontSize = 12.sp
      )
    }
    when {
      view.isRemovable -> MobileAddonActionPill(
        label = stringResource(R.string.addons_binding_remove),
        icon = Icons.Default.Delete,
        onClick = { onRemove(view.key, view.boundProviderId ?: view.providerId) }
      )

      view.isConfirmable -> MobileAddonActionPill(
        // "Sostituisci" quando la chiave è già coperta dalla registry con un
        // provider diverso, come la TV.
        label = if (view.status == ProviderBindingProposalStatus.REGISTRY) {
          stringResource(R.string.addons_binding_replace)
        } else {
          stringResource(R.string.addons_binding_confirm)
        },
        icon = Icons.Default.Check,
        onClick = { onConfirm(view.key, view.providerId) }
      )
    }
  }
}

/** Conferma touch della rimozione: protegge l'URL di configurazione salvato. */
@Composable
fun MobileAddonRemoveDialog(
  addonName: String,
  onConfirm: () -> Unit,
  onDismiss: () -> Unit
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    containerColor = NovaSurfaceVariant,
    title = {
      Text(
        text = stringResource(R.string.addons_remove_confirm_title),
        color = NovaTextPrimary,
        fontWeight = FontWeight.Bold
      )
    },
    text = {
      Text(
        text = stringResource(R.string.addons_remove_confirm_body, addonName),
        color = NovaTextSecondary
      )
    },
    confirmButton = {
      TextButton(onClick = onConfirm) {
        Text(
          text = stringResource(R.string.action_remove),
          color = NovaRed,
          fontWeight = FontWeight.Bold
        )
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text(text = stringResource(R.string.action_cancel), color = NovaTextSecondary)
      }
    }
  )
}

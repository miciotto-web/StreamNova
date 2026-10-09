package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.example.R
import com.example.data.prefs.AppSettingsRepository
import com.example.data.stremio.AddonSyncServer
import com.example.data.stremio.InstalledAddon
import com.example.data.stremio.RESOURCE_CATALOG
import com.example.data.stremio.StremioCatalogDefinition
import com.example.data.stremio.provider.CatalogKey
import com.example.data.stremio.provider.DefaultProviderCatalogResolver
import com.example.data.stremio.provider.ProviderBinding
import com.example.data.stremio.provider.ProviderBindingConfirmation
import com.example.data.stremio.provider.ProviderBindingProposalStatus
import com.example.data.stremio.provider.ProviderBindingProposalView
import com.example.data.stremio.provider.ProviderCatalogResolver
import com.example.data.stremio.provider.UserProviderBindingStore
import com.example.data.repository.StremioCatalogRepository
import com.example.ui.components.ProviderConstants
import com.example.ui.components.QrCodeInfoCard
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
import kotlinx.coroutines.launch

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
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun AddonsScreen(
  viewModel: StreamNovaViewModel,
  modifier: Modifier = Modifier
) {
  val addons by viewModel.installedAddons.collectAsState()
  val isInstalling by viewModel.addonInstalling.collectAsState()
  val message by viewModel.addonMessage.collectAsState()

  var showAddDialog by remember { mutableStateOf(false) }

  // ── Micro-server locale per la configurazione da smartphone ─────────────
  // Il server resta in ascolto finché la schermata è attiva: il QR Code
  // punta a http://<IP_LOCALE>:8080. Le installazioni arrivate dal telefono
  // passano da StremioAddonRepository.addons, quindi la lista qui sotto si
  // aggiorna in modo reattivo senza refresh manuali.
  val serverState by AddonSyncServer.serverState.collectAsState()
  val serverUrl by AddonSyncServer.serverUrl.collectAsState()
  LaunchedEffect(Unit) { AddonSyncServer.start() }
  DisposableEffect(Unit) { onDispose { AddonSyncServer.stop() } }

  // ── Binding catalogo -> provider ───────────────────────────────────────────
  // L'euristica PROPONE, l'utente CONFERMA: nessuna associazione nasce senza un
  // click esplicito. La persistenza e' quella di C1 (UserProviderBindingStore ->
  // AppSettingsRepository), quindi la precedenza USER > REGISTRY del resolver
  // vale anche per cio' che viene creato da qui.
  val userBindingsJson by AppSettingsRepository.userProviderBindingsJson.collectAsState()
  val userBindings = remember(userBindingsJson) { UserProviderBindingStore.decode(userBindingsJson) }
  val resolver = remember { StremioCatalogRepository.resolver() }
  val providerNames = remember { ProviderConstants.ALL.associate { it.id to it.name } }
  val scope = rememberCoroutineScope()

  // Le righe si ricalcolano a ogni cambio dei binding utente: dopo una conferma o
  // una rimozione lo stato passa a "Associato" senza ricaricare il manifest.
  val onConfirmBinding: (CatalogKey, String) -> Unit = { key, providerId ->
    // `resolver` come guardia: un binding gia' dichiarato in registry per lo stesso
    // provider non viene duplicato come USER.
    val result = ProviderBindingConfirmation.confirm(userBindings, key, providerId, resolver::resolve)
    if (result.changed) scope.launch { UserProviderBindingStore.persist(result.bindings) }
  }
  val onRemoveBinding: (CatalogKey, String) -> Unit = { key, providerId ->
    val result = ProviderBindingConfirmation.remove(userBindings, key, providerId)
    if (result.changed) scope.launch { UserProviderBindingStore.persist(result.bindings) }
  }

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
        .padding(start = 32.dp, top = 24.dp, end = 32.dp)
        // Focus group: la ricerca direzionale del D-pad viene prima risolta TRA gli
        // elementi di questa schermata. Senza di esso la ricerca parte dalla radice
        // Compose e le voci della sidebar (focusabili anche chiusa) competono
        // geometricamente con i pulsanti di qui: avendo questi ultimi distanze
        // verticali molto maggiori, il focus saltava sulla sidebar con UP/DOWN.
        .focusGroup(),
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
          modifier = Modifier
            .fillMaxWidth()
            // Limite superiore: sopra il pulsante l'intestazione non e' focusabile,
            // quindi UP non ha destinazione interna e non deve uscire verso la
            // sidebar. Con la lista vuota vale lo stesso per il DOWN.
            .focusProperties {
              up = FocusRequester.Cancel
              if (addons.isEmpty()) down = FocusRequester.Cancel
            },
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

      // ── Configurazione remota via QR (micro-server locale) ───────────
      item(key = "addons_remote_setup") {
        QrCodeInfoCard(
          url = serverUrl,
          fallbackMessage = (serverState as? AddonSyncServer.ServerState.Error)?.message,
          modifier = Modifier.fillMaxWidth()
        )
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
      itemsIndexed(addons, key = { _, addon -> addon.baseUrl }) { index, addon ->
        AddonCard(
          addon = addon,
          isLast = index == addons.lastIndex,
          userBindings = userBindings,
          resolver = resolver,
          providerNames = providerNames,
          onToggleEnabled = { enabled -> viewModel.setAddonEnabled(addon.id, enabled) },
          onRemove = { viewModel.removeAddon(addon.id) },
          onConfirmBinding = onConfirmBinding,
          onRemoveBinding = onRemoveBinding
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
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun AddonCard(
  addon: InstalledAddon,
  isLast: Boolean,
  userBindings: List<ProviderBinding>,
  resolver: ProviderCatalogResolver,
  providerNames: Map<String, String>,
  onToggleEnabled: (Boolean) -> Unit,
  onRemove: () -> Unit,
  onConfirmBinding: (CatalogKey, String) -> Unit,
  onRemoveBinding: (CatalogKey, String) -> Unit
) {
  val manifest = addon.manifest
  var isCatalogsExpanded by rememberSaveable(addon.id) { mutableStateOf(false) }
  var isProvidersExpanded by rememberSaveable(addon.id) { mutableStateOf(false) }
  val catalogs = manifest.validCatalogs()
  val proposals = rememberProviderProposals(addon, userBindings, resolver)
  val idPrefixes = manifest.idPrefixesForResource(RESOURCE_CATALOG) ?: manifest.idPrefixes
  val prefixesText = idPrefixes?.joinToString(", ")

  // Limite inferiore della schermata: sull'ultima card, se sotto la riga azioni non
  // resta nessuna riga focusabile (sezioni non espanse o senza righe), il DOWN non ha
  // destinazione interna e non deve uscire verso la sidebar. Ora OGNI riga della
  // sezione "Associazioni Provider" e' focusabile, non solo quelle con la pillola.
  val isBottomBoundary = isLast &&
    !(isCatalogsExpanded && catalogs.isNotEmpty()) &&
    !(isProvidersExpanded && proposals.isNotEmpty())

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .background(NovaCardBg, RoundedCornerShape(14.dp))
      .border(1.dp, NovaDivider, RoundedCornerShape(14.dp))
      .padding(16.dp)
      .then(
        if (isBottomBoundary) Modifier.focusProperties { down = FocusRequester.Cancel }
        else Modifier
      )
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
          text = "ID: ${addon.id}",
          color = NovaTextMuted,
          fontSize = 11.sp,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
          text = "URL: ${addon.baseUrl}",
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

      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        // Diagnostica cataloghi (focusabile col D-pad)
        TvFocusableBox(
          shape = RoundedCornerShape(50),
          focusedScale = 1.05f,
          onClick = { isCatalogsExpanded = !isCatalogsExpanded }
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
              .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            Icon(
              imageVector = Icons.Default.Info,
              contentDescription = null,
              tint = if (isFocused) NovaCyanBright else NovaTextSecondary,
              modifier = Modifier.size(16.dp)
            )
            Text(
              text = if (isCatalogsExpanded) "Cataloghi (${catalogs.size}) ▲" else "Cataloghi disponibili (${catalogs.size}) ▼",
              color = if (isFocused) NovaCyanBright else NovaTextSecondary,
              fontSize = 13.sp,
              fontWeight = FontWeight.SemiBold
            )
          }
        }

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

        // Associazioni Provider proposte (focusabile col D-pad)
        TvFocusableBox(
          shape = RoundedCornerShape(50),
          focusedScale = 1.05f,
          onClick = { isProvidersExpanded = !isProvidersExpanded }
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
              .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            Icon(
              imageVector = Icons.Default.Star,
              contentDescription = null,
              tint = if (isFocused) NovaCyanBright else NovaTextSecondary,
              modifier = Modifier.size(16.dp)
            )
            Text(
              text = if (isProvidersExpanded) {
                "Associazioni Provider (${proposals.size}) ▲"
              } else {
                "Associazioni Provider (${proposals.size}) ▼"
              },
              color = if (isFocused) NovaCyanBright else NovaTextSecondary,
              fontSize = 13.sp,
              fontWeight = FontWeight.SemiBold
            )
          }
        }
      }
    }

    if (isCatalogsExpanded) {
      Spacer(modifier = Modifier.height(14.dp))
      CatalogDiagnosticSection(
        catalogs = catalogs,
        globalPrefixesText = prefixesText
      )
    }

    if (isProvidersExpanded) {
      Spacer(modifier = Modifier.height(14.dp))
      ProviderBindingSection(
        addonTitle = manifest.displayTitle,
        proposals = proposals,
        providerNames = providerNames,
        isScreenBottom = isLast,
        onConfirm = onConfirmBinding,
        onRemove = onRemoveBinding
      )
    }
  }
}

/**
 * Sezione diagnostica dei cataloghi dichiarati dal manifest dell'addon.
 */
@Composable
private fun CatalogDiagnosticSection(
  catalogs: List<StremioCatalogDefinition>,
  globalPrefixesText: String?
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
      text = "Cataloghi dichiarati dal manifest (${catalogs.size}):",
      color = NovaCyanBright,
      fontSize = 13.sp,
      fontWeight = FontWeight.Bold
    )

    if (catalogs.isEmpty()) {
      Text(
        text = "Nessun catalogo dichiarato nel manifest.",
        color = NovaTextMuted,
        fontSize = 12.sp,
        modifier = Modifier.padding(vertical = 4.dp)
      )
    } else {
      catalogs.forEach { catalog ->
        TvFocusableBox(
          shape = RoundedCornerShape(8.dp),
          focusedScale = 1.01f
        ) { isFocused ->
          CatalogDiagnosticCard(
            catalog = catalog,
            globalPrefixesText = globalPrefixesText,
            isFocused = isFocused
          )
        }
      }
    }
  }
}

/**
 * Singola scheda catalogo diagnostico con tutti gli attributi dichiarati dal manifest.
 */
@Composable
private fun CatalogDiagnosticCard(
  catalog: StremioCatalogDefinition,
  globalPrefixesText: String?,
  isFocused: Boolean
) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .background(
        if (isFocused) NovaCardBgFocused.copy(alpha = 0.95f) else NovaCardBgFocused,
        RoundedCornerShape(8.dp)
      )
      .border(
        1.dp,
        if (isFocused) NovaCyanBright else NovaDivider,
        RoundedCornerShape(8.dp)
      )
      .padding(12.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp)
  ) {
    // Nome visualizzato
    Text(
      text = catalog.displayTitle,
      color = if (isFocused) NovaCyanBright else NovaTextPrimary,
      fontSize = 15.sp,
      fontWeight = FontWeight.Bold
    )

    // ID catalogo
    Row {
      Text(
        text = "ID: ",
        color = NovaTextMuted,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium
      )
      Text(
        text = catalog.effectiveId,
        color = NovaCyan,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold
      )
    }

    // Tipo
    Row {
      Text(
        text = "Tipo: ",
        color = NovaTextMuted,
        fontSize = 12.sp
      )
      Text(
        text = catalog.effectiveType,
        color = NovaTextSecondary,
        fontSize = 12.sp
      )
    }

    // idPrefixes
    if (!globalPrefixesText.isNullOrBlank()) {
      Row {
        Text(
          text = "idPrefixes: ",
          color = NovaTextMuted,
          fontSize = 12.sp
        )
        Text(
          text = globalPrefixesText,
          color = NovaTextSecondary,
          fontSize = 12.sp
        )
      }
    }

    // Extra supportati
    val extrasList = catalog.supportedExtraNames()
    Row {
      Text(
        text = "Extra: ",
        color = NovaTextMuted,
        fontSize = 12.sp
      )
      Text(
        text = if (extrasList.isNotEmpty()) extrasList.joinToString(", ") else "nessuno",
        color = NovaTextSecondary,
        fontSize = 12.sp
      )
    }

    // Badge per search, genre, skip
    Spacer(modifier = Modifier.height(2.dp))
    Row(
      horizontalArrangement = Arrangement.spacedBy(6.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      DiagnosticBadge(
        label = "search",
        isSupported = catalog.supportsSearch()
      )
      val genreLabel = if (catalog.supportsGenre() && catalog.genreOptions().isNotEmpty()) {
        "genre (${catalog.genreOptions().size})"
      } else {
        "genre"
      }
      DiagnosticBadge(
        label = genreLabel,
        isSupported = catalog.supportsGenre()
      )
      DiagnosticBadge(
        label = "skip",
        isSupported = catalog.supportsSkip()
      )
    }
  }
}

/**
 * Badge di diagnostica per indicatori di supporto (search, genre, skip).
 */
@Composable
private fun DiagnosticBadge(
  label: String,
  isSupported: Boolean
) {
  val bg = if (isSupported) NovaCyan.copy(alpha = 0.15f) else NovaCardBg
  val textCol = if (isSupported) NovaCyanBright else NovaTextMuted
  val borderCol = if (isSupported) NovaCyan.copy(alpha = 0.35f) else NovaDivider

  Text(
    text = if (isSupported) "✓ $label" else "✗ $label",
    color = textCol,
    fontSize = 11.sp,
    fontWeight = FontWeight.Medium,
    modifier = Modifier
      .background(bg, RoundedCornerShape(4.dp))
      .border(0.5.dp, borderCol, RoundedCornerShape(4.dp))
      .padding(horizontal = 6.dp, vertical = 2.dp)
  )
}

/**
 * Calcola le proposte del proposer euristico per un addon, arricchite con lo stato
 * reale di ogni chiave (USER / REGISTRY / nessuna).
 *
 * Il resolver è l'unico oracolo sugli "associati": la UI quindi concorda sempre con
 * la precedenza USER > REGISTRY usata dal caricamento dei cataloghi. Le proposte non
 * scrivono nulla e non dipendono dalla rete.
 */
@Composable
private fun rememberProviderProposals(
  addon: InstalledAddon,
  userBindings: List<ProviderBinding>,
  resolver: ProviderCatalogResolver
): List<ProviderBindingProposalView> = remember(addon.id, userBindings, resolver) {
  ProviderBindingConfirmation.proposalsFor(
    manifest = addon.manifest,
    providers = DefaultProviderCatalogResolver.defaultAliasProviders(),
    existingBinding = { key -> resolver.resolve(key) }
  )
}

/**
 * Sezione "Associazioni Provider" di un addon: le proposte euristiche raggruppate per
 * provider StreamNova, con il pulsante di conferma.
 *
 * Un catalogo senza proposta non viene né nascosto né bloccato: resta un normale
 * catalogo utilizzabile dai flussi generici (Home, Film, Serie TV, ricerca).
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun ProviderBindingSection(
  addonTitle: String,
  proposals: List<ProviderBindingProposalView>,
  providerNames: Map<String, String>,
  isScreenBottom: Boolean,
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
      text = "Associazioni Provider proposte (${proposals.size}):",
      color = NovaCyanBright,
      fontSize = 13.sp,
      fontWeight = FontWeight.Bold
    )
    Text(
      text = "Suggerimenti automatici per i cataloghi di questo addon: diventano " +
        "associazioni solo se confermati. Tutti i cataloghi restano utilizzabili comunque.",
      color = NovaTextMuted,
      fontSize = 12.sp
    )
    Text(
      text = "Le associazioni gia' presenti nel registry sono invece attive automaticamente, " +
        "senza conferma, e hanno la precedenza su questa proposta solo se l'utente le sostituisce.",
      color = NovaTextMuted,
      fontSize = 12.sp
    )

    if (proposals.isEmpty()) {
      Text(
        text = "Nessun catalogo di questo addon è stato abbinato a un provider.",
        color = NovaTextMuted,
        fontSize = 12.sp,
        modifier = Modifier.padding(vertical = 4.dp)
      )
    } else {
      // groupBy preserva l'ordine di encounter: l'ultima riga renderizzata e'
      // proposals.last(), che e' l'ultimo elemento focusabile della schermata.
      val lastView = proposals.lastOrNull()
      proposals.groupBy { it.providerId }.forEach { (providerId, rows) ->
        Text(
          text = providerNames[providerId] ?: providerId,
          color = NovaTextPrimary,
          fontSize = 14.sp,
          fontWeight = FontWeight.Bold,
          modifier = Modifier.padding(top = 2.dp)
        )
        rows.forEach { view ->
          // Ultima riga della sezione: e' l'ultimo stop D-pad della schermata, quindi
          // il DOWN non ha destinazione interna e non deve uscire verso la sidebar.
          val boundaryModifier =
            if (isScreenBottom && view === lastView) {
              Modifier.focusProperties { down = FocusRequester.Cancel }
            } else {
              Modifier
            }

          if (view.isRemovable || view.isConfirmable) {
            // Riga con azione: lo stop D-pad e' la pillola dentro la riga.
            ProviderBindingRow(
              addonTitle = addonTitle,
              view = view,
              providerNames = providerNames,
              modifier = boundaryModifier,
              onConfirm = onConfirm,
              onRemove = onRemove
            )
          } else {
            // Riga senza azione (gia' associata): non aveva alcuna pillola e quindi
            // nessuno stop, per cui il D-pad non poteva attraversarla. L'intera card
            // diventa l'elemento focusabile (stesso schema dei cataloghi). Essendo a
            // tutta larghezza non viene mai considerata nelle ricerche <-->, che
            // restano quindi invariate.
            TvFocusableBox(
              modifier = Modifier
                .fillMaxWidth()
                .then(boundaryModifier),
              shape = RoundedCornerShape(8.dp),
              focusedScale = 1.02f
            ) {
              ProviderBindingRow(
                addonTitle = addonTitle,
                view = view,
                providerNames = providerNames,
                onConfirm = onConfirm,
                onRemove = onRemove
              )
            }
          }
        }
      }
    }
  }
}

/**
 * Riga di una proposta: dati del catalogo, tipo, addon sorgente e azione.
 *
 * Un solo azione per volta: [ProviderBindingProposalView.isConfirmable] e
 * [ProviderBindingProposalView.isRemovable] garantiscono che non si possano creare
 * duplicati (un binding identico esistente mostra solo "Associato").
 */
@Composable
private fun ProviderBindingRow(
  addonTitle: String,
  view: ProviderBindingProposalView,
  providerNames: Map<String, String>,
  modifier: Modifier = Modifier,
  onConfirm: (CatalogKey, String) -> Unit,
  onRemove: (CatalogKey, String) -> Unit
) {
  val boundName = view.boundProviderId?.let { providerNames[it] ?: it }

  Column(
    modifier = modifier
      .fillMaxWidth()
      .background(NovaCardBgFocused, RoundedCornerShape(8.dp))
      .border(
        1.dp,
        if (view.isRemovable) NovaGreen.copy(alpha = 0.45f) else NovaDivider,
        RoundedCornerShape(8.dp)
      )
      .padding(12.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp)
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = view.catalogTitle,
          color = NovaTextPrimary,
          fontSize = 14.sp,
          fontWeight = FontWeight.Bold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
        Text(
          text = "catalogId: ${view.key.normalizedCatalogId}",
          color = NovaCyan,
          fontSize = 12.sp,
          fontWeight = FontWeight.SemiBold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
      }
      DiagnosticBadge(label = view.key.stremioType, isSupported = true)
    }

    Text(
      text = "Addon: ${view.key.normalizedAddonId} ($addonTitle)",
      color = NovaTextSecondary,
      fontSize = 12.sp,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis
    )

    // Motivi della proposta: rendono leggibile perche' il catalogo e' stato abbinato.
    view.proposal.reasons.take(2).forEach { reason ->
      Text(
        text = "- $reason",
        color = NovaTextMuted,
        fontSize = 11.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )
    }

    Spacer(modifier = Modifier.height(4.dp))
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
      when {
        view.isRemovable -> {
          Text(
            text = "Associato a ${boundName ?: view.providerId} · dall'utente",
            color = NovaGreen,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
          )
          ProviderActionPill(
            label = "Rimuovi associazione",
            icon = Icons.Default.Delete,
            onClick = { onRemove(view.key, view.boundProviderId ?: view.providerId) }
          )
        }
        view.isAlreadyBoundToSameProvider -> {
          Text(
            // L'origine dichiara da dove arriva l'associazione: il registry attiva i
            // cataloghi automaticamente, senza alcuna conferma dell'utente.
            text = if (view.status == ProviderBindingProposalStatus.REGISTRY) {
              "Associato · da registry"
            } else {
              "Associato"
            },
            color = NovaGreen,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
          )
        }
        view.isConfirmable -> {
          // Su una chiave gia' dichiarata in registry la conferma sostituisce il
          // provider: la precedenza USER > REGISTRY resta quella del resolver.
          val isReplacement = view.status == ProviderBindingProposalStatus.REGISTRY
          ProviderActionPill(
            label = if (isReplacement) "Sostituisci" else "Conferma",
            icon = Icons.Default.Check,
            onClick = { onConfirm(view.key, view.providerId) }
          )
        }
      }
    }
  }
}

/**
 * Pillola azione per TV: focusabile col D-pad e premibile con il tasto OK.
 */
@Composable
private fun ProviderActionPill(
  label: String,
  icon: ImageVector,
  onClick: () -> Unit
) {
  TvFocusableBox(
    shape = RoundedCornerShape(50),
    focusedScale = 1.05f,
    onClick = onClick
  ) { isFocused ->
    Row(
      modifier = Modifier
        .background(
          if (isFocused) NovaCyan.copy(alpha = 0.22f) else NovaCardBg,
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
        imageVector = icon,
        contentDescription = null,
        tint = if (isFocused) NovaCyanBright else NovaTextSecondary,
        modifier = Modifier.size(16.dp)
      )
      Text(
        text = label,
        color = if (isFocused) NovaCyanBright else NovaTextSecondary,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold
      )
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

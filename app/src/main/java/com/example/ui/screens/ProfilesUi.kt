package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.AppRestarter
import com.example.R
import com.example.data.profiles.ProfileAvatar
import com.example.data.profiles.ProfileEmojiCategory
import com.example.data.profiles.ProfileMutationResult
import com.example.data.profiles.ProfileNameError
import com.example.data.profiles.ProfileRules
import com.example.data.profiles.ProfilesRepository
import com.example.data.profiles.UserProfile
import com.example.ui.components.ModalOverlayState
import com.example.ui.components.ProfileAvatarBadge
import com.example.ui.components.TextInputDialog
import com.example.ui.components.TvActionButton
import com.example.ui.components.TvFocusBringIntoView
import com.example.ui.components.TvFocusableBox
import com.example.ui.components.gradientColors
import com.example.ui.theme.NovaBackground
import com.example.ui.theme.NovaCardBg
import com.example.ui.theme.NovaCardBgFocused
import com.example.ui.theme.NovaCyan
import com.example.ui.theme.NovaCyanBright
import com.example.ui.theme.NovaDivider
import com.example.ui.theme.NovaGreen
import com.example.ui.theme.NovaRed
import com.example.ui.theme.NovaSurface
import com.example.ui.theme.NovaSurfaceVariant
import com.example.ui.theme.NovaTextMuted
import com.example.ui.theme.NovaTextPrimary
import com.example.ui.theme.NovaTextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Schermata interna del flusso profili. */
enum class ProfileScreen {
  PICKER,
  MANAGER,
  ACTIONS,
  CREATE_NAME,
  RENAME_NAME,
  AVATAR,
  DELETE,
  SWITCHING
}

/**
 * Sezione «Profili» delle Impostazioni.
 *
 * Mostra il profilo attivo e l'accesso alla gestione. Il flusso completo vive in un
 * [Dialog] a finestra intera ([ProfilesOverlayHost]): può quindi essere composto dentro
 * la LazyColumn delle Impostazioni senza essere tagliato o scorrere con essa.
 */
@Composable
fun ProfilesSection() {
  val profiles by ProfilesRepository.profiles.collectAsState()
  val activeProfileId by ProfilesRepository.activeProfileId.collectAsState()
  val active = profiles.firstOrNull { it.id == activeProfileId }
  var overlay by remember { mutableStateOf<ProfileScreen?>(null) }

  SettingsSection(title = stringResource(R.string.profiles_section)) {
    SettingsRow(
      icon = Icons.Default.Person,
      title = active?.displayName() ?: stringResource(R.string.profiles_default_name),
      value = stringResource(R.string.profiles_active_label),
      showChevron = true,
      onClick = { overlay = ProfileScreen.PICKER }
    )
    SettingsRow(
      icon = Icons.Default.ManageAccounts,
      title = stringResource(R.string.profiles_manage),
      showChevron = true,
      onClick = { overlay = ProfileScreen.MANAGER }
    )
  }

  overlay?.let { initial ->
    ProfilesOverlayHost(
      initialScreen = initial,
      onDismiss = { overlay = null }
    )
  }
}

/**
 * Flusso profili a finestra intera.
 *
 * È un Dialog (e non un overlay in-window) perché può essere richiesto sia dalle
 * Impostazioni, dentro una lista scrollabile, sia all'avvio sopra tutta l'app. La
 * finestra separata isola anche il focus del telecomando: il D-pad non può finire
 * sulle card sottostanti.
 */
@Composable
fun ProfilesOverlayHost(
  initialScreen: ProfileScreen,
  onDismiss: () -> Unit
) {
  val localizedContext = LocalContext.current
  val localizedConfiguration = LocalConfiguration.current
  val scope = rememberCoroutineScope()
  val profiles by ProfilesRepository.profiles.collectAsState()
  val activeProfileId by ProfilesRepository.activeProfileId.collectAsState()

  var screen by remember { mutableStateOf(initialScreen) }
  var selectedProfileId by remember { mutableStateOf<String?>(null) }
  var nameError by remember { mutableStateOf<ProfileNameError?>(null) }
  var notice by remember { mutableStateOf<String?>(null) }
  var switchingProfileName by remember { mutableStateOf<String?>(null) }

  val selectedProfile = profiles.firstOrNull { it.id == selectedProfileId }

  fun goToManager(message: String? = null) {
    notice = message
    nameError = null
    selectedProfileId = null
    screen = ProfileScreen.MANAGER
  }

  fun switchTo(profile: UserProfile) {
    if (profile.id == activeProfileId) {
      onDismiss()
      return
    }
    // Nome leggibile nella lingua corrente anche per il profilo principale automatico.
    switchingProfileName = if (profile.isPrimary && !profile.hasCustomName) {
      localizedContext.getString(R.string.profiles_default_name)
    } else {
      profile.name
    }
    screen = ProfileScreen.SWITCHING
    scope.launch {
      val saved = ProfilesRepository.setActiveProfile(profile.id)
      if (saved) {
        // Un attimo per leggere il messaggio, poi il processo riparte sul nuovo storage.
        delay(450)
        AppRestarter.restart(localizedContext)
      } else {
        switchingProfileName = null
        goToManager(localizedContext.getString(R.string.profiles_error_save))
      }
    }
  }

  fun submitName(rawName: String, renameTarget: UserProfile?) {
    val validation = ProfileRules.nameError(
      raw = rawName,
      profiles = profiles,
      excludeId = renameTarget?.id
    )
    if (validation != null) {
      nameError = validation
      return
    }
    scope.launch {
      val result = if (renameTarget == null) {
        ProfilesRepository.createProfile(rawName)
      } else {
        ProfilesRepository.renameProfile(renameTarget.id, rawName)
      }
      when (result) {
        is ProfileMutationResult.Success -> {
          nameError = null
          goToManager(
            if (renameTarget == null) {
              localizedContext.getString(R.string.profiles_created, result.profile.name)
            } else {
              localizedContext.getString(R.string.profiles_renamed)
            }
          )
        }
        is ProfileMutationResult.InvalidName -> nameError = result.error
        ProfileMutationResult.LimitReached -> goToManager(
          localizedContext.getString(R.string.profiles_limit_reached, ProfileRules.MAX_PROFILES)
        )
        else -> goToManager(localizedContext.getString(R.string.profiles_error_save))
      }
    }
  }

  fun deleteProfile(profile: UserProfile) {
    scope.launch {
      val result = ProfilesRepository.deleteProfile(profile.id)
      when (result) {
        is ProfileMutationResult.Success ->
          goToManager(localizedContext.getString(R.string.profiles_deleted, profile.name))
        ProfileMutationResult.ActiveProfile ->
          goToManager(localizedContext.getString(R.string.profiles_delete_active))
        ProfileMutationResult.ProtectedProfile ->
          goToManager(localizedContext.getString(R.string.profiles_delete_primary))
        else -> goToManager(localizedContext.getString(R.string.profiles_error_save))
      }
    }
  }

  fun saveAvatar(profile: UserProfile, avatar: ProfileAvatar, emoji: String?) {
    scope.launch {
      ProfilesRepository.updateAvatar(profile.id, avatar, emoji)
      goToManager()
    }
  }

  /**
   * BACK della finestra Dialog.
   *
   * In una finestra separata il tasto BACK arriva al Dialog e non ai BackHandler
   * Compose: qui viene tradotto nella navigazione interna, così non chiude mai l'intero
   * flusso quando l'utente si trova in una sottoschermata.
   */
  fun handleBack() {
    when (screen) {
      ProfileScreen.PICKER -> onDismiss()
      ProfileScreen.MANAGER -> screen = ProfileScreen.PICKER
      ProfileScreen.SWITCHING -> Unit
      else -> goToManager()
    }
  }

  Dialog(
    onDismissRequest = { handleBack() },
    properties = DialogProperties(
      dismissOnBackPress = true,
      dismissOnClickOutside = false,
      usePlatformDefaultWidth = false,
      decorFitsSystemWindows = false
    )
  ) {
    CompositionLocalProvider(
      LocalContext provides localizedContext,
      LocalConfiguration provides localizedConfiguration
    ) {
      // Il Dialog è una seconda finestra: segnalare il modale aperto blocca i tasti
      // diretti alla Home/Impostazioni sottostanti.
      DisposableEffect(Unit) {
        ModalOverlayState.onShown()
        onDispose { ModalOverlayState.onHidden() }
      }

      when (screen) {
        ProfileScreen.PICKER -> ProfilePickerScreen(
          profiles = profiles,
          activeProfileId = activeProfileId,
          onSelect = { profile ->
            if (profile.id == activeProfileId) onDismiss() else switchTo(profile)
          },
          onManageProfiles = { screen = ProfileScreen.MANAGER },
          onClose = onDismiss
        )

        ProfileScreen.MANAGER -> ProfileManagerScreen(
          profiles = profiles,
          activeProfileId = activeProfileId,
          notice = notice,
          onProfileClick = { profile ->
            selectedProfileId = profile.id
            notice = null
            screen = ProfileScreen.ACTIONS
          },
          onCreateProfile = {
            selectedProfileId = null
            nameError = null
            screen = ProfileScreen.CREATE_NAME
          },
          onClose = onDismiss,
          onBack = {
            notice = null
            screen = ProfileScreen.PICKER
          }
        )

        ProfileScreen.ACTIONS -> {
          val profile = selectedProfile
          LaunchedEffect(profile) {
            if (profile == null) goToManager()
          }
          if (profile != null) {
            ProfileActionsScreen(
              profile = profile,
              isActive = profile.id == activeProfileId,
              onUseProfile = { switchTo(profile) },
              onRename = { nameError = null; screen = ProfileScreen.RENAME_NAME },
              onChangeColor = { screen = ProfileScreen.AVATAR },
              onDelete = { screen = ProfileScreen.DELETE },
              onBack = { goToManager() }
            )
          }
        }

        ProfileScreen.CREATE_NAME, ProfileScreen.RENAME_NAME -> {
          val renameTarget = if (screen == ProfileScreen.RENAME_NAME) selectedProfile else null
          LaunchedEffect(screen, renameTarget) {
            if (screen == ProfileScreen.RENAME_NAME && renameTarget == null) goToManager()
          }
          if (screen != ProfileScreen.RENAME_NAME || renameTarget != null) {
            val errorMessage = nameError?.let { nameErrorText(it) }
            TextInputDialog(
              title = stringResource(
                if (renameTarget == null) R.string.profiles_new_title else R.string.profiles_rename_title
              ),
              hint = stringResource(R.string.profiles_name_hint),
              initialText = renameTarget?.name.orEmpty(),
              confirmLabel = stringResource(R.string.action_save),
              errorMessage = errorMessage,
              onConfirm = { submitName(it, renameTarget) },
              onDismiss = { goToManager() }
            )
          }
        }

        ProfileScreen.AVATAR -> {
          val profile = selectedProfile
          LaunchedEffect(profile) {
            if (profile == null) goToManager()
          }
          if (profile != null) {
            ProfileAvatarScreen(
              profile = profile,
              onSave = { avatar, emoji -> saveAvatar(profile, avatar, emoji) },
              onBack = { goToManager() }
            )
          }
        }

        ProfileScreen.DELETE -> {
          val profile = selectedProfile
          LaunchedEffect(profile) {
            if (profile == null) goToManager()
          }
          if (profile != null) {
            ProfileDeleteScreen(
              profile = profile,
              onConfirm = { deleteProfile(profile) },
              onBack = { goToManager() }
            )
          }
        }

        ProfileScreen.SWITCHING -> ProfileSwitchingScreen(switchingProfileName.orEmpty())
      }
    }
  }
}

// ── Picker ────────────────────────────────────────────────────────────────────

@Composable
private fun ProfilePickerScreen(
  profiles: List<UserProfile>,
  activeProfileId: String,
  onSelect: (UserProfile) -> Unit,
  onManageProfiles: () -> Unit,
  onClose: () -> Unit
) {
  val initialProfile = profiles.firstOrNull { it.id == activeProfileId } ?: profiles.firstOrNull()
  val tileFocusRequesters = remember(profiles) {
    profiles.associate { it.id to FocusRequester() }
  }
  val manageFocusRequester = remember { FocusRequester() }

  BackHandler { onClose() }

  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(
        Brush.verticalGradient(
          listOf(
            NovaBackground,
            Color(0xFF0C1526),
            NovaBackground
          )
        )
      ),
    contentAlignment = Alignment.Center
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth(0.94f)
        .wrapContentHeight(),
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      Text(
        text = stringResource(R.string.profiles_title),
        color = NovaTextPrimary,
        fontSize = 34.sp,
        fontWeight = FontWeight.Bold
      )
      Spacer(modifier = Modifier.height(8.dp))
      Text(
        text = stringResource(R.string.profiles_subtitle),
        color = NovaTextSecondary,
        fontSize = 15.sp,
        textAlign = TextAlign.Center
      )
      Spacer(modifier = Modifier.height(36.dp))

      TvFocusBringIntoView(topOffsetDp = 24.dp, bottomMarginDp = 24.dp) {
        LazyRow(
          modifier = Modifier.fillMaxWidth(),
          contentPadding = PaddingValues(horizontal = 32.dp),
          horizontalArrangement = Arrangement.spacedBy(22.dp)
        ) {
          items(profiles, key = { it.id }) { profile ->
            ProfileTile(
              profile = profile,
              isActive = profile.id == activeProfileId,
              focusRequester = tileFocusRequesters[profile.id],
              onClick = { onSelect(profile) },
              modifier = Modifier.width(150.dp)
            )
          }
        }
      }

      Spacer(modifier = Modifier.height(40.dp))

      Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        TvActionButton(
          text = stringResource(R.string.profiles_manage),
          icon = Icons.Default.ManageAccounts,
          isPrimary = false,
          onClick = onManageProfiles,
          modifier = Modifier.focusRequester(manageFocusRequester)
        )
        TvActionButton(
          text = stringResource(R.string.action_close),
          isPrimary = true,
          onClick = onClose
        )
      }
    }
  }

  RequestInitialFocus(tileFocusRequesters[initialProfile?.id] ?: manageFocusRequester)
}

@Composable
private fun ProfileTile(
  profile: UserProfile,
  isActive: Boolean,
  focusRequester: FocusRequester?,
  onClick: () -> Unit,
  modifier: Modifier = Modifier
) {
  TvFocusableBox(
    onClick = onClick,
    shape = RoundedCornerShape(18.dp),
    focusedScale = 1.06f,
    modifier = modifier.then(
      if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier
    )
  ) { isFocused ->
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .background(
          if (isFocused) NovaCardBgFocused else NovaCardBg,
          RoundedCornerShape(18.dp)
        )
        .padding(vertical = 18.dp, horizontal = 10.dp),
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      ProfileAvatarCircle(profile = profile, size = 92.dp, fontSize = 38.sp)
      Spacer(modifier = Modifier.height(12.dp))
      Text(
        text = profile.displayName(),
        color = if (isFocused) NovaTextPrimary else NovaTextSecondary,
        fontSize = 15.sp,
        fontWeight = if (isFocused) FontWeight.Bold else FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center
      )
      Spacer(modifier = Modifier.height(8.dp))
      if (isActive) {
        Text(
          text = stringResource(R.string.profiles_active_badge).uppercase(),
          color = NovaCyanBright,
          fontSize = 11.sp,
          fontWeight = FontWeight.Bold,
          letterSpacing = 0.6.sp,
          modifier = Modifier
            .background(NovaCyan.copy(alpha = 0.16f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 3.dp)
        )
      } else {
        Spacer(modifier = Modifier.height(21.dp))
      }
    }
  }
}

// ── Gestione ──────────────────────────────────────────────────────────────────

@Composable
private fun ProfileManagerScreen(
  profiles: List<UserProfile>,
  activeProfileId: String,
  notice: String?,
  onProfileClick: (UserProfile) -> Unit,
  onCreateProfile: () -> Unit,
  onClose: () -> Unit,
  onBack: () -> Unit
) {
  val firstFocusRequester = remember { FocusRequester() }

  BackHandler { onBack() }

  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(Color.Black.copy(alpha = 0.88f)),
    contentAlignment = Alignment.Center
  ) {
    ProfilePanel(
      modifier = Modifier
        .fillMaxWidth(0.72f)
        .fillMaxHeight(0.86f)
    ) {
      Text(
        text = stringResource(R.string.profiles_manage),
        color = NovaTextPrimary,
        fontSize = 24.sp,
        fontWeight = FontWeight.Bold
      )
      Spacer(modifier = Modifier.height(4.dp))
      Text(
        text = stringResource(R.string.profiles_subtitle),
        color = NovaTextSecondary,
        fontSize = 13.sp
      )
      notice?.let {
        Spacer(modifier = Modifier.height(10.dp))
        Text(
          text = it,
          color = NovaGreen,
          fontSize = 13.sp,
          fontWeight = FontWeight.Medium
        )
      }
      Spacer(modifier = Modifier.height(18.dp))

      Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
        TvFocusBringIntoView(topOffsetDp = 24.dp, bottomMarginDp = 24.dp) {
          LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 12.dp)
          ) {
            items(profiles, key = { it.id }) { profile ->
              ProfileManagerRow(
                profile = profile,
                isActive = profile.id == activeProfileId,
                onClick = { onProfileClick(profile) },
                modifier = if (profile.id == profiles.firstOrNull()?.id) {
                  Modifier.focusRequester(firstFocusRequester)
                } else {
                  Modifier
                }
              )
            }
            item(key = "create_profile") {
              ProfileManagerRow(
                icon = Icons.Default.Add,
                title = stringResource(R.string.profiles_create),
                value = null,
                isActive = false,
                onClick = onCreateProfile
              )
            }
          }
        }
      }

      Spacer(modifier = Modifier.height(16.dp))
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
      ) {
        TvActionButton(
          text = stringResource(R.string.action_close),
          isPrimary = true,
          onClick = onClose
        )
      }
    }
  }

  RequestInitialFocus(firstFocusRequester)
}

@Composable
private fun ProfileManagerRow(
  profile: UserProfile,
  isActive: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier
) {
  ProfileManagerRow(
    leading = { ProfileAvatarCircle(profile = profile, size = 44.dp, fontSize = 18.sp) },
    title = profile.displayName(),
    value = when {
      isActive -> stringResource(R.string.profiles_active_badge)
      profile.isPrimary -> stringResource(R.string.profiles_primary_badge)
      else -> null
    },
    valueColor = if (isActive) NovaCyanBright else NovaTextMuted,
    onClick = onClick,
    modifier = modifier
  )
}

@Composable
private fun ProfileManagerRow(
  icon: ImageVector,
  title: String,
  value: String?,
  isActive: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier
) {
  ProfileManagerRow(
    leading = {
      Box(
        modifier = Modifier
          .size(44.dp)
          .background(NovaSurfaceVariant, CircleShape),
        contentAlignment = Alignment.Center
      ) {
        Icon(
          imageVector = icon,
          contentDescription = null,
          tint = NovaCyanBright,
          modifier = Modifier.size(22.dp)
        )
      }
    },
    title = title,
    value = value,
    valueColor = NovaTextMuted,
    onClick = onClick,
    modifier = modifier
  )
}

@Composable
private fun ProfileManagerRow(
  leading: @Composable () -> Unit,
  title: String,
  value: String?,
  valueColor: Color,
  onClick: () -> Unit,
  modifier: Modifier = Modifier
) {
  TvFocusableBox(
    onClick = onClick,
    shape = RoundedCornerShape(12.dp),
    focusedScale = 1.02f,
    modifier = modifier.fillMaxWidth()
  ) { isFocused ->
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .background(
          if (isFocused) NovaCardBgFocused else Color.Transparent,
          RoundedCornerShape(12.dp)
        )
        .padding(horizontal = 14.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      leading()
      Spacer(modifier = Modifier.width(14.dp))
      Text(
        text = title,
        color = if (isFocused) NovaTextPrimary else NovaTextSecondary,
        fontSize = 16.sp,
        fontWeight = if (isFocused) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f)
      )
      if (value != null) {
        Spacer(modifier = Modifier.width(12.dp))
        Text(
          text = value,
          color = valueColor,
          fontSize = 12.sp,
          fontWeight = FontWeight.Medium,
          maxLines = 1
        )
      }
      Spacer(modifier = Modifier.width(6.dp))
      Icon(
        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = if (isFocused) NovaCyanBright else NovaTextMuted,
        modifier = Modifier.size(20.dp)
      )
    }
  }
}

// ── Azioni su un profilo ─────────────────────────────────────────────────────

@Composable
private fun ProfileActionsScreen(
  profile: UserProfile,
  isActive: Boolean,
  onUseProfile: () -> Unit,
  onRename: () -> Unit,
  onChangeColor: () -> Unit,
  onDelete: () -> Unit,
  onBack: () -> Unit
) {
  val firstFocusRequester = remember { FocusRequester() }

  BackHandler { onBack() }

  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(Color.Black.copy(alpha = 0.88f)),
    contentAlignment = Alignment.Center
  ) {
    ProfilePanel(modifier = Modifier.fillMaxWidth(0.62f).widthIn(max = 640.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        ProfileAvatarCircle(profile = profile, size = 56.dp, fontSize = 22.sp)
        Spacer(modifier = Modifier.width(16.dp))
        Column {
          Text(
            text = stringResource(R.string.profiles_action_title, profile.displayName()),
            color = NovaTextPrimary,
            fontSize = 21.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
          Text(
            text = when {
              isActive -> stringResource(R.string.profiles_active_label)
              profile.isPrimary -> stringResource(R.string.profiles_primary_badge)
              else -> stringResource(R.string.profiles_section)
            },
            color = NovaTextMuted,
            fontSize = 12.sp
          )
        }
      }
      Spacer(modifier = Modifier.height(20.dp))

      if (!isActive) {
        ProfileActionRow(
          icon = Icons.Default.PlayArrow,
          title = stringResource(R.string.profiles_use),
          onClick = onUseProfile,
          modifier = Modifier.focusRequester(firstFocusRequester)
        )
      }
      ProfileActionRow(
        icon = Icons.Default.Edit,
        title = stringResource(R.string.profiles_rename),
        onClick = onRename,
        modifier = if (isActive) Modifier.focusRequester(firstFocusRequester) else Modifier
      )
      ProfileActionRow(
        icon = Icons.Default.Palette,
        title = stringResource(R.string.profiles_edit_avatar),
        onClick = onChangeColor
      )
      if (!profile.isPrimary && !isActive) {
        Spacer(modifier = Modifier.height(8.dp))
        ProfileActionRow(
          icon = Icons.Default.Delete,
          title = stringResource(R.string.profiles_delete),
          danger = true,
          onClick = onDelete
        )
      } else {
        Spacer(modifier = Modifier.height(10.dp))
        Text(
          text = stringResource(
            if (profile.isPrimary) R.string.profiles_delete_primary else R.string.profiles_delete_active
          ),
          color = NovaTextMuted,
          fontSize = 12.sp,
          modifier = Modifier.padding(horizontal = 6.dp)
        )
      }

      Spacer(modifier = Modifier.height(20.dp))
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
      ) {
        TvActionButton(
          text = stringResource(R.string.action_cancel),
          isPrimary = false,
          onClick = onBack
        )
      }
    }
  }

  RequestInitialFocus(firstFocusRequester)
}

@Composable
private fun ProfileActionRow(
  icon: ImageVector,
  title: String,
  danger: Boolean = false,
  onClick: () -> Unit,
  modifier: Modifier = Modifier
) {
  TvFocusableBox(
    onClick = onClick,
    shape = RoundedCornerShape(12.dp),
    focusedScale = 1.02f,
    modifier = modifier.fillMaxWidth()
  ) { isFocused ->
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .background(
          if (isFocused) NovaCardBgFocused else Color.Transparent,
          RoundedCornerShape(12.dp)
        )
        .padding(horizontal = 14.dp, vertical = 14.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        tint = when {
          danger && isFocused -> NovaRed
          danger -> NovaRed.copy(alpha = 0.8f)
          isFocused -> NovaCyanBright
          else -> NovaTextMuted
        },
        modifier = Modifier.size(22.dp)
      )
      Spacer(modifier = Modifier.width(14.dp))
      Text(
        text = title,
        color = when {
          danger -> NovaRed
          isFocused -> NovaTextPrimary
          else -> NovaTextSecondary
        },
        fontSize = 16.sp,
        fontWeight = if (isFocused) FontWeight.SemiBold else FontWeight.Normal
      )
    }
  }
}

// ── Avatar del profilo (emoji + colore) ─────────────────────────────────────

@Composable
internal fun ProfileAvatarScreen(
  profile: UserProfile,
  onSave: (ProfileAvatar, String?) -> Unit,
  onBack: () -> Unit
) {
  var selectedEmoji by remember(profile.id) { mutableStateOf(profile.avatarEmoji) }
  var selectedColor by remember(profile.id) { mutableStateOf(profile.avatar) }
  // Si apre direttamente la categoria dell'emoji attuale (o Cinema per un profilo
  // senza emoji/da versioni precedenti).
  var selectedCategory by remember(profile.id) {
    mutableStateOf(ProfileRules.categoryFor(profile.avatarEmoji) ?: ProfileEmojiCategory.CINEMA)
  }

  val categoryFocusRequesters = remember {
    ProfileEmojiCategory.entries.associateWith { FocusRequester() }
  }
  // Un FocusRequester per OGNI coppia (categoria, emoji): la stessa emoji compare in
  // più categorie e un singolo requester non può essere legato a due nodi.
  val emojiFocusRequesters = remember {
    ProfileEmojiCategory.entries.associateWith { category ->
      (listOf<String?>(null) + ProfileRules.emojisFor(category))
        .associateWith { FocusRequester() }
    }
  }
  val colorFocusRequesters = remember {
    ProfileAvatar.entries.associateWith { FocusRequester() }
  }
  // Requester della RIGA emoji visibile (quella della categoria selezionata). È l'unico
  // punto di atterraggio del D-pad DOWN dai chip sempre agganciato: un requester della
  // prima emoji di una categoria NON selezionata punta a un nodo che non esiste nella
  // composizione e il focus search termina con `IllegalStateException: FocusRequester is
  // not initialized` (stesso crash documentato in HeroBanner per le card riciclate).
  val emojiRowFocusRequester = remember { FocusRequester() }
  val activeEmojis: List<String?> = remember(selectedCategory) {
    listOf<String?>(null) + ProfileRules.emojisFor(selectedCategory)
  }

  BackHandler { onBack() }

  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(Color.Black.copy(alpha = 0.9f)),
    contentAlignment = Alignment.Center
  ) {
    ProfilePanel(modifier = Modifier.fillMaxWidth(0.78f).widthIn(max = 780.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        ProfileAvatarBadge(
          emoji = selectedEmoji,
          initial = profile.initial,
          avatar = selectedColor,
          size = 86.dp,
          fontSize = 34.sp
        )
        Spacer(modifier = Modifier.width(18.dp))
        Column {
          Text(
            text = stringResource(R.string.profiles_avatar_title),
            color = NovaTextPrimary,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
          )
          Spacer(modifier = Modifier.height(3.dp))
          Text(
            text = profile.displayName(),
            color = NovaTextSecondary,
            fontSize = 13.sp
          )
        }
      }

      Spacer(modifier = Modifier.height(20.dp))
      Text(
        text = stringResource(R.string.profiles_avatar_emoji).uppercase(),
        color = NovaTextMuted,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp
      )
      Spacer(modifier = Modifier.height(10.dp))
      Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ProfileEmojiCategory.entries.forEach { category ->
          ProfileEmojiCategoryChip(
            label = stringResource(category.labelRes()),
            selected = category == selectedCategory,
            focusRequester = categoryFocusRequesters.getValue(category),
            downFocusRequester = emojiRowFocusRequester,
            onClick = { selectedCategory = category }
          )
        }
      }
      Spacer(modifier = Modifier.height(10.dp))
      LazyRow(
        modifier = Modifier.fillMaxWidth().focusRequester(emojiRowFocusRequester),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 2.dp)
      ) {
        items(activeEmojis, key = { it ?: "initial" }) { emoji ->
          ProfileEmojiChoice(
            emoji = emoji,
            selected = emoji == selectedEmoji,
            focusRequester = emojiFocusRequesters
              .getValue(selectedCategory)
              .getValue(emoji),
            onClick = { selectedEmoji = emoji }
          )
        }
      }

      Spacer(modifier = Modifier.height(20.dp))
      Text(
        text = stringResource(R.string.profiles_avatar_color).uppercase(),
        color = NovaTextMuted,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp
      )
      Spacer(modifier = Modifier.height(10.dp))
      Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        ProfileAvatar.entries.forEach { avatar ->
          ProfileColorChoice(
            avatar = avatar,
            selected = avatar == selectedColor,
            focusRequester = colorFocusRequesters.getValue(avatar),
            onClick = { selectedColor = avatar }
          )
        }
      }

      Spacer(modifier = Modifier.height(26.dp))
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)
      ) {
        TvActionButton(
          text = stringResource(R.string.action_cancel),
          isPrimary = false,
          onClick = onBack
        )
        TvActionButton(
          text = stringResource(R.string.action_save),
          isPrimary = true,
          onClick = { onSave(selectedColor, selectedEmoji) }
        )
      }
    }
  }

  val activeCategoryEmojiRequesters = emojiFocusRequesters.getValue(selectedCategory)
  val initialFocus = selectedEmoji?.let { activeCategoryEmojiRequesters[it] }
    ?: activeCategoryEmojiRequesters.getValue(null)
  RequestInitialFocus(initialFocus)
}

@Composable
private fun ProfileEmojiCategoryChip(
  label: String,
  selected: Boolean,
  focusRequester: FocusRequester,
  downFocusRequester: FocusRequester,
  onClick: () -> Unit
) {
  TvFocusableBox(
    onClick = onClick,
    shape = RoundedCornerShape(50),
    focusedScale = 1.05f,
    // DOWN porta sempre alla riga emoji VISIBILE: la ricerca geometrica, con due file
    // sotto, può altrimenti scivolare sulla riga dei colori. Il requester appartiene alla
    // riga stessa (sempre composta), non a una singola emoji che può non essere montata.
    modifier = Modifier
      .focusRequester(focusRequester)
      .focusProperties { down = downFocusRequester }
  ) { isFocused ->
    Text(
      text = label,
      color = when {
        selected -> Color.Black
        isFocused -> NovaCyanBright
        else -> NovaTextSecondary
      },
      fontSize = 13.sp,
      fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
      modifier = Modifier
        .background(
          when {
            selected -> NovaCyanBright
            isFocused -> NovaCyan.copy(alpha = 0.18f)
            else -> NovaSurfaceVariant
          },
          RoundedCornerShape(50)
        )
        .border(
          width = 1.dp,
          color = if (selected) NovaCyanBright else NovaDivider,
          shape = RoundedCornerShape(50)
        )
        .padding(horizontal = 15.dp, vertical = 8.dp)
    )
  }
}

private fun ProfileEmojiCategory.labelRes(): Int = when (this) {
  ProfileEmojiCategory.CINEMA -> R.string.profiles_emoji_category_cinema
  ProfileEmojiCategory.THRILLER -> R.string.profiles_emoji_category_thriller
  ProfileEmojiCategory.COMEDY -> R.string.profiles_emoji_category_comedy
  ProfileEmojiCategory.SCIFI -> R.string.profiles_emoji_category_scifi
}

@Composable
private fun ProfileEmojiChoice(
  emoji: String?,
  selected: Boolean,
  focusRequester: FocusRequester,
  onClick: () -> Unit
) {
  TvFocusableBox(
    onClick = onClick,
    shape = CircleShape,
    focusedScale = 1.08f,
    modifier = Modifier
      .size(58.dp)
      .focusRequester(focusRequester)
  ) { isFocused ->
    Box(
      modifier = Modifier
        .fillMaxSize()
        .background(
          when {
            selected -> NovaCyan.copy(alpha = 0.22f)
            isFocused -> NovaCardBgFocused
            else -> NovaSurfaceVariant
          },
          CircleShape
        )
        .border(
          width = if (selected) 2.dp else 1.dp,
          color = if (selected) NovaCyanBright else NovaDivider,
          shape = CircleShape
        ),
      contentAlignment = Alignment.Center
    ) {
      Text(
        text = emoji ?: stringResource(R.string.profiles_avatar_initial),
        color = if (emoji == null) NovaTextSecondary else Color.Unspecified,
        fontSize = if (emoji == null) 18.sp else 27.sp,
        fontWeight = if (emoji == null) FontWeight.Bold else FontWeight.Normal
      )
      if (selected) {
        Box(
          modifier = Modifier
            .align(Alignment.TopEnd)
            .size(17.dp)
            .background(NovaCyanBright, CircleShape),
          contentAlignment = Alignment.Center
        ) {
          Icon(
            imageVector = Icons.Default.Check,
            contentDescription = null,
            tint = Color.Black,
            modifier = Modifier.size(12.dp)
          )
        }
      }
    }
  }
}

@Composable
private fun ProfileColorChoice(
  avatar: ProfileAvatar,
  selected: Boolean,
  focusRequester: FocusRequester,
  onClick: () -> Unit
) {
  TvFocusableBox(
    onClick = onClick,
    shape = CircleShape,
    focusedScale = 1.08f,
    modifier = Modifier
      .size(58.dp)
      .focusRequester(focusRequester)
  ) { isFocused ->
    Box(
      modifier = Modifier
        .fillMaxSize()
        .background(Brush.linearGradient(avatar.gradientColors()), CircleShape),
      contentAlignment = Alignment.Center
    ) {
      if (selected) {
        Icon(
          imageVector = Icons.Default.Check,
          contentDescription = null,
          tint = Color.White,
          modifier = Modifier.size(26.dp)
        )
      } else if (isFocused) {
        Box(
          modifier = Modifier
            .size(46.dp)
            .border(2.dp, Color.White.copy(alpha = 0.75f), CircleShape)
        )
      }
    }
  }
}

// ── Conferma eliminazione ────────────────────────────────────────────────────

@Composable
private fun ProfileDeleteScreen(
  profile: UserProfile,
  onConfirm: () -> Unit,
  onBack: () -> Unit
) {
  val cancelFocusRequester = remember { FocusRequester() }

  BackHandler { onBack() }

  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(Color.Black.copy(alpha = 0.9f)),
    contentAlignment = Alignment.Center
  ) {
    ProfilePanel(modifier = Modifier.fillMaxWidth(0.62f).widthIn(max = 640.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
          imageVector = Icons.Default.Delete,
          contentDescription = null,
          tint = NovaRed,
          modifier = Modifier.size(28.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
          text = stringResource(R.string.profiles_delete_title),
          color = NovaTextPrimary,
          fontSize = 22.sp,
          fontWeight = FontWeight.Bold
        )
      }
      Spacer(modifier = Modifier.height(14.dp))
      Text(
        text = stringResource(R.string.profiles_delete_message, profile.displayName()),
        color = NovaTextSecondary,
        fontSize = 14.sp
      )
      Spacer(modifier = Modifier.height(26.dp))
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)
      ) {
        TvActionButton(
          text = stringResource(R.string.action_cancel),
          isPrimary = false,
          onClick = onBack,
          modifier = Modifier.focusRequester(cancelFocusRequester)
        )
        ProfileDangerButton(
          text = stringResource(R.string.profiles_delete_confirm),
          onClick = onConfirm
        )
      }
    }
  }

  // Focus sul pulsante sicuro: un OK accidentale non elimina il profilo.
  RequestInitialFocus(cancelFocusRequester)
}

@Composable
private fun ProfileDangerButton(
  text: String,
  onClick: () -> Unit
) {
  TvFocusableBox(
    shape = RoundedCornerShape(50),
    focusedScale = 1.05f,
    focusedBorderColor = NovaRed,
    onClick = onClick
  ) { isFocused ->
    Text(
      text = text,
      color = if (isFocused) Color.White else NovaRed,
      fontSize = 14.sp,
      fontWeight = FontWeight.Bold,
      modifier = Modifier
        .background(
          if (isFocused) NovaRed else NovaRed.copy(alpha = 0.16f),
          RoundedCornerShape(50)
        )
        .border(1.dp, NovaRed, RoundedCornerShape(50))
        .padding(horizontal = 22.dp, vertical = 10.dp)
    )
  }
}

// ── Cambio profilo ──────────────────────────────────────────────────────────

@Composable
private fun ProfileSwitchingScreen(profileName: String) {
  // Durante il riavvio il BACK non deve annullare l'operazione già persistita.
  BackHandler { }

  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(NovaBackground),
    contentAlignment = Alignment.Center
  ) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      CircularProgressIndicator(
        color = NovaCyanBright,
        strokeWidth = 3.dp,
        modifier = Modifier.size(44.dp)
      )
      Spacer(modifier = Modifier.height(20.dp))
      Text(
        text = stringResource(R.string.profiles_switching),
        color = NovaTextPrimary,
        fontSize = 18.sp,
        fontWeight = FontWeight.SemiBold
      )
      if (profileName.isNotBlank()) {
        Spacer(modifier = Modifier.height(6.dp))
        Text(
          text = profileName,
          color = NovaTextMuted,
          fontSize = 13.sp
        )
      }
    }
  }
}

// ── Componenti condivisi ────────────────────────────────────────────────────

@Composable
private fun ProfilePanel(
  modifier: Modifier = Modifier,
  content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
  Column(
    modifier = modifier
      .background(NovaSurface, RoundedCornerShape(22.dp))
      .border(1.dp, NovaCyan.copy(alpha = 0.35f), RoundedCornerShape(22.dp))
      .padding(28.dp),
    content = content
  )
}

@Composable
private fun ProfileAvatarCircle(
  profile: UserProfile,
  size: androidx.compose.ui.unit.Dp,
  fontSize: androidx.compose.ui.unit.TextUnit
) {
  ProfileAvatarBadge(
    emoji = profile.avatarEmoji,
    initial = profile.initial,
    avatar = profile.avatar,
    size = size,
    fontSize = fontSize
  )
}

/**
 * Nome mostrato all'utente.
 *
 * Il profilo principale creato automaticamente non ha un nome "scelto": finché non
 * viene rinominato mostra l'etichetta localizzata nella lingua corrente dell'app.
 */
@Composable
private fun UserProfile.displayName(): String =
  if (isPrimary && !hasCustomName) stringResource(R.string.profiles_default_name) else name

/**
 * Messaggio localizzato per un errore di nome. Va chiamato in composizione perché usa
 * [stringResource].
 */
@Composable
private fun nameErrorText(error: ProfileNameError): String = when (error) {
  ProfileNameError.EMPTY -> stringResource(R.string.profiles_error_empty)
  ProfileNameError.TOO_LONG ->
    stringResource(R.string.profiles_error_too_long, ProfileRules.MAX_NAME_LENGTH)
  ProfileNameError.DUPLICATE -> stringResource(R.string.profiles_error_duplicate)
}

/**
 * Richiede il focus iniziale su una schermata appena composta.
 *
 * Il nodo può non essere ancora agganciato alla finestra nel primo frame: si ritenta per
 * alcuni frame, come fanno gli altri dialog TV dell'app, senza mai lasciare il focus
 * sulla schermata sottostante.
 */
@Composable
private fun RequestInitialFocus(focusRequester: FocusRequester) {
  LaunchedEffect(focusRequester) {
    repeat(30) {
      val focused = runCatching { focusRequester.requestFocus() }.isSuccess
      if (focused) return@LaunchedEffect
      withFrameNanos { }
    }
  }
}

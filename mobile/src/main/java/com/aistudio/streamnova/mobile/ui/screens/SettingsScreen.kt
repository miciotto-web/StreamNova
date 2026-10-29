package com.aistudio.streamnova.mobile.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aistudio.streamnova.mobile.BuildConfig
import com.aistudio.streamnova.mobile.R
import com.aistudio.streamnova.mobile.ui.MobileSettingsViewModel
import com.example.data.prefs.DecoderFallbackMode
import com.example.data.prefs.PreferredResolution
import com.example.data.prefs.StreamingEngineMode
import com.example.ui.theme.NovaCyan
import com.example.ui.theme.NovaSurfaceVariant
import com.example.ui.theme.NovaTextMuted
import com.example.ui.theme.NovaTextPrimary
import com.example.ui.theme.NovaTextSecondary

@Composable
fun SettingsScreen(onBack: () -> Unit) {
  BackHandler(onBack = onBack)
  val viewModel: MobileSettingsViewModel = viewModel()
  val state by viewModel.uiState.collectAsStateWithLifecycle()

  // Il PIN non passa da rememberSaveable: non deve finire in chiaro nel saved state.
  var pin by remember { mutableStateOf("") }
  var newPin by remember { mutableStateOf("") }
  var pinMessage by rememberSaveable { mutableStateOf<String?>(null) }
  var pinError by rememberSaveable { mutableStateOf(false) }
  var torBoxKey by rememberSaveable { mutableStateOf("") }
  val pinSavedMessage = stringResource(R.string.settings_parental_pin_saved)
  val pinChangedMessage = stringResource(R.string.settings_parental_pin_changed)

  LaunchedEffect(state.torBoxApiKey) {
    if (torBoxKey.isBlank() && state.torBoxApiKey.isNotBlank()) {
      torBoxKey = state.torBoxApiKey
    }
  }

  LazyColumn(
    modifier = Modifier
      .fillMaxSize()
      .widthIn(max = 900.dp),
    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 32.dp)
  ) {
    item(key = "topbar") {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .statusBarsPadding()
          .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        IconButton(onClick = onBack) {
          Icon(
            Icons.Default.ArrowBack,
            contentDescription = stringResource(R.string.action_back),
            tint = NovaTextPrimary
          )
        }
        Text(
          text = stringResource(R.string.settings_title),
          color = NovaTextPrimary,
          fontSize = 24.sp,
          fontWeight = FontWeight.Bold
        )
      }
    }

    item(key = "playback-title") {
      SettingsSectionTitle(stringResource(R.string.settings_playback))
    }
    item(key = "auto-resume") {
      SettingsSwitchRow(
        title = stringResource(R.string.settings_auto_resume),
        checked = state.playback.autoResume,
        onCheckedChange = viewModel::setAutoResume
      )
    }
    item(key = "autoplay") {
      SettingsSwitchRow(
        title = stringResource(R.string.settings_autoplay),
        checked = state.autoplayEnabled,
        onCheckedChange = viewModel::setAutoplayNext
      )
    }
    item(key = "italian-audio") {
      SettingsSwitchRow(
        title = stringResource(R.string.settings_italian_audio),
        checked = state.playback.prioritizeItalianAudio,
        onCheckedChange = viewModel::setPrioritizeItalianAudio
      )
    }
    item(key = "subtitles") {
      SettingsSwitchRow(
        title = stringResource(R.string.settings_subtitles),
        checked = state.playback.subtitlesEnabled,
        onCheckedChange = viewModel::setSubtitlesEnabled
      )
    }
    item(key = "resolution") {
      SettingsChoiceRow(
        title = stringResource(R.string.settings_resolution),
        options = PreferredResolution.entries,
        selected = state.playback.preferredResolution,
        label = { it.label },
        onSelect = viewModel::setPreferredResolution
      )
    }
    item(key = "decoder") {
      SettingsChoiceRow(
        title = "Fallback codec",
        options = DecoderFallbackMode.entries,
        selected = state.playback.decoderFallbackMode,
        label = { it.label },
        onSelect = viewModel::setDecoderFallback
      )
    }

    item(key = "parental-title") {
      SettingsSectionTitle(stringResource(R.string.settings_parental))
    }
    item(key = "parental-switch") {
      SettingsSwitchRow(
        title = stringResource(R.string.settings_parental_enabled),
        checked = state.parentalEnabled,
        onCheckedChange = { enabled ->
          if (viewModel.setParentalEnabled(enabled, pin)) {
            pin = ""
            newPin = ""
            pinError = false
            pinMessage = null
          } else {
            pinError = true
            pinMessage = null
          }
        }
      )
    }
    item(key = "parental-pin") {
      Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
        OutlinedTextField(
          value = pin,
          onValueChange = { value ->
            if (value.length <= 4 && value.all { it.isDigit() }) {
              pin = value
              pinError = false
            }
          },
          modifier = Modifier.fillMaxWidth(),
          label = {
            Text(
              stringResource(
                if (state.hasParentalPin) R.string.settings_parental_pin_current
                else R.string.settings_parental_pin
              )
            )
          },
          placeholder = { Text(stringResource(R.string.settings_parental_pin_hint)) },
          singleLine = true,
          visualTransformation = PasswordVisualTransformation(),
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
          colors = settingsFieldColors()
        )
        if (state.hasParentalPin) {
          OutlinedTextField(
            value = newPin,
            onValueChange = { value ->
              if (value.length <= 4 && value.all { it.isDigit() }) {
                newPin = value
                pinError = false
              }
            },
            modifier = Modifier
              .fillMaxWidth()
              .padding(top = 8.dp),
            label = { Text(stringResource(R.string.settings_parental_pin_new)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            colors = settingsFieldColors()
          )
        }
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.End
        ) {
          TextButton(
            onClick = {
              if (state.hasParentalPin) {
                val changed = viewModel.changeParentalPin(pin, newPin)
                pinError = !changed
                pinMessage = if (changed) pinChangedMessage else null
                if (changed) {
                  pin = ""
                  newPin = ""
                }
              } else {
                val saved = viewModel.saveParentalPin(pin)
                pinError = !saved
                pinMessage = if (saved) pinSavedMessage else null
                if (saved) pin = ""
              }
            }
          ) {
            Text(
              text = stringResource(
                if (state.hasParentalPin) R.string.settings_parental_pin_change
                else R.string.settings_save
              ),
              color = NovaCyan
            )
          }
        }
        if (pinError) {
          Text(
            text = stringResource(
              if (state.hasParentalPin) R.string.settings_parental_pin_incorrect
              else R.string.settings_parental_pin_required
            ),
            color = com.example.ui.theme.NovaRed,
            fontSize = 12.sp
          )
        } else if (pinMessage != null) {
          Text(text = pinMessage.orEmpty(), color = NovaCyan, fontSize = 12.sp)
        }
      }
    }
    item(key = "parental-level") {
      SettingsChoiceRow(
        title = stringResource(R.string.settings_parental_level),
        options = listOf("14", "16", "18"),
        selected = state.parentalLevel,
        label = { "$it+" },
        onSelect = viewModel::setParentalLevel
      )
    }

    item(key = "engine-title") {
      SettingsSectionTitle(stringResource(R.string.settings_engine))
    }
    item(key = "engine-mode") {
      SettingsChoiceRow(
        title = stringResource(R.string.settings_engine),
        options = StreamingEngineMode.entries,
        selected = state.engineMode,
        label = {
          when (it) {
            StreamingEngineMode.HTTP_WEB -> stringResource(R.string.settings_engine_http)
            StreamingEngineMode.DEBRID_TORBOX -> stringResource(R.string.settings_engine_debrid)
          }
        },
        onSelect = viewModel::setEngineMode
      )
    }
    item(key = "torbox-key") {
      Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
        OutlinedTextField(
          value = torBoxKey,
          onValueChange = { torBoxKey = it },
          modifier = Modifier.fillMaxWidth(),
          label = { Text(stringResource(R.string.settings_torbox_key)) },
          placeholder = { Text(stringResource(R.string.settings_torbox_key_hint)) },
          singleLine = true,
          colors = settingsFieldColors()
        )
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.End
        ) {
          TextButton(
            onClick = { viewModel.setTorBoxApiKey(torBoxKey) }
          ) {
            Text(stringResource(R.string.settings_save), color = NovaCyan)
          }
        }
      }
    }

    item(key = "about-title") {
      SettingsSectionTitle(stringResource(R.string.settings_about))
    }
    item(key = "version") {
      SettingsInfoRow(
        label = stringResource(R.string.settings_version),
        value = BuildConfig.VERSION_NAME
      )
    }
    item(key = "app-id") {
      SettingsInfoRow(
        label = stringResource(R.string.settings_app_id),
        value = BuildConfig.APPLICATION_ID
      )
    }
  }
}

@Composable
private fun SettingsSectionTitle(title: String) {
  Text(
    text = title,
    color = NovaCyan,
    fontSize = 14.sp,
    fontWeight = FontWeight.Bold,
    modifier = Modifier.padding(start = 20.dp, top = 22.dp, bottom = 6.dp)
  )
}

@Composable
private fun SettingsSwitchRow(
  title: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clickable { onCheckedChange(!checked) }
      .padding(horizontal = 20.dp, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Text(
      text = title,
      color = NovaTextPrimary,
      fontSize = 15.sp,
      modifier = Modifier.weight(1f)
    )
    Switch(
      checked = checked,
      onCheckedChange = onCheckedChange,
      colors = SwitchDefaults.colors(
        checkedThumbColor = NovaCyan,
        checkedTrackColor = NovaCyan.copy(alpha = 0.4f),
        uncheckedThumbColor = NovaTextMuted,
        uncheckedTrackColor = NovaSurfaceVariant
      )
    )
  }
}

@Composable
private fun <T> SettingsChoiceRow(
  title: String,
  options: List<T>,
  selected: T,
  label: @Composable (T) -> String,
  onSelect: (T) -> Unit
) {
  Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
    Text(text = title, color = NovaTextSecondary, fontSize = 13.sp)
    Spacer(modifier = Modifier.height(6.dp))
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      items(options) { option ->
        FilterChip(
          selected = option == selected,
          onClick = { onSelect(option) },
          label = { Text(label(option)) },
          colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = NovaCyan.copy(alpha = 0.18f),
            selectedLabelColor = NovaCyan,
            labelColor = NovaTextMuted
          )
        )
      }
    }
  }
}

@Composable
private fun SettingsInfoRow(label: String, value: String) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 20.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Text(text = label, color = NovaTextMuted, fontSize = 14.sp, modifier = Modifier.weight(1f))
    Text(text = value, color = NovaTextSecondary, fontSize = 13.sp)
  }
}

@Composable
private fun settingsFieldColors() = OutlinedTextFieldDefaults.colors(
  focusedBorderColor = NovaCyan,
  unfocusedBorderColor = NovaSurfaceVariant,
  focusedTextColor = NovaTextPrimary,
  unfocusedTextColor = NovaTextPrimary,
  cursorColor = NovaCyan
)

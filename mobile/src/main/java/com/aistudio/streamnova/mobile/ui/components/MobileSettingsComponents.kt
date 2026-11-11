package com.aistudio.streamnova.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistudio.streamnova.mobile.R
import com.example.data.remote.simkl.SimklLoginPrompt
import com.example.ui.theme.NovaCardBg
import com.example.ui.theme.NovaCyan
import com.example.ui.theme.NovaCyanBright
import com.example.ui.theme.NovaDivider
import com.example.ui.theme.NovaSurfaceVariant
import com.example.ui.theme.NovaTextMuted
import com.example.ui.theme.NovaTextPrimary
import com.example.ui.theme.NovaTextSecondary
import kotlinx.coroutines.delay

/** Sezione impostazioni: intestazione con accento ciano e card, come la TV. */
@Composable
fun MobileSettingsSection(
  title: String,
  modifier: Modifier = Modifier,
  content: @Composable ColumnScope.() -> Unit
) {
  Column(modifier = modifier.fillMaxWidth()) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Box(
        modifier = Modifier
          .width(4.dp)
          .height(18.dp)
          .background(NovaCyanBright, RoundedCornerShape(2.dp))
      )
      Spacer(modifier = Modifier.width(10.dp))
      Text(
        text = title.uppercase(),
        color = NovaTextPrimary,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp
      )
    }
    Spacer(modifier = Modifier.height(10.dp))
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .background(NovaCardBg, RoundedCornerShape(14.dp))
        .border(1.dp, NovaDivider, RoundedCornerShape(14.dp))
        .padding(vertical = 4.dp),
      verticalArrangement = Arrangement.spacedBy(2.dp),
      content = content
    )
  }
}

/**
 * Sezione impostazioni espandibile: intestazione touch con stato Aperto/Chiuso e
 * contenuto nella stessa card delle altre sezioni.
 *
 * Replica la sezione «Continua a guardare» della TV 1.0.7: lo stato vive solo nella
 * composizione, le preferenze interne restano persistite singolarmente.
 */
@Composable
fun MobileSettingsExpandableSection(
  title: String,
  subtitle: String,
  expanded: Boolean,
  expandedLabel: String,
  collapsedLabel: String,
  onToggle: () -> Unit,
  modifier: Modifier = Modifier,
  content: @Composable ColumnScope.() -> Unit
) {
  val stateLabel = if (expanded) expandedLabel else collapsedLabel
  Column(modifier = modifier.fillMaxWidth()) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .selectable(
          selected = expanded,
          role = Role.Button,
          onClick = onToggle
        )
        .semantics { stateDescription = stateLabel }
        .background(NovaCardBg, RoundedCornerShape(12.dp))
        .border(1.dp, NovaDivider, RoundedCornerShape(12.dp))
        .padding(horizontal = 16.dp, vertical = 14.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = title,
          color = NovaTextPrimary,
          fontSize = 16.sp,
          fontWeight = FontWeight.Bold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(3.dp))
        Text(
          text = subtitle,
          color = NovaTextSecondary,
          fontSize = 12.sp,
          lineHeight = 16.sp,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis
        )
      }
      Spacer(modifier = Modifier.width(12.dp))
      Text(
        text = stateLabel,
        color = NovaCyanBright,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold
      )
      Spacer(modifier = Modifier.width(6.dp))
      Icon(
        imageVector = if (expanded) Icons.Default.KeyboardArrowUp
        else Icons.Default.KeyboardArrowDown,
        contentDescription = null,
        tint = NovaCyanBright,
        modifier = Modifier.size(20.dp)
      )
    }
    if (expanded) {
      Spacer(modifier = Modifier.height(8.dp))
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .background(NovaCardBg.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
          .border(1.dp, NovaDivider, RoundedCornerShape(14.dp))
          .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        content = content
      )
    }
  }
}

/** Riga impostazione touch: icona, titolo, valore a destra e chevron. */
@Composable
fun MobileSettingsRow(
  icon: ImageVector,
  title: String,
  value: String? = null,
  valueColor: Color = NovaCyanBright,
  enabled: Boolean = true,
  onClick: (() -> Unit)? = null,
  trailing: (@Composable () -> Unit)? = null
) {
  val clickable = enabled && onClick != null
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clickable(enabled = clickable) { onClick?.invoke() }
      .padding(horizontal = 16.dp, vertical = 13.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Icon(
      imageVector = icon,
      contentDescription = null,
      tint = if (enabled) NovaCyan else NovaTextMuted,
      modifier = Modifier.size(20.dp)
    )
    Spacer(modifier = Modifier.width(14.dp))
    val valueBelowTitle = value != null && value.length > 16
    if (valueBelowTitle) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = title,
          color = if (enabled) NovaTextPrimary else NovaTextMuted,
          fontSize = 15.sp,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis
        )
        Text(
          text = value.orEmpty(),
          color = if (enabled) valueColor else NovaTextMuted,
          fontSize = 12.sp,
          fontWeight = FontWeight.Medium,
          textAlign = TextAlign.End,
          modifier = Modifier.fillMaxWidth()
        )
      }
    } else {
      Text(
        text = title,
        color = if (enabled) NovaTextPrimary else NovaTextMuted,
        fontSize = 15.sp,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f)
      )
      if (value != null) {
        Spacer(modifier = Modifier.width(10.dp))
        Text(
          text = value,
          color = if (enabled) valueColor else NovaTextMuted,
          fontSize = 13.sp,
          fontWeight = FontWeight.Medium,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis
        )
      }
    }
    when {
      trailing != null -> {
        Spacer(modifier = Modifier.width(8.dp))
        trailing()
      }
      onClick != null -> Icon(
        imageVector = Icons.Default.ChevronRight,
        contentDescription = null,
        tint = NovaTextMuted,
        modifier = Modifier.size(18.dp)
      )
    }
  }
}

/** Riga con switch; il tocco su tutta la riga cambia il valore. */
@Composable
fun MobileSettingsSwitchRow(
  icon: ImageVector,
  title: String,
  checked: Boolean,
  enabled: Boolean = true,
  onCheckedChange: (Boolean) -> Unit
) {
  MobileSettingsRow(
    icon = icon,
    title = title,
    enabled = enabled,
    onClick = { onCheckedChange(!checked) },
    trailing = {
      Switch(
        checked = checked,
        enabled = enabled,
        onCheckedChange = { onCheckedChange(it) },
        colors = SwitchDefaults.colors(
          checkedThumbColor = NovaCyanBright,
          checkedTrackColor = NovaCyan.copy(alpha = 0.45f),
          uncheckedThumbColor = NovaTextMuted,
          uncheckedTrackColor = NovaSurfaceVariant
        )
      )
    }
  )
}

/** Nota esplicativa sotto una riga (descrizioni TV e limitazioni reali). */
@Composable
fun MobileSettingsNote(text: String, color: Color = NovaTextSecondary) {
  Text(
    text = text,
    color = color,
    fontSize = 12.sp,
    lineHeight = 17.sp,
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp, vertical = 6.dp)
  )
}

/** Dialog di scelta a elenco, equivalente touch del TrackSelectionDialog TV. */
@Composable
fun MobileChoiceDialog(
  title: String,
  options: List<String>,
  selectedIndex: Int,
  onSelect: (Int) -> Unit,
  onDismiss: () -> Unit
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title, color = NovaTextPrimary) },
    text = {
      Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        options.forEachIndexed { index, option ->
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .clickable { onSelect(index) }
              .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            RadioButton(
              selected = index == selectedIndex,
              onClick = { onSelect(index) },
              colors = RadioButtonDefaults.colors(
                selectedColor = NovaCyanBright,
                unselectedColor = NovaTextMuted
              )
            )
            Text(
              text = option,
              color = if (index == selectedIndex) NovaCyanBright else NovaTextPrimary,
              fontSize = 15.sp
            )
          }
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss) {
        Text(stringResource(R.string.action_close), color = NovaCyan)
      }
    },
    containerColor = NovaSurfaceVariant
  )
}

/**
 * Dialog di inserimento chiave API.
 *
 * [secure] maschera il testo e offre il toggle mostra/nascondi: la chiave non
 * appare mai in chiaro nella schermata Impostazioni.
 */
@Composable
fun MobileTextInputDialog(
  title: String,
  hint: String,
  initialText: String,
  secure: Boolean = false,
  confirmLabel: String = stringResource(R.string.settings_save),
  onConfirm: (String) -> Unit,
  onDismiss: () -> Unit
) {
  var text by remember { mutableStateOf(initialText) }
  var visible by remember { mutableStateOf(!secure) }
  val focusRequester = remember { FocusRequester() }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title, color = NovaTextPrimary) },
    text = {
      OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        modifier = Modifier
          .fillMaxWidth()
          .focusRequester(focusRequester),
        placeholder = { Text(hint, color = NovaTextMuted) },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None
        else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
        trailingIcon = if (secure) {
          {
            IconButton(onClick = { visible = !visible }) {
              Icon(
                imageVector = if (visible) Icons.Default.VisibilityOff
                else Icons.Default.Visibility,
                contentDescription = stringResource(
                  if (visible) R.string.action_hide_key else R.string.action_show_key
                ),
                tint = NovaTextMuted
              )
            }
          }
        } else null,
        colors = settingsFieldColors()
      )
    },
    confirmButton = {
      TextButton(onClick = { onConfirm(text) }) {
        Text(confirmLabel, color = NovaCyan)
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text(stringResource(R.string.action_cancel), color = NovaTextSecondary)
      }
    },
    containerColor = NovaSurfaceVariant
  )

  LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
}

/** Dialog PIN: un solo campo a 4 cifre, errore localizzato, submit esplicito. */
@Composable
fun MobilePinDialog(
  title: String,
  subtitle: String,
  errorMessage: String?,
  isNewPin: Boolean = false,
  onSubmit: (String) -> Unit,
  onDismiss: () -> Unit,
  onNavigateBack: (() -> Unit)? = null
) {
  // Ogni passo (crea → conferma, verifica, cambio PIN) riparte da un campo vuoto;
  // anche un errore azzera il valore, così non si reinvia per sbaglio il PIN errato.
  var pin by remember(subtitle, errorMessage) { mutableStateOf("") }
  val focusRequester = remember { FocusRequester() }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title, color = NovaTextPrimary) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(subtitle, color = NovaTextSecondary, fontSize = 14.sp)
        OutlinedTextField(
          value = pin,
          onValueChange = { value ->
            if (value.length <= 4 && value.all(Char::isDigit)) pin = value
          },
          modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester),
          label = { Text(stringResource(R.string.parental_pin_label)) },
          singleLine = true,
          isError = errorMessage != null,
          visualTransformation = PasswordVisualTransformation(),
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
          colors = settingsFieldColors()
        )
        errorMessage?.let {
          Text(text = it, color = com.example.ui.theme.NovaRed, fontSize = 12.sp)
        }
      }
    },
    confirmButton = {
      TextButton(
        enabled = pin.length == 4,
        onClick = { onSubmit(pin) }
      ) {
        Text(
          text = stringResource(if (isNewPin) R.string.settings_save else R.string.parental_pin_unlock),
          color = NovaCyan
        )
      }
    },
    dismissButton = {
      TextButton(onClick = onNavigateBack ?: onDismiss) {
        Text(
          text = stringResource(
            if (onNavigateBack != null) R.string.action_back else R.string.action_cancel
          ),
          color = NovaTextSecondary
        )
      }
    },
    containerColor = NovaSurfaceVariant
  )

  LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
}

/** Dialog informativo semplice (opzione non disponibile su Mobile). */
@Composable
fun MobileInfoDialog(title: String, body: String, onDismiss: () -> Unit) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title, color = NovaTextPrimary) },
    text = { Text(body, color = NovaTextSecondary) },
    confirmButton = {
      TextButton(onClick = onDismiss) {
        Text(stringResource(R.string.action_close), color = NovaCyan)
      }
    },
    containerColor = NovaSurfaceVariant
  )
}

/**
 * Pannello di login SIMKL mobile.
 *
 * La TV mostra un QR perché non ha tastiera; sul telefono il gesto naturale è
 * aprire il link e digitare il codice (o lasciarlo già compilato quando SIMKL
 * restituisce l'URL completo). Il polling e le regole di annullamento restano
 * quelli del client condiviso.
 */
@Composable
fun MobileSimklLoginDialog(
  prompt: SimklLoginPrompt?,
  waiting: Boolean,
  requesting: Boolean,
  errorMessage: String?,
  canRetry: Boolean,
  countdownSeconds: Long,
  instruction: String,
  statusMessage: String?,
  onRetry: () -> Unit,
  onOpenLink: () -> Unit,
  onDismiss: () -> Unit
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.settings_simkl_login_title), color = NovaTextPrimary) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (prompt != null) {
          Text(instruction, color = NovaTextSecondary, fontSize = 13.sp)
          Text(
            text = prompt.userCode,
            color = NovaTextPrimary,
            fontSize = 34.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 3.sp
          )
          Text(
            text = stringResource(R.string.settings_simkl_countdown, countdownSeconds.toInt()),
            color = if (countdownSeconds > 0L) NovaTextSecondary else com.example.ui.theme.NovaOrange,
            fontSize = 12.sp
          )
        }
        statusMessage?.let { message ->
          Row(verticalAlignment = Alignment.CenterVertically) {
            if (waiting || requesting) {
              CircularProgressIndicator(
                color = NovaCyanBright,
                strokeWidth = 2.dp,
                modifier = Modifier.size(15.dp)
              )
              Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
              text = message,
              color = if (errorMessage != null) com.example.ui.theme.NovaOrange else NovaCyanBright,
              fontSize = 13.sp
            )
          }
        }
        errorMessage?.let { Text(it, color = com.example.ui.theme.NovaOrange, fontSize = 13.sp) }
      }
    },
    confirmButton = {
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (prompt != null) {
          TextButton(onClick = onOpenLink) {
            Text(stringResource(R.string.settings_simkl_login_open), color = NovaCyan)
          }
        }
        TextButton(onClick = onDismiss) {
          Text(stringResource(R.string.action_cancel), color = NovaTextSecondary)
        }
        if (canRetry) {
          TextButton(onClick = onRetry) {
            Text(stringResource(R.string.settings_simkl_new_code), color = NovaCyan)
          }
        }
      }
    },
    containerColor = NovaSurfaceVariant
  )
}

@Composable
private fun settingsFieldColors() = OutlinedTextFieldDefaults.colors(
  focusedBorderColor = NovaCyan,
  unfocusedBorderColor = NovaSurfaceVariant,
  focusedTextColor = NovaTextPrimary,
  unfocusedTextColor = NovaTextPrimary,
  cursorColor = NovaCyan
)

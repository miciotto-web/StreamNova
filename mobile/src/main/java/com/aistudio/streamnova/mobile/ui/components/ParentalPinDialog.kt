package com.aistudio.streamnova.mobile.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistudio.streamnova.mobile.R
import com.example.ui.theme.NovaCyan
import com.example.ui.theme.NovaRed
import com.example.ui.theme.NovaSurfaceVariant
import com.example.ui.theme.NovaTextPrimary
import com.example.ui.theme.NovaTextSecondary

/**
 * Dialog touch per lo sblocco di UN solo titolo.
 *
 * Il PIN non viene mai mostrato in chiaro, non viene persistito qui e non esce da
 * questo composable: il chiamante lo inoltra alla verifica del repository.
 */
@Composable
fun ParentalPinDialog(
  mediaTitle: String,
  pin: String,
  error: Boolean,
  onPinChange: (String) -> Unit,
  onConfirm: () -> Unit,
  onDismiss: () -> Unit
) {
  val focusRequester = remember { FocusRequester() }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.parental_pin_title), color = NovaTextPrimary) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
          text = stringResource(R.string.parental_pin_message, mediaTitle),
          color = NovaTextSecondary
        )
        OutlinedTextField(
          value = pin,
          onValueChange = { value ->
            if (value.length <= 4 && value.all(Char::isDigit)) onPinChange(value)
          },
          label = { Text(stringResource(R.string.parental_pin_label)) },
          singleLine = true,
          isError = error,
          visualTransformation = PasswordVisualTransformation(),
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
          modifier = Modifier.focusRequester(focusRequester)
        )
        if (error) {
          Text(
            text = stringResource(R.string.parental_pin_error),
            color = NovaRed,
            fontSize = 12.sp
          )
        }
      }
    },
    confirmButton = {
      TextButton(
        enabled = pin.length == 4,
        onClick = onConfirm
      ) {
        Text(stringResource(R.string.parental_pin_unlock), color = NovaCyan)
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text(stringResource(R.string.action_cancel), color = NovaTextSecondary)
      }
    },
    containerColor = NovaSurfaceVariant
  )

  LaunchedEffect(Unit) {
    // Su alcuni dispositivi il campo non è ancora allegato al primo frame.
    runCatching { focusRequester.requestFocus() }
  }
}

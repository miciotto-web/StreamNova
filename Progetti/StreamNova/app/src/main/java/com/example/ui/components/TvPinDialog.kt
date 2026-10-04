package com.example.ui.components

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.R
import com.example.ui.theme.NovaCyan
import com.example.ui.theme.NovaCyanBright
import com.example.ui.theme.NovaSurface
import com.example.ui.theme.NovaSurfaceVariant
import com.example.ui.theme.NovaTextMuted
import com.example.ui.theme.NovaTextPrimary
import com.example.ui.theme.NovaTextSecondary

/**
 * Dialog di inserimento PIN a 4 cifre per Android TV.
 *
 * Include:
 * - Tastierino numerico 0-9 ottimizzato D-pad
 * - 4 slot grafici che riflettono le cifre inserite (mascherate)
 * - Tasto backspace per cancellare l'ultima cifra
 * - Tasto conferma (abilitato quando sono presenti 4 cifre)
 * - Supporto ai tasti fisici 0-9 e DEL del telecomando TV
 * - Messaggio di errore per PIN errato o non corrispondente
 * - Pulsante Annulla
 */
@Composable
fun TvPinDialog(
  title: String,
  subtitle: String? = null,
  errorMessage: String? = null,
  onPinSubmit: (String) -> Unit,
  onDismiss: () -> Unit
) {
  var pin by remember { mutableStateOf("") }
  val firstKeyFocusRequester = remember { FocusRequester() }

  LaunchedEffect(Unit) {
    withFrameNanos { }
    try {
      firstKeyFocusRequester.requestFocus()
    } catch (_: Exception) {
    }
  }

  fun appendDigit(digit: Char) {
    if (pin.length < 4) {
      val newPin = pin + digit
      pin = newPin
      if (newPin.length == 4) {
        onPinSubmit(newPin)
      }
    }
  }

  fun deleteDigit() {
    if (pin.isNotEmpty()) {
      pin = pin.dropLast(1)
    }
  }

  Dialog(onDismissRequest = onDismiss) {
    Column(
      modifier = Modifier
        .width(360.dp)
        .background(NovaSurface, RoundedCornerShape(20.dp))
        .border(1.5.dp, NovaCyan, RoundedCornerShape(20.dp))
        .padding(24.dp)
        .onKeyEvent { keyEvent ->
          if (keyEvent.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) return@onKeyEvent false
          val keyCode = keyEvent.nativeKeyEvent.keyCode
          when {
            keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
              val digit = ('0'.code + (keyCode - KeyEvent.KEYCODE_0)).toChar()
              appendDigit(digit)
              true
            }
            keyCode == KeyEvent.KEYCODE_DEL -> {
              deleteDigit()
              true
            }
            else -> false
          }
        },
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      // Intestazione con Titolo e tasto Chiudi
      Row(
        modifier = Modifier.width(312.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          text = title,
          color = NovaTextPrimary,
          fontSize = 18.sp,
          fontWeight = FontWeight.Bold
        )
        TvFocusableBox(
          shape = CircleShape,
          onClick = onDismiss,
          modifier = Modifier.size(32.dp)
        ) { isFocused ->
          Box(
            modifier = Modifier
              .size(32.dp)
              .background(if (isFocused) NovaSurfaceVariant else Color.Transparent, CircleShape),
            contentAlignment = Alignment.Center
          ) {
            Icon(
              imageVector = Icons.Default.Close,
              contentDescription = stringResource(R.string.action_close),
              tint = if (isFocused) NovaCyanBright else NovaTextSecondary,
              modifier = Modifier.size(20.dp)
            )
          }
        }
      }

      if (!subtitle.isNullOrBlank()) {
        Spacer(modifier = Modifier.height(6.dp))
        Text(
          text = subtitle,
          color = NovaTextSecondary,
          fontSize = 13.sp,
          textAlign = TextAlign.Center,
          modifier = Modifier.padding(horizontal = 8.dp)
        )
      }

      Spacer(modifier = Modifier.height(20.dp))

      // 4 Slot indicatori delle cifre
      Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        for (i in 0 until 4) {
          val isFilled = i < pin.length
          val isActive = i == pin.length
          Box(
            modifier = Modifier
              .size(46.dp)
              .background(
                color = when {
                  isFilled -> NovaCyan.copy(alpha = 0.22f)
                  isActive -> NovaSurfaceVariant
                  else -> Color.Black.copy(alpha = 0.35f)
                },
                shape = RoundedCornerShape(12.dp)
              )
              .border(
                width = if (isFilled || isActive) 1.5.dp else 1.dp,
                color = when {
                  isFilled -> NovaCyanBright
                  isActive -> NovaCyan
                  else -> Color.White.copy(alpha = 0.2f)
                },
                shape = RoundedCornerShape(12.dp)
              ),
            contentAlignment = Alignment.Center
          ) {
            if (isFilled) {
              Box(
                modifier = Modifier
                  .size(12.dp)
                  .background(NovaCyanBright, CircleShape)
              )
            }
          }
        }
      }

      // Messaggio di errore
      if (!errorMessage.isNullOrBlank()) {
        Spacer(modifier = Modifier.height(10.dp))
        Text(
          text = errorMessage,
          color = Color(0xFFFF5252),
          fontSize = 12.sp,
          fontWeight = FontWeight.SemiBold,
          textAlign = TextAlign.Center
        )
      } else {
        Spacer(modifier = Modifier.height(14.dp))
      }

      Spacer(modifier = Modifier.height(6.dp))

      // Tastierino numerico 0-9 (layout TV 3 colonne)
      val keypadRows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("DEL", "0", "OK")
      )

      Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        keypadRows.forEachIndexed { rowIndex, row ->
          Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            row.forEach { keyLabel ->
              val isFirstKey = rowIndex == 0 && keyLabel == "1"
              TvFocusableBox(
                modifier = Modifier
                  .then(if (isFirstKey) Modifier.focusRequester(firstKeyFocusRequester) else Modifier),
                shape = RoundedCornerShape(10.dp),
                onClick = {
                  when (keyLabel) {
                    "DEL" -> deleteDigit()
                    "OK" -> if (pin.length == 4) onPinSubmit(pin)
                    else -> appendDigit(keyLabel[0])
                  }
                }
              ) { isFocused ->
                Box(
                  modifier = Modifier
                    .size(width = 68.dp, height = 44.dp)
                    .background(
                      color = when {
                        isFocused -> NovaCyan.copy(alpha = 0.28f)
                        keyLabel == "OK" && pin.length == 4 -> NovaCyan.copy(alpha = 0.15f)
                        else -> NovaSurfaceVariant
                      },
                      shape = RoundedCornerShape(10.dp)
                    )
                    .border(
                      width = if (isFocused) 1.5.dp else 1.dp,
                      color = when {
                        isFocused -> NovaCyanBright
                        keyLabel == "OK" && pin.length == 4 -> NovaCyan
                        else -> Color.White.copy(alpha = 0.12f)
                      },
                      shape = RoundedCornerShape(10.dp)
                    ),
                  contentAlignment = Alignment.Center
                ) {
                  when (keyLabel) {
                    "DEL" -> Icon(
                      imageVector = Icons.AutoMirrored.Filled.Backspace,
                      contentDescription = stringResource(R.string.parental_pin_delete),
                      tint = if (isFocused) NovaCyanBright else NovaTextSecondary,
                      modifier = Modifier.size(18.dp)
                    )
                    "OK" -> Icon(
                      imageVector = Icons.Default.Check,
                      contentDescription = stringResource(R.string.action_confirm),
                      tint = if (pin.length == 4) (if (isFocused) NovaCyanBright else NovaCyan) else NovaTextMuted,
                      modifier = Modifier.size(20.dp)
                    )
                    else -> Text(
                      text = keyLabel,
                      color = if (isFocused) NovaCyanBright else NovaTextPrimary,
                      fontSize = 18.sp,
                      fontWeight = FontWeight.Bold
                    )
                  }
                }
              }
            }
          }
        }
      }

      Spacer(modifier = Modifier.height(16.dp))

      // Pulsante Annulla
      TvFocusableBox(
        shape = RoundedCornerShape(50),
        onClick = onDismiss
      ) { isFocused ->
        Box(
          modifier = Modifier
            .background(
              if (isFocused) NovaCyan.copy(alpha = 0.2f) else Color.Transparent,
              RoundedCornerShape(50)
            )
            .border(
              width = 1.dp,
              color = if (isFocused) NovaCyanBright else Color.White.copy(alpha = 0.25f),
              shape = RoundedCornerShape(50)
            )
            .padding(horizontal = 24.dp, vertical = 8.dp),
          contentAlignment = Alignment.Center
        ) {
          Text(
            text = stringResource(R.string.action_cancel),
            color = if (isFocused) NovaCyanBright else NovaTextSecondary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
          )
        }
      }
    }
  }
}

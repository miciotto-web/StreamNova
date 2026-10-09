package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val QrCardBg = Color(0xFF0f172a)
private val QrSurface = Color(0xFFf8fafc)
private val QrModule = Color(0xFF0f172a)
private val QrAccent = Color(0xFF06b6d4)
private val QrTextMuted = Color(0xFF94a3b8)

/** Matrice di moduli (true = modulo scuro) generata da ZXing. */
private class QrCodeMatrix(val size: Int, private val modules: BooleanArray) {
  fun module(x: Int, y: Int): Boolean = modules[y * size + x]
}

private suspend fun encodeQrCode(content: String): QrCodeMatrix? = withContext(Dispatchers.Default) {
  try {
    val hints = mapOf(
      EncodeHintType.MARGIN to 1,
      EncodeHintType.CHARACTER_SET to "UTF-8"
    )
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, hints)
    val width = matrix.width
    val height = matrix.height
    if (width <= 0 || height <= 0 || width != height) return@withContext null
    QrCodeMatrix(width, BooleanArray(width * height) { i -> matrix.get(i % width, i / width) })
  } catch (e: Throwable) {
    null
  }
}

/**
 * Card con QR Code dell'indirizzo del micro-server [AddonSyncServer].
 *
 * Quando [url] è null (server non in ascolto / rete non connessa) o il QR non
 * può essere generato, mostra il fallback testuale con l'indirizzo digitabile.
 */
@Composable
fun QrCodeInfoCard(
  url: String?,
  modifier: Modifier = Modifier,
  fallbackMessage: String? = null
) {
  val qr by produceState<QrCodeMatrix?>(initialValue = null, url) {
    value = url?.let { encodeQrCode(it) }
  }
  // Snapshot locale: smart-cast possibile anche dentro le lambda di Canvas.
  val matrix = qr

  Column(
    modifier = modifier
      .background(QrCardBg, RoundedCornerShape(16.dp))
      .border(2.dp, QrAccent, RoundedCornerShape(16.dp))
      .padding(16.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(10.dp)
  ) {
    Icon(
      imageVector = Icons.Default.Info,
      contentDescription = null,
      tint = QrAccent,
      modifier = Modifier.size(28.dp)
    )

    Text(
      text = stringResource(R.string.addons_remote_title),
      color = QrAccent,
      fontSize = 15.sp,
      fontWeight = FontWeight.Bold
    )

    Text(
      text = stringResource(R.string.addons_remote_desc),
      color = QrTextMuted,
      fontSize = 12.sp,
      lineHeight = 16.sp,
      modifier = Modifier.fillMaxWidth()
    )

    when {
      url != null && matrix != null -> {
        Box(
          modifier = Modifier
            .size(180.dp)
            .background(QrSurface, RoundedCornerShape(12.dp))
            .padding(8.dp)
        ) {
          Canvas(modifier = Modifier.size(164.dp)) {
            val moduleSize = size.minDimension / matrix.size
            val drawn = moduleSize * matrix.size
            val origin = Offset((size.width - drawn) / 2f, (size.height - drawn) / 2f)
            for (y in 0 until matrix.size) {
              for (x in 0 until matrix.size) {
                if (matrix.module(x, y)) {
                  drawRect(
                    color = QrModule,
                    topLeft = Offset(origin.x + x * moduleSize, origin.y + y * moduleSize),
                    size = Size(moduleSize, moduleSize)
                  )
                }
              }
            }
          }
        }

        Text(
          text = stringResource(R.string.addons_remote_hint, url),
          color = QrAccent,
          fontSize = 12.sp,
          fontWeight = FontWeight.Medium,
          modifier = Modifier.fillMaxWidth()
        )
      }

      url != null -> {
        // QR non generabile: l'utente digita l'indirizzo a mano.
        Text(
          text = stringResource(R.string.addons_remote_hint, url),
          color = QrAccent,
          fontSize = 13.sp,
          fontWeight = FontWeight.Medium,
          modifier = Modifier.fillMaxWidth()
        )
      }

      else -> {
        Box(
          modifier = Modifier
            .width(180.dp)
            .height(180.dp)
            .background(QrSurface, RoundedCornerShape(12.dp))
            .padding(20.dp),
          contentAlignment = Alignment.Center
        ) {
          Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            Icon(
              imageVector = Icons.Default.Close,
              contentDescription = null,
              tint = Color(0xFF475569),
              modifier = Modifier.size(40.dp)
            )
            Text(
              text = fallbackMessage ?: stringResource(R.string.addons_remote_offline),
              color = Color(0xFF475569),
              fontSize = 12.sp,
              fontWeight = FontWeight.Medium,
              modifier = Modifier.fillMaxWidth()
            )
          }
        }
      }
    }
  }
}

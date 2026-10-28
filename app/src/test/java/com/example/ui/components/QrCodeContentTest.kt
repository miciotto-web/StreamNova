package com.example.ui.components

import com.example.data.remote.simkl.SimklAuthFlow
import com.example.data.remote.simkl.SimklLoginPrompt
import com.google.zxing.BinaryBitmap
import com.google.zxing.LuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Il QR del pannello SIMKL deve contenere **il codice di quella richiesta**: qui viene
 * riletto con lo stesso motore che userebbe un telefono, sulla matrice che [QrCodeImage]
 * disegna davvero. Senza questa verifica un QR "bello" ma con un contenuto sbagliato (o
 * fisso) passerebbe come corretto.
 */
class QrCodeContentTest {

  private companion object {
    /** Quattro moduli di bianco attorno al simbolo: è la quiet zone che un lettore cerca. */
    const val QUIET_ZONE = 4

    /** Un modulo = 4 pixel: è la scala a cui un lettore reale incontra il QR sullo schermo. */
    const val SCALE = 4
  }

  /** Sorgente di luminanza costruita dalla matrice: nero sui moduli scuri, bianco altrove. */
  private class MatrixLuminanceSource(private val qr: QrCodeMatrix) :
    LuminanceSource(qr.size * SCALE + QUIET_ZONE * 2, qr.size * SCALE + QUIET_ZONE * 2) {

    override fun getRow(y: Int, row: ByteArray?): ByteArray {
      val out = if (row != null && row.size >= getWidth()) row else ByteArray(getWidth())
      for (x in 0 until getWidth()) out[x] = luminance(x, y)
      return out
    }

    override fun getMatrix(): ByteArray {
      val out = ByteArray(getWidth() * getHeight())
      for (y in 0 until getHeight()) {
        for (x in 0 until getWidth()) out[y * getWidth() + x] = luminance(x, y)
      }
      return out
    }

    private fun luminance(x: Int, y: Int): Byte {
      val moduleX = (x - QUIET_ZONE) / SCALE
      val moduleY = (y - QUIET_ZONE) / SCALE
      val insideMatrix = x >= QUIET_ZONE && y >= QUIET_ZONE &&
        x - QUIET_ZONE < qr.size * SCALE && y - QUIET_ZONE < qr.size * SCALE
      val dark = insideMatrix && qr.module(moduleX, moduleY)
      return (if (dark) 0 else 255).toByte()
    }
  }

  private fun encode(payload: String): QrCodeMatrix = runBlocking {
    val matrix = encodeQrCode(payload)
    assertNotNull("ZXing non ha prodotto alcuna matrice per «$payload»", matrix)
    matrix!!
  }

  /** Rilegge il QR come farebbe un telefono; se non ci riesce il test deve dire perché. */
  private fun readBack(matrix: QrCodeMatrix): String = try {
    QRCodeReader().decode(BinaryBitmap(HybridBinarizer(MatrixLuminanceSource(matrix)))).text
  } catch (error: Throwable) {
    throw AssertionError("QR non rileggibile (${error.javaClass.simpleName}: ${error.message})", error)
  }

  private fun prompt(
    flow: SimklAuthFlow = SimklAuthFlow.V2_DEVICE,
    userCode: String = "BDWP-HQPK",
    qrPayload: String
  ) = SimklLoginPrompt(
    flow = flow,
    userCode = userCode,
    verificationUri = "https://simkl.com/pin",
    qrPayload = qrPayload,
    deviceCode = "device-code",
    expiresAtEpochMs = 900_000L,
    intervalSeconds = 5L
  )

  @Test
  fun ilQrSiRileggeNellUrlPrecompilatoDelCodice() {
    val payload = "https://simkl.com/pin?user_code=BDWP-HQPK"
    val matrix = encode(payload)

    assertEquals("il QR deve contenere esattamente l'URL precompilato", payload, readBack(matrix))
    assertTrue("matrice quadrata e di dimensione plausibile", matrix.size >= 21)
  }

  @Test
  fun ilQrDelFlussoV1SiRileggeNellIndirizzoDiVerifica() {
    // In V1 il codice non è precompilato: il QR apre la sola pagina, il codice si digita.
    val payload = "https://simkl.com/pin"
    val matrix = encode(payload)

    assertEquals(payload, readBack(matrix))
    assertNotEquals(
      "l'indirizzo da solo non può contenere anche il codice",
      payload,
      readBack(encode("https://simkl.com/pin?user_code=BDWP-HQPK"))
    )
  }

  @Test
  fun ilContenutoDelQrSegueIlPromptERestaInsiemeAlCodice() {
    // V2 con `verification_uri_complete`: il QR contiene il codice già inserito.
    val precompilato = prompt(qrPayload = "https://simkl.com/pin?user_code=BDWP-HQPK")
    assertTrue(precompilato.qrPrefillsCode)
    assertEquals(
      "https://simkl.com/pin?user_code=BDWP-HQPK",
      readBack(encode(precompilato.qrPayload))
    )

    // V2 senza il campo completo: stesso indirizzo di verifica, codice da digitare.
    val daDigitare = prompt(qrPayload = "https://simkl.com/pin")
    assertTrue(!daDigitare.qrPrefillsCode)
    assertEquals("https://simkl.com/pin", readBack(encode(daDigitare.qrPayload)))

    // Due codici diversi non possono produrre lo stesso QR.
    val altro = prompt(userCode = "ZXCV-BNML", qrPayload = "https://simkl.com/pin?user_code=ZXCV-BNML")
    assertNotEquals(
      "il QR deve cambiare insieme al codice",
      readBack(encode(altro.qrPayload)),
      readBack(encode(precompilato.qrPayload))
    )
  }

  @Test
  fun unContenutoNonCodificabileNonProduceUnQr() {
    // Nessuna pagina bianca spacciata per QR: senza matrice la card resta senza disegno e
    // il pannello mostra il codice a schermo.
    assertEquals(null, runBlocking { encodeQrCode("") })
  }
}

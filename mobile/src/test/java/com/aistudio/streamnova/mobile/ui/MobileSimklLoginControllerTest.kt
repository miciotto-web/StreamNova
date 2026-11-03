package com.aistudio.streamnova.mobile.ui

import com.example.data.remote.simkl.SimklAuthException
import com.example.data.remote.simkl.SimklClientIdMissingException
import com.example.data.remote.simkl.SimklClientRejectedException
import com.example.data.remote.simkl.SimklInvalidResponseException
import com.example.data.remote.simkl.SimklLoginExpiredException
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Test

class MobileSimklLoginControllerTest {

  @Test
  fun clientIdAssenteNonDisponibile() {
    assertEquals(
      MobileSimklLoginError.NOT_AVAILABLE,
      simklLoginErrorOf(SimklClientIdMissingException("missing"))
    )
  }

  @Test
  fun clientRifiutatoHaUnMotivoDistintoDallaConfigurazione() {
    assertEquals(
      MobileSimklLoginError.CLIENT_REJECTED,
      simklLoginErrorOf(SimklClientRejectedException("rejected"))
    )
  }

  @Test
  fun rispostaInvalidaECodiceScadutoSonoDistinti() {
    assertEquals(
      MobileSimklLoginError.INVALID_RESPONSE,
      simklLoginErrorOf(SimklInvalidResponseException("invalid"))
    )
    assertEquals(
      MobileSimklLoginError.EXPIRED,
      simklLoginErrorOf(SimklLoginExpiredException("expired"))
    )
  }

  @Test
  fun erroreDiRete() {
    assertEquals(
      MobileSimklLoginError.NETWORK,
      simklLoginErrorOf(IOException("offline"))
    )
  }

  @Test
  fun erroreGenericoNonVieneAttribuitoAllaRete() {
    assertEquals(
      MobileSimklLoginError.GENERIC,
      simklLoginErrorOf(IllegalStateException("boom"))
    )
  }

  @Test
  fun erroreDiAutorizzazione() {
    assertEquals(
      MobileSimklLoginError.AUTH,
      simklLoginErrorOf(SimklAuthException("denied"))
    )
  }
}

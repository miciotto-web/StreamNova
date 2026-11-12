package com.example

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Riavvio controllato dell'app, usato dal cambio profilo.
 *
 * Perché non basta `startActivity` + kill: la nuova Activity nascerebbe nello STESSO
 * processo, con Room e DataStore ancora legati al profilo precedente, e verrebbe uccisa
 * insieme al processo. Perché non basta un allarme: Android blocca l'avvio di Activity
 * dal background ("Abort background activity starts").
 *
 * Soluzione: si avvia [RestartProxyActivity] in un PROCESSO SEPARATO e visibile in primo
 * piano; poi si termina il processo principale. Il proxy, che è già a schermo e quindi
 * autorizzato ad avviare Activity, lancia MainActivity quando il vecchio processo è
 * morto: l'Application viene ricreata e legge il profilo appena salvato.
 */
object AppRestarter {

  /**
   * Extra del launch intent che evita di riproporre il picker subito dopo un cambio
   * profilo esplicito. Un avvio normale dal launcher non ce l'ha.
   */
  const val EXTRA_SKIP_PROFILE_GATE = "com.example.streamnova.extra.SKIP_PROFILE_GATE"

  private const val TAG = "AppRestarter"

  fun restart(context: Context) {
    val appContext = context.applicationContext
    val proxyIntent = Intent(appContext, RestartProxyActivity::class.java).apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      putExtra(EXTRA_SKIP_PROFILE_GATE, true)
    }
    // Il proxy deve essere visibile PRIMA che il processo principale muoia: è la sua
    // finestra in primo piano che autorizza il successivo avvio di MainActivity.
    appContext.startActivity(proxyIntent)

    Handler(Looper.getMainLooper()).postDelayed({
      Log.i(TAG, "Termino il processo principale per caricare il nuovo profilo")
      Runtime.getRuntime().exit(0)
    }, 350L)
  }
}

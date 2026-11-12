package com.example

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * Activity "trampolino" del cambio profilo.
 *
 * Vive in un processo separato (`:profile_restart`, vedi manifest) e viene mostrata
 * PRIMA che il processo principale termini. Quando questo è morto, lancia MainActivity:
 * essendo la sua finestra in primo piano, l'avvio non viene bloccato dalle restrizioni
 * Android sulle background activity. Il nuovo processo principale ricrea
 * [StreamNovaApplication], che apre il database del profilo appena scelto.
 *
 * L'interfaccia è costruita a mano (senza Compose): serve solo un'attesa breve e non
 * deve inizializzare repository o DataStore.
 */
class RestartProxyActivity : Activity() {

  private val handler = Handler(Looper.getMainLooper())
  private val relaunch = Runnable { launchMainActivity() }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.setBackgroundDrawable(ColorDrawable(Color.BLACK))

    val density = resources.displayMetrics.density
    val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
    val column = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER
    }
    column.addView(
      ProgressBar(this).apply { isIndeterminate = true },
      LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
      )
    )
    column.addView(
      TextView(this).apply {
        text = getString(R.string.profiles_switching)
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        gravity = Gravity.CENTER
      },
      LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
      ).apply { topMargin = (20 * density).toInt() }
    )
    root.addView(
      column,
      FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT
      )
    )
    setContentView(root)
  }

  override fun onResume() {
    super.onResume()
    // Attesa sufficiente perché il processo principale (terminato da AppRestarter)
    // sia davvero uscito prima di avviare la nuova istanza.
    handler.removeCallbacks(relaunch)
    handler.postDelayed(relaunch, RELAUNCH_DELAY_MS)
  }

  override fun onPause() {
    super.onPause()
    handler.removeCallbacks(relaunch)
  }

  @Deprecated("Il BACK durante il cambio profilo non deve annullare l'operazione")
  override fun onBackPressed() {
    // No-op intenzionale.
  }

  private fun launchMainActivity() {
    val intent = Intent(this, MainActivity::class.java).apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
      putExtra(AppRestarter.EXTRA_SKIP_PROFILE_GATE, true)
    }
    startActivity(intent)
    finish()
  }

  private companion object {
    const val RELAUNCH_DELAY_MS = 1_000L
  }
}

package com.aistudio.streamnova.mobile

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aistudio.streamnova.mobile.ui.MobileApp
import com.aistudio.streamnova.mobile.ui.MobileOrientationController
import com.example.data.prefs.AppSettingsRepository
import com.example.ui.theme.MyApplicationTheme
import java.util.Locale

class MainActivity : ComponentActivity() {

  /** Lingua con cui è stato creato il base context di questa istanza. */
  private var attachedLanguage: String = "it"

  /** Unico punto di scrittura dell'orientamento (policy Fase 8.1). */
  private lateinit var orientationController: MobileOrientationController

  override fun attachBaseContext(newBase: Context) {
    // Localizza l'Activity stessa (non solo la composizione): anche i Dialog
    // Compose, che nascono in una finestra separata, ereditano questa lingua.
    attachedLanguage = AppSettingsRepository.appLanguage.value
    super.attachBaseContext(newBase.withAppLanguage(attachedLanguage))
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // Policy di orientamento: telefono portrait fuori dal player e landscape
    // durante la riproduzione; tablet sempre landscape; multi-window senza
    // forzature. Applicata prima di setContent per ridurre il mismatch iniziale.
    orientationController = MobileOrientationController(this)
    orientationController.applyBasePolicy()
    enableEdgeToEdge(
      statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
      navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
    )
    setContent {
      val appLanguage by AppSettingsRepository.appLanguage.collectAsStateWithLifecycle()
      val context = LocalContext.current
      // La configurazione di partenza è quella corrente della finestra: cambia a
      // ogni rotazione/ridimensionamento anche se il manifest evita la
      // ricreazione dell'Activity. Non va "fotografata" una sola volta, altrimenti
      // LocalConfiguration resterebbe stale (es. dettaglio in landscape).
      val baseConfiguration = LocalConfiguration.current
      val locale = remember(appLanguage) { Locale.forLanguageTag(appLanguage) }
      val localizedConfiguration = remember(baseConfiguration, locale) {
        Configuration(baseConfiguration).apply {
          setLocale(locale)
          setLayoutDirection(locale)
        }
      }
      val localizedContext = remember(context, localizedConfiguration) {
        context.createConfigurationContext(localizedConfiguration)
      }

      // Il cambio lingua ricrea l'Activity: le finestre già aperte (dialog,
      // toast, popup) devono ripartire con il nuovo base context localizzato.
      LaunchedEffect(appLanguage) {
        if (appLanguage != attachedLanguage) recreate()
      }

      CompositionLocalProvider(
        LocalContext provides localizedContext,
        LocalConfiguration provides localizedConfiguration
      ) {
        MyApplicationTheme {
          MobileApp(orientationController = orientationController)
        }
      }
    }
  }

  override fun onConfigurationChanged(newConfig: Configuration) {
    super.onConfigurationChanged(newConfig)
    // Rotazione, ridimensionamento, fold/unfold: rivaluta la policy sul display
    // corrente. La guardia nel controller evita richieste ridondanti.
    orientationController.onConfigurationChanged()
  }

  override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
    super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
    // Split-screen/freeform: la policy passa a UNSPECIFIED e lascia che il
    // layout segua i vincoli della finestra.
    orientationController.onMultiWindowModeChanged()
  }
}

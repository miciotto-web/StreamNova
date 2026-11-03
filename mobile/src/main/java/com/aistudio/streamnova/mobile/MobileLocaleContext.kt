package com.aistudio.streamnova.mobile

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Restituisce un Context con la lingua dell'app applicata alle risorse.
 *
 * Serve anche ai `Dialog` di Compose: creano una nuova finestra il cui Context
 * deriva dall'Activity, non dal `LocalContext` fornito in composizione. Localizzare
 * solo la composizione lascerebbe i testi dei dialog nella lingua di sistema
 * (difetto verificato in fase di smoke test), quindi la lingua viene applicata al
 * base context dell'Activity e la Activity viene ricreata al cambio.
 */
fun Context.withAppLanguage(language: String): Context {
  val locale = Locale.forLanguageTag(language.ifBlank { "it" })
  val configuration = Configuration(resources.configuration).apply {
    setLocale(locale)
    setLayoutDirection(locale)
  }
  return createConfigurationContext(configuration)
}

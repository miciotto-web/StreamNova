package com.example.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

/**
 * Verifica il helper [findActivity] usato dal PlayerScreen per applicare
 * FLAG_KEEP_SCREEN_ON alla finestra dell'Activity durante la riproduzione: la
 * risoluzione deve funzionare anche quando il [Context] è un wrapper (ciò che
 * accade realmente su Fire TV/Android TV) e deve fallire in sicurezza su un
 * contesto non-Activity (es. Application).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PlayerScreenFindActivityTest {

  @Test
  fun findActivityUnwrapsNestedContextWrappers() {
    val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    val wrapped: Context = ContextWrapper(ContextWrapper(activity))

    assertEquals(activity, wrapped.findActivity())
  }

  @Test
  fun findActivityReturnsActivityItself() {
    val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

    assertEquals(activity, activity.findActivity())
  }

  @Test
  fun findActivityReturnsNullForNonActivityContext() {
    val appContext: Context = ApplicationProvider.getApplicationContext()

    assertNull(appContext.findActivity())
  }
}
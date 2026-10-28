plugins {
  alias(libs.plugins.android.library)
}

android {
  namespace = "com.example.core.player"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    minSdk = 24
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }

  testOptions {
    unitTests {
      isIncludeAndroidResources = true
      isReturnDefaultValues = true
    }
  }
}

// Le AAR locali NuvioTV sostituiscono i moduli Media3 stock: la stessa esclusione
// va applicata anche qui, altrimenti il modulo player risolverebbe le classi stock
// al posto delle fork usate dall'app TV.
configurations.all {
  exclude(group = "androidx.media3", module = "media3-exoplayer")
  exclude(group = "androidx.media3", module = "media3-common")
  exclude(group = "androidx.media3", module = "media3-datasource")
  exclude(group = "androidx.media3", module = "media3-datasource-okhttp")
  exclude(group = "androidx.media3", module = "media3-exoplayer-hls")
  exclude(group = "androidx.media3", module = "media3-extractor")
  exclude(group = "androidx.media3", module = "media3-ui")
}

dependencies {
  api(project(":core:model"))
  api(project(":core:data"))

  // NuvioTV Media3 forked AARs: esposte con `api` perché l'app TV costruisce
  // ancora direttamente ExoPlayer/PlayerView e i suoi controlli.
  api(files("libs/lib-common-release.aar"))
  api(files("libs/lib-datasource-release.aar"))
  api(files("libs/lib-datasource-okhttp-release.aar"))
  api(files("libs/lib-exoplayer-release.aar"))
  api(files("libs/lib-exoplayer-hls-release.aar"))
  api(files("libs/lib-extractor-release.aar"))
  api(files("libs/lib-nuvio-engine-android-0.1.2.aar"))
  api(files("libs/lib-ui-release.aar"))
  api(files("libs/lib-decoder-ffmpeg-release.aar"))
  api(files("libs/lib-decoder-av1-release.aar"))
  api(files("libs/lib-decoder-iamf-release.aar"))
  api(files("libs/lib-decoder-mpegh-release.aar"))
  api(files("libs/nextlib-mediainfo-local.aar"))

  // Moduli Media3 stock coerenti con le fork (decoder/database/container).
  api(libs.androidx.media3.decoder)
  api(libs.androidx.media3.database)
  api(libs.androidx.media3.container)

  // Dipendenze transitive richieste dalle AAR fork, non incluse al loro interno.
  api("com.google.guava:guava:33.3.1-android")
  api("androidx.annotation:annotation-experimental:1.3.1")

  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.okhttp)

  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.mockwebserver)
  testImplementation(libs.robolectric)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
}

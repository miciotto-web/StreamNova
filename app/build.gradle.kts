import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy
import java.security.KeyStore
import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.roborazzi)
  alias(libs.plugins.secrets)
  alias(libs.plugins.google.services)
}

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.aistudio.streamnova.tvapp"
    minSdk = 24
    targetSdk = 36
    versionCode = 1
    versionName = "0.1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    // TMDB API key is never hardcoded here: it would be committed and published.
    // Resolution order: environment variable, then local.properties (git-ignored),
    // then empty string. With no key the app behaves exactly as before, the TMDB
    // catalog stays empty instead of failing. Note that a key shipped in the APK is
    // extractable by anyone: this only keeps it out of the public repository.
    val localProps = Properties().apply {
      val localFile = rootProject.file("local.properties")
      if (localFile.exists()) localFile.inputStream().use { load(it) }
    }
    val tmdbKey = (System.getenv("TMDB_API_KEY")
      ?: localProps.getProperty("TMDB_API_KEY")
      ?: "").replace("\"", "")
    buildConfigField("String", "ENV_TMDB_API_KEY", "\"$tmdbKey\"")
  }

  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      val keystoreFile = file(keystorePath)
      val releaseAlias = "upload"
      val storeSecret = System.getenv("STORE_PASSWORD")
      val keySecret = System.getenv("KEY_PASSWORD")

      // Fail fast: only enforced for release tasks, so debug builds and unit tests
      // keep working without signing credentials. Never fallback to debug.keystore:
      // a debug-signed artifact must never be passed off as a production release.
      val isReleaseBuild = gradle.startParameter.taskNames.any { it.contains("release", ignoreCase = true) }
      if (isReleaseBuild) {
        val problems = mutableListOf<String>()
        if (!keystoreFile.exists()) problems += "keystore not found at $keystorePath (set KEYSTORE_PATH or place it at the project root)"
        if (storeSecret.isNullOrEmpty()) problems += "STORE_PASSWORD is not set"
        if (keySecret.isNullOrEmpty()) problems += "KEY_PASSWORD is not set"
        if (problems.isNotEmpty()) {
          throw GradleException(
            "StreamNova release signing is not configured:\n" +
              problems.joinToString("\n") { "  - $it" }
          )
        }
        // Verify the alias really exists before handing the file to AGP,
        // so a wrong alias fails here instead of inside the packaging task.
        val probe = KeyStore.getInstance("PKCS12")
        probe.load(keystoreFile.inputStream(), storeSecret.toCharArray())
        if (!probe.containsAlias(releaseAlias)) {
          throw GradleException(
            "Release signing alias '$releaseAlias' not found in the keystore at $keystorePath"
          )
        }
      }

      storeFile = keystoreFile
      storePassword = storeSecret
      keyAlias = releaseAlias
      keyPassword = keySecret
    }
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("release")
    }
    debug { signingConfig = signingConfigs.getByName("debugConfig") }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions {
    unitTests {
      isIncludeAndroidResources = true
      // Permette ai test JVM di usare android.util.Log (usato dagli estrattori
      // per il tracing in Logcat) senza Robolectric.
      isReturnDefaultValues = true
    }
  }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

base {
  archivesName.set("StreamNova")
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
  ignoreList.add("FIREBASE_APPCHECK_DEBUG_TOKEN")
}

googleServices { missingGoogleServicesStrategy = MissingGoogleServicesStrategy.WARN }

// Exclude stock Media3 modules replaced by NuvioTV forked AARs
configurations.all {
    exclude(group = "androidx.media3", module = "media3-exoplayer")
    exclude(group = "androidx.media3", module = "media3-common")
    exclude(group = "androidx.media3", module = "media3-datasource")
    exclude(group = "androidx.media3", module = "media3-datasource-okhttp")
    exclude(group = "androidx.media3", module = "media3-exoplayer-hls")
    exclude(group = "androidx.media3", module = "media3-extractor")
    exclude(group = "androidx.media3", module = "media3-ui")
}

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(platform(libs.firebase.bom))
  // implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  // implementation(libs.androidx.camera.camera2)
  // implementation(libs.androidx.camera.core)
  // implementation(libs.androidx.camera.lifecycle)
  // implementation(libs.androidx.camera.view)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.coil.compose)

  // NuvioTV Media3 forked AARs (replace stock Media3)
  implementation(files("libs/lib-common-release.aar"))
  implementation(files("libs/lib-datasource-release.aar"))
  implementation(files("libs/lib-datasource-okhttp-release.aar"))
  implementation(files("libs/lib-exoplayer-release.aar"))
  implementation(files("libs/lib-exoplayer-hls-release.aar"))
  implementation(files("libs/lib-extractor-release.aar"))
  implementation(files("libs/lib-nuvio-engine-android-0.1.2.aar"))
  implementation(files("libs/lib-ui-release.aar"))
  implementation(files("libs/lib-decoder-ffmpeg-release.aar"))
  implementation(files("libs/lib-decoder-av1-release.aar"))
  implementation(files("libs/lib-decoder-iamf-release.aar"))
  implementation(files("libs/lib-decoder-mpegh-release.aar"))
  implementation(files("libs/nextlib-mediainfo-local.aar"))

  // Stock Media3 modules from Maven (coherent 1.8.0)
  implementation(libs.androidx.media3.decoder)
  implementation(libs.androidx.media3.database)
  implementation(libs.androidx.media3.container)

  // AndroidX RecyclerView required at runtime by Media3 UI (PlayerView)
  implementation(libs.androidx.recyclerview)

  // Transitive dependencies required by forked local AARs (not bundled in AARs)
  implementation("com.google.guava:guava:33.3.1-android")
  implementation("androidx.annotation:annotation-experimental:1.3.1")
  // Generazione QR Code per la configurazione addon remota (AddonSyncServer)
  implementation("com.google.zxing:core:3.5.3")
  implementation(libs.converter.moshi)
  // Firebase non e' usato da StreamNova: nessun sorgente lo referenzia e senza
  // google-services.json non esiste auto-inizializzazione. Le dipendenze restano
  // commentate, non eliminate, per un eventuale uso futuro.
  // implementation(libs.firebase.ai)
  // Uncomment to use Firestore:
  // implementation(libs.firebase.firestore)

  // Uncomment ALL FOUR of the following dependencies together to use Firebase Auth and Google
  // Sign-In via Credential Manager:
  // implementation(libs.firebase.auth)
  // implementation(libs.androidx.credentials)
  // implementation(libs.androidx.credentials.play.services)
  // implementation(libs.googleid)
  // App Check e' richiesto insieme a Firebase AI Logic e va riattivato solo se si usano:
  // implementation(libs.firebase.appcheck.recaptcha)
  // implementation(libs.firebase.appcheck.debug)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.logging.interceptor)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  // implementation(libs.play.services.location)
  implementation(libs.retrofit)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
  "ksp"(libs.moshi.kotlin.codegen)
}

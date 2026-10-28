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

// Il logger va preso qui: dentro i blocchi DSL annidati (`defaultConfig`, `signingConfigs`)
// non c'è un receiver `Project` su cui risolverlo.
val buildLogger = logger

/**
 * Vero per un Client ID SIMKL che può davvero essere usato. Regola gemella di
 * `SimklRepository.sanitizeSimklClientId`, che resta l'unica applicata a runtime: qui
 * serve solo a dire in fase di build, prima di installare l'APK, che il login non potrà
 * funzionare. I segnaposto sono quelli di `.env.example`.
 */
val isUsableSimklClientId: (String) -> Boolean = { raw ->
  val value = raw.trim().trim('"')
  val upper = value.uppercase()
  value.isNotEmpty() &&
    !upper.startsWith("DEFAULT_") &&
    !upper.startsWith("YOUR_") &&
    upper != "SIMKL_CLIENT_ID"
}

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.aistudio.streamnova.tvapp"
    minSdk = 24
    targetSdk = 36
    // Release stabile ufficiale StreamNova V1.0.
    // Ogni release successiva deve incrementare versionCode.
    versionCode = 1005
    versionName = "1.0.3"

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

    // SIMKL client ID: developer-side configuration only. It is never asked to the
    // user, so it must arrive with the build: environment variable, then
    // local.properties, then .env (the file documented in .env.example). All three
    // are git-ignored, so no credential enters the repository. .env also feeds
    // BuildConfig.SIMKL_CLIENT_ID through the secrets plugin below; reading it here
    // as well keeps the two channels in agreement, instead of leaving the plugin's
    // placeholder as the only value when .env is missing.
    val dotEnvProps = Properties().apply {
      val dotEnvFile = rootProject.file(".env")
      if (dotEnvFile.exists()) dotEnvFile.inputStream().use { load(it) }
    }
    val simklClientId = (System.getenv("SIMKL_CLIENT_ID")
      ?: localProps.getProperty("SIMKL_CLIENT_ID")
      ?: dotEnvProps.getProperty("SIMKL_CLIENT_ID")
      ?: "").replace("\"", "")
    buildConfigField("String", "ENV_SIMKL_CLIENT_ID", "\"$simklClientId\"")

    // A build without a usable client ID installs and runs, but "Collega account
    // SIMKL" can only answer "non disponibile": no code is ever requested. That
    // silent hole shipped once, so the build says it out loud instead of leaving a
    // panel that looks broken on device (simkl_client_id_report.md). Placeholders
    // from .env.example count as missing; the authoritative check, which also runs
    // at runtime, is SimklRepository.sanitizeSimklClientId.
    // Testo senza accenti: questo messaggio viene stampato su console Windows, dove la
    // code page può non essere UTF-8.
    if (!isUsableSimklClientId(simklClientId)) {
      buildLogger.warn(
        """
        |SIMKL_CLIENT_ID is not set for this build: SIMKL login will only answer
        |"Il collegamento a SIMKL non e' disponibile in questa build di StreamNova."
        |Set it before building, in one of these places (all git-ignored, never committed):
        |  - local.properties:  SIMKL_CLIENT_ID=<id from https://simkl.com/settings/developer/>
        |  - .env:              SIMKL_CLIENT_ID=<id>    (copy .env.example first)
        |  - environment:       export SIMKL_CLIENT_ID=<id>
        |                       (Windows PowerShell: ${'$'}env:SIMKL_CLIENT_ID="<id>")
        |The ID is a developer credential: it is never invented, asked to the user, or committed.
        """.trimMargin()
      )
    }
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
      enableV1Signing = true
      enableV2Signing = true
    }
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
      enableV1Signing = true
      enableV2Signing = true
    }
  }

  // ABI splits: every device only downloads/installs the native libraries it can
  // actually load, instead of the ~90 MB of lib/*/*.so carried by a universal APK.
  // All the ABIs already shipped by the project are kept, so no device loses
  // support: 32-bit ARM (older Fire TV Stick / Android TV boxes), 64-bit ARM
  // (current boxes and sticks) and x86/x86_64 (emulator and the few Intel-based
  // Android TV devices).
  //
  // isUniversalApk = true adds a fifth artifact that carries every ABI. It exists
  // to reach installations still running 1.0.0: that updater predates the
  // ABI-aware selection and simply takes the first APK of the release (or any file
  // literally named app-release.apk), which on the published 1.0.1 release is the
  // arm64 file — uninstallable on a 32-bit-only device such as a Fire TV Stick.
  // Publishing the universal artifact under the exact name `app-release.apk` gives
  // those clients a single file that every device can install; updaters from 1.0.1
  // onwards ignore it (it does not declare an architecture) and keep using their
  // own split. Revertible with this one line once 1.0.0 installations are gone.
  splits {
    abi {
      isEnable = true
      reset()
      include("x86", "x86_64", "armeabi-v7a", "arm64-v8a")
      isUniversalApk = true
    }
  }

  packaging {
    jniLibs {
      // Store lib/*/*.so compressed (android:extractNativeLibs="true") instead of
      // the AGP default of keeping them uncompressed inside the APK. The .so files
      // are the dominant part of each split (~23 MB of 28 MB for arm64-v8a) and
      // they deflate to ~45% of their size, i.e. the download shrinks from
      // 29.4 MB to ~15.6 MB.
      //
      // Trade-off, deliberate and reversible with this one line: the runtime
      // extracts the libraries into the app data directory, so the APK is much
      // smaller but the on-device footprint is larger (APK + extracted copies)
      // and the installation takes slightly longer. Nothing about loading,
      // MediaCodec, or JNI changes.
      useLegacyPackaging = true
    }
  }

  buildTypes {
    release {
      isCrunchPngs = true
      // R8: removes the unused code reachable from the release runtime classpath.
      // This is the single biggest dex win (material-icons-extended, unused Media3
      // renderers/extractors, kotlin-reflect and the Moshi reflection adapter are
      // all only reachable through shrinkable code paths).
      isMinifyEnabled = true
      // Removes the resources that no reachable code/manifest entry references.
      // Requires minification to be enabled.
      isShrinkResources = true
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
  testImplementation(libs.mockwebserver)
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


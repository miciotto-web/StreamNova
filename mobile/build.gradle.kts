import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
}

val buildLogger = logger

/**
 * StreamNova Mobile è un'app separata: stesso core condiviso della TV, ma
 * application ID, canale di distribuzione e firma indipendenti.
 *
 * Le credenziali non sono mai nel sorgente: arrivano da variabili d'ambiente,
 * local.properties o .env, esattamente come per la build TV.
 */
fun readBuildSetting(name: String, localProps: Properties, dotEnvProps: Properties): String =
  (System.getenv(name)
    ?: localProps.getProperty(name)
    ?: dotEnvProps.getProperty(name)
    ?: "").replace("\"", "")

android {
  namespace = "com.aistudio.streamnova.mobile"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.aistudio.streamnova.mobile"
    minSdk = 24
    targetSdk = 36
    versionCode = 2
    versionName = "1.1.0"

    val localProps = Properties().apply {
      val localFile = rootProject.file("local.properties")
      if (localFile.exists()) localFile.inputStream().use { load(it) }
    }
    val dotEnvProps = Properties().apply {
      val dotEnvFile = rootProject.file(".env")
      if (dotEnvFile.exists()) dotEnvFile.inputStream().use { load(it) }
    }

    buildConfigField("String", "TMDB_API_KEY", "\"${dotEnvProps.getProperty("TMDB_API_KEY", "")}\"")
    buildConfigField("String", "ENV_TMDB_API_KEY", "\"${readBuildSetting("TMDB_API_KEY", localProps, dotEnvProps)}\"")
    buildConfigField("String", "SIMKL_CLIENT_ID", "\"${dotEnvProps.getProperty("SIMKL_CLIENT_ID", "")}\"")
    buildConfigField("String", "ENV_SIMKL_CLIENT_ID", "\"${readBuildSetting("SIMKL_CLIENT_ID", localProps, dotEnvProps)}\"")

    if (readBuildSetting("TMDB_API_KEY", localProps, dotEnvProps).isBlank()) {
      buildLogger.warn(
        "Mobile: TMDB_API_KEY non configurata. Catalogo e ricerca remota resteranno vuoti " +
          "finché la chiave non viene impostata in local.properties, .env o ambiente."
      )
    }
  }

  signingConfigs {
    create("mobileRelease") {
      val keystorePath = System.getenv("MOBILE_KEYSTORE_PATH") ?: "${rootDir}/mobile-upload-key.jks"
      val keystoreFile = file(keystorePath)
      val storeSecret = System.getenv("MOBILE_STORE_PASSWORD")
      val keySecret = System.getenv("MOBILE_KEY_PASSWORD")

      // Fail fast solo per le build release: debug e test non richiedono credenziali.
      // La firma release mobile va definita e verificata prima della pubblicazione.
      val isReleaseBuild = gradle.startParameter.taskNames.any { it.contains("release", ignoreCase = true) }
      if (isReleaseBuild) {
        val problems = mutableListOf<String>()
        if (!keystoreFile.exists()) problems += "keystore not found at $keystorePath (set MOBILE_KEYSTORE_PATH)"
        if (storeSecret.isNullOrEmpty()) problems += "MOBILE_STORE_PASSWORD is not set"
        if (keySecret.isNullOrEmpty()) problems += "MOBILE_KEY_PASSWORD is not set"
        if (problems.isNotEmpty()) {
          throw GradleException(
            "StreamNova Mobile release signing is not configured:\n" +
              problems.joinToString("\n") { "  - $it" }
          )
        }
      }

      storeFile = keystoreFile
      storePassword = storeSecret
      keyAlias = "mobile"
      keyPassword = keySecret
      enableV1Signing = true
      enableV2Signing = true
    }
  }

  buildTypes {
    debug {
      applicationIdSuffix = ""
    }
    release {
      isMinifyEnabled = false
      isShrinkResources = false
      signingConfig = signingConfigs.getByName("mobileRelease")
    }
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
      // I test JVM del modulo toccano classi condivise del core che usano
      // android.util.Log (StremioAddonRepository, UserProviderBindingStore):
      // i metodi non strumentati restituiscono i valori di default invece di
      // lanciare "not mocked". Stessa scelta di :core:data.
      isReturnDefaultValues = true
    }
  }

  packaging {
    jniLibs {
      // Stessa scelta della TV: APK più piccolo, estrazione nativa al primo avvio.
      useLegacyPackaging = true
    }
  }
}

// Le AAR fork NuvioTV sostituiscono i moduli Media3 stock anche nell'app mobile.
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
  testImplementation(libs.junit)
  // Test JVM del flusso Add-on: ViewModel con Main dispatcher sostituito.
  testImplementation(libs.kotlinx.coroutines.test)

  implementation(project(":core:model"))
  implementation(project(":core:designsystem"))
  implementation(project(":core:data"))
  implementation(project(":core:player"))

  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.recyclerview)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.coil.compose)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)

  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
}

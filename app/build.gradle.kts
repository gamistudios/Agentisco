// import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.roborazzi)
  // alias(libs.plugins.secrets)
  // alias(libs.plugins.google.services)
}

android {
  namespace = "com.agentisco"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.agentisco"
    minSdk = 24
    // targetSdk must stay < 29: Android SELinux denies execve of binaries in
    // app-writable storage for apps targeting SDK 29+, which the proot Debian
    // terminal requires. Termux pins targetSdk 28 for the same reason.
    targetSdk = 28
    versionCode = 1
    versionName = "1.0"

    ndk {
      abiFilters += listOf("arm64-v8a", "armeabi-v7a")
    }

    externalNativeBuild {
      cmake {
        // Builds the PTY JNI helper (libtermux.so) vendored from termux-app.
        cppFlags += ""
        arguments += listOf("-DANDROID_STL=none")
      }
    }

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  // Release signing credentials come from environment variables (set by CI from
  // repository secrets: AGENTISCO_KEYSTORE_PATH — itself decoded at runtime from
  // AGENTISCO_KEYSTORE_BASE64 — plus AGENTISCO_KEYSTORE_PASSWORD,
  // AGENTISCO_KEY_ALIAS, AGENTISCO_KEY_PASSWORD), falling back to the local,
  // git-ignored .env file so `./gradlew assembleRelease` signs on a dev machine
  // without manual exports. The keystore itself is never committed.
  val envFile = rootProject.file(".env")
  val envVars: Map<String, String> = if (envFile.exists()) {
    envFile.readLines()
      .filter { it.contains('=') && !it.trimStart().startsWith("#") }
      .associate { line ->
        val idx = line.indexOf('=')
        line.substring(0, idx).trim() to line.substring(idx + 1).trim()
      }
  } else {
    emptyMap()
  }

  fun signingSecret(name: String): String? =
    System.getenv(name)?.takeIf { it.isNotBlank() } ?: envVars[name]?.takeIf { it.isNotBlank() }

  val releaseKeystorePath: String? = signingSecret("AGENTISCO_KEYSTORE_PATH")

  signingConfigs {
    create("release") {
      if (releaseKeystorePath != null) {
        storeFile = file(releaseKeystorePath)
        storePassword = signingSecret("AGENTISCO_KEYSTORE_PASSWORD")
        keyAlias = signingSecret("AGENTISCO_KEY_ALIAS")
        keyPassword = signingSecret("AGENTISCO_KEY_PASSWORD")
      }
    }
    create("debugConfig") {
      val debugPath = signingSecret("AGENTISCO_KEYSTORE_PATH")
        ?: "${rootDir}/debug.keystore"

      val debugStorePass = signingSecret("AGENTISCO_KEYSTORE_PASSWORD")
        ?: "android"

      val debugKeyAlias = signingSecret("AGENTISCO_KEY_ALIAS")
        ?: "androiddebugkey"

      val debugKeyPass = signingSecret("AGENTISCO_KEY_PASSWORD")
        ?: "android"

      storeFile = file(debugPath as String)
      storePassword = debugStorePass
      keyAlias = debugKeyAlias
      keyPassword = debugKeyPass
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      // Only wire up release signing when real credentials are present, so a
      // debug-only build never touches (or needs) the release keystore.
      signingConfig = if (releaseKeystorePath != null) signingConfigs.getByName("release") else null
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
  testOptions { unitTests { isIncludeAndroidResources = true } }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
  externalNativeBuild {
    cmake {
      path = file("src/main/cpp/CMakeLists.txt")
      version = "3.22.1"
    }
  }
  ndkVersion = "27.2.12479018"
  packaging {
    jniLibs {
      // proot, its loader and the bundled rootfs tarball must exist as REAL
      // extracted files: proot is execve'd and the rootfs is read as a plain
      // archive. Uncompressed in-APK libs (useLegacyPackaging=false) never
      // land in nativeLibraryDir, so extraction must stay on (same reason
      // Termux requires it).
      useLegacyPackaging = true
      // The bundled Linux rootfs tarballs (librootfs*.so) are gzip archives,
      // not ELF objects — strip must not touch them.
      keepDebugSymbols += "**/librootfs*.so"
    }
  }
  lint {
    // targetSdk 28 is deliberate (see comment in defaultConfig): Android denies
    // execve of app-data binaries at targetSdk >= 29, which proot requires.
    disable += "ExpiredTargetSdkVersion"
  }
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
// secrets {
//   propertiesFileName = ".env"
//   defaultPropertiesFileName = ".env.example"
//   ignoreList.add("FIREBASE_APPCHECK_DEBUG_TOKEN")
// }

// googleServices { missingGoogleServicesStrategy = MissingGoogleServicesStrategy.WARN }

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  implementation(platform(libs.androidx.compose.bom))
  // implementation(platform(libs.firebase.bom))
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
  // implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  // implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.coil.compose)
  implementation(libs.coil.svg)
  // implementation(libs.converter.moshi)
  // implementation(libs.firebase.ai)
  // Uncomment to use Firestore:
  // implementation(libs.firebase.firestore)

  // Uncomment ALL FOUR of the following dependencies together to use Firebase Auth and Google
  // Sign-In via Credential Manager:
  // implementation(libs.firebase.auth)
  // implementation(libs.androidx.credentials)
  // implementation(libs.androidx.credentials.play.services)
  // implementation(libs.googleid)
  // implementation(libs.firebase.appcheck.recaptcha)
  // implementation(libs.firebase.appcheck.debug)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.logging.interceptor)
  // implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  // Debian rootfs download + extraction (tar.gz / tar.xz) for the real Linux terminal.
  implementation(libs.commons.compress)
  implementation(libs.tukaani.xz)
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
  // "ksp"(libs.moshi.kotlin.codegen)
}
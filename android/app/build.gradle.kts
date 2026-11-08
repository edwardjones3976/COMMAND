import java.util.Properties
import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.secrets)
  alias(libs.plugins.google.services)
  alias(libs.plugins.hilt.android)
}

android {
  namespace = "com.command"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.aistudio.command.vtxrpl"
    minSdk = 24
    targetSdk = 36
    versionCode = 2
    versionName = "4.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    // reCAPTCHA Enterprise site key for release App Check. Never tracked: set RECAPTCHA_SITE_KEY in the
    // environment or in the git-ignored local.properties.
    buildConfigField("String", "RECAPTCHA_SITE_KEY", "\"${localSecret("RECAPTCHA_SITE_KEY")}\"")
  }

  // The release config exists only when KEYSTORE_PATH points at a keystore; otherwise release builds are
  // unsigned (CI) and the owner signs them with apksigner.
  val releaseKeystore = System.getenv("KEYSTORE_PATH")?.takeIf { file(it).isFile }
  val phoneKeystore = System.getenv("COMMAND_SIGNING_KEYSTORE")?.takeIf { file(it).isFile }
  signingConfigs {
    if (releaseKeystore != null) {
      create("release") {
        storeFile = file(releaseKeystore)
        storePassword = System.getenv("STORE_PASSWORD")
        keyAlias = "upload"
        keyPassword = System.getenv("KEY_PASSWORD")
      }
    }
    // One signing identity for every build that goes on the owner's phone, so that a later build installs over an earlier one in place
    // and keeps its alarms and history (amendment 39). The keystore lives outside the repository; without these four settings every
    // build signs as before (debug and noexact with the machine's own debug key, release unsigned).
    if (phoneKeystore != null) {
      create("phone") {
        storeFile = file(phoneKeystore)
        storePassword = System.getenv("COMMAND_SIGNING_STORE_PASSWORD")
        keyAlias = System.getenv("COMMAND_SIGNING_KEY_ALIAS")
        keyPassword = System.getenv("COMMAND_SIGNING_KEY_PASSWORD")
      }
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
      // The upload key, when there is one, is for the store; otherwise the phone identity, so a release build also updates in place.
      signingConfig = signingConfigs.findByName("release") ?: signingConfigs.findByName("phone")
    }
    debug {
      // Local test build: AI Studio's debug.keystore isn't in the export, so AGP's default debug key is used unless the phone identity is set.
      signingConfigs.findByName("phone")?.let { signingConfig = it }
    }
    // A debug build whose manifest drops USE_EXACT_ALARM, so on a current emulator the exact-alarm app-op decides and the
    // inexact and degraded paths really run. Same sources as debug.
    create("noexact") {
      initWith(getByName("debug"))
      matchingFallbacks += "debug"
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    isCoreLibraryDesugaringEnabled = true
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions {
    unitTests {
      isIncludeAndroidResources = true
    }
  }
  sourceSets {
    getByName("noexact") {
      java.directories.add("src/debug/java")
      kotlin.directories.add("src/debug/java")
    }
  }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
  ignoreList.add("FIREBASE_APPCHECK_DEBUG_TOKEN")
}

googleServices { missingGoogleServicesStrategy = MissingGoogleServicesStrategy.WARN }

fun localSecret(name: String): String {
  System.getenv(name)?.let { return it }
  val file = rootProject.file("local.properties")
  if (!file.isFile) return ""
  val props = Properties()
  file.inputStream().use { props.load(it) }
  return props.getProperty(name, "")
}

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  coreLibraryDesugaring(libs.desugar.jdk.libs)
  implementation(project(":voice"))
  implementation(project(":domain"))
  implementation(project(":data"))
  implementation(project(":alarm"))
  implementation(libs.hilt.android)
  implementation(libs.androidx.hilt.navigation.compose)
  implementation(platform(libs.androidx.compose.bom))
  implementation(platform(libs.firebase.bom))
  // implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.camera.camera2)
  implementation(libs.androidx.camera.core)
  implementation(libs.androidx.camera.lifecycle)
  implementation(libs.androidx.camera.view)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  // implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  // implementation(libs.coil.compose)
  // implementation(libs.converter.moshi)
  implementation(libs.firebase.appcheck.recaptcha)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.zxing.core)
  // implementation(libs.logging.interceptor)
  // implementation(libs.moshi.kotlin)
  // implementation(libs.okhttp)
  // implementation(libs.play.services.location)
  // implementation(libs.retrofit)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.hilt.android.testing)
  testImplementation(libs.androidx.work.testing)
  testImplementation(project(":testing-android"))
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  androidTestImplementation(libs.androidx.uiautomator)
  debugImplementation(libs.firebase.appcheck.debug)
  "noexactImplementation"(libs.firebase.appcheck.debug)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.hilt.compiler)
  "kspTest"(libs.hilt.compiler)
  // "ksp"(libs.moshi.kotlin.codegen)
}

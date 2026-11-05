plugins {
  alias(libs.plugins.android.library)
}

android {
  namespace = "com.command.voice"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig { minSdk = 24 }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    isCoreLibraryDesugaringEnabled = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
}

dependencies {
  coreLibraryDesugaring(libs.desugar.jdk.libs)
  api(project(":domain"))
  implementation(libs.kotlinx.coroutines.android)

  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
}

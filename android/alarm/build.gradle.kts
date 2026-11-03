plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.hilt.android)
}

android {
  namespace = "com.command.alarm"
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
  api(project(":data"))
  implementation(libs.androidx.core.ktx)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.hilt.android)
  ksp(libs.hilt.compiler)

  testImplementation(libs.junit)
  testImplementation(libs.robolectric)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.hilt.android.testing)
  testImplementation(libs.androidx.room.runtime)
  testImplementation(libs.androidx.room.ktx)
  testImplementation(project(":testing-jvm"))
  testImplementation(project(":testing-android"))
  kspTest(libs.hilt.compiler)
}

plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.google.devtools.ksp)
}

android {
  namespace = "com.command.testing.android"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig { minSdk = 24 }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    isCoreLibraryDesugaringEnabled = true
  }
}

// Shared fakes and the Hilt test module that swaps every platform binding for one, so service, receiver and screen tests in
// several modules run against the same in-memory database and fakes.
dependencies {
  coreLibraryDesugaring(libs.desugar.jdk.libs)
  api(project(":domain"))
  api(project(":data"))
  api(project(":alarm"))
  api(project(":testing-jvm"))
  api(libs.junit)
  api(libs.robolectric)
  api(libs.androidx.core)
  api(libs.hilt.android.testing)
  api(libs.androidx.room.runtime)
  api(libs.androidx.room.ktx)
  implementation(libs.hilt.android)
  implementation(libs.kotlinx.coroutines.android)
  ksp(libs.hilt.compiler)
}

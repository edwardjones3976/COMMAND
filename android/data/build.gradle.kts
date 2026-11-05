plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.kotlin.serialization)
}

android {
  namespace = "com.command.data"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig { minSdk = 24 }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    isCoreLibraryDesugaringEnabled = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
}

// Room writes the schema JSON of every version here; the files are committed and checked by MigrationTest.
ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
  coreLibraryDesugaring(libs.desugar.jdk.libs)
  api(project(":domain"))
  implementation(libs.androidx.room.runtime)
  implementation(libs.androidx.room.ktx)
  ksp(libs.androidx.room.compiler)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.hilt.android)
  ksp(libs.hilt.compiler)

  testImplementation(libs.junit)
  testImplementation(libs.robolectric)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(project(":testing-jvm"))
}

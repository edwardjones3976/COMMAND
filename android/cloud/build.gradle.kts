plugins {
  alias(libs.plugins.android.library)
}

android {
  namespace = "com.command.cloud"
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
  implementation(project(":voice"))
  implementation(platform(libs.firebase.bom))
  implementation(libs.firebase.ai)
  testImplementation(libs.junit)
}

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.kotlin.jvm)
}

// Same Java 8 target as :domain, which it is consumed by in tests (Gradle refuses a Java 8 consumer of a 17 library).
java {
  sourceCompatibility = JavaVersion.VERSION_1_8
  targetCompatibility = JavaVersion.VERSION_1_8
}

kotlin {
  compilerOptions {
    jvmTarget.set(JvmTarget.JVM_1_8)
    freeCompilerArgs.add("-Xjdk-release=1.8")
  }
}

dependencies {
  api(project(":domain"))
  testImplementation(libs.junit)
}

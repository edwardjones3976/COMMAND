import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.kotlin.jvm)
}

// Java 8 bytecode and Java 8 API surface (-Xjdk-release): the compiler itself rejects any JDK API newer than
// Java 8, which is what Android API 24 (with core-library desugaring for java.time) can rely on.
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
  testImplementation(libs.junit)
  testImplementation(project(":testing-jvm"))
}

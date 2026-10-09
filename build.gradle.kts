import com.ncorti.ktfmt.gradle.tasks.KtfmtFormatTask

plugins {
  id("com.android.application") version "9.2.1" apply false
  id("org.jetbrains.kotlin.jvm") version "2.3.10" apply false
  id("com.ncorti.ktfmt.gradle") version "0.25.0"
}

tasks.register<KtfmtFormatTask>("format") {
  source = project.fileTree(rootDir)
  // ktfmt 0.25 does not discover AGP's built-in Kotlin source sets automatically.
  include(
      "*.gradle.kts",
      "app/*.gradle.kts",
      "extension-core/*.gradle.kts",
      "ipc-probe/*.gradle.kts",
      "app/src/**/*.kt",
      "extension-core/src/**/*.kt",
      "ipc-probe/src/**/*.kt")
}

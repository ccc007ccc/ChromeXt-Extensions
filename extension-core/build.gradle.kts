plugins {
  id("org.jetbrains.kotlin.jvm")
  id("com.ncorti.ktfmt.gradle")
}

kotlin { jvmToolchain(21) }

dependencies {
  implementation("com.google.code.gson:gson:2.12.1")
  testImplementation("junit:junit:4.13.2")
}

tasks.test { useJUnit() }

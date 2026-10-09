plugins {
  id("com.android.application")
  id("com.ncorti.ktfmt.gradle")
}

android {
  compileSdk { version = release(37) { minorApiLevel = 0 } }
  buildToolsVersion = "37.0.0"
  namespace = "org.matrix.chromext"

  defaultConfig {
    applicationId = "io.github.ccc007ccc.chromextextensions"
    minSdk = 26
    targetSdk = 35
    versionCode = 3
    versionName = "0.3.0-dev"
  }

  buildFeatures {
    buildConfig = true
    aidl = true
  }

  buildTypes {
    release {
      isShrinkResources = true
      isMinifyEnabled = true
      proguardFiles("proguard-rules.pro")
    }
  }

  androidResources {
    additionalParameters += listOf("--allow-reserved-package-id", "--package-id", "0x42")
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
  }

  lint {
    disable +=
        listOf(
            "Internationalization",
            "UnsafeIntentLaunch",
            "SetJavaScriptEnabled",
            "UnspecifiedRegisterReceiverFlag",
            "Usability:Icons")
  }
}

dependencies {
  compileOnly("io.github.libxposed:api:102.0.0")
  implementation(project(":extension-core"))
  implementation("com.google.code.gson:gson:2.12.1")
  testImplementation("io.github.libxposed:api:102.0.0")
  testImplementation("junit:junit:4.13.2")
}

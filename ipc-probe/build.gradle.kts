plugins { id("com.android.application") }

android {
  namespace = "org.matrix.chromext.probe"
  compileSdk { version = release(37) { minorApiLevel = 0 } }
  buildToolsVersion = "37.0.0"
  defaultConfig {
    applicationId = "io.github.ccc007ccc.chromextextensions.ipcprobe"
    minSdk = 26
    targetSdk = 35
    versionCode = 1
    versionName = "1.0"
  }
  buildFeatures { aidl = true }
  sourceSets
      .getByName("main")
      .aidl
      .directories
      .add(rootProject.file("app/src/main/aidl").absolutePath)
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
  }
}

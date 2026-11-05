plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.gaeljaffrain.pocketartnet"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gaeljaffrain.pocketartnet"
        minSdk = 22
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Debug-signed: this app is only ever sideloaded with adb.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // JVM unit tests: android.jar stubs return 0/null (SystemClock, Log) instead of throwing.
    testOptions { unitTests.isReturnDefaultValues = true }
}

// No AndroidX on purpose: plain framework views keep the APK tiny and fast on the R1.
dependencies {
    testImplementation("junit:junit:4.13.2")
}

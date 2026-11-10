plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.cuemodo.pocketpeek"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.cuemodo.pocketpeek"
        minSdk = 22
        targetSdk = 35
        // Bump versionName only; versionCode follows it (0.2 -> 200, 1.4.3 -> 10403) so every release
        // installs as an update. The release workflow checks the git tag matches (v0.2).
        versionName = "0.1"
        versionCode = versionName!!.split(".").let { p ->
            require(p.size in 2..3 && p.all { it.toIntOrNull() in 0..99 }) { "versionName must be X.Y or X.Y.Z" }
            p[0].toInt() * 10000 + p[1].toInt() * 100 + p.getOrElse(2) { "0" }.toInt()
        }
        base.archivesName.set("pocketpeek-${versionName}")
    }

    // Release key comes from the environment (GitHub secrets in CI). Without it, release builds fall
    // back to this machine's debug key so `assembleRelease` still works locally; don't distribute
    // those APKs, as they can't update (or be updated by) properly signed ones.
    val releaseKeystore = System.getenv("POCKETPEEK_KEYSTORE")?.takeIf { it.isNotBlank() }
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("POCKETPEEK_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("POCKETPEEK_KEY_ALIAS")
                keyPassword = System.getenv("POCKETPEEK_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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

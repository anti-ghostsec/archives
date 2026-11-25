plugins {
    id("com.android.application")
}

// One CPU type per build: arm64-v8a for phones (the default), x86_64 for
// the emulator (-Pabi=x86_64). The Rust engine is built for the same one
// (build-android.sh).
val abi = (findProperty("abi") as String?) ?: "arm64-v8a"

android {
    namespace = "io.github.archives.app"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "io.github.archives.app"
        minSdk = 26
        targetSdk = 36
        // Each build must count higher than the one it updates: the release
        // workflow passes the number of commits (-PversionCode=...).
        versionCode = (findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = (findProperty("versionName") as String?) ?: "0.1.0"
        ndk { abiFilters += abi }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // The Kotlin side of the Rust engine's bindings (UniFFI).
    implementation("net.java.dev.jna:jna:5.19.1@aar")
    // Only for the tests, not in the app.
    testImplementation("junit:junit:4.13.2")
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}

android {
    namespace = "com.efai.hybrid"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.efai.hybrid"
        minSdk = 30            // Android 11+: simplifies window-metrics / display APIs
        targetSdk = 35         // Android 15
        versionCode = 1
        versionName = "0.1.0-floating"
        ndk { abiFilters += listOf("arm64-v8a") }   // Samsung phones; keeps the APK small
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
    kotlinOptions { jvmTarget = "17" }
}

chaquopy {
    defaultConfig {
        version = "3.11"
        // GitHub Actions (or another build host) supplies this path.
        // For local builds, set PYTHON_EXECUTABLE to your Python 3.11 executable.
        System.getenv("PYTHON_EXECUTABLE")?.let { buildPython(it) }
        pip {
            install("numpy")
            install("opencv-python")   // must exist in Chaquopy's wheel repo; see README
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
}

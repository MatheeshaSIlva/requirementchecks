plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.spike.launcherprobe"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.spike.launcherprobe"
        minSdk = 29
        // 34 on purpose: avoids forced edge-to-edge so the plain-View test UI needs no inset handling.
        targetSdk = 34
        versionCode = 1
        versionName = (project.findProperty("buildSha") as String?)?.take(7) ?: "local"
    }

    signingConfigs {
        // Committed on purpose: CI and Android Studio sign with the SAME key, so APKs update over each other.
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    signingConfigs {
        getByName("debug") {
            // Fixed key committed on purpose: CI builds then update each other in place.
            storeFile = rootProject.file("probe-debug.keystore")
            storePassword = "android"
            keyAlias = "probe"
            keyPassword = "android"
        }
    }

    buildFeatures {
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    val shizuku = "13.1.5"
    implementation("dev.rikka.shizuku:api:$shizuku")
    implementation("dev.rikka.shizuku:provider:$shizuku")
    compileOnly(project(":hidden-stubs"))
}

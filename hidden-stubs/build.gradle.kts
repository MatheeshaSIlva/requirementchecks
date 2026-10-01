// Compile-time stubs for hidden framework classes. This module is compileOnly in :app,
// so nothing from it is packaged; at runtime the real framework classes are used.
plugins {
    id("com.android.library")
}

android {
    namespace = "dev.spike.hiddenstubs"
    compileSdk = 35
    defaultConfig {
        minSdk = 29
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

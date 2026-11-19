plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.leodev.adwarescanner"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.leodev.adwarescanner"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
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
    kotlinOptions {
        jvmTarget = "17"
    }
}

// No libraries on purpose: the app uses only the Android framework,
// has no INTERNET permission, and never sends anything off the phone.
dependencies {
}

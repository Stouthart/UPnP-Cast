plugins {
    id("com.android.application")
}

layout.buildDirectory = layout.projectDirectory.dir("build.nosync") // iCloud Drive skips .nosync names.

android {
    namespace = "nl.stouthart.upnpcast"
    compileSdk = 37

    defaultConfig {
        applicationId = "nl.stouthart.upnpcast"
        minSdk = 36 // RedMagic Astra (Android 16).
        targetSdk = 37
        versionCode = 2 // Keeps counting up: the phones carried the internal 0.1 builds as 1.
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("debug") // Sideloaded only, so the debug key is enough.
        }
    }

    buildFeatures {
        buildConfig = true // VERSION_NAME for the copyright line, as a compile-time constant.
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

plugins {
    id("com.android.application")
}

layout.buildDirectory = layout.projectDirectory.dir("build.nosync") // iCloud Drive skips .nosync names.

android {
    namespace = "nl.stouthart.upnpcast"
    compileSdk = 37

    defaultConfig {
        applicationId = "nl.stouthart.upnpcast"
        minSdk = 34 // Android 14; tested on 16 (RedMagic Astra) and 17 (S25 Edge).
        targetSdk = 37
        versionCode = 10 // Keeps counting up: the phones carried the internal 0.1 builds as 1.
        versionName = "2.1"
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

dependencies {
    testImplementation("junit:junit:4.13.2") // The guard tests only: nothing of it reaches the APK.
    testImplementation("net.sf.kxml:kxml2:2.3.0") // Android's own XML parser, for the reply check on the computer.
}

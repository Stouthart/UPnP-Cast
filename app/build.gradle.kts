import java.util.Properties

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
        versionCode = 15 // Keeps counting up: the phones carried the internal 0.1 builds as 1.
        versionName = "3.0"
    }

    // The release key: its keystore and passwords in keystore.properties beside the project, kept out of git. Without
    // that file, as on another machine, the debug key signs, as before.
    val keystore = rootProject.file("keystore.properties")
    signingConfigs {
        if (keystore.exists()) {
            val k = Properties().apply { keystore.inputStream().use { load(it) } }
            create("release") {
                storeFile = file(k.getProperty("storeFile"))
                storePassword = k.getProperty("storePassword")
                keyAlias = k.getProperty("keyAlias")
                keyPassword = k.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName(if (keystore.exists()) "release" else "debug")
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

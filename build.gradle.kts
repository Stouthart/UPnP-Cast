plugins {
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.android") version "2.4.21" apply false // Pins the Kotlin version AGP's built-in Kotlin uses.
}

layout.buildDirectory = layout.projectDirectory.dir("build.nosync") // iCloud Drive skips .nosync names.

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// A GitHub Actions a futás sorszámát adja át, így minden új APK frissítésként települ.
val buildNumber = (project.findProperty("versionCode") as String?)?.toInt() ?: 1

android {
    namespace = "hu.kettleredzo.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "hu.kettleredzo.app"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = "0.6.$buildNumber"
    }

    // Fix kulcs, hogy a frissítés ne kérje az előző verzió eltávolítását.
    // A kulcsot az első GitHub Actions-futás hozza létre és tölti fel a repóba.
    signingConfigs {
        create("kettler") {
            storeFile = rootProject.file("keystore/kettler.jks")
            storePassword = "kettler123"
            keyAlias = "kettler"
            keyPassword = "kettler123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("kettler")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.checkout.alfasignal"
    compileSdk = 34

    defaultConfig {
        // Standalone "signal" watcher — a separate app from ALFA SMS.
        applicationId = "com.checkout.alfasignal"
        minSdk = 24
        targetSdk = 34
        versionCode = ((project.findProperty("signalVersionCode") as String?)?.toInt()) ?: 1
        versionName = (project.findProperty("signalVersionName") as String?) ?: "1.0"

        // The signal webhook fired when an incoming SMS contains the trigger word.
        // Overridable with -PsignalUrl=...
        val signalUrl = (project.findProperty("signalUrl") as String?)
            ?: "https://project.alfa-vpn.ru/api/signal/Karenchikosikus123"
        buildConfigField("String", "DEFAULT_WEBHOOK_URL", "\"$signalUrl\"")
    }

    signingConfigs {
        create("release") {
            // Reuse the ALFA release keystore (lives in the app module).
            storeFile = file("../app/alfa-release.jks")
            storePassword = "alfasms123"
            keyAlias = "alfa"
            keyPassword = "alfasms123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
}

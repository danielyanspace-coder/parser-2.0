plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.messagesender"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.messagesender"
        minSdk = 24
        targetSdk = 34
        // Bump versionCode on every release. Override: -PversionCode=3 -PversionName=2.1
        versionCode = ((project.findProperty("versionCode") as String?)?.toInt()) ?: 2
        versionName = (project.findProperty("versionName") as String?) ?: "2.0"

        // Default control server. The real server URL is delivered inside the QR
        // code the user scans, so this is only a fallback / display default.
        // Overridable with -PserverUrl=...
        val serverUrl = (project.findProperty("serverUrl") as String?) ?: "https://alfa-vpn.ru"
        buildConfigField("String", "DEFAULT_SERVER_URL", "\"$serverUrl\"")
    }

    signingConfigs {
        create("release") {
            storeFile = file("alfa-release.jks")
            storePassword = "alfasms123"
            keyAlias = "alfa"
            keyPassword = "alfasms123"
        }
    }

    buildTypes {
        debug {
            // Same code path as release; the device is controlled entirely by the
            // server it pairs with, so there is no license flag to bypass.
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    // QR scanning (camera + ZXing decoder).
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}

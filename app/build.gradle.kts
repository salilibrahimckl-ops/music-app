plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.salil.tarang"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.salil.tarang"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "3.0"
    }

    signingConfigs {
        create("tarang") {
            storeFile = file("tarang.jks")
            storePassword = "tarang123"
            keyAlias = "tarang"
            keyPassword = "tarang123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("tarang")
        }
        debug {
            signingConfig = signingConfigs.getByName("tarang")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { checkReleaseBuilds = false; abortOnError = false }
}

dependencies {
    val media3 = "1.4.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-session:$media3")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.webkit:webkit:1.11.0")
}

/* AGP's version is declared here rather than in the root build, because this
   module is only included when an Android SDK is present — see settings.gradle.
   That keeps `./gradlew :core:test` working on a machine with no SDK. */
plugins {
    id("com.android.application") version "8.5.2"
    id("org.jetbrains.kotlin.android") version "2.0.21"
}

android {
    namespace = "art.plume.anvil"
    compileSdk = 34

    defaultConfig {
        /* PERMANENT once published: on the Play Store an applicationId can
           never be changed — a new one is a new listing, with no upgrade path
           for anyone who installed the old one. Worth being sure of before the
           first release. */
        applicationId = "art.plume.anvil"
        minSdk = 26            // GL ES 3.1 is universal from here, and 26 is >99% of devices
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":core"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity:1.9.2")
}

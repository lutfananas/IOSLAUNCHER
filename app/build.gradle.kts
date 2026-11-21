plugins {
    id("com.android.application")
}

android {
    namespace = "com.ioslauncher"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.ioslauncher"
        minSdk = 31
        targetSdk = 37
        versionCode = 3
        versionName = "1.0.2"
    }

    signingConfigs {
        // Sideload key committed to the repo so local builds, CI builds and GitHub Releases all share one
        // signature (updates install over each other). It's a throwaway key — not for Play Store publishing.
        create("sideload") {
            storeFile = rootProject.file("keystore/sideload.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("sideload")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("sideload")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-ktx:1.12.4")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.dynamicanimation:dynamicanimation-ktx:1.1.0")
}

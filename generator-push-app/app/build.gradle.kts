plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.webgen.generator"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.webgen.generator.push"
        minSdk = 26
        targetSdk = 34
        versionCode = 5
        versionName = "1.2.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Framework-only on purpose (no AndroidX) so the app can also be built
    // with the manual aapt2 + kotlinc + d8 pipeline (build_generator.sh).
    //
    // apksig (com.android.apksig) is the library behind the SDK's apksigner
    // tool; libs/apksig-only.jar is extracted from the SDK build-tools
    // apksigner.jar and contains only the com/android/apksig/** classes.
    // apksig is part of the Android Open Source Project, licensed under the
    // Apache License, Version 2.0.
    implementation(files("../libs/apksig-only.jar"))
}

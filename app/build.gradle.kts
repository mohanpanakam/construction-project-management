plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.panakam.construction"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.panakam.construction"
        minSdk = 35
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    // Firebase — disabled until google-services.json is configured
    // implementation(platform("com.google.firebase:firebase-bom:34.18.0"))
    // implementation("com.google.firebase:firebase-auth-ktx:22.0.0")

    // Biometric authentication
    implementation("androidx.biometric:biometric:1.1.0")

    // Coil – image loading for photo thumbnails
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Coroutines — 1.10.1 is built with Kotlin 2.x, eliminates stdlib version mismatch
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")

    // Jetpack Navigation Compose
    implementation("androidx.navigation:navigation-compose:2.8.9")

    // Extended Material Icons (Logout, Visibility, Business, Inventory, etc.)
    implementation("androidx.compose.material:material-icons-extended")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
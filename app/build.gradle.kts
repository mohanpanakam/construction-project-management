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
        // NOTE: minSdk was previously 35 (Android 15) — that's a hard install-time
        // block, not a runtime bug: it made the app entirely uninstallable on any
        // Android 14 (API 34) or older device ("not compatible with your device" /
        // INSTALL_FAILED_OLDER_SDK when sideloading). Nothing in this app actually
        // requires API 35 (no Build.VERSION_CODES.VANILLA_ICE_CREAM-gated code; the
        // manifest itself only declares tools:targetApi="31"). Lowered to 26 (covers
        // effectively all real-world devices) so Android 14 users can install it.
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "1.2"

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
    implementation("androidx.biometric:biometric:1.2.0-alpha05")

    // Fragment — required for FragmentActivity (used by BiometricPrompt)
    implementation("androidx.fragment:fragment-ktx:1.8.6")

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
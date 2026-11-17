plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.kosign.minibank"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kosign.minibank"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "5.2.0"

        // Reached via `adb reverse` (see adb-reverse.ps1 / adb-reverse.sh).
        // Bank: app gateway /gw/app/v1. Portal: only POST /api/v1/admin/trace.
        // Web: partner page, partner app and /links/*. Partner: only used by the embedded Starlight SDK.
        buildConfigField("String", "BANK_URL", "\"http://localhost:8082\"")
        buildConfigField("String", "PORTAL_URL", "\"http://localhost:8081\"")
        buildConfigField("String", "WEB_URL", "\"http://localhost:3000\"")
        buildConfigField("String", "PARTNER_URL", "\"http://localhost:8083\"")
        buildConfigField("String", "APP_VERSION", "\"5.2.0\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "ir.jirjirak.sms"
    compileSdk = 36

    defaultConfig {
        applicationId = "ir.jirjirak.sms"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        // Release: the key from GitHub Secrets (see .github/workflows/build-apk.yml). There is NO
        // fallback to a committed key — anybody holding it could ship an «update» of an app that
        // reads bank SMS. Without the secrets, only the debug APK is built.
        create("release") {
            val path = System.getenv("KEYSTORE_PATH")
            val storePass = System.getenv("STORE_PASSWORD")
            val keyPass = System.getenv("KEY_PASSWORD")
            if (path != null && file(path).exists() && !storePass.isNullOrEmpty() && !keyPass.isNullOrEmpty()) {
                storeFile = file(path)
                storePassword = storePass
                keyAlias = System.getenv("KEY_ALIAS") ?: "jirjirak"
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val release = signingConfigs.getByName("release")
            if (release.storeFile != null) {
                signingConfig = release
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    testImplementation(libs.junit)
}

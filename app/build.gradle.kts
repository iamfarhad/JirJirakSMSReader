import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// SemVer from version.properties — the single source. versionCode is derived so that every higher
// version installs over the lower one; equal codes would refuse the update.
val semver: String = Properties().apply { rootProject.file("version.properties").inputStream().use { load(it) } }
    .getProperty("VERSION").trim()
val semverParts = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)$").matchEntire(semver)?.destructured?.toList()?.map { it.toInt() }
    ?: throw GradleException("version.properties: VERSION must be MAJOR.MINOR.PATCH, got \"$semver\"")
require(semverParts[1] < 1000 && semverParts[2] < 1000) { "MINOR and PATCH must stay below 1000" }

// A v* tag must name the version in the source — a release built from a mismatched tag would
// carry one number on GitHub and another in the APK.
System.getenv("GITHUB_REF_NAME")?.takeIf { it.startsWith("v") && System.getenv("GITHUB_REF_TYPE") == "tag" }?.let { tag ->
    if (tag != "v$semver") throw GradleException("Tag $tag does not match version.properties ($semver).")
}

android {
    namespace = "ir.jirjirak.sms"
    compileSdk = 36

    defaultConfig {
        applicationId = "ir.jirjirak.sms"
        minSdk = 24
        targetSdk = 36
        versionCode = semverParts[0] * 1_000_000 + semverParts[1] * 1_000 + semverParts[2]
        versionName = semver
    }

    signingConfigs {
        // Release: the key from GitHub Secrets (see .github/workflows/build-apk.yml). There is NO
        // fallback to a committed key — anybody holding it could ship an «update» of an app that
        // reads bank SMS. Without the secrets, only the debug APK is built.
        create("release") {
            val path = System.getenv("KEYSTORE_PATH")
            val storePass = System.getenv("STORE_PASSWORD")
            val keyPass = System.getenv("KEY_PASSWORD")
            val hasKey = path != null && file(path).exists()
            // A key without its passwords is a broken setup, not «build unsigned»: an unsigned
            // release used to slip through and publish a release with no APK in it.
            if (hasKey && (storePass.isNullOrBlank() || keyPass.isNullOrBlank())) {
                throw GradleException("Release keystore present but STORE_PASSWORD / KEY_PASSWORD is missing.")
            }
            if (hasKey) {
                storeFile = file(path!!)
                storePassword = storePass
                // GitHub passes an unset secret as "", which an elvis alone would keep.
                keyAlias = System.getenv("KEY_ALIAS")?.takeIf { it.isNotBlank() } ?: "jirjirak"
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

import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Read secrets from local.properties (gitignored). This file should contain:
//   photos_api_key=YOUR_ACTUAL_KEY_HERE
// If the file doesn't exist (e.g. in CI), the placeholder value is used and must be
// replaced before the APK is sideloaded.
val localProperties = Properties().also { props ->
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        props.load(localPropertiesFile.inputStream())
    }
}

android {
    namespace = "eu.l42.lucos_photos_android"
    compileSdk = 36

    defaultConfig {
        applicationId = "eu.l42.lucos_photos_android"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Inject the API key at build time from local.properties.
        // To set the real key: add `photos_api_key=YOUR_KEY` to local.properties (never commit this file).
        buildConfigField(
            "String",
            "PHOTOS_API_KEY",
            "\"${localProperties.getProperty("photos_api_key", "REPLACE_WITH_YOUR_API_KEY")}\"",
        )
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // No signing config is defined here. The CI build produces an unsigned APK which
            // must be signed locally before sideloading to the device. This is intentional for
            // a single-device sideloaded app — the signing key is never committed to the repo.
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.androidx.test.core)
}

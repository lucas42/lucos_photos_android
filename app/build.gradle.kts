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

// Signing credentials can be supplied via environment variables (used in the production-build-apk
// CI job). All four must be set for signing to be configured; if any are absent the build
// produces an unsigned APK (the normal case for the unsigned build-apk job and local dev builds).
val keystoreFile = System.getenv("ANDROID_KEYSTORE_FILE").takeIf { !it.isNullOrBlank() }
val keystorePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD").takeIf { !it.isNullOrBlank() }
val keyAlias = "lucos-photos"
// Fall back to ANDROID_KEYSTORE_PASSWORD if ANDROID_KEY_PASSWORD is absent or blank —
// it's common Android convention to use the same password for both.
val keyPassword = System.getenv("ANDROID_KEY_PASSWORD").takeIf { !it.isNullOrBlank() }
    ?: keystorePassword
val ciApiKey = System.getenv("KEY_LUCOS_PHOTOS")

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

        // Inject the API key at build time. In CI the KEY_LUCOS_PHOTOS env var is used;
        // locally, set photos_api_key in local.properties (never commit that file).
        val apiKey = ciApiKey
            ?: localProperties.getProperty("photos_api_key", "REPLACE_WITH_YOUR_API_KEY")
        buildConfigField("String", "PHOTOS_API_KEY", "\"$apiKey\"")
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (keystoreFile != null && keystorePassword != null && keyPassword != null) {
            create("release") {
                storeFile = file(keystoreFile)
                storePassword = keystorePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Use the release signingConfig when all signing env vars are present (CI production
            // build). Otherwise produce an unsigned APK (unsigned build-apk job, local dev).
            val releaseSigningConfig = signingConfigs.findByName("release")
            if (releaseSigningConfig != null) {
                signingConfig = releaseSigningConfig
            }
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

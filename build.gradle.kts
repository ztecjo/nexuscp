import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val buildNumber = providers.environmentVariable("NEXUSCP_BUILD_NUMBER").orNull

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

fun prop(name: String, env: String, fallback: String = ""): String =
    keystoreProperties.getProperty(name)
        ?: providers.environmentVariable(env).orNull
        ?: fallback

android {
    namespace = "com.ztec.cplay"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.ztec.cplay"
        minSdk = 24
        targetSdk = 37
        versionCode = 21
        versionName = buildNumber?.let { "2.1（$it）" } ?: "2.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file(prop("debug.storeFile", "ANDROID_DEBUG_KEYSTORE_PATH", "keystore/nexuscp-debug.jks"))
            storePassword = prop("debug.storePassword", "ANDROID_DEBUG_KEYSTORE_PASSWORD")
            keyAlias = prop("debug.keyAlias", "ANDROID_DEBUG_KEY_ALIAS", "nexuscp-debug")
            keyPassword = prop("debug.keyPassword", "ANDROID_DEBUG_KEY_PASSWORD")
        }
        create("release") {
            storeFile = file(prop("release.storeFile", "ANDROID_KEYSTORE_PATH", "keystore/nexuscp-release.jks"))
            storePassword = prop("release.storePassword", "ANDROID_KEYSTORE_PASSWORD")
            keyAlias = prop("release.keyAlias", "ANDROID_KEY_ALIAS", "nexuscp-release")
            keyPassword = prop("release.keyPassword", "ANDROID_KEY_PASSWORD")
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            optimization {
                enable = false
            }
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.bouncycastle)
    implementation(libs.jmdns)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.17")
}

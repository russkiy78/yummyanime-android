plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/** Value from an environment variable (CI) or a Gradle property (~/.gradle/gradle.properties, -P...). */
fun setting(env: String, property: String): String? =
    System.getenv(env)?.takeIf { it.isNotBlank() } ?: (findProperty(property) as String?)?.takeIf { it.isNotBlank() }

// Public X-Application token from https://yummyani.me/dev/applications.
// It can also be changed at runtime in the app settings.
val yummyAppToken: String = setting("YUMMY_APP_TOKEN", "yummy.appToken").orEmpty()

// CI passes the release version; local builds use the base version from gradle.properties.
val appVersionName: String = setting("VERSION_NAME", "yummy.versionName") ?: "${property("yummy.version")}.0"
val appVersionCode: Int = setting("VERSION_CODE", "yummy.versionCode")?.toInt() ?: 1

// Release signing. Without a keystore the release build falls back to the debug key.
val releaseKeystore: String? = setting("YUMMY_KEYSTORE_FILE", "yummy.keystore.file")

android {
    namespace = "com.yani.client"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.yani.client"
        minSdk = 24
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("String", "APP_TOKEN", "\"$yummyAppToken\"")
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = setting("YUMMY_KEYSTORE_PASSWORD", "yummy.keystore.password")
                keyAlias = setting("YUMMY_KEY_ALIAS", "yummy.key.alias")
                keyPassword = setting("YUMMY_KEY_PASSWORD", "yummy.key.password")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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

    androidResources {
        localeFilters += listOf("en", "ru")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
}

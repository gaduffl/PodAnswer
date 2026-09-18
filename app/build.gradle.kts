plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "de.graulich.podanswer"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.graulich.podanswer"
        minSdk = 29
        targetSdk = 35
        versionCode = 7
        versionName = "0.1.6"
    }

    val signingEnvironment = mapOf(
        "storeFile" to System.getenv("ANDROID_KEYSTORE_PATH"),
        "storePassword" to System.getenv("ANDROID_KEYSTORE_PASSWORD"),
        "keyAlias" to System.getenv("ANDROID_KEY_ALIAS"),
        "keyPassword" to System.getenv("ANDROID_KEY_PASSWORD"),
    )
    val hasReleaseSigning = signingEnvironment.values.all { !it.isNullOrBlank() }

    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = file(signingEnvironment.getValue("storeFile")!!)
                storePassword = signingEnvironment.getValue("storePassword")
                keyAlias = signingEnvironment.getValue("keyAlias")
                keyPassword = signingEnvironment.getValue("keyPassword")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        getByName("release") {
            isDebuggable = false
            isMinifyEnabled = false
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

tasks.configureEach {
    if (name == "packageRelease" || name == "assembleRelease" || name == "bundleRelease") {
        doFirst {
            val required = listOf(
                "ANDROID_KEYSTORE_PATH",
                "ANDROID_KEYSTORE_PASSWORD",
                "ANDROID_KEY_ALIAS",
                "ANDROID_KEY_PASSWORD",
            )
            val missing = required.filter { System.getenv(it).isNullOrBlank() }
            if (missing.isNotEmpty()) {
                throw GradleException("Release signing is required. Missing: ${missing.joinToString()}")
            }
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

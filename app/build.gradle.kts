plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.thousandofwind.pharmacy"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.thousandofwind.pharmacy"
        minSdk = 26
        targetSdk = 34
        versionCode = providers.gradleProperty("pharmacyVersionCode").orNull?.toInt() ?: 1
        versionName = providers.gradleProperty("pharmacyVersionName").orNull ?: "1.0"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    // Main CI builds use a fixed private debug key; local/PR builds keep the usual local debug key.
    System.getenv("PHARMACY_SIGNING_STORE")?.takeIf { it.isNotBlank() }?.let { path ->
        signingConfigs.getByName("debug") {
            storeFile = file(path)
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    listOf("debug", "release").forEach { variant ->
        sourceSets.getByName(variant).assets.srcDir(layout.buildDirectory.dir("generated/noticeAssets/$variant"))
    }

    packaging {
        resources {
            // Duplicate metadata is replaced by full, per-artifact notices in generated legal assets.
            // notice-assets.gradle.kts reads originals before this packaging exclusion.
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

apply(from = "notice-assets.gradle.kts")

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")

    implementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("io.coil-kt:coil-compose:2.7.0")

    implementation("com.google.mlkit:text-recognition-chinese:16.0.0")

    implementation("com.aallam.openai:openai-client:4.1.0")
    implementation("io.ktor:ktor-client-okhttp:3.0.0")
    implementation("io.ktor:ktor-client-auth:3.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Only the focused JVM rule tests are maintained; no UI/instrumentation test stack.
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.ktor:ktor-client-mock:3.0.0")
}

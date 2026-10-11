plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.serialization")
}
android {
    namespace = "com.thousandofwind.aipoc.koog"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.thousandofwind.aipoc.koog"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "poc"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint.disable += setOf("MissingApplicationIcon", "DataExtractionRules") // Deliberate non-backed-up disposable PoC.
    packaging.resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/INDEX.LIST", "META-INF/versions/9/OSGI-INF/MANIFEST.MF")
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":harness"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("ai.koog:agents-core:1.3.0")
    implementation("ai.koog:http-client-ktor:1.3.0")
    testImplementation("junit:junit:4.13.2")
}

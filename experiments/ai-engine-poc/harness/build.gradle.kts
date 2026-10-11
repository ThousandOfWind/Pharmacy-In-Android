plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}
kotlin { jvmToolchain(17) }
dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
    // Only the isolated PoC ships this in-memory transport, never the pharmacy app.
    api("io.ktor:ktor-client-mock:3.3.3")
}

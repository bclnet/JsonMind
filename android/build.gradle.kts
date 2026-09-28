// Root build for the JsonMind library: one pure Kotlin/JVM module, jsonmind.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

allprojects {
    group = "com.bclnet.jsonmind"
    version = "1.0.0"
}

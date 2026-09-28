// Root build for the JsonMind libraries: jsonmind (the minds) and jsonmind-tokenx (the TokenX adapter), both pure Kotlin/JVM.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

allprojects {
    group = "com.bclnet.jsonmind"
    version = "1.0.0"
}

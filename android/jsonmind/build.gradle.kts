plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `java-library`
    `maven-publish`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    api(libs.jsonui.core)
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}

publishing {
    publications { create<MavenPublication>("maven") { from(components["java"]); artifactId = "jsonmind" } }
}

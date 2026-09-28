plugins {
    alias(libs.plugins.kotlin.jvm)
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
    api(project(":jsonmind"))
    api(libs.tokenx.core)
    testImplementation(libs.junit)
}

publishing {
    publications { create<MavenPublication>("maven") { from(components["java"]); artifactId = "jsonmind-tokenx" } }
}

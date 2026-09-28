pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "JsonMind"

// JsonUI is a git submodule (third_party/JsonUI); its modules are substituted by coordinates.
// When JsonMind is itself an included build, the outer build supplies JsonUI instead.
if (gradle.parent == null) includeBuild("../third_party/JsonUI/android")

include(":jsonmind")

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "KiteFFmpeg"
include(":kiteffmpeg-core")
include(":kiteffmpeg-sample")
// include(":kiteffmpeg-gpl"): uncomment once kiteffmpeg-gpl/build.gradle.kts is implemented (see kiteffmpeg-gpl/README.md)

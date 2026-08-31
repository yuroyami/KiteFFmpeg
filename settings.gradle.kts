// TYPESAFE_PROJECT_ACCESSORS was enabled here and used nowhere: no build file ever referenced a
// `projects.` accessor. It only generated classes, and on 2026-08-30 those classes were the single
// thing blocking the module rename to :kiteffmpeg, because Gradle derives an accessor name from
// each project and "KiteFFmpeg" and "kiteffmpeg" collide case-insensitively. Removed rather than
// worked around: a feature preview nothing consumes is cost with no benefit.

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
include(":kiteffmpeg")
include(":kiteffmpeg-sample")
// include(":kiteffmpeg-gpl"): uncomment once kiteffmpeg-gpl/build.gradle.kts is implemented (see kiteffmpeg-gpl/README.md)

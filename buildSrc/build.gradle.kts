plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    // CheckCinteropCouplingTask's test. buildSrc's own tests are not part of the main build's
    // graph, so the verification gate calls them explicitly.
    testImplementation(kotlin("test"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // The coupling ratchet is measured against the real repository, which is buildSrc's parent.
    systemProperty("kiteffmpeg.repo.root", layout.projectDirectory.asFile.parentFile.absolutePath)
}

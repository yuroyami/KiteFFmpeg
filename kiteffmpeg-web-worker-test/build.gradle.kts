plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

/*
 * A test of the asynchronous runtime in a Web Worker (#183). Nothing here is published.
 *
 * The main source set is a second wasm executable, the one a Worker loads. It opens a runtime on
 * `kite-jspi` or `kite-asyncify`, reads a real HLS stream through it, and posts what it saw. The
 * test is the page: it starts the Worker and checks the answer.
 */
kotlin {
    jvmToolchain(21)

    wasmJs {
        outputModuleName.set("kiteffmpeg-worker-probe")
        browser {
            testTask {
                // karma.config.d/worker.js reads this switch, which means here what it means for
                // :kiteffmpeg's web tests: a module that was not linked fails the test.
                environment(
                    "KITEFFMPEG_WEB_MODULE_REQUIRED",
                    providers.gradleProperty("kiteffmpeg.web.requireModule").getOrElse("false"),
                )
            }
        }
        binaries.executable()
    }

    sourceSets {
        wasmJsMain.dependencies {
            implementation(project(":kiteffmpeg"))
            implementation(libs.kotlinx.coroutines.core)
        }
        wasmJsTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

// The page needs the Worker's binary and the two asynchronous modules served beside it.
// karma.config.d/worker.js serves the development binary from its compile directory.
tasks.named { it == "wasmJsBrowserTest" }.configureEach {
    dependsOn("wasmJsDevelopmentExecutableCompileSync")
    mustRunAfter(":kiteffmpeg:linkKiteFFmpegAsyncWasmModules")
    inputs.files(fileTree(rootDir.resolve("kiteffmpeg/build/kite-web-async")))
    inputs.file(rootDir.resolve("native/kitecodec-web/tests/hls-fixture.json"))
}

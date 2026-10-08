// AsyncRuntimeInWorkerTest starts a real Worker (#183). The page gets three things from disk,
// proxied to fixed paths:
// - the Worker's binary this module builds, its development executable, under /worker/;
// - the two asynchronous modules of :kiteffmpeg:linkKiteFFmpegAsyncWasmModules, under
//   /kite-web-async/;
// - the HLS stream of the Node check, at /kite-hls-fixture.json.
// A module that was not linked is named as null, and the test skips it, or fails when
// -Pkiteffmpeg.web.requireModule=true asked for it.
// karma.conf.js lives in <root>/build/wasm/packages/<project>-test, four levels under the root.
const path = require("path");
const fs = require("fs");
const root = path.resolve(__dirname, "../../../..");
const workerDir = path.join(root, "kiteffmpeg-web-worker-test/build/compileSync/wasmJs/main/developmentExecutable/kotlin");
const asyncDir = path.join(root, "kiteffmpeg/build/kite-web-async");
const hlsFixture = path.join(root, "native/kitecodec-web/tests/hls-fixture.json");
config.files.push(
    { pattern: path.join(workerDir, "*"), included: false, served: true, watched: false },
    { pattern: path.join(asyncDir, "*"), included: false, served: true, watched: false },
    { pattern: hlsFixture, included: false, served: true, watched: false },
);
// Percent-encoded: a bare '#' in a checkout's path is a fragment, so the proxy target would
// otherwise stop at the hash.
const served = (file) => "/absolute" + encodeURI(file).replace(/#/g, "%23");
config.proxies = Object.assign({}, config.proxies, {
    "/worker/": served(workerDir + "/"),
    "/kite-web-async/": served(asyncDir + "/"),
    "/kite-hls-fixture.json": served(hlsFixture),
});
// ES modules need the mime type a browser accepts for a script.
config.mime = Object.assign({}, config.mime, { "text/javascript": ["mjs"], "application/wasm": ["wasm"] });
const has = (name) => fs.existsSync(path.join(asyncDir, name));
// The test reads this as __karma__.config.kiteWorker.
config.set({
    client: Object.assign({}, config.client, {
        kiteWorker: {
            worker: "/worker/kiteffmpeg-worker-probe.mjs",
            jspi: has("kite-jspi.mjs") ? "/kite-web-async/kite-jspi.mjs" : null,
            asyncify: has("kite-asyncify.mjs") ? "/kite-web-async/kite-asyncify.mjs" : null,
            hlsFixture: "/kite-hls-fixture.json",
            required: process.env.KITEFFMPEG_WEB_MODULE_REQUIRED === "true",
        },
    }),
});

// The browser half of the web tests loads the codec modules the build linked, as a page would:
// - kite.mjs and kite.wasm of linkKiteFFmpegWasmModule, under /kite-web/;
// - the two asynchronous modules of linkKiteFFmpegAsyncWasmModules, under /kite-web-async/;
// - the HLS stream of the Node check, at /kite-hls-fixture.json.
// A module that was not linked is named as null, and its tests skip, or fail when
// -Pkiteffmpeg.web.requireModule=true asked for them.
// karma.conf.js lives in <root>/build/wasm/packages/<project>-test, four levels under the root.
const path = require("path");
const fs = require("fs");
const root = path.resolve(__dirname, "../../../..");
const moduleDir = path.join(root, "kiteffmpeg/build/kite-web");
const asyncDir = path.join(root, "kiteffmpeg/build/kite-web-async");
const hlsFixture = path.join(root, "native/kitecodec-web/tests/hls-fixture.json");
config.files.push(
    { pattern: path.join(moduleDir, "*"), included: false, served: true, watched: false },
    { pattern: path.join(asyncDir, "*"), included: false, served: true, watched: false },
    { pattern: hlsFixture, included: false, served: true, watched: false },
);
// Percent-encoded: a bare '#' in a checkout's path is a fragment, so the proxy target would
// otherwise stop at the hash.
const served = (file) => "/absolute" + encodeURI(file).replace(/#/g, "%23");
config.proxies = Object.assign({}, config.proxies, {
    "/kite-web/": served(moduleDir + "/"),
    "/kite-web-async/": served(asyncDir + "/"),
    "/kite-hls-fixture.json": served(hlsFixture),
});
// ES modules need the mime type a browser accepts for a script.
config.mime = Object.assign({}, config.mime, { "text/javascript": ["mjs"], "application/wasm": ["wasm"] });
const has = (dir, name) => fs.existsSync(path.join(dir, name));
// The tests read this as __karma__.config.kiteModules. Node has no such object and reads the
// environment instead.
config.set({
    client: Object.assign({}, config.client, {
        kiteModules: {
            module: has(moduleDir, "kite.mjs") ? "/kite-web/kite.mjs" : null,
            jspi: has(asyncDir, "kite-jspi.mjs") ? "/kite-web-async/kite-jspi.mjs" : null,
            asyncify: has(asyncDir, "kite-asyncify.mjs") ? "/kite-web-async/kite-asyncify.mjs" : null,
            hlsFixture: "/kite-hls-fixture.json",
            required: process.env.KITEFFMPEG_WEB_MODULE_REQUIRED === "true",
        },
    }),
});

// Runs one linked wasmJs test module in headless Chrome and reports every test.
//
// The toolchain runs wasmJs tests in Chrome too, but its page serves no other files. Some tests need
// the codec modules and a Worker program, so this script serves them and tells the tests where they
// are, in `__karma__.config`, the object that karma filled under the Gradle build.
//
// Usage: node browser.mjs <module>_test.mjs <config.json> [<url path>=<directory> ...]
// CHROME names the Chrome executable. Without it, the script looks in the usual places.
import { spawn } from "node:child_process";
import fs from "node:fs";
import http from "node:http";
import os from "node:os";
import path from "node:path";

const [entry, configFile, ...mountArgs] = process.argv.slice(2);
if (!entry || !configFile) {
  console.error("usage: node browser.mjs <module>_test.mjs <config.json> [<url path>=<directory> ...]");
  process.exit(2);
}
const config = JSON.parse(fs.readFileSync(configFile, "utf8"));
const mounts = mountArgs.map((arg) => {
  const at = arg.indexOf("=");
  return [arg.slice(0, at), arg.slice(at + 1)];
});
mounts.push(["/test/", path.dirname(path.resolve(entry))]);

const types = {
  ".mjs": "text/javascript", ".js": "text/javascript", ".wasm": "application/wasm",
  ".json": "application/json", ".html": "text/html", ".map": "application/json",
};

// The page defines Mocha-style describe and it, because kotlin-test reports tests to them when they
// exist. It sends each result back to this script.
const page = `<!doctype html><meta charset="utf-8"><title>tests</title><script type="module">
globalThis.__karma__ = { config: ${JSON.stringify(config)} };
const report = (line) => fetch("/report", { method: "POST", body: line });
const root = { name: "", children: [], skipped: false };
let current = root;
function suite(name, body, skipped) {
  const node = { name, children: [], skipped: skipped || current.skipped };
  current.children.push(node);
  const parent = current;
  current = node;
  try { body(); } finally { current = parent; }
}
globalThis.describe = (name, body) => suite(name, body, false);
globalThis.xdescribe = (name, body) => suite(name, body, true);
globalThis.it = (name, body) => current.children.push({ name, body, skipped: current.skipped });
globalThis.xit = (name, body) => current.children.push({ name, body, skipped: true });
globalThis.kotlinTest ??= { adapterTransformer: (adapter) => adapter };
const counts = { passed: 0, failed: 0, ignored: 0 };
async function run(node, prefix) {
  for (const child of node.children) {
    const name = prefix ? prefix + "." + child.name : child.name;
    if (child.children) { await run(child, name); continue; }
    if (child.skipped) { counts.ignored++; continue; }
    try { await child.body(); counts.passed++; }
    catch (error) { counts.failed++; await report("Failed " + name + ": " + (error?.stack ?? error)); }
  }
}
try {
  const tests = await import("/test/${path.basename(entry)}");
  if (typeof tests.startUnitTests === "function") tests.startUnitTests();
  await run(root, "");
} catch (error) {
  counts.failed++;
  await report("The test module failed to load: " + (error?.stack ?? error));
}
await report("DONE " + JSON.stringify(counts));
</script>`;

let chrome;
let finished = false;
const server = http.createServer((request, response) => {
  const url = decodeURIComponent(new URL(request.url, "http://localhost").pathname);
  if (request.method === "POST" && url === "/report") {
    let body = "";
    request.on("data", (chunk) => { body += chunk; });
    request.on("end", () => {
      response.end();
      if (body.startsWith("DONE ")) {
        const counts = JSON.parse(body.slice(5));
        console.log(`${counts.passed} passed, ${counts.failed} failed, ${counts.ignored} ignored`);
        finish(counts.failed > 0 || counts.passed === 0 ? 1 : 0);
      } else {
        console.log(body);
      }
    });
    return;
  }
  if (url === "/" || url === "/index.html") {
    response.writeHead(200, { "content-type": "text/html" });
    response.end(page);
    return;
  }
  const mount = mounts.find(([prefix]) => url.startsWith(prefix) || url === prefix.replace(/\/$/, ""));
  const file = mount && (mount[0].endsWith("/") ? path.join(mount[1], url.slice(mount[0].length)) : mount[1]);
  if (!file || !fs.existsSync(file) || !fs.statSync(file).isFile()) {
    response.writeHead(404);
    response.end();
    return;
  }
  response.writeHead(200, { "content-type": types[path.extname(file)] ?? "application/octet-stream" });
  fs.createReadStream(file).pipe(response);
});

function findChrome() {
  if (process.env.CHROME) return process.env.CHROME;
  const candidates = [
    "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
    "/usr/bin/google-chrome", "/usr/bin/google-chrome-stable", "/usr/bin/chromium", "/usr/bin/chromium-browser",
  ];
  const found = candidates.find((candidate) => fs.existsSync(candidate));
  if (!found) throw new Error("No Chrome found. Set CHROME to the Chrome executable.");
  return found;
}

function finish(code) {
  if (finished) return;
  finished = true;
  chrome?.kill();
  server.close();
  process.exitCode = code;
}

const profile = fs.mkdtempSync(path.join(os.tmpdir(), "kite-web-tests-"));
server.listen(0, "127.0.0.1", () => {
  const { port } = server.address();
  chrome = spawn(findChrome(), [
    "--headless=new", "--no-first-run", "--no-default-browser-check", "--disable-gpu",
    `--user-data-dir=${profile}`, `http://127.0.0.1:${port}/`,
  ], { stdio: "ignore" });
  chrome.on("exit", () => {
    fs.rmSync(profile, { recursive: true, force: true });
    if (!finished) {
      console.log("Chrome stopped before the tests finished.");
      finish(1);
    }
  });
});
// The worker test allows 90 seconds per strategy. Nothing here takes 15 minutes.
setTimeout(() => {
  console.log("The tests did not finish in 15 minutes.");
  finish(1);
}, 15 * 60 * 1000).unref();

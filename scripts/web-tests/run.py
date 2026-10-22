#!/usr/bin/env python3
"""Run the web tests that `./kotlin test` cannot run.

`./kotlin test` cannot run JS tests. It runs wasmJs tests in Chrome, but its page cannot reach the codec
modules or the Worker program. This script links the tests with the toolchain, then runs them:

- js: the JS tests of kiteffmpeg, in Node, through runner.mjs.
- wasmJs: the wasmJs tests of kiteffmpeg, in Node, with the codec modules named in the environment.
- wasmJsBrowser: the same tests in headless Chrome, through browser.mjs, which serves the codec modules.
- worker: the test of kiteffmpeg-web-worker-test in headless Chrome, with the probe program it starts.

The codec modules come from `./kotlin do linkKiteFFmpegWasmModule` (build/kite-web) and
`./kotlin do linkKiteFFmpegAsyncWasmModules` (build/kite-web-async). A test passes without its module,
unless you pass --require-modules.
"""
import argparse
import glob
import json
import os
import tempfile
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
MODULE = "kiteffmpeg"
ARTIFACTS = ROOT / "build/artifacts/CompiledWebArtifact"
CACHE = Path.home() / ("Library/Caches/JetBrains/Kotlin" if sys.platform == "darwin" else ".cache/JetBrains/Kotlin")


def node():
    """Node from PATH, else the one the Kotlin Toolchain downloaded."""
    import shutil
    found = shutil.which("node")
    if found:
        return found
    downloaded = sorted(glob.glob(str(CACHE / "extract.cache/*node-v*/bin/node")))
    if downloaded:
        return downloaded[-1]
    raise RuntimeError(f"No node executable on PATH or in {CACHE}")


def run(*args, env=None):
    print("Running " + " ".join(str(a) for a in args[:3]) + (" ..." if len(args) > 3 else ""), flush=True)
    subprocess.run([str(a) for a in args], cwd=ROOT, text=True, check=True, env=env)


def kotlin(*args):
    run(ROOT / ("kotlin.bat" if sys.platform == "win32" else "kotlin"), *args)


def linked(pattern):
    """The newest test entry that matches pattern under the toolchain's web artifacts."""
    found = sorted(ARTIFACTS.glob(pattern), key=lambda p: p.stat().st_mtime)
    if not found:
        raise RuntimeError(f"No linked test module matches {ARTIFACTS / pattern}")
    return found[-1]


def js_tests(executable):
    kotlin("task", f":{MODULE}:linkJsTest")
    run(executable, HERE / "runner.mjs", linked(f"{MODULE}jsTest*/kotlin-output/{MODULE}_test.mjs"))


def wasm_js_tests(executable, require_modules):
    kotlin("task", f":{MODULE}:linkWasmJsTestDebug")
    env = dict(os.environ)
    env["KITEFFMPEG_WEB_MODULE"] = str(ROOT / "build/kite-web/kite.mjs")
    env["KITEFFMPEG_WEB_ASYNC_MODULES"] = str(ROOT / "build/kite-web-async")
    env["KITEFFMPEG_WEB_HLS_FIXTURE"] = str(ROOT / "native/kitecodec-web/tests/hls-fixture.json")
    env["KITEFFMPEG_WEB_MODULE_REQUIRED"] = "true" if require_modules else "false"
    entry = linked(f"{MODULE}wasmJsTest*/kotlin-output/{MODULE}_test.mjs")
    run(executable, HERE / "runner.mjs", entry, env=env)


def browser(executable, module, config, mounts):
    kotlin("task", f":{module}:linkWasmJsTestDebug")
    entry = linked(f"{module}wasmJsTest*/kotlin-output/{module}_test.mjs")
    with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as file:
        json.dump(config, file)
    try:
        run(executable, HERE / "browser.mjs", entry, file.name, *[f"{url}={path}" for url, path in mounts])
    finally:
        os.unlink(file.name)


def modules_config(require_modules):
    """The addresses the page serves the codec modules at, or None for a module that is not linked."""
    web, web_async = ROOT / "build/kite-web", ROOT / "build/kite-web-async"
    served = lambda directory, name, url: url if (directory / name).is_file() else None
    config = {
        "jspi": served(web_async, "kite-jspi.mjs", "/kite-web-async/kite-jspi.mjs"),
        "asyncify": served(web_async, "kite-asyncify.mjs", "/kite-web-async/kite-asyncify.mjs"),
        "hlsFixture": "/kite-hls-fixture.json",
        "required": require_modules,
    }
    mounts = [("/kite-web/", web), ("/kite-web-async/", web_async),
              ("/kite-hls-fixture.json", ROOT / "native/kitecodec-web/tests/hls-fixture.json")]
    return config, served(web, "kite.mjs", "/kite-web/kite.mjs"), mounts


def wasm_js_browser_tests(executable, require_modules):
    config, module, mounts = modules_config(require_modules)
    browser(executable, MODULE, {"kiteModules": dict(config, module=module)}, mounts)


def worker_tests(executable, require_modules):
    probe = "kiteffmpeg-web-worker-probe"
    kotlin("build", "-m", probe)
    found = sorted((ROOT / "build/tasks").glob(f"_{probe}_buildWasmJsAppWasmJsDebug"))
    if not found:
        raise RuntimeError(f"No build of {probe} under build/tasks")
    config, _, mounts = modules_config(require_modules)
    config["worker"] = f"/worker/{probe}.mjs"
    browser(executable, "kiteffmpeg-web-worker-test", {"kiteWorker": config}, mounts + [("/worker/", found[-1])])


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--platform", choices=("js", "wasmJs", "wasmJsBrowser", "worker"), action="append",
                        help="run one platform; repeat it for more (default: js and wasmJs)")
    parser.add_argument("--require-modules", action="store_true",
                        help="fail the wasmJs tests that need a codec module when it is not linked")
    args = parser.parse_args()
    platforms = args.platform or ["js", "wasmJs"]
    executable = node()
    if "js" in platforms:
        js_tests(executable)
    if "wasmJs" in platforms:
        wasm_js_tests(executable, args.require_modules)
    if "wasmJsBrowser" in platforms:
        wasm_js_browser_tests(executable, args.require_modules)
    if "worker" in platforms:
        worker_tests(executable, args.require_modules)


if __name__ == "__main__":
    try:
        main()
    except (subprocess.CalledProcessError, RuntimeError, OSError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)

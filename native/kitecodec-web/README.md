# The asynchronous input bridge of the web codec modules

The plain web codec module, `kite`, asks a byte source for bytes and needs the answer at once.
The two modules built from this folder, `kite-jspi` and `kite-asyncify`, can wait for it: the C
stack that asked is parked while JavaScript works, and goes on when the answer is there. See
[the design](../../docs/async-byte-io.md) and KiteFFmpeg issue 183.

- `kite_async_bridge.c` holds the callbacks FFmpeg calls and the exports a host calls.
- `kite_async_imports.js` is the emscripten library that marks the bridge's imports as suspending.
- `tests/check_async_module.mjs` drives the real FFmpeg in one module from Node.
- `tests/hls-fixture.json` is the 4 s HLS stream the check reads. The file says how it was made.

## Build and check

```bash
./gradlew :kiteffmpeg:checkKiteFFmpegAsyncWasmModules -Pkiteffmpeg.hostTargetsOnly=true
```

The task links both modules into `kiteffmpeg/build/kite-web-async` and runs the check on each.
It needs `emcc` and a Node with JavaScript Promise Integration on PATH.

## What a host gives a module

The host creates the module with a `kiteAsync` object:

| Member | Answers | Meaning |
| --- | --- | --- |
| `read(source, buf, len)` | a Promise | Bytes written at `buf`: their count, -1 at the end, -2 for a failure. |
| `seek(source, offset, whence, result)` | a Promise | The 64-bit position read at `offset` and the answer written at `result`. Returns 0, or a negative value to fail. |
| `open(url, source, size, seekable)` | a Promise | A nested source for `url`: 0 when it is served, -3 to decline it. |
| `close(source)` | a Promise | Releases a nested source, once. |
| `sleep(usec)` | a Promise | Resolves after the wait FFmpeg asked for. |
| `tags(source, tags)` | at once | Hands over the tags the last read brought. |
| `location(source, buf, cap)` | at once | The address the bytes came from, or 0 for the address asked for. |

`Module.kiteAsyncCall(name, args)` calls an export that can park and answers with a Promise.
`Module.kiteAsyncStrategy` is `"jspi"` or `"asyncify"`.

## Two rules

One bridge serves both mechanisms, and Asyncify sets the rules:

- An export that can park takes no 64-bit parameter.
- A suspending import returns no 64-bit value.

Every 64-bit value crosses through memory instead.

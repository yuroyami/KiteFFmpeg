# Asynchronous byte sources

A `MediaByteSource` must answer each read at once. That suits bytes in memory, a file or a blocking
HTTP client, but not a source that answers later, such as a Fetch response in a browser. An
`AsyncMediaRuntime` reads media from an `AsyncMediaByteSource`, whose calls suspend until the bytes
arrive.

Use the runtime when your bytes come from suspending code. For everything else, the synchronous
API in [Decoding frames](decoding.md) is simpler.

## Create a runtime

A runtime owns one lane, which is the one place where it runs codec work. On the JVM, Android and
native, the lane is a thread of its own, and FFmpeg waits there while your source works elsewhere:

```kotlin
val runtime = FFmpeg.createAsyncRuntime()
```

On the web, the lane is a codec module of its own, apart from the module that `KiteFFmpegWeb.load()`
installs. `FFmpeg.createAsyncRuntime()` fails there with `FFmpegError.Unsupported`, so load the
runtime from the web loader instead:

```kotlin
val runtime = KiteFFmpegWeb.loadAsyncRuntime(
    WebAsyncCodecArtifacts(
        jspiUrl = "./kite-jspi.mjs",
        asyncifyUrl = "./kite-asyncify.mjs",
    ),
)
```

The two modules come in `kiteffmpeg-wasm-js-<version>-web-async.zip`, beside the `web` zip of the
`wasmJs` artifact on Maven Central. Unpack it beside your page. `kite-jspi` needs JavaScript
Promise Integration in the browser. `kite-asyncify` runs in every browser, and it is larger. The
loader picks one module like this:

| Promise Integration | Addresses given | Module |
| --- | --- | --- |
| Present | Both, or `jspiUrl` only | `kite-jspi` |
| Present | `asyncifyUrl` only | `kite-asyncify` |
| Absent | Both, or `asyncifyUrl` only | `kite-asyncify` |
| Absent | `jspiUrl` only | Fails with `FFmpegError.Unsupported` |

`WebAsyncCodecArtifacts` refuses no address and a blank address. A module that fails to load or to
validate fails the call, and the loader never tries the other address instead. Each call creates a
new runtime with a module of its own. Under a bundler, instantiate the module yourself and pass it
to `KiteFFmpegWeb.attachAsyncRuntime(codecModule)`. A module has one owner, so a second attach of
the same module throws `IllegalStateException`.

Close the runtime when you are done. The close also closes every source, packet, decoder and frame
that it gave out and that is still open.

## Write a byte source

An `AsyncMediaByteSource` has the same shape as `MediaByteSource`, with suspending calls. This one
serves a `ByteArray`. A real one would await a network client in `read`:

```kotlin
import io.github.yuroyami.kiteffmpeg.AsyncMediaByteSource

class BytesSource(private val bytes: ByteArray) : AsyncMediaByteSource {
    private var position = 0L

    override val seekable: Boolean = true

    override suspend fun size(): Long = bytes.size.toLong()

    override suspend fun read(into: ByteArray, offset: Int, length: Int): Int {
        if (position >= bytes.size) return -1
        val count = minOf(length.toLong(), bytes.size - position).toInt()
        bytes.copyInto(into, offset, position.toInt(), position.toInt() + count)
        position += count
        return count
    }

    override suspend fun seek(position: Long) {
        this.position = position
    }

    override suspend fun close() {}
}
```

`read` returns the count of bytes it read (at least 1), or -1 at the end. It never returns 0.
`size()` returns null when the size is unknown, and `seek` is called only when `seekable` is true.

## Read media

`runtime.open` takes the same parameters as the byte-source `MediaSource.open`, and returns an
`AsyncMediaSource`. Its `info` describes the container, and its decode flows emit `AsyncFrame`:

```kotlin
import io.github.yuroyami.kiteffmpeg.FFmpeg
import io.github.yuroyami.kiteffmpeg.useAsync

FFmpeg.createAsyncRuntime().useAsync { runtime ->
    runtime.open(BytesSource(bytes)).useAsync { source ->
        val video = source.info.primaryVideo ?: return@useAsync
        source.decodedFrames(video).collect { frame ->
            frame.useAsync { println("${it.info.width}x${it.info.height}") }
        }
    }
}
```

`useAsync` closes the resource after the block, also when the block fails or the caller is
canceled. Every handle of the runtime is `AsyncCloseable`, because its close waits for the lane.

An `AsyncMediaSource` also has `decodeStreams`, `extractFrame`, `seekMicros`, `pause`, `resume` and
`interrupt`. An `AsyncFrame` copies its pixels with the suspending `copyPlanesToByteArray()` and
`copyPlanesInto(...)`. The packet reader and the decoders (`openPacketReader`, `openDecoder` and
`openSubtitleDecoder`) need `@OptIn(KiteFFmpegLowLevelApi::class)`, as in the synchronous API.

To buffer frames, use `bufferAsyncFrames()`. It closes every frame that the collector never
receives. It takes `Channel.BUFFERED` or a count of zero or more, and it refuses an unlimited or
conflated buffer, because that would hold or lose native memory.

## Serve the addresses that the media names

An HLS playlist names its segments and keys. Pass an `AsyncMediaByteOpener` as `nestedOpener` to
serve them:

```kotlin
val source = runtime.open(
    io = fetchSource(playlistUrl),
    url = playlistUrl,
    nestedOpener = { address ->
        if (address.startsWith("https://cdn.example/")) fetchSource(address) else null
    },
)
```

Here `fetchSource` stands for your own code that returns an `AsyncMediaByteSource`. The addresses
come from the media, which is untrusted input, so open only the schemes and hosts that you expect,
and return null for every other address. Return a new source object for every call.

## Rules for a source

- **The runtime owns the source.** It owns it from the call of `open`, also when the open fails or
  the caller is canceled, and it calls `close` exactly once. A source that the opener returns
  belongs to the runtime from the moment it returns.
- **One source object serves one open.** The runtime refuses an object that it already holds, with
  `IllegalArgumentException`.
- **Calls arrive one at a time.** A source must not call the runtime that is reading it, from any
  of its methods.
- **Honor cancellation, and put a limit on every wait.** A canceled operation cancels the call in
  flight and calls `close` while that call may still be finishing. So `close` must stop the
  transport, and a `read` that returns after its cancellation must not write into its array.

## How cancellation and waiting work

- The runtime runs one operation at a time. While one source waits for bytes, every other
  operation of the same runtime waits too, the copy and the close of a frame included. Copy the
  pictures and the sound you need before you start a read that may wait. Two runtimes do not wait
  for each other.
- A caller canceled while it waits for the lane starts nothing, and the source stays usable.
- A caller canceled after its read entered FFmpeg stops that source for good. The runtime waits
  for FFmpeg to unwind, and every later operation of the source fails with
  `FFmpegError.Interrupted`. Close the source and open the media again. The runtime and its other
  sources stay usable.
- `interrupt()` stops a source the same way, from any thread, without waiting.
- On the web, `FFmpeg.setLogSink` does not hear a runtime's own module. The lines that FFmpeg logs
  during a failed open still arrive on the `FFmpegException`.

## Related

- [Decoding frames](decoding.md): the synchronous `MediaSource` and `MediaByteSource`.
- [Getting started](getting-started.md#set-up-the-web): the web module that `KiteFFmpegWeb.load()`
  loads.

# Concurrency

KiteFFmpeg's API is coroutine-first: the long-running entry points (`Transcoder.transcode`, `Remuxer.remux`, `MediaSource.seekMicros`, `extractFrame`, `VideoEncoder.drive`, `AudioEncoder.drive`) are `suspend` functions, and decoded frames arrive as a `Flow<Frame>`. That makes the pipeline easy to compose. The native layer underneath still has strict rules about which code may call which object. This page collects them.

**Coroutines make KiteFFmpeg easy to call. They do not make libav thread-safe.** Confine each native object to one coroutine at a time.

## libav contexts are not thread-safe

Some KiteFFmpeg objects wrap native state: `MediaSource` (an `AVFormatContext` plus per-stream decoders), `MediaSink` and its encoders, and `FilterGraph`. Don't call any of them from concurrent coroutines. `MediaSource` takes a small state lock that turns the most common overlaps into an `IllegalStateException` rather than a native crash: a second decode flow while one is collecting, a batch decode while a `PacketReader` owns the demuxer cursor, or any call after `close`. That lock guards the bookkeeping only, not the native calls, so other overlapping calls still break its state. `FilterGraph` and `MediaSink` take a lock around each native call, so overlapping calls wait for each other, but the order of the frames then depends on the scheduler. Share none of them.

This is a rule about *concurrent access to one object*, not about threads in general. It is fine to:

- use different `MediaSource` / `MediaSink` / `FilterGraph` objects from different coroutines (even in parallel, for example transcoding two files at once),
- move a pipeline between suspension points onto whatever thread the dispatcher picks, as long as calls into any one object never overlap.

It is not fine to share one object between concurrently running coroutines.

## Stopping a blocked open, read or seek

Two members exist to be called from another thread while the work they stop is blocked.

- `MediaSource.interrupt()` is the one member of a source callable while a read, seek or decode is blocked on it. The blocked call returns with `FFmpegError.Interrupted` at FFmpeg's next check, and every later call on the source fails the same way. It is one-way, and it must never run concurrently with, or after, `close`, which stays required.
- `OpenInterrupt` reaches an open that has not returned a source yet, for example on a server that accepts the connection and then sends nothing. Create one before the open, pass it to `MediaSource.open`, and call its `interrupt()` from any thread. The source the open returns keeps the same request, so a later interrupt stops its reads too.

FFmpeg looks for either request inside its network protocols and during stream discovery, and this library looks between the reads of a `MediaByteSource`. A wait inside a caller's own `MediaByteSource.read` finishes before the request is seen. On the web nothing runs while an open runs, so the request is seen only before and after it.

## MediaSource: one coroutine context

A `MediaSource` is confined to one coroutine context. Call every member from that same context, never concurrently. That covers `streams`, `seekMicros`, `extractFrame`, and collecting `decodedFrames` or `decodeStreams`.

The demuxer is the part that cannot be shared. A demuxer reads the container and splits it into separate streams, and one `MediaSource` has one demuxer, which reads from one position. So only one decode flow may collect at a time: a second `decodedFrames` collection that starts while the first runs throws `IllegalStateException`. When you need several streams (video plus audio is the common case), use `decodeStreams`. It demuxes once and interleaves the frames for you:

```kotlin
// WRONG: the second collection throws IllegalStateException
coroutineScope {
    launch { source.decodedFrames(video).collect { /* … */ } }
    launch { source.decodedFrames(audio).collect { /* … */ } }
}

// RIGHT: one demux pass, frames interleaved, routed by stream
source.decodeStreams(listOfNotNull(source.primaryVideo, source.primaryAudio))
    .collect { frame ->
        frame.use {
            when (it.info.type) {
                MediaType.Video -> handleVideo(it)
                MediaType.Audio -> handleAudio(it)
                else -> {}
            }
        }
    }
```

The same confinement applies to seeking. `seekMicros` moves the shared demuxer position. Call it between collections, from the same context. Never call it while a flow on the same source is being collected.

## MediaSink: drive encoders concurrently, at the pace they play

All encoders attached to one `MediaSink` share the underlying muxer, and the sink locks it, while each encoder runs its codec under its own lock. So one sink's `drive` calls may run in concurrent coroutines, and they must, for an output longer than 10 seconds:

```kotlin
// WRONG for more than 10 seconds: the file holds all of the video but its last
// 10 seconds before the first audio packet (#198)
videoEncoder.drive(videoFrames)
audioEncoder.drive(audioFrames)

// RIGHT for paced sources, such as a camera and a microphone
coroutineScope {
    launch { videoEncoder.drive(videoFrames) }
    launch { audioEncoder.drive(audioFrames) }
}
```

FFmpeg's muxer holds packets for interleaving only until they span 10 seconds, so the file follows the order in which the drives hand packets over, within that window. Concurrent drives keep it while both flows deliver at the pace they play. Flows that generate frames as fast as they can drift apart, and there is no public per-frame encode yet to feed both encoders in time order from one coroutine. When both streams come from the same input, use [Transcoder](transcoding.md), which reads the file once and routes each frame to its encoder in time order.

## FilterGraph is confined too

A `FilterGraph` follows the same rule: `feedInput`, `flushInput`, and collecting the `process` flow are calls into one native graph. Feed a multi-input graph's inputs from one coroutine, in any order. Do not feed them from several coroutines at once.

## Close every collected frame

Frames emitted by `decodedFrames`, `decodeStreams`, and `FilterGraph.process` are owned by the collector: each stays valid until you `close()` it, so `toList()` and handing a frame to another coroutine are safe. `buffer()`, `flowOn`, `conflate` and `produceIn` are not: a flow canceled part way drops the frames they queued without closing them, so use `bufferFrames()` instead. The obligation is release, not timing. Close every collected frame, or its native buffers leak. Callback-style outputs (`FilterGraph.feedInput`'s `onOutput`) are the exception: those frames are valid only inside the callback, so call `copy()` to keep one. The full ownership contract is in [Decoding, Frame ownership](decoding.md#frame-ownership).

## Cancellation

The pipelines cooperate with structured concurrency: cancellation is honored at suspension points. Canceling the coroutine that runs `Transcoder.transcode`, collects a decode flow, or awaits a `drive` call stops the work at the next suspension and releases the native resources on the way out (decoders, frames, and graphs are freed in `finally` blocks; `use { }` handles the objects you opened yourself).

Two practical consequences:

- **Cancellation is prompt but not instantaneous.** A decode/encode step that is already inside a native call finishes that call first; the loop then observes cancellation before the next one.
- **A canceled transcode leaves a short output file.** The sink still flushes its encoders and writes the trailer as it closes, so the file is valid and holds the part written before the cancellation, including the frames the encoders still held. That drain runs after the cancel, so a slow encoder makes the cancel take that much longer. Delete it if you do not want a short file.

`Transcoder.transcode` and `Remuxer.remux` run their work on their own dispatcher, `Dispatchers.IO` unless you pass another as `dispatcher`, so the thread you call them from stays free, and a cancel sent from that same thread reaches them. Their `onProgress` callbacks run in your coroutine context. The other entry points run on the dispatcher you call them from, so call them from a background dispatcher yourself. [Transcoding](transcoding.md#threads-and-cancellation) has the details.

```kotlin
val job = launch {
    Transcoder.transcode(input = "in.mp4", output = "out.mp4", spec = spec)
}
// later:
job.cancelAndJoin()   // stops at the next suspension point, frees native state
```

## Quick reference

| Object | Rule |
|---|---|
| `MediaSource` | Confine to one coroutine context. One active flow at a time; `decodeStreams` for several streams. Seek between collections, not during. |
| `MediaSink` + encoders | Add all streams first, then drive each encoder in its own coroutine, at the pace its media plays. Sequential drives longer than 10 s do not interleave. |
| `FilterGraph` | Feed/flush/collect from one coroutine. |
| `Frame` from a flow | Owned by the collector until closed. Close every one. Decode ahead with `bufferFrames()`, never `buffer()` or `flowOn`. |
| Separate objects | Independent. Parallel pipelines over different files are fine. |

## Related

- [Decoding](decoding.md): the frame-ownership contract in full.
- [Encoding & muxing](encoding-muxing.md): driving encoders by hand.
- [Transcoding](transcoding.md): the one-call pipeline that applies all of these rules for you.

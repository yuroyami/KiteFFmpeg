# Concurrency

KiteFFmpeg's API is coroutine-first: the long-running entry points (`Transcoder.transcode`, `Remuxer.remux`, `MediaSource.seekMicros`, `extractFrame`, `VideoEncoder.drive`, `AudioEncoder.drive`) are `suspend` functions, and decoded frames arrive as a `Flow<Frame>`. That makes the pipeline easy to compose. The native layer underneath still has strict rules about which code may call which object. This page collects them.

**Coroutines make KiteFFmpeg easy to call. They do not make libav thread-safe.** Confine each native object to one coroutine at a time.

## libav contexts are not thread-safe

Some KiteFFmpeg objects wrap native state: `MediaSource` (an `AVFormatContext` plus per-stream decoders), `MediaSink` and its encoders, and `FilterGraph`. None of them may be called from concurrent coroutines. FFmpeg's contexts have no internal locking for the way KiteFFmpeg drives them; two concurrent calls into the same context corrupt state rather than merely slowing down.

This is a rule about *concurrent access to one object*, not about threads in general. It is fine to:

- use different `MediaSource` / `MediaSink` / `FilterGraph` objects from different coroutines (even in parallel, for example transcoding two files at once),
- move a pipeline between suspension points onto whatever thread the dispatcher picks, as long as calls into any one object never overlap.

It is not fine to share one object between concurrently running coroutines.

## MediaSource: one coroutine context

A `MediaSource` is confined to one coroutine context. Call every member from that same context, never concurrently. That covers `streams`, `seekMicros`, `extractFrame`, and collecting `decodedFrames` or `decodeStreams`.

The demuxer causes the most trouble. A demuxer reads the container and splits it into separate streams. Collecting two `decodedFrames` flows at the same time makes both loops call into the same demuxer concurrently. They **race**, and the result is undefined. When you need several streams (video plus audio is the common case), use `decodeStreams`. It demuxes once and interleaves the frames for you:

```kotlin
// WRONG: two concurrent flows race on the shared demuxer
coroutineScope {
    launch { source.decodedFrames(video).collect { /* … */ } }
    launch { source.decodedFrames(audio).collect { /* … */ } }
}

// RIGHT: one demux pass, frames interleaved, routed by stream
source.decodeStreams(listOfNotNull(source.primaryVideo, source.primaryAudio))
    .collect { frame ->
        when (frame.info.type) {
            MediaType.Video -> handleVideo(frame)
            MediaType.Audio -> handleAudio(frame)
            else -> {}
        }
    }
```

The same confinement applies to seeking. `seekMicros` moves the shared demuxer position. Call it between collections, from the same context. Never call it while a flow on the same source is being collected.

## MediaSink: drive encoders sequentially

All encoders attached to one `MediaSink` share the underlying muxer. Do not run one sink's `drive` calls in concurrent coroutines:

```kotlin
// WRONG: both drives funnel packets into the same muxer concurrently
coroutineScope {
    launch { videoEncoder.drive(videoFrames) }
    launch { audioEncoder.drive(audioFrames) }
}

// RIGHT: sequential drives; the muxer still interleaves the packets correctly
videoEncoder.drive(videoFrames)
audioEncoder.drive(audioFrames)
```

When both streams come from the same input, the better shape is the one [Transcoder](transcoding.md) uses internally: `decodeStreams` for a single interleaved frame flow, routing each frame to its encoder as it arrives. That runs in one coroutine from start to finish.

## FilterGraph is confined too

A `FilterGraph` follows the same rule: `feedInput`, `flushInput`, and collecting the `process` flow are calls into one native graph. Feed a multi-input graph's inputs from one coroutine, in any order. Do not feed them from several coroutines at once.

## Close every collected frame

Frames emitted by `decodedFrames`, `decodeStreams`, and `FilterGraph.process` are owned by the collector: each stays valid until you `close()` it, so buffering operators (`buffer()`, `toList()`) and handing frames across coroutines are safe. The obligation is release, not timing. Close every collected frame, or its native buffers leak. Callback-style outputs (`FilterGraph.feedInput`'s `onOutput`) are the exception: those frames are valid only inside the callback, so call `copy()` to keep one. The full ownership contract is in [Decoding, Frame ownership](decoding.md#frame-ownership).

## Cancellation

The pipelines cooperate with structured concurrency: cancellation is honored at suspension points. Cancelling the coroutine that runs `Transcoder.transcode`, collects a decode flow, or awaits a `drive` call stops the work at the next suspension and releases the native resources on the way out (decoders, frames, and graphs are freed in `finally` blocks; `use { }` handles the objects you opened yourself).

Two practical consequences:

- **Cancellation is prompt but not instantaneous.** A decode/encode step that is already inside a native call finishes that call first; the loop then observes cancellation before the next one.
- **A canceled transcode leaves a truncated output file.** The trailer is only written by a clean `MediaSink.close()` / a completed `transcode`, so treat the output of a canceled run as garbage and delete it.

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
| `MediaSink` + encoders | Add all streams first, then drive encoders sequentially from one coroutine. |
| `FilterGraph` | Feed/flush/collect from one coroutine. |
| `Frame` from a flow | Consume synchronously in the collector; copy to keep. |
| Separate objects | Independent. Parallel pipelines over different files are fine. |

## Related

- [Decoding](decoding.md): the frame-ownership contract in full.
- [Encoding & muxing](encoding-muxing.md): driving encoders by hand.
- [Transcoding](transcoding.md): the one-call pipeline that applies all of these rules for you.

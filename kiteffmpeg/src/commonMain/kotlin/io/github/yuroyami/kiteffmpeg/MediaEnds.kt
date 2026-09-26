package io.github.yuroyami.kiteffmpeg

/**
 * Where a transcode or a remux reads. [open] is called once for each open, because a transcode
 * opens its input a second time when the encoder must see the first frame. [name] is how an error
 * names the input, and [path] is set only for a path, which the same-file check needs.
 */
internal class InputEnd(val name: String, val path: String?, val open: () -> MediaSource)

/** Where a transcode or a remux writes. [path] is set only for a path. */
internal class OutputEnd(val path: String?, val open: () -> MediaSink)

internal fun inputAt(path: String, options: Map<String, String>): InputEnd =
    InputEnd(path, path) { if (options.isEmpty()) MediaSource.open(path) else MediaSource.open(path, options) }

internal fun inputFrom(source: () -> MediaByteSource): InputEnd =
    InputEnd("the caller's byte source", null) { MediaSource.open(source()) }

internal fun outputAt(path: String): OutputEnd = OutputEnd(path) { MediaSink.open(path) }

internal fun outputInto(sink: MediaByteSink, format: String, options: Map<String, String>): OutputEnd =
    OutputEnd(null) { MediaSink.open(sink, format, options) }

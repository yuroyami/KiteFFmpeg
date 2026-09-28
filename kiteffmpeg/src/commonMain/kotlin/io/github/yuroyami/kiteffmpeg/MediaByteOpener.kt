package io.github.yuroyami.kiteffmpeg

/**
 * Opens the other addresses that media read through a [MediaByteSource] refers to.
 *
 * An HLS playlist names more bytes than its own: its variant playlists, its segments and its
 * encryption keys. FFmpeg asks for each of them by address, and [open] answers with the bytes at
 * that address. Pass an opener to the byte-source [MediaSource.open] together with the playlist's
 * `url`, so that the relative addresses in the playlist resolve against it.
 *
 * - An address arrives absolute, for example `https://cdn.example/live/seg-12.ts`.
 * - An AES-128 segment arrives as the address of its encrypted bytes. The key arrives through
 *   this opener too, and KiteFFmpeg decrypts the segment itself.
 * - A live playlist asks for its own address again each time it reloads.
 *
 * The addresses come from the media, and the media is untrusted input. FFmpeg's own address
 * checks do not run when a source has an opener, so the opener is the only gate. Open only the
 * schemes and hosts you expect, and return null for every other address.
 *
 * Threading and blocking follow [MediaByteSource]. Calls arrive on the thread that drives the
 * demuxer, one at a time, and [open] may block, for example on a network request. A null or an
 * exception fails that one address. FFmpeg's HLS demuxer then logs the failure and moves to the
 * next segment, and a failure during the open fails the open.
 *
 * Lifetime. The [MediaSource] owns every byte source that [open] returns. FFmpeg closes each one
 * when it has read what it needs, and the rest close with the MediaSource.
 */
public fun interface MediaByteOpener {

    /** The bytes at [url], or null to refuse the address. */
    public fun open(url: String): MediaByteSource?
}

/** Until the C bridge serves them, the byte-source open refuses the three parameters that describe its bytes. */
internal fun refuseByteSourceHints(url: String?, mimeType: String?, nestedOpener: MediaByteOpener?) {
    if (url == null && mimeType == null && nestedOpener == null) return
    throw FFmpegException(
        FFmpegError.Unsupported(
            FFmpegError.AVERROR_PATCHWELCOME,
            "url, mimeType and nestedOpener do not reach FFmpeg yet",
        ),
    )
}

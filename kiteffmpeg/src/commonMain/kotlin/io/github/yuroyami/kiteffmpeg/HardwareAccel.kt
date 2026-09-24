package io.github.yuroyami.kiteffmpeg

/**
 * A hardware acceleration a decoder can be opened with (KiteFFmpeg window 3).
 *
 * This is deliberately NOT the same seam as naming a decoder. FFmpeg has two hardware shapes:
 * decoders that ARE the hardware path under their own name (`h264_mediacodec`), selected through
 * [MediaSource.openDecoder]'s `decoder` parameter, and HWACCELs that sit behind the ordinary
 * decoder and are attached to its context before open. VideoToolbox is the second kind, so it is
 * a request here rather than a [CodecId].
 *
 * Capability honesty: requesting an acceleration the running FFmpeg does not carry fails
 * TYPED at open with FFmpeg's own error (ENOSYS on a build without the framework), never
 * silently. A build that carries it can still refuse a particular stream at decode time; that
 * refusal arrives as software frames, visible per frame through [FrameInfo.isHardware], which is
 * what a player's fallback logic reads.
 */
public enum class HardwareAccel {
    /** Apple VideoToolbox, attached as a device context behind `h264`/`hevc`. */
    VideoToolbox,

    /**
     * Direct3D 11 video acceleration on Windows, attached as a device context behind the ordinary
     * `h264`, `hevc`, `vp9`, `mpeg2video`, `vc1` and `wmv3` decoders. Only the Windows builds
     * carry it, which are the `mingwX64` target and the JVM on Windows; on any other platform the
     * request fails typed at open. Its frames stay in GPU memory ([FrameInfo.isHardware]) until
     * [Frame.downloadFromHardware] copies them into main memory.
     */
    D3d11va,
}

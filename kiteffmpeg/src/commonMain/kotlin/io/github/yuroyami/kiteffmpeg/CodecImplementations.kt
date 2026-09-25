package io.github.yuroyami.kiteffmpeg

import kotlin.jvm.JvmInline

/**
 * One encoder, by its FFmpeg name (`libx264`, `h264_videotoolbox`, `aac`): an implementation that
 * produces a [CodecId]. [FFmpeg.codecOf] says which format, and [FFmpeg.encodersFor] lists the
 * encoders a build has for a format.
 *
 * Which encoders exist depends on how FFmpeg was built. The published artifacts are LGPL and
 * carry no libx264 or libx265; the software video encoder every build has is [Mpeg4].
 */
@JvmInline
public value class EncoderId(public val name: String) {
    public companion object {
        public val Mpeg4: EncoderId = EncoderId("mpeg4")
        public val Mjpeg: EncoderId = EncoderId("mjpeg")
        public val Png: EncoderId = EncoderId("png")
        public val Aac: EncoderId = EncoderId("aac")
        public val Opus: EncoderId = EncoderId("opus")
        public val Flac: EncoderId = EncoderId("flac")
        public val PcmS16: EncoderId = EncoderId("pcm_s16le")

        /** GPL or external encoders, present only in an FFmpeg built with them. */
        public val Libx264: EncoderId = EncoderId("libx264")
        public val Libx265: EncoderId = EncoderId("libx265")
        public val LibSvtAv1: EncoderId = EncoderId("libsvtav1")
        public val LibVpxVp9: EncoderId = EncoderId("libvpx-vp9")
        public val LibAomAv1: EncoderId = EncoderId("libaom-av1")
        public val LibOpus: EncoderId = EncoderId("libopus")
        public val LibMp3Lame: EncoderId = EncoderId("libmp3lame")

        /** Hardware encoders. They exist only in builds with the matching platform support. */
        public val H264VideoToolbox: EncoderId = EncoderId("h264_videotoolbox")
        public val HevcVideoToolbox: EncoderId = EncoderId("hevc_videotoolbox")
        public val H264MediaCodec: EncoderId = EncoderId("h264_mediacodec")
        public val HevcMediaCodec: EncoderId = EncoderId("hevc_mediacodec")
        public val H264Nvenc: EncoderId = EncoderId("h264_nvenc")
        public val HevcNvenc: EncoderId = EncoderId("hevc_nvenc")
        public val Av1Nvenc: EncoderId = EncoderId("av1_nvenc")
    }
}

/**
 * One decoder, by its FFmpeg name (`libdav1d`, `h264_mediacodec`): an implementation that reads a
 * [CodecId]. [MediaSource.openDecoder] takes one to pick a decoder other than FFmpeg's default.
 */
@JvmInline
public value class DecoderId(public val name: String) {
    public companion object {
        public val LibDav1d: DecoderId = DecoderId("libdav1d")

        /** Android's hardware decoders, present in the Android builds. */
        public val H264MediaCodec: DecoderId = DecoderId("h264_mediacodec")
        public val HevcMediaCodec: DecoderId = DecoderId("hevc_mediacodec")
        public val Av1MediaCodec: DecoderId = DecoderId("av1_mediacodec")
        public val Vp9MediaCodec: DecoderId = DecoderId("vp9_mediacodec")
        public val Vp8MediaCodec: DecoderId = DecoderId("vp8_mediacodec")
    }
}

package io.github.yuroyami.kiteffmpeg

import kotlin.jvm.JvmInline

/** What kind of data flows on a stream / through a codec / out of a filter graph. */
public enum class MediaType {
    Video, Audio, Subtitle, Data, Attachment, Unknown;

    public val isAv: Boolean get() = this == Video || this == Audio
}

/**
 * The pixel format of a video frame, named exactly as FFmpeg names it (`yuv420p`, `nv12`, …).
 * The platform bridge translates the name to an `AV_PIX_FMT_*` value.
 */
@JvmInline
public value class PixelFormat(public val name: String) {
    public companion object {
        public val Yuv420p : PixelFormat = PixelFormat("yuv420p")
        public val Yuv422p : PixelFormat = PixelFormat("yuv422p")
        public val Yuv444p : PixelFormat = PixelFormat("yuv444p")
        public val Nv12    : PixelFormat = PixelFormat("nv12")
        public val Rgb24   : PixelFormat = PixelFormat("rgb24")
        public val Rgba    : PixelFormat = PixelFormat("rgba")
        public val Bgra    : PixelFormat = PixelFormat("bgra")
        public val Gray8   : PixelFormat = PixelFormat("gray")

        /** 10-bit formats for HDR and high-bit-depth pipelines (HEVC Main10, AV1 10-bit). */
        public val Yuv420p10le : PixelFormat = PixelFormat("yuv420p10le")
        public val Yuv422p10le : PixelFormat = PixelFormat("yuv422p10le")
        public val Yuv444p10le : PixelFormat = PixelFormat("yuv444p10le")
        /** Semi-planar 10-bit, used by the VideoToolbox, NVENC and MediaCodec hardware paths. */
        public val P010le      : PixelFormat = PixelFormat("p010le")

        public val None    : PixelFormat = PixelFormat("none")
    }
}

/**
 * Audio sample format. Planar variants store each channel in its own plane; packed variants
 * interleave channels. `s16` / `s16p` is the most common decoder output.
 */
@JvmInline
public value class SampleFormat(public val name: String) {
    /**
     * Name-based heuristic (`…p` suffix). It is correct for every FFmpeg sample format name,
     * and only meaningful for names FFmpeg actually knows.
     */
    public val isPlanar: Boolean get() = name.endsWith("p")

    public companion object {
        public val U8   : SampleFormat = SampleFormat("u8");    public val U8p  : SampleFormat = SampleFormat("u8p")
        public val S16  : SampleFormat = SampleFormat("s16");   public val S16p : SampleFormat = SampleFormat("s16p")
        public val S32  : SampleFormat = SampleFormat("s32");   public val S32p : SampleFormat = SampleFormat("s32p")
        public val S64  : SampleFormat = SampleFormat("s64");   public val S64p : SampleFormat = SampleFormat("s64p")
        public val Flt  : SampleFormat = SampleFormat("flt");   public val FltP : SampleFormat = SampleFormat("fltp")
        public val Dbl  : SampleFormat = SampleFormat("dbl");   public val DblP : SampleFormat = SampleFormat("dblp")
        public val None : SampleFormat = SampleFormat("none")
    }
}

/** Codec identifier: symbolic name (`h264`, `aac`, `libx264`). Matches `avcodec_find_*_by_name`. */
@JvmInline
public value class CodecId(public val name: String) {
    public companion object {
        public val H264   : CodecId = CodecId("h264");        public val Hevc   : CodecId = CodecId("hevc")
        public val Av1    : CodecId = CodecId("av1");         public val Vp9    : CodecId = CodecId("vp9")
        public val Vp8    : CodecId = CodecId("vp8");         public val Mjpeg  : CodecId = CodecId("mjpeg")
        public val Aac    : CodecId = CodecId("aac");         public val Mp3    : CodecId = CodecId("mp3")
        public val Opus   : CodecId = CodecId("opus");        public val Vorbis : CodecId = CodecId("vorbis")
        public val Flac   : CodecId = CodecId("flac");        public val PcmS16 : CodecId = CodecId("pcm_s16le")
        public val Libx264 : CodecId = CodecId("libx264");    public val Libx265 : CodecId = CodecId("libx265")
        public val LibOpus : CodecId = CodecId("libopus");    public val LibMp3 : CodecId = CodecId("libmp3lame")
        public val Png     : CodecId = CodecId("png")

        /** Hardware encoders. They resolve at runtime only on builds with the matching hwaccel. */
        public val H264VideoToolbox : CodecId = CodecId("h264_videotoolbox")
        public val HevcVideoToolbox : CodecId = CodecId("hevc_videotoolbox")
        public val H264MediaCodec   : CodecId = CodecId("h264_mediacodec")
        public val HevcMediaCodec   : CodecId = CodecId("hevc_mediacodec")
    }
}

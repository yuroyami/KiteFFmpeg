# kiteffmpeg-gpl

> **GPL build variant of KiteFFmpeg.** Drop-in replacement for `kiteffmpeg-core` that adds libx264 / libx265 for quality-focused software H.264 / H.265 encode.

## When to use this artifact

- Open-source projects whose own license is GPL-compatible
- Server-side / desktop tooling where GPL distribution is acceptable
- "Pro" / power-user encoder builds (e.g. Eurika Encoder's Pro download)
- Internal-only deployments where redistribution isn't a concern

## When **not** to use this

- iOS App Store and Mac App Store apps: GPL section 7 is incompatible with App Store terms
- Closed-source commercial apps: GPL requires full source disclosure
- Any product where you want flexible licensing: once you link this, the whole app is GPL

For those cases, use **`kiteffmpeg-core`** (LGPL) instead, paired with platform hardware encoders (`h264_videotoolbox`, `h264_mediacodec`; NVENC and WebCodecs are on the roadmap) or `libsvtav1` for AV1.

## Status

**Skeleton module, not yet implemented.** It is not built or published, and it is commented out of `settings.gradle.kts`.

The FFmpeg build side already exists in `buildSrc`: `BuildFFmpegTask` builds **LGPL by default**, and the GPL flavour (`--enable-gpl --enable-version3 --enable-libx264 --enable-libx265`) is available today through the `:kiteffmpeg-core:buildFFmpegFor<Target>Gpl` task variants, which write to `native-libs/gpl/<target>/`. Building `kiteffmpeg-core` with `-Pkiteffmpeg.ffmpeg.license=gpl` links against that tree. What remains is packaging that flavour as this drop-in artifact.

Implementation plan:

1. ~~Flip the FFmpeg build default from GPL to LGPL~~. Done: `BuildFFmpegTask` defaults to LGPL; GPL is an explicit opt-in via the `Gpl` task variants.
2. This module's `build.gradle.kts` will declare the same Kotlin Multiplatform targets as `kiteffmpeg-core` and pull native libs from `native-libs/gpl/<target>/` instead of `native-libs/lgpl/<target>/`.
3. Re-export the entire `kiteffmpeg-core` public API by depending on `kiteffmpeg-core` as an `api(project(":kiteffmpeg-core"))`. Consumers should be able to swap `kiteffmpeg-core` for `kiteffmpeg-gpl` in their Gradle deps without touching any Kotlin code.
4. The only API difference: `kiteffmpeg-gpl` registers `libx264` and `libx265` as available encoders. Consumer code uses `CodecId.Libx264` etc. exactly as today; in `kiteffmpeg-core` builds that codec name won't be resolvable at runtime and the encoder factory throws.

## Coordinates (once published)

```kotlin
// LGPL (default, App Store safe)
implementation("io.github.yuroyami:kiteffmpeg-core:0.x.x")

// GPL (mutually exclusive: pick one, not both)
implementation("io.github.yuroyami:kiteffmpeg-gpl:0.x.x")
```

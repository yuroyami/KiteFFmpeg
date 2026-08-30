# Licensing

KiteFFmpeg's own Kotlin code is **Apache-2.0**. The FFmpeg it links against is not. FFmpeg is **LGPL-2.1 or later**, or **GPL** when built with `--enable-gpl`. When you ship an app that embeds KiteFFmpeg, the FFmpeg license travels with your binary, and it carries obligations. This page is the practical guide to meeting them.

!!! warning "Not legal advice"
    This page summarizes the obligations as they are commonly understood. It also explains how KiteFFmpeg's build outputs help you meet them. It is not legal advice. For a commercial product, have a lawyer review your distribution plan.

## The two flavors

You choose the license when FFmpeg is built, not in Kotlin code:

| Flavor | Configure | Effective license | libx264 / libx265 | Who builds it |
|---|---|---|---|---|
| **LGPL** | no `--enable-gpl` | LGPL-2.1+ | no | KiteFFmpeg, and this is what ships |
| **GPL** | `--enable-gpl`, usually `--enable-version3` | **GPL-3.0** with version3 | yes | you, in your own tree |

`buildFFmpegFor<Target>` produces the LGPL flavour, and every published artifact carries it.
**There is no GPL task.** `buildFFmpegFor<Target>Gpl` existed once and was deleted on 2026-08-21:
shipping a GPL-flavoured binary decides the licence of every application that links it, which is not
a library's decision to make. `-Pkiteffmpeg.ffmpeg.license=gpl` still selects
`native-libs/gpl/<target>/`, so the GPL route is a tree you build and own.
[Platform support](platforms.md#licensing) explains how to select a flavour.

## LGPL obligations when you distribute

The LGPL flavor does **not** make your app open source. It does obligate you, whenever you distribute the app to others, to do four things.

1. **Ship the license text.** Include FFmpeg's `COPYING.LGPLv2.1` and its `LICENSE.md`, which lists the per-component terms. An about screen, a bundled `licenses/` directory, or an oss-attribution page all work.
2. **Tell users FFmpeg is in there** and that it is LGPL-licensed, with a pointer to its source.
3. **Offer the FFmpeg source code.** You must offer the *complete corresponding source* of the exact FFmpeg you built, including your own modifications. KiteFFmpeg's FFmpeg releases attach the exact source tarball (`ffmpeg-<version>-source.tar.gz`) next to the binary zips. Pointing at that release asset satisfies the offer, and it does not depend on a third-party URL staying alive. A URL to the upstream tag or commit also works in practice. The conservative reading of the license says the offer must remain valid, so host or link a copy that will.
4. **Let users relink**, under LGPL-2.1 §6. Users must be able to substitute a modified FFmpeg and run your app with it. How hard that is depends on how you link.

### Static and dynamic linking under §6

- **Dynamic linking** satisfies §6 naturally, whether you link system dylibs and `.so` files or FFmpeg dylibs you bundle yourself. The user replaces the library file. **Prefer dynamic linking where it is practical.** This is what KiteFFmpeg's system-FFmpeg mode does.
- **Static linking** (KiteFFmpeg's vendored `.a` builds) still complies, but only if you provide the material needed to relink. That means your app's object files, or an equivalent mechanism, so a user can produce a working binary against their own FFmpeg. Most vendors meet this by offering the linkable object code on request.

!!! warning "The App Store problem"
    Static linking plus the iOS App Store is the hardest case. Even if you offer object files, a user cannot practically install a relinked binary on a stock iPhone. Whether that satisfies §6 is disputed, and this is the argument that historically kept VLC out of the App Store. Positions differ, and many apps do ship LGPL code statically linked with an object-file offer. If you want to be conservative on Apple platforms, keep FFmpeg as a dynamically loaded framework inside your app bundle, so it can at least be replaced in the bundle. Take real legal advice before you ship.

### What the KiteFFmpeg release zips include

The prebuilt FFmpeg archives on KiteFFmpeg's GitHub Releases are named `ffmpeg-<version>-<license>-<triple>.zip`; since 2026-08-22 they are the publication pipeline's input (their contents get embedded into the published klibs) and the LGPL source-offer anchor. Each one is packaged for compliance. Next to `include/` and `lib/`, every zip carries:

- `COPYING.LGPLv2.1`, always. The `gpl` zips add `COPYING.GPLv2` and `COPYING.GPLv3`.
- `LICENSE.md` from the FFmpeg source tree, which is the per-component license map.
- `BUILD-INFO.txt`, which records the FFmpeg tag and commit, the full `./configure` line, the target, the license profile, and the source-code URL for that exact build.

The release itself also carries `ffmpeg-<version>-source.tar.gz`. That is the exact FFmpeg source those binaries were built from, so the source offer is self-contained on the same page as the binaries.

Ship the license texts onward with your app. Use `BUILD-INFO.txt` plus the attached source tarball to satisfy the source offer. Together they identify and provide the complete corresponding source.

## GPL flavor restrictions

The GPL flavor is different in kind: linking it makes the **whole combined work GPL-3.0**. If you distribute that work, then:

- your application's full source code must be available under a GPL-compatible license,
- you cannot use App Store, closed-source or proprietary distribution,
- open-source apps, server-side tools and internal tools are fine. The GPL's obligations trigger on *distribution*, so purely internal or server use does not require releasing source to the world.

If any of that is a problem, stay on the LGPL flavour. Use the hardware encoders (VideoToolbox on
Apple, MediaCodec on Android) or the `mpeg4` software baseline instead of x264 and x265.

## Third-party components

The FFmpeg build is not only FFmpeg, but it is very nearly so. This list was read out of the
published `lib/` directories rather than from the configure line, and it is short on purpose: a
self-contained artifact can only bundle what cross-compiles for all eleven targets.

| Component | License | Where it is |
|---|---|---|
| FFmpeg (libav\*) | LGPL-2.1+ | bundled in every published artifact |
| dav1d | BSD-2-Clause | bundled in every published artifact; it is the software AV1 decoder |
| zlib | zlib license | linked from the platform SDK or the system, not bundled |

**Nothing else is linked.** No SVT-AV1, libvpx, libaom, libopus, libmp3lame, libwebp, FreeType,
HarfBuzz, FriBidi or libass. An earlier "fat" desktop profile enabled most of those and was deleted
on 2026-08-22, because it could never produce a self-contained Release asset: Homebrew ships
graphite2 shared-only, so the result always trailed dynamic dependencies.

x264 and x265 appear only in a GPL tree you build yourself, and both are **GPL-2.0+**.

Permissive components (BSD, zlib) only require you to reproduce their licence text and copyright
notice. FFmpeg's LGPL obligations are the ones with real work in them, and they are above.

## Patents: a separate question

Everything above is about **copyright**. Patents are a different layer, and complying with the LGPL or the GPL does nothing for them. A copyright license is not a patent grant.

- **Software H.264 and HEVC decoding** is in every KiteFFmpeg FFmpeg profile. Active patent pools cover it in some countries: Via LA for H.264, and Access Advance, Via LA and others for HEVC. Whether a given app needs a pool license depends on what it does, where it ships, and how many users it has. **That check is the app distributor's responsibility, not KiteFFmpeg's.** Typical pool terms only start charging above significant unit volumes, so small and non-commercial apps are rarely affected. A large commercial product should ask a lawyer.
- **Hardware codecs** (VideoToolbox on Apple platforms, MediaCodec on Android) are usually covered by the patent license the device vendor already pays for. Use them where they are available. They are also faster.
- **AV1, VP8, VP9, Opus and FLAC** are intended to be royalty-free and are the safest software-codec choice. Note that Sisvel operates a patent pool that claims to read on AV1 and VP9. The Alliance for Open Media disputes this and runs a legal defense program. "Royalty-free" here is a strongly defended position, not a court-settled guarantee.
- **MP3 is patent-free.** The last patents expired in 2017.

A practical summary for a commercial product: use hardware decode where you can, choose AV1 or Opus for software encode, and get real legal advice before you ship software H.264 or HEVC to a large paying audience.

## Practical checklist

Before you ship an app that embeds KiteFFmpeg:

- [ ] Know your flavor: LGPL (default) or GPL (`-Pkiteffmpeg.ffmpeg.license=gpl`). If GPL, confirm your whole app is GPL-3.0-compatible. If it is not, switch flavors.
- [ ] Bundle the license texts: `COPYING.LGPLv2.1`, FFmpeg's `LICENSE.md`, and notices for the third-party components above. Add `COPYING.GPLv3` for the GPL flavor.
- [ ] State in your app's about or licenses screen that it uses FFmpeg and the listed components.
- [ ] Provide the source offer. Link or host the exact FFmpeg source your build used. `BUILD-INFO.txt` in the release zips records the tag, the commit, and the configure line.
- [ ] Decide your §6 story: dynamic linking is easiest, or static linking plus an offer of relinkable object files. On Apple platforms, consider the App Store problem above.
- [ ] If you ship software H.264 or HEVC decode in a large commercial app, read the [patent question](#patents-a-separate-question). Otherwise stay with hardware decode and AV1 or Opus.
- [ ] Keep KiteFFmpeg's own Apache-2.0 `LICENSE` and `NOTICE` in your attribution set.

## Related

- [Platform support](platforms.md#licensing): choosing the flavor, and per-platform encoder guidance.
- [About KiteFFmpeg](about.md#license): the short version.

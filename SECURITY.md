# Security Policy

## Why this matters here

KiteCodec parses untrusted media. Demuxing, decoding, and filtering attacker-controlled files is a classic native attack surface: a crafted container or bitstream can trigger memory corruption in the linked libav\* libraries, or in KiteCodec's own `ffkmp_*` C helpers and binding layer. That runs inside your app's process. If your application feeds user-supplied files into `MediaSource`, `Transcoder`, or `Remuxer`, treat media-parsing bugs as security bugs.

Note that many such issues are FFmpeg bugs, not KiteCodec bugs. Vulnerabilities in FFmpeg itself should go to the [FFmpeg security process](https://ffmpeg.org/security.html); we still want to hear about them if KiteCodec's pinned builds ship an affected version, so we can bump the pin.

## Reporting a vulnerability

Please **do not open a public issue** for suspected vulnerabilities (crashes on malformed input, memory corruption, out-of-bounds reads/writes, etc.).

Instead, use **GitHub private vulnerability reporting**: [github.com/yuroyami/KiteCodec/security/advisories/new](https://github.com/yuroyami/KiteCodec/security/advisories/new) ("Report a vulnerability" on the repository's Security tab).

Include if you can:

- the platform and how FFmpeg was sourced (system / vendored LGPL / vendored GPL, and its version),
- a minimal reproducing file or generator command, and the API entry point (`MediaSource.open`, `Transcoder.transcode`, ...),
- the crash output (stack trace, ASan report if available).

You should get an acknowledgement within a week. Please allow a fix to land before disclosing publicly; we will credit reporters in the advisory unless you prefer otherwise.

## Supported versions

KiteCodec is pre-1.0 and IS published to Maven Central, and this line said the opposite until
2026-08-24. That matters here rather than only being untidy: a published artifact cannot be
withdrawn, so a security fix reaches users as a NEW version and never as a correction to an old one.
There are no maintained release branches; fixes land on `main` and ship in the next patch. Versions
already on Central stay exactly as they are.

| Version | Supported |
|---|---|
| `main` (latest source) | ✅ |
| 0.0.x snapshots / anything older | no, rebuild from `main` |

## Hardening notes for integrators

- The published artifacts EMBED a pinned FFmpeg (`n8.0`, minimal codec/filter set) plus dav1d, so your exposure is exactly what the artifact version names and is auditable from it. There is no download step and no checksum for a consumer to verify or bypass.
- Keep the KiteCodec version fresh: FFmpeg regularly fixes parsing CVEs, and since FFmpeg ships inside the artifact, picking up such a fix means bumping KiteCodec.
- Sandbox or isolate the process that parses fully untrusted input where your platform allows it.

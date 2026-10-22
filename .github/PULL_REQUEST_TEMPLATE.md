## What & why

<!-- What does this PR change, and what problem does it solve? Link the issue if there is one. -->

## How was it tested

- [ ] `./kotlin test -m kiteffmpeg -p macosArm64` (or your host platform) passes
- [ ] `scripts/e2e.sh` passes (required for pipeline-affecting changes)
- **Platform tested on**: <!-- e.g. macOS arm64 -->
- **FFmpeg used**: <!-- system / vendored lgpl / vendored gpl, and version -->

## Checklist

- [ ] New/changed public API has KDoc
- [ ] Docs under `docs/` updated if behavior they describe changed
- [ ] No `kotlinx.cinterop` / `ffmpeg.*` types leaked into `commonMain`

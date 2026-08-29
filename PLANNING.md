# Where the plan lives

**KiteCodec has no plan document of its own. It never should.** KiteCodec and KitePlayer are one
product built as two repositories, and a backlog split across two files is how an item gets lost.

Everything for BOTH repositories is in the sibling checkout, in two files:

- **`../KitePlayer/MASTER_PLAN.md`** is every open item, ordered into phases. Read it every
  session. KiteCodec's items live there with everyone else's. The law: the same commit that
  changes the tree updates it; finished work is deleted; done means gone.
- **`../KitePlayer/GOTCHAS.md`** is the working rules, the build and toolchain traps, and the
  decisions with their reasons. Its admission test: if reading the code or running the gate
  would teach you this, it does not belong there.

## What used to be here, and where it went

The old planning system (`KPKMP-FUTURE.md`, `KPKMP-PAST.md` and the audit documents that fed
them, including `SOLSUPREME.md` and `SUPREME.md` from this repository and the SALANKE set from
the sibling) was distilled into the two files above on 2026-08-29 and deleted. Nothing open was
dropped: the distillation was proved by a row-coverage check before the deletion commit. Git
history keeps every original; `git log --diff-filter=D` in each repository finds them.

## The two things a KiteCodec reader most often wants

1. **The release gate.** A public claim about the pair is blocked until every box in
   MASTER_PLAN.md's release-gate table is green. KiteCodec's distribution half is finished
   (Maven Central since 2026-08-24); KitePlayer's is the plan's distribution phase.
2. **The licence story.** Every profile this repository builds is portable and LGPL, and a test
   enforces it. NOTICE names ffmpeg-n8.0 as the LGPL source offer for versions Central can
   never withdraw; that tag is kept forever.

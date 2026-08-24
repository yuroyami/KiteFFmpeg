# Where the plan lives

**KiteCodec has no plan document of its own. It never should.** KiteCodec and KitePlayer are one
product built as two repositories, and a backlog split across two files is how an item gets lost.

Everything open for BOTH repositories is in the sibling checkout:

- **`../KitePlayer/KPKMP-FUTURE.md`** is what is true and what is ahead. Read it every session.
  Its section 17.15 is the consolidated register: every open row in the project, one line each,
  with a pointer to its detail. KiteCodec's rows are the second table there, plus sections 17.16
  and 17.19.
- **`../KitePlayer/KPKMP-PAST.md`** is what already happened. Read it almost never. Section 14.114
  holds the execution logs of the 2026-08-18 surges, including their measurements.

## What used to be here

`SOLSUPREME.md` (a third party code audit of the pair) and `SUPREME.md` (a verification pass over
it, plus six execution logs) lived in this repository until 2026-08-19. Both were distilled into
KPKMP-FUTURE.md and deleted. Nothing was dropped: their open findings became register rows, their
logs became archive entries, and the distillation re-verified every claim against the tree rather
than copying it.

Git has both files if you want the originals: `git show 39470ab:SOLSUPREME.md` and
`git log --diff-filter=D -- SUPREME.md` will find them.

## The two things a KiteCodec reader most often wants

1. **The release gate.** A public claim about the pair is blocked until every box in
   KPKMP-FUTURE.md 17.17 is green. The correctness half is nearly done. The distribution half is
   now split: KiteCodec's is finished (on Maven Central, its whole `P0-11..P0-19` program closed
   2026-08-24), and KitePlayer's has not started, which is `KP-PROD` phase 1 in 17.15.
2. **What replaced the licence decision.** The portable Linux and Windows "GPL" build tasks are
   gone: they produced trees with no GPL code in them, so `P0-14` closed by deletion on 2026-08-21
   rather than being decided. Every profile this repository builds is portable and LGPL, and a test
   enforces it. The open owner decisions now live in 17.15 marked `[owner]`, and none of them are
   KiteCodec's any more.

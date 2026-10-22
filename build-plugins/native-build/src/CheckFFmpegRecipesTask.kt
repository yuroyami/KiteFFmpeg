package io.github.yuroyami.kiteffmpeg.buildtools

import java.io.File
import java.io.Serializable

/**
 * One vendored tree's identity and the recipe this checkout would bake into it.
 *
 * A data class rather than three parallel lists. Everything here is plain data.
 */
data class FFmpegRecipeExpectation(
    val taskName: String,
    val treePath: String,
    val fingerprint: Set<String>,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Fails when a vendored FFmpeg tree was baked from a different recipe than the checkout now says.
 *
 * **Why this exists.** A vendored tree is a dead artifact. Nothing rebuilds it, nothing depended on
 * the task that makes it, and so a recipe change in `native-build` and the `.a` files on disk drift
 * apart with no gate red anywhere. Measured on 2026-08-19: `av1_videotoolbox` was pinned into the
 * Apple hwaccel list and every Apple tree on the proving machine still lacked it a day later, which
 * made AV1 hardware decode impossible for reasons no check could report. The evidence was already
 * on disk the whole time; every bake stamps its exact configure line into
 * [BuildFFmpegTask.CONFIGURE_EVIDENCE_RELATIVE_PATH]. Nobody was reading it.
 *
 * `KITEFFMPEG_AUTO_BAKE=true` is the automatic answer to the same problem: it makes the interop
 * step of each target bake its tree first, when the tree is missing or stale. This check is for
 * builds that do NOT opt into that, where a red light beats a silent lie.
 *
 * A tree with no stamp is SKIPPED rather than failed: stamps arrived later, and refusing an older
 * tree would say "your recipe changed" when the truth is "this tree predates the evidence".
 */
open class CheckFFmpegRecipesTask : BuildStep() {

    val expectations = ListProperty<FFmpegRecipeExpectation>()

    fun check() {
        val stale = mutableListOf<String>()
        var checked = 0
        expectations.get().forEach { expectation ->
            val tree = File(expectation.treePath)
            val stamp = tree.resolve(BuildFFmpegTask.CONFIGURE_EVIDENCE_RELATIVE_PATH)
            if (!stamp.isFile) return@forEach
            checked++
            val reason = BuildFFmpegTask.staleReason(
                stamp.readText().trim(),
                expectation.fingerprint.toList(),
            ) ?: return@forEach
            stale += "  ${tree.name}: $reason\n    fix: ./kotlin do ${expectation.taskName}"
        }
        if (checked == 0) {
            logger.lifecycle(
                "[KiteFFmpeg] checkFFmpegRecipes: no vendored tree carries a recipe stamp, so there is " +
                    "nothing to compare. Bake one with `./kotlin do buildFFmpegFor<Target>`.",
            )
            return
        }
        if (stale.isNotEmpty()) {
            throw BuildFailure(
                "[KiteFFmpeg] ${stale.size} of $checked vendored FFmpeg tree(s) no longer match this " +
                    "checkout's recipe:\n" + stale.joinToString("\n") +
                    "\n  or set KITEFFMPEG_AUTO_BAKE=true and let the build re-bake what it needs.",
            )
        }
        logger.lifecycle(
            "[KiteFFmpeg] checkFFmpegRecipes: $checked vendored tree(s) match the current recipe.",
        )
    }
}

package io.github.yuroyami.kiteffmpeg.buildtools

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.io.Serializable

/**
 * One vendored tree's identity and the recipe this checkout would bake into it.
 *
 * A data class rather than three parallel lists, and `Serializable` rather than script-level state:
 * a task action in a `.kts` file that closes over a script `val` drags a Gradle script object into
 * the configuration cache, which it refuses to serialise. Everything here is plain data.
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
 * the task that makes it, and so a recipe change in `buildSrc` and the `.a` files on disk drift
 * apart with no gate red anywhere. Measured on 2026-08-19: `av1_videotoolbox` was pinned into the
 * Apple hwaccel list and every Apple tree on the proving machine still lacked it a day later, which
 * made AV1 hardware decode impossible for reasons no check could report. The evidence was already
 * on disk the whole time; every bake since B1 stamps its exact configure line into
 * [BuildFFmpegTask.CONFIGURE_EVIDENCE_RELATIVE_PATH]. Nobody was reading it.
 *
 * `-Pkiteffmpeg.ffmpeg.autoBake=true` is the automatic answer to the same problem: it makes the
 * compile tasks depend on the bake, so Gradle re-bakes exactly when its inputs moved. This task is
 * for builds that do NOT opt into that, where a red light beats a silent lie.
 *
 * A tree with no stamp is SKIPPED rather than failed: stamps arrived in B1, and refusing an older
 * tree would say "your recipe changed" when the truth is "this tree predates the evidence".
 */
abstract class CheckFFmpegRecipesTask : DefaultTask() {

    @get:Input
    abstract val expectations: ListProperty<FFmpegRecipeExpectation>

    @TaskAction
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
            stale += "  ${tree.name}: $reason\n    fix: ./gradlew :kiteffmpeg-core:${expectation.taskName}"
        }
        if (checked == 0) {
            logger.lifecycle(
                "[KiteFFmpeg] checkFFmpegRecipes: no vendored tree carries a recipe stamp, so there is " +
                    "nothing to compare. Bake one with :kiteffmpeg-core:buildFFmpegFor<Target>.",
            )
            return
        }
        if (stale.isNotEmpty()) {
            throw GradleException(
                "[KiteFFmpeg] ${stale.size} of $checked vendored FFmpeg tree(s) no longer match this " +
                    "checkout's recipe:\n" + stale.joinToString("\n") +
                    "\n  or set -Pkiteffmpeg.ffmpeg.autoBake=true and let the build re-bake what it needs.",
            )
        }
        logger.lifecycle(
            "[KiteFFmpeg] checkFFmpegRecipes: $checked vendored tree(s) match the current recipe.",
        )
    }
}

package io.github.yuroyami.kiteffmpeg.buildtools

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * The release job must name every target the build knows about.
 *
 * A v-tag release ships one prebuilt per triple per licence flavour. The triples live in the
 * [TargetTriple] enum, and the jobs that build them live in a GitHub Actions matrix that repeats
 * each triple by hand. Nothing compared the two, so adding a target to the enum and forgetting the
 * workflow produced a release that was short one triple, with no gate anywhere going red: the
 * build succeeds, the tests pass, and the missing binary is only discovered by the consumer who
 * needed it.
 *
 * The matrix pairs `triple:` (the enum's `dirName`) with `task:` (its `gradleSuffix`), so both
 * fields are checked, not just the presence of a name. A YAML parser is not needed for that and
 * would be the more fragile choice: the pairs are single-line flow mappings on purpose.
 */
abstract class CheckReleaseTargetMirrorTask : DefaultTask() {

    /** `.github/workflows/release-binaries.yml`. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val workflowFile: RegularFileProperty

    @TaskAction
    fun check() {
        val file = workflowFile.get().asFile
        val findings = findings(file.readText())
        if (findings.isNotEmpty()) {
            throw GradleException(
                "${file.name} and the TargetTriple enum disagree:\n" +
                    findings.joinToString("\n") { "  - $it" } +
                    "\n\nA release ships one prebuilt per triple per licence flavour, so a triple " +
                    "missing here is a binary nobody builds and no gate reports.",
            )
        }
        logger.lifecycle(
            "[KiteFFmpeg] release-binaries.yml carries all ${TargetTriple.entries.size} triples.",
        )
    }

    internal companion object {
        /** A single-line flow mapping carrying both fields, in either order. */
        private val PAIR = Regex("""\{[^}\n]*\btriple:\s*([a-z0-9-]+)[^}\n]*\}""")
        private val TASK_IN_PAIR = Regex("""\btask:\s*([A-Za-z0-9]+)""")

        /** `triple: [a, b, c]`, the matrix axis the pairs then map to tasks. */
        private val AXIS = Regex("""^\s*triple:\s*\[([^\]]*)]""", RegexOption.MULTILINE)

        /** Everything wrong with [workflow], as sentences. Empty means the two agree. */
        fun findings(workflow: String): List<String> {
            val byDirName = TargetTriple.entries.associateBy { it.dirName }
            val problems = mutableListOf<String>()
            val paired = mutableSetOf<String>()

            PAIR.findAll(workflow).forEach { match ->
                val triple = match.groupValues[1]
                val task = TASK_IN_PAIR.find(match.value)?.groupValues?.get(1)
                val known = byDirName[triple]
                if (known == null) {
                    problems += "`$triple` is not a TargetTriple. Known: " +
                        byDirName.keys.sorted().joinToString(", ")
                    return@forEach
                }
                // The job EXISTS either way, so it counts as paired. Without this, one misspelled
                // task field also reports "no job" and "no mapping", and three sentences about one
                // mistake read as three mistakes.
                paired += triple
                when {
                    task == null ->
                        problems += "`$triple` has no `task:` in its matrix entry, so the job has " +
                            "no Gradle task suffix to call."
                    task != known.gradleSuffix ->
                        problems += "`$triple` is paired with task `$task`, but TargetTriple spells " +
                            "it `${known.gradleSuffix}`."
                }
            }

            AXIS.findAll(workflow).forEach { match ->
                match.groupValues[1].split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    .forEach { triple ->
                        if (triple !in byDirName) {
                            problems += "the matrix axis lists `$triple`, which is not a TargetTriple."
                        } else if (triple !in paired) {
                            problems += "the matrix axis lists `$triple` but no entry maps it to a task."
                        }
                    }
            }

            (byDirName.keys - paired).sorted().forEach {
                problems += "`$it` is a TargetTriple with no job in this workflow."
            }
            return problems
        }
    }
}

package io.github.yuroyami.kiteffmpeg.buildtools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CheckReleaseTargetMirrorTaskTest {

    /** A workflow naming every triple correctly, built from the enum so it cannot drift here. */
    private fun completeWorkflow(): String = buildString {
        appendLine("jobs:")
        appendLine("  build:")
        appendLine("    strategy:")
        appendLine("      matrix:")
        appendLine("        triple: [${TargetTriple.entries.joinToString(", ") { it.dirName }}]")
        appendLine("        include:")
        TargetTriple.entries.forEach {
            appendLine("          - { triple: ${it.dirName}, task: ${it.gradleSuffix} }")
        }
    }

    @Test
    fun `the workflow this repository ships names every triple`() {
        assertEquals(emptyList(), CheckReleaseTargetMirrorTask.findings(completeWorkflow()))
    }

    /**
     * The failure the check exists for. A target added to the enum and forgotten in the workflow
     * ships a release one prebuilt short, and every other gate stays green: the build compiles,
     * the tests pass, and nothing anywhere reads the workflow.
     */
    @Test
    fun `a triple with no job is named`() {
        val dropped = TargetTriple.MingwX64
        val workflow = completeWorkflow()
            .lineSequence()
            .filterNot { "task: ${dropped.gradleSuffix}" in it }
            .joinToString("\n")
            // Drop it from the axis too, or the axis check reports it instead.
            .replace("${dropped.dirName}, ", "")
            .replace(", ${dropped.dirName}", "")
        val findings = CheckReleaseTargetMirrorTask.findings(workflow)
        assertEquals(1, findings.size, "expected one finding, got: $findings")
        assertTrue(dropped.dirName in findings.single(), "must name the triple: $findings")
        assertTrue("no job" in findings.single(), "must say what is wrong: $findings")
    }

    @Test
    fun `a triple whose task suffix is misspelled is named, with both spellings`() {
        val workflow = completeWorkflow().replace("task: MacosX64", "task: MacOsX64")
        val finding = CheckReleaseTargetMirrorTask.findings(workflow).single()
        assertTrue("MacOsX64" in finding && "MacosX64" in finding, "both spellings: $finding")
    }

    @Test
    fun `a triple the enum does not have is named`() {
        val workflow = completeWorkflow() + "          - { triple: haiku-m68k, task: HaikuM68k }\n"
        val finding = CheckReleaseTargetMirrorTask.findings(workflow).single()
        assertTrue("haiku-m68k" in finding && "not a TargetTriple" in finding, finding)
    }

    @Test
    fun `an axis entry with no task mapping is named`() {
        // The axis and the include list are two places, so a triple can be requested by the matrix
        // and still have no task to call.
        val workflow = completeWorkflow()
            .lineSequence()
            .filterNot { "task: ${TargetTriple.LinuxArm64.gradleSuffix}" in it }
            .joinToString("\n")
        val findings = CheckReleaseTargetMirrorTask.findings(workflow)
        assertEquals(2, findings.size, "the axis and the enum both notice: $findings")
        assertTrue(findings.any { "no entry maps it to a task" in it }, "$findings")
        assertTrue(findings.any { "no job in this workflow" in it }, "$findings")
    }

    @Test
    fun `a matrix entry missing its task field is named rather than passed over`() {
        val workflow = completeWorkflow()
            .replace("{ triple: ios-x64, task: IosX64 }", "{ triple: ios-x64 }")
        val findings = CheckReleaseTargetMirrorTask.findings(workflow)
        assertTrue(findings.any { "ios-x64" in it && "no `task:`" in it }, "$findings")
    }
}

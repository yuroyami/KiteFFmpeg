package io.github.yuroyami.kiteffmpeg.buildtools

import org.gradle.api.GradleException
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * A source this repository compiles from a git checkout under `vendor/`: where upstream keeps
 * it, the tag this repository builds, and the full commit that tag named upstream when it was
 * pinned. The commit is what a build checks, because a tag can be moved or made again and a
 * commit names the whole tree's content (#145).
 */
data class PinnedSource(val name: String, val url: String, val tag: String, val commit: String) {
    init {
        require(COMMIT.matches(commit)) { "$name's pinned commit must be a full commit id, not '$commit'" }
    }

    /** The command that makes a checkout of this source at [path], quoted for a POSIX shell. */
    fun cloneCommand(path: String): String = "git clone --depth 1 --branch $tag $url ${shellQuoted(path)}"

    private companion object {
        val COMMIT = Regex("[0-9a-f]{40}|[0-9a-f]{64}")
    }
}

/**
 * Proves that a vendored checkout holds exactly the commit a build names, and nothing beside it.
 *
 * A build used to name a source only by its tag, so a checkout of another release, or one with an
 * edited file, compiled under that tag's name, and an edit left an already built tree up to date
 * (#145). VLC's contrib build checks a git source against a pinned full commit for the same
 * reason. Here a build refuses a checkout at any other commit, and one that `git status` does not
 * call clean, counting untracked and ignored files, because a fresh clone has none. There is no
 * switch to build an edited checkout: a change to a library belongs in a patch that the build
 * applies and records, so the output never names a release it is not.
 *
 * Every git call clears git's own environment variables, which a build started from a git hook
 * inherits and which would otherwise point the call at the repository that ran the hook.
 */
object VendoredCheckout {

    /**
     * What a build would read from [dir], in one line that changes whenever that does: `absent`,
     * a reason it is not a checkout of its own, the commit it holds when git calls it clean, or
     * that commit followed by a digest of every change. Never throws, so a task can declare it as
     * an input and leave the refusal to [requirePristine], which then gets to run.
     */
    fun state(dir: File): String = try {
        when (val reading = read(dir)) {
            is Reading.Absent -> "absent"
            is Reading.Unreadable -> "unreadable: ${reading.reason}"
            is Reading.Checkout ->
                if (reading.isClean) reading.head else "${reading.head} with changes ${reading.changeDigest(dir)}"
        }
    } catch (failure: Exception) {
        "unreadable: $failure"
    }

    /**
     * Refuses unless [dir] is a git checkout of its own at [source]'s commit with nothing changed,
     * added or ignored in it. [changeAdvice] tells the reader where a change to this source
     * belongs instead.
     */
    fun requirePristine(dir: File, source: PinnedSource, changeAdvice: String) {
        val path = dir.absolutePath
        when (val reading = read(dir)) {
            is Reading.Absent -> throw GradleException(
                "The ${source.name} source is not at $path. Clone the tag this repository builds:\n" +
                    "  ${source.cloneCommand(path)}",
            )
            is Reading.Unreadable -> throw GradleException(
                "$path cannot be read as a ${source.name} checkout: ${reading.reason}.\n" +
                    "A build compiles only a git checkout of its own at the commit it names, so that " +
                    "the output is the release it says it is. Move it aside and clone the tag this " +
                    "repository builds:\n" +
                    "  ${source.cloneCommand(path)}",
            )
            is Reading.Checkout -> {
                if (reading.head != source.commit) {
                    throw GradleException(
                        "$path is checked out at ${reading.head}, but this repository builds " +
                            "${source.name} ${source.tag}, which is commit ${source.commit}. A build " +
                            "from another commit would carry the name ${source.tag} while compiling " +
                            "something else. Move it aside and clone the tag again:\n" +
                            "  ${source.cloneCommand(path)}\n" +
                            "If upstream has moved the tag itself, that is a change to review before " +
                            "the pin moves, not one to take silently.",
                    )
                }
                if (!reading.isClean) {
                    throw GradleException(
                        buildString {
                            append("$path holds ${source.name} ${source.tag} with changes beside it, ")
                            append("so a build would compile source that nothing records.")
                            if (reading.changes.isNotEmpty()) {
                                append(" git status reports:")
                                reading.changes.take(LISTED).forEach { append("\n  ").append(it.display) }
                                if (reading.changes.size > LISTED) {
                                    append("\n  and ${reading.changes.size - LISTED} more")
                                }
                            }
                            if (reading.hidden.isNotEmpty()) {
                                append("\ngit is told not to look at these tracked files, so its status ")
                                append("cannot vouch for them:")
                                reading.hidden.take(LISTED).forEach { append("\n  ").append(it) }
                                if (reading.hidden.size > LISTED) {
                                    append("\n  and ${reading.hidden.size - LISTED} more")
                                }
                                append("\nClear that with git update-index --no-assume-unchanged ")
                                append("--no-skip-worktree, or git sparse-checkout disable.")
                            }
                            append("\n").append(changeAdvice)
                            append(" To keep the changes while you do, set them aside with:\n")
                            append("  git -C ${shellQuoted(path)} stash push --all")
                        },
                    )
                }
            }
        }
    }

    private const val LISTED = 20

    /** One line of `git status`: its two status letters and the path, as git prints it. */
    internal data class Change(val code: String, val path: String) {
        val display: String get() = "$code $path"
        val isUntrackedOrIgnored: Boolean get() = code == "??" || code == "!!"
    }

    internal sealed class Reading {
        object Absent : Reading()
        data class Unreadable(val reason: String) : Reading()
        data class Checkout(val head: String, val changes: List<Change>, val hidden: List<String>) : Reading() {
            val isClean: Boolean get() = changes.isEmpty() && hidden.isEmpty()

            /**
             * A digest of every change: git's own status, the difference of the tracked files from
             * the commit, and the size and time of each untracked or ignored file. Their content
             * is not read, because an ignored tree can be a whole build; any edit moves its time.
             */
            fun changeDigest(dir: File): String {
                val digest = MessageDigest.getInstance("SHA-256")
                fun add(bytes: ByteArray) {
                    digest.update(bytes.size.toString().toByteArray())
                    digest.update(':'.code.toByte())
                    digest.update(bytes)
                }
                changes.forEach { add(it.display.toByteArray()) }
                hidden.forEach { add("hidden $it".toByteArray()) }
                val diff = git(dir, "diff", "HEAD", "--binary", "--no-ext-diff", "--no-textconv", "--no-color")
                add(if (diff.code == 0) diff.out else "diff failed: ${diff.errText}".toByteArray())
                changes.filter { it.isUntrackedOrIgnored }.forEach { change ->
                    val file = dir.resolve(change.path)
                    add("${change.path} ${file.length()} ${file.lastModified()}".toByteArray())
                }
                return "sha256:" + digest.digest().joinToString("") { "%02x".format(it) }
            }
        }
    }

    internal fun read(dir: File): Reading {
        if (!dir.isDirectory) return Reading.Absent
        val topLevel = try {
            git(dir, "rev-parse", "--show-toplevel")
        } catch (failure: IOException) {
            return Reading.Unreadable("git could not be run (${failure.message})")
        }
        if (topLevel.code != 0) return Reading.Unreadable("it is not a git checkout (${topLevel.errText})")
        val root = File(topLevel.text).canonicalFile
        if (root != dir.canonicalFile) {
            return Reading.Unreadable("it is not a git checkout of its own, but part of the one at $root")
        }
        val head = git(dir, "rev-parse", "--verify", "HEAD^{commit}")
        if (head.code != 0) return Reading.Unreadable("it has no commit checked out (${head.errText})")
        val status = git(dir, "status", "--porcelain=v1", "-z", "--untracked-files=all", "--ignored")
        if (status.code != 0) return Reading.Unreadable("git status failed (${status.errText})")
        val flags = git(dir, "ls-files", "-v", "-z")
        if (flags.code != 0) return Reading.Unreadable("git ls-files failed (${flags.errText})")
        return Reading.Checkout(head.text, parseStatus(status.out), parseHidden(flags.out))
    }

    /**
     * The entries of `git status --porcelain=v1 -z`: two status letters, a space and a path, each
     * ended by a zero byte. A rename or copy carries its source path as one more entry, which
     * belongs to the one before it and is skipped.
     */
    internal fun parseStatus(out: ByteArray): List<Change> {
        val fields = String(out, Charsets.UTF_8).split('\u0000').filter { it.isNotEmpty() }
        val changes = mutableListOf<Change>()
        var index = 0
        while (index < fields.size) {
            val entry = fields[index]
            val change = Change(entry.take(2), entry.drop(3))
            changes += change
            index += if (change.code[0] == 'R' || change.code[0] == 'C') 2 else 1
        }
        return changes
    }

    /**
     * The tracked files `git ls-files -v` marks as ones git does not look at: a lower-case letter
     * is a file assumed unchanged, and `S` is one outside a sparse checkout. `git status` reports
     * neither, so an edit hidden either way would otherwise pass as clean.
     */
    internal fun parseHidden(out: ByteArray): List<String> =
        String(out, Charsets.UTF_8).split('\u0000')
            .filter { it.length > 2 }
            .filter { it[0].isLowerCase() || it[0] == 'S' }
            .map { "${it.substring(2)} (${if (it[0] == 'S') "outside the sparse checkout" else "assumed unchanged"})" }

    internal class GitResult(val code: Int, val out: ByteArray, val err: ByteArray) {
        val text: String get() = String(out, Charsets.UTF_8).trim()
        val errText: String get() = String(err, Charsets.UTF_8).trim().lineSequence().firstOrNull().orEmpty()
    }

    /**
     * Runs git on [dir] with no optional locks, so a read never writes the checkout's index, and
     * with replace objects off, so the commit read is the one stored.
     */
    internal fun git(dir: File, vararg args: String): GitResult {
        val command = listOf("git", "--no-optional-locks", "--no-replace-objects", "-C", dir.absolutePath) + args
        val builder = ProcessBuilder(command)
        val env = builder.environment()
        GIT_LOCAL_ENV.forEach { env.remove(it) }
        val process = builder.start()
        process.outputStream.close()
        val err = CompletableFuture.supplyAsync { process.errorStream.readBytes() }
        val out = process.inputStream.readBytes()
        if (!process.waitFor(GIT_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
            process.destroyForcibly()
            throw GradleException("git ${args.joinToString(" ")} in $dir took longer than $GIT_TIMEOUT_MINUTES minutes")
        }
        return GitResult(process.exitValue(), out, err.get())
    }

    private const val GIT_TIMEOUT_MINUTES = 2L

    /** What `git rev-parse --local-env-vars` prints for git 2.43, the variables that pick a repository. */
    private val GIT_LOCAL_ENV = listOf(
        "GIT_ALTERNATE_OBJECT_DIRECTORIES", "GIT_CONFIG", "GIT_CONFIG_PARAMETERS", "GIT_CONFIG_COUNT",
        "GIT_OBJECT_DIRECTORY", "GIT_DIR", "GIT_WORK_TREE", "GIT_IMPLICIT_WORK_TREE", "GIT_GRAFT_FILE",
        "GIT_INDEX_FILE", "GIT_NO_REPLACE_OBJECTS", "GIT_REPLACE_REF_BASE", "GIT_PREFIX", "GIT_SHALLOW_FILE",
        "GIT_COMMON_DIR",
    )
}

/** [path] as one POSIX shell word: unchanged when it is plainly safe, else in single quotes. */
internal fun shellQuoted(path: String): String =
    if (path.isNotEmpty() && path.all { it.isLetterOrDigit() || it in "/._-+:@%,_" }) {
        path
    } else {
        "'" + path.replace("'", "'\\''") + "'"
    }

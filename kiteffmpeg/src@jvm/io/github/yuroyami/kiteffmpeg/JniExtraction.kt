package io.github.yuroyami.kiteffmpeg

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest

/**
 * Puts the bundled native library files into one folder that later launches reuse (#208).
 *
 * Each launch used to copy the bundle into a fresh temporary folder and rely on `deleteOnExit`.
 * Windows refuses to delete a loaded DLL, so every launch there left a 27 MB copy behind. The
 * folder is now named after the bundle's own SHA-256, so a launch finds the copy an earlier one
 * made and writes nothing. A file is rewritten only when it is missing or its bytes differ, and
 * that is checked on every launch, because the copy sits in a folder other programs can reach.
 *
 * At each extraction, older folders, from another version or from the old one-folder-per-launch
 * scheme, are deleted when they are more than an hour old. A delete that fails means another
 * process still has the library loaded, so that folder is left alone. The hour keeps a launch of
 * another version from losing a folder it has just made and not yet loaded.
 */
internal object JniExtraction {

    /** One file of the bundle: its name and its bytes. */
    class BundleFile(val name: String, val bytes: ByteArray)

    /**
     * The folder under [root] holding [files], extracted now unless an earlier launch already did.
     * Returns null when this process cannot own such a folder (another user made it, or its
     * permissions cannot be set), and the caller then falls back to a private temporary folder.
     */
    fun extract(root: File, files: List<BundleFile>, now: Long = System.currentTimeMillis()): File? {
        val digest = MessageDigest.getInstance("SHA-256")
        files.forEach { file ->
            digest.update(file.name.toByteArray())
            digest.update(0)
            digest.update(file.bytes)
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }.take(HASH_CHARS)
        val directory = File(root, "$PREFIX$hash-${userTag()}")
        removeStale(root, keep = directory, now = now)
        if (!ownedDirectory(directory)) return null
        // A folder in use reads as fresh, so another version's cleanup leaves it alone.
        directory.setLastModified(now)
        for (file in files) {
            val target = File(directory, file.name)
            if (target.isFile && sameBytes(target, file.bytes)) continue
            val partial = Files.createTempFile(directory.toPath(), ".${file.name}", ".part")
            try {
                Files.write(partial, file.bytes)
                try {
                    Files.move(partial, target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(partial, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } catch (failure: Exception) {
                // Another process may have written the same bytes first and be holding the file.
                if (!(target.isFile && sameBytes(target, file.bytes))) throw failure
            } finally {
                Files.deleteIfExists(partial)
            }
        }
        return directory
    }

    private fun sameBytes(file: File, bytes: ByteArray): Boolean =
        file.length() == bytes.size.toLong() && file.readBytes().contentEquals(bytes)

    /**
     * Makes [directory] if it is missing and says whether this process may use it: on a POSIX
     * system only when this user owns it and nobody else can write to it, because the temporary
     * folder is shared between users.
     */
    private fun ownedDirectory(directory: File): Boolean {
        val path = directory.toPath()
        val posix = "posix" in path.fileSystem.supportedFileAttributeViews()
        if (!directory.isDirectory) {
            runCatching {
                if (posix) {
                    Files.createDirectory(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
                } else {
                    Files.createDirectory(path)
                }
            }
            if (!directory.isDirectory) return false
        }
        if (!posix) return true
        return runCatching {
            val owner = Files.getOwner(path).name
            val permissions = PosixFilePermissions.toString(Files.getPosixFilePermissions(path))
            owner == System.getProperty("user.name") && permissions.substring(3) == "------"
        }.getOrDefault(false)
    }

    private fun removeStale(root: File, keep: File, now: Long) {
        val children = root.listFiles() ?: return
        for (child in children) {
            if (!child.isDirectory || child == keep) continue
            val ours = child.name.startsWith(PREFIX) || child.name.startsWith(LEGACY_PREFIX)
            if (!ours || now - child.lastModified() < STALE_AFTER_MILLIS) continue
            // A file that will not delete is a library another process has loaded; leave it all.
            val files = child.listFiles().orEmpty()
            if (files.all { it.delete() }) child.delete()
        }
    }

    /** Keeps two users of one machine out of each other's folder name. */
    private fun userTag(): String =
        System.getProperty("user.name").orEmpty().filter { it.isLetterOrDigit() }.take(USER_CHARS).ifEmpty { "user" }

    internal const val PREFIX = "kiteffmpeg-jni-"
    internal const val LEGACY_PREFIX = "kitecodec-jni"
    internal const val STALE_AFTER_MILLIS = 60L * 60L * 1000L
    private const val HASH_CHARS = 24
    private const val USER_CHARS = 32
}

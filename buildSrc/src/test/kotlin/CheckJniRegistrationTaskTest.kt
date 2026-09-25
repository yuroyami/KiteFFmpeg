package io.github.yuroyami.kiteffmpeg.buildtools

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The registration check reads what it claims to, accepts the real adapter, and rejects each kind
 * of mismatch the compiler cannot see: an extra argument, a wrong argument type, a wrong return
 * type and a row whose function does not exist.
 */
class CheckJniRegistrationTaskTest {

    private val repoRoot: File = File(System.getProperty("kiteffmpeg.repo.root") ?: "..").canonicalFile

    private val row = """KJ_METHOD("io/github/yuroyami/kiteffmpeg/Internals", "nativeGraphSend", "(JJ)I", kj_graph_send)"""

    private fun definition(signature: String) = """
        JNIEXPORT $signature
        {
            return 0;
        }
    """.trimIndent()

    private fun mismatches(signature: String) =
        JniRegistration.mismatches(JniRegistration.rows(row), listOf(definition(signature)))

    @Test
    fun descriptorsMapToTheirJniTypes() {
        assertEquals(
            JniRegistration.Signature("jlongArray", listOf("JNIEnv*", "jclass", "jstring", "jint", "jobjectArray", "jbyteArray", "jobject")),
            JniRegistration.expected("(Ljava/lang/String;I[Ljava/lang/String;[BLio/github/yuroyami/kiteffmpeg/JniByteIo;)[J"),
        )
        assertEquals(JniRegistration.Signature("void", listOf("JNIEnv*", "jclass")), JniRegistration.expected("()V"))
        assertEquals(JniRegistration.Signature("jboolean", listOf("JNIEnv*", "jclass", "jobjectArray")), JniRegistration.expected("([[I)Z"))
    }

    @Test
    fun aDefinitionOverSeveralLinesIsRead() {
        val source = """
            JNIEXPORT jlongArray JNICALL kj_graph_build_video(
                JNIEnv *env, jclass cls, jstring description,
                jint width, jint height)
            {
        """.trimIndent()
        assertEquals(
            mapOf("kj_graph_build_video" to JniRegistration.Signature("jlongArray", listOf("JNIEnv*", "jclass", "jstring", "jint", "jint"))),
            JniRegistration.definitions(source),
        )
    }

    @Test
    fun aMatchingFunctionPasses() {
        assertEquals(emptyList(), mismatches("jint JNICALL kj_graph_send(JNIEnv *env, jclass cls, jlong source, jlong frame)"))
        assertEquals(emptyList(), mismatches("jint JNICALL kj_graph_send(JNIEnv *env, jobject self, jlong source, jlong frame)"))
    }

    @Test
    fun eachKindOfMismatchIsReported() {
        val broken = listOf(
            "jint JNICALL kj_graph_send(JNIEnv *env, jclass cls, jlong source, jlong frame, jint extra)",
            "jint JNICALL kj_graph_send(JNIEnv *env, jclass cls, jlong source, jint frame)",
            "jlong JNICALL kj_graph_send(JNIEnv *env, jclass cls, jlong source, jlong frame)",
            "jint JNICALL kj_graph_sent(JNIEnv *env, jclass cls, jlong source, jlong frame)",
        )
        for (signature in broken) {
            val problems = mismatches(signature)
            assertEquals(1, problems.size, "not reported: $signature")
            assertTrue(problems.single().startsWith("kj_graph_send"), problems.single())
        }
    }

    @Test
    fun theRealAdapterMatchesEveryDescriptor() {
        val jni = repoRoot.resolve("native/kitecodec-jni")
        val rows = JniRegistration.rows(jni.resolve("methods.def").readText())
        val sources = jni.listFiles { file -> file.name.endsWith(".c") }.orEmpty().map { it.readText() }
        assertTrue(rows.size > 200, "only ${rows.size} rows parsed")
        assertEquals(emptyList(), JniRegistration.mismatches(rows, sources))
    }
}

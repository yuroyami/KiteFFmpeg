package io.github.yuroyami.kiteffmpeg

/** A path-free, order-independent transcript shared by the native, JVM and Android contract arms. */
internal class CodecContractTranscript {
    private val values = mutableMapOf<String, String>()

    fun put(key: String, value: Any?) {
        require('\n' !in key && '=' !in key) { "Transcript key is not scalar: $key" }
        val rendered = value?.toString() ?: "null"
        require('\n' !in rendered && '\r' !in rendered) { "Transcript value is not scalar: $key" }
        check(values.put(key, rendered) == null) { "Duplicate transcript key: $key" }
    }

    fun render(): String = values.entries
        .sortedBy { it.key }
        .joinToString(separator = "\n", postfix = "\n") { (key, value) -> "$key=$value" }
}

/* The KiteFFmpeg C ABI: the FFmpeg header versus runtime identity gate.
 *
 * The FFmpeg identity gate, which the plan calls the highest value
 * clause in B1. What it prevents was demonstrated live rather than argued: older FFmpeg headers
 * against a newer runtime link cleanly, every symbol resolves, and 38 measured struct field offsets
 * are wrong. 48 of the helpers in kitecodec_helpers.h read or write through one of them, so the
 * process reads wrong values and then dies inside av_frame_free, with AddressSanitizer naming a
 * four byte read 36 bytes past a 416 byte region. In the nondeterministic case it is silent.
 *
 * OPAQUE FROM BIRTH. This header includes no FFmpeg header and names no FFmpeg type. That is a
 * deliberate property and not an accident of what it happens to need: S1.a.8 grew the opaque
 * surface across the handle and helper headers, so these three headers are now the complete
 * cinterop boundary. The report is flat plain data with fixed char arrays and no pointers, because
 * cinterop binds our own struct with real offsets and Kotlin reads it with one nativeHeap.alloc plus
 * plain field reads.
 *
 * NO TWO-DIMENSIONAL ARRAYS ANYWHERE IN THE REPORT. cinterop flattens `char names[6][16]` into a
 * single byte array, so `names[i]` would be byte i and not row i, which is a wrong reading that
 * compiles. The per-library names therefore arrive through kc_ffmpeg_library_name(), one accessor
 * returning a `const char *` per index, which is the form plan section 15.2 B1.6 step 1 decides on.
 *
 * WHY THIS NEEDS A REAL LIBRARY. kc_init is guarded by pthread_once. A function-local static inside
 * a `static inline` function in a def file would give one flag per translation unit, so the gate
 * would run once per consumer of the header rather than once per process, and the expectations it
 * compares against could differ between those copies. The same argument settles where the frozen
 * header numbers come from: kitecodec_abi.c is compiled in THE C ARCHIVE'S OWN compile, against the
 * same include tree as every helper unit in that archive, so within the archive the offsets and
 * these macros came from one set of headers. The archive is now the sole FFmpeg-header baking, and
 * the compile task keeps its six version-header inputs content-tracked. cinterop parses no FFmpeg
 * header; its metadata gate instead proves that all six former LIB version constants are absent and
 * that every direct binding belongs to the `_ffkmp_` or `_kc_` boundary. The interlude's historical
 * two-bakings comparison caught a stale archive before this reduction, but there is deliberately no
 * second header baking to compare now. Nothing can recover the archive's header version after the
 * fact, which is why freezing it at compile time remains the only correct construction.
 */

#ifndef KITECODEC_ABI_H
#define KITECODEC_ABI_H

#include <stdint.h>

/* The version of THIS C surface, not of FFmpeg. Major changes when a declaration in its public headers
 * changes shape; minor when something is added compatibly. Read at runtime by kc_abi_version(). */
#define KITECODEC_C_ABI_MAJOR 2
#define KITECODEC_C_ABI_MINOR 6

/* The six libraries the gate covers, and their fixed order inside the report's arrays. Every array
 * in kc_ffmpeg_report is indexed by these, and kc_ffmpeg_library_name() names them. */
#define KC_FFMPEG_LIBRARY_COUNT 6
#define KC_LIB_AVUTIL     0
#define KC_LIB_AVCODEC    1
#define KC_LIB_AVFORMAT   2
#define KC_LIB_AVFILTER   3
#define KC_LIB_SWSCALE    4
#define KC_LIB_SWRESAMPLE 5

/* Fixed text capacities. Sized from measurement on the proving machine rather than guessed:
 * av_version_info() is "8.0", avutil_license() is "GPL version 3 or later" (22 bytes), and the
 * longest provisioning directory seen is a Homebrew Cellar path of 46 bytes. Every write into these
 * goes through one bounded copy helper, so a longer string truncates and never overruns. */
#define KC_TEXT_REF   32
#define KC_TEXT_NAME  64
#define KC_TEXT_LIST 128
#define KC_TEXT_PATH 512
/* The provisioning sentence gets its own, larger capacity, and the reason is a measurement rather than
 * caution. On the proving machine it comes out 483 bytes long against a 512 byte field: a provisioning
 * directory thirty characters longer than this one would have truncated the actionable half of the
 * sentence away, and the actionable half is the entire point of it. The bound is arithmetic over the
 * five embedded fields at their declared capacities (ref 31, dir 511, flavour 31, runtime version 63,
 * runtime licence 63) plus the fixed text: 1024 was 12 bytes short of that worst case, measured at the
 * interlude by compiling with the build defines at capacity, where the sentence came out 1011
 * bytes with two runtime fields still 101 bytes below their own caps. 1152 clears the worst case with
 * margin. tests/test_identity.c asserts the arithmetic bound, not just this machine's instance, so the
 * capacity cannot go back to being tight without a test failing on every machine. */
#define KC_TEXT_SENTENCE 1152

/* The overall outcome of the gate. Negative means reject; kc_init() returns exactly this value.
 *
 * Policy, decided in plan section 15.2 B1.6 step 3 and not to be reopened here:
 *  - major must be EXACTLY equal, hard reject, no override, because 38 field offsets were measured
 *    to move across a major and FFmpeg's own doc/developer.texi permits reordering struct contents
 *    at a major bump;
 *  - runtime minor must be AT OR ABOVE header minor, because FFmpeg guarantees backward
 *    compatibility only, so a runtime older than the headers is the dangerous direction;
 *  - micro is compared, reported, and never rejects;
 *  - the six *_configuration() strings must agree with each other, and disagreement rejects,
 *    because that is a mixed install and the version numbers alone cannot see it.
 */
enum kc_status {
    KC_STATUS_OK = 0,
    KC_STATUS_MAJOR_MISMATCH = -1,
    KC_STATUS_RUNTIME_OLDER = -2,
    KC_STATUS_CONFIGURATION_MISMATCH = -3
};

/* The per-library finding. KC_VERDICT_MICRO_OLDER is a finding and not a rejection: it is reported
 * so a bug report carries it, and the status stays KC_STATUS_OK. */
enum kc_verdict {
    KC_VERDICT_OK = 0,
    KC_VERDICT_MAJOR_MISMATCH = 1,
    KC_VERDICT_RUNTIME_OLDER = 2,
    KC_VERDICT_MICRO_OLDER = 3,
    KC_VERDICT_CONFIGURATION_DISAGREES = 4
};

/* The identity report: everything a rejection message and a bug report need, in one flat struct.
 *
 * Every numeric array is indexed by the KC_LIB_* constants above. `header_*` is what the compiler
 * saw when this library was built; `runtime_*` is what the linked FFmpeg answers today. Both columns
 * are always populated, including on a reject, because a rejection that does not say what it found
 * is a rejection nobody can act on.
 */
typedef struct kc_ffmpeg_report {
    /* KC_STATUS_OK, or the negative kc_status the comparison produced. */
    int32_t status;

    /* 0 when the gate was not bypassed. Otherwise the ORIGINAL negative status, so the fact that a
     * diagnostic bypass was used travels with every diagnostic dump a bug report carries. Plan
     * section 15.6 question 3 makes that mandatory: no investigation may start from a silently
     * bypassed gate. Note the shape: when the bypass fires, `status` becomes KC_STATUS_OK and this
     * field holds what the verdict would have been, so a caller that only looks at `status`
     * continues, and a caller that reports state sees the whole truth. */
    int32_t bypassed;

    /* KITECODEC_C_ABI_MAJOR and _MINOR of the compiled library, so a mismatch between a klib and an
     * archive built at different times is visible too. */
    int32_t abi_major;
    int32_t abi_minor;

    int32_t header_major[KC_FFMPEG_LIBRARY_COUNT];
    int32_t header_minor[KC_FFMPEG_LIBRARY_COUNT];
    int32_t header_micro[KC_FFMPEG_LIBRARY_COUNT];
    int32_t runtime_major[KC_FFMPEG_LIBRARY_COUNT];
    int32_t runtime_minor[KC_FFMPEG_LIBRARY_COUNT];
    int32_t runtime_micro[KC_FFMPEG_LIBRARY_COUNT];

    /* One kc_verdict per library. */
    int32_t verdict[KC_FFMPEG_LIBRARY_COUNT];

    /* 1 when all six *_configuration() strings are byte equal, 0 when they are not. A mixed install
     * has agreeing version numbers and disagreeing configuration strings, which is why this is a
     * separate question from the six comparisons above. */
    int32_t configuration_agrees;

    /* How many of the six disagreed with libavutil's string, and their names, comma separated. */
    int32_t configuration_disagreed_count;
    char configuration_disagreed[KC_TEXT_LIST];

    /* What the artifact was BUILT for. Supplied by the build through -DKC_BUILD_FFMPEG_REF,
     * -DKC_BUILD_FFMPEG_LICENSE and -DKC_BUILD_FFMPEG_DIR; each falls back to "unknown" when the
     * build did not say, which is itself information worth reporting. */
    char build_ffmpeg_ref[KC_TEXT_REF];
    char build_license_flavour[KC_TEXT_REF];
    char build_provisioning_dir[KC_TEXT_PATH];

    /* What the runtime answers. `runtime_license` is the contradiction check: the build declares
     * FFmpegLicense.LGPL while the linked Homebrew runtime returns "GPL version 3 or later", so both
     * strings ride in every rejection and every diagnostic dump and the contradiction is visible
     * instead of latent. Resolving it is B7's, not this gate's. */
    char runtime_version_info[KC_TEXT_NAME];
    char runtime_license[KC_TEXT_NAME];

    /* One actionable sentence: what to link, or how to rebuild, plus the bypass and the warning that
     * it is not a supported configuration. Never empty. Sized so that even with every embedded field
     * at its declared capacity the sentence fits untruncated, which is asserted arithmetically by
     * tests/test_identity.c; see KC_TEXT_SENTENCE for the measured bound. */
    char provisioning[KC_TEXT_SENTENCE];
} kc_ffmpeg_report;

/* KC_API marks a symbol as deliberately exported. Guarded because kitecodec_helpers.h defines the
 * same macro with the same replacement text, and the two headers meet in one translation unit:
 * kitecodec_abi.h is listed LAST in ffmpeg.def's `headers` line. An identical redefinition is
 * benign in C, and the guard makes it impossible to depend on which order they arrive in. */
#ifndef KC_API
#if defined(_WIN32)
#define KC_API __declspec(dllexport)
#else
#define KC_API __attribute__((visibility("default")))
#endif
#endif

#ifdef __cplusplus
extern "C" {
#endif

/* Run the gate, once per process, and return its verdict: KC_STATUS_OK or a negative kc_status.
 *
 * Every KiteFFmpeg entry point calls this FIRST, before anything allocates: the Kotlin factories
 * through requireCompatibleFFmpeg, and every C constructor helper through KC_GATE_OPEN below. That
 * second half was missing until the gate-refusal suite was written, and the claim on this line was
 * false for a pure C or JNI consumer, which reaches the exported helpers directly. Cheap after the
 * first call: pthread_once plus a load of a cached int. Safe to call from any thread at any time.
 *
 * When the environment variable KITECODEC_FFMPEG_ABI_BYPASS is exactly "1", a rejection is
 * downgraded to KC_STATUS_OK, a warning naming the exact mismatch with both identities is written to
 * stderr exactly once, and kc_ffmpeg_report::bypassed records what the verdict would have been. The
 * bypass is opt-in, never the default, and never quiet. It exists because an unbypassable gate turns
 * one false rejection into an outage inside a consumer's product that the consumer cannot patch; see
 * plan section 15.4 under B1.6 and section 15.6 question 3.
 */
KC_API int kc_init(void);

/* True when the gate accepts this FFmpeg. Every constructor helper asks before it builds anything,
 * which is what makes the gate hold for a pure C or JNI consumer and not only for the Kotlin call
 * sites. Cheap: kc_init after its first call is a pthread_once plus a load. */
#define KC_GATE_OPEN() (kc_init() == KC_STATUS_OK)

/* Copy the identity report into the caller's storage. Runs kc_init() first, so the report is always
 * populated. `out` may be NULL, in which case this only ensures the gate has run. */
KC_API void kc_ffmpeg_report_get(kc_ffmpeg_report *out);

/* (KITECODEC_C_ABI_MAJOR << 16) | (KITECODEC_C_ABI_MINOR << 8), the same packing FFmpeg uses for its
 * own AV_VERSION_INT, so a caller that already unpacks one unpacks this the same way. */
KC_API uint32_t kc_abi_version(void);

/* The name of library `index`, one of the KC_LIB_* constants: "libavutil", "libavcodec",
 * "libavformat", "libavfilter", "libswscale", "libswresample". Returns "" for an index out of
 * range, never NULL. This is the accessor that exists so the report needs no `char names[6][16]`. */
KC_API const char *kc_ffmpeg_library_name(int index);

/* The name of a kc_verdict: "ok", "major mismatch", "runtime older than headers", "micro older than
 * headers", "configuration disagrees". Returns "unknown" for an unrecognised value, never NULL.
 * It exists so the Kotlin side prints the verdict without keeping a second copy of this table. */
KC_API const char *kc_verdict_name(int verdict);

/* The runtime's configure line, from avcodec_configuration(). This and the
 * six *_version() queries behind the identity report, because that is where they belong: a caller
 * asking what FFmpeg it has is asking an identity question. Runs kc_init() first. The returned
 * pointer is into libavcodec's own static storage and lives for the life of the process. */
KC_API const char *kc_ffmpeg_configuration(void);

/* The outcomes of kc_jvm_attach. S1.c.1 adds this pair so a JVM consumer (the JNI bridge) can hand
 * FFmpeg the JavaVM it needs for its own MediaCodec JNI calls. Kept beside the identity surface
 * because attaching a VM is a process-identity act, not a media operation. */
enum kc_jvm_status {
    KC_JVM_OK = 0,
    KC_JVM_BAD_ARGUMENT = -1,
    KC_JVM_UNSUPPORTED = -2,
    KC_JVM_FFMPEG_REFUSED = -3
};

/* Hands the JavaVM to FFmpeg on Android and refuses meaningfully everywhere else.
 *
 * A NULL argument returns KC_JVM_BAD_ARGUMENT on every platform. On an Android build this runs the
 * identity gate first, then av_jni_set_java_vm(java_vm, NULL) inside this archive, and returns
 * KC_JVM_OK on success or KC_JVM_FFMPEG_REFUSED when FFmpeg rejects the VM. On every non-Android
 * build it returns KC_JVM_UNSUPPORTED without referencing the FFmpeg JNI symbol at all, so the
 * archive keeps linking on hosts whose FFmpeg was built without --enable-jni. The caller may invoke
 * it more than once with the same VM; FFmpeg treats a repeated identical VM as a no-op. */
KC_API int kc_jvm_attach(void *java_vm);

#ifdef __cplusplus
}
#endif

#endif /* KITECODEC_ABI_H */

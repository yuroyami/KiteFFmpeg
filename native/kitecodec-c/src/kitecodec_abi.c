/* HAND WRITTEN. Unlike its nine neighbours under src/, this unit is not generated from ffmpeg.def:
 * there was never any identity gate in the def to extract (register item B1-02). scripts/verify-lift.sh
 * compares only the nine generated helper units against the def body, so this file is outside that
 * comparison by construction and editing it is normal work rather than a gate failure.
 *
 * The FFmpeg header versus runtime identity gate. See include/kitecodec_abi.h for the contract, the
 * policy and why the report has the shape it has.
 *
 * Three properties of this file are load bearing rather than incidental.
 *
 *  1. kitecodec_ffmpeg_versions.h is included FIRST, before kitecodec_abi.h. The hermetic test in
 *     tests/test_identity.c compiles this same source several more times against shim include trees
 *     under tests/fake_headers/, and each shim renames this unit's seven exported symbols through
 *     macros so that several doctored copies can be linked into one test binary. A rename has to be
 *     in scope before kitecodec_abi.h declares the names, or the declaration and the definition would
 *     disagree and the link would fail.
 *
 *  2. The expectations are a file scope `static const` array initialised from the six
 *     LIB*_VERSION_INT macros. That bakes them into the object at compile time, in the same compile
 *     that baked every struct offset the helper layer reads through. Nothing can recover a header
 *     version afterwards, so this is the only construction that is correct by definition rather than
 *     by care.
 *
 *  3. pthread_once, not a function local static. External linkage plus one process wide flag is the
 *     whole point: a `static inline` gate in a header would give one flag per translation unit.
 */

#include "kitecodec_ffmpeg_versions.h"
#include "kitecodec_abi.h"

#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* What the artifact was built for, supplied by the build.
 *
 * kiteffmpeg-core/build.gradle.kts passes all three through CompileKiteFFmpegCTask.buildDefines, which
 * declares them as task inputs so changing one rebuilds the archive. scripts/build-host.sh passes the
 * same three for the host test binaries. The fallbacks are not decoration: an archive that reports
 * "unknown" here was built by something that did not say, and a bug report saying so is worth more
 * than one that silently reports a plausible default. */
#ifndef KC_BUILD_FFMPEG_REF
#define KC_BUILD_FFMPEG_REF "unknown"
#endif
#ifndef KC_BUILD_FFMPEG_LICENSE
#define KC_BUILD_FFMPEG_LICENSE "unknown"
#endif
#ifndef KC_BUILD_FFMPEG_DIR
#define KC_BUILD_FFMPEG_DIR "unknown"
#endif

/* The diagnostic bypass. Opt in only, exact value only, and never quiet: see plan section 15.6
 * question 3, whose three conditions this file implements literally. An empty value, "true", "yes" or
 * "0" do NOT enable it, because a bypass that fires on anything truthy is a bypass somebody enables
 * by accident. */
#define KC_BYPASS_ENV "KITECODEC_FFMPEG_ABI_BYPASS"
#define KC_BYPASS_VALUE "1"

typedef unsigned (*kc_version_fn)(void);
typedef const char *(*kc_string_fn)(void);

/* The fixed order of the report's arrays, matching the KC_LIB_* constants of the header. */
static const char *const KC_LIBRARY_NAMES[KC_FFMPEG_LIBRARY_COUNT] = {
    "libavutil",
    "libavcodec",
    "libavformat",
    "libavfilter",
    "libswscale",
    "libswresample",
};

/* The frozen expectations. Property 2 of the file note above. */
static const unsigned KC_HEADER_VERSION[KC_FFMPEG_LIBRARY_COUNT] = {
    LIBAVUTIL_VERSION_INT,
    LIBAVCODEC_VERSION_INT,
    LIBAVFORMAT_VERSION_INT,
    LIBAVFILTER_VERSION_INT,
    LIBSWSCALE_VERSION_INT,
    LIBSWRESAMPLE_VERSION_INT,
};

static const kc_version_fn KC_RUNTIME_VERSION[KC_FFMPEG_LIBRARY_COUNT] = {
    avutil_version,
    avcodec_version,
    avformat_version,
    avfilter_version,
    swscale_version,
    swresample_version,
};

static const kc_string_fn KC_RUNTIME_CONFIGURATION[KC_FFMPEG_LIBRARY_COUNT] = {
    avutil_configuration,
    avcodec_configuration,
    avformat_configuration,
    avfilter_configuration,
    swscale_configuration,
    swresample_configuration,
};

static pthread_once_t kc_gate_once = PTHREAD_ONCE_INIT;

/* File scope, so it is zero initialised before the gate runs and every unwritten field reads as 0 or
 * "" rather than as whatever was on the stack. */
static kc_ffmpeg_report kc_gate_report;

/* AV_VERSION_INT packs (major << 16) | (minor << 8) | micro. */
static int kc_version_major(unsigned packed) { return (int)((packed >> 16) & 0xFFu); }
static int kc_version_minor(unsigned packed) { return (int)((packed >> 8) & 0xFFu); }
static int kc_version_micro(unsigned packed) { return (int)(packed & 0xFFu); }

/* Bounded copy into a fixed report field. A source longer than the field truncates; it never
 * overruns, and it always leaves a terminator. This is the only way anything is written into the
 * report's char arrays, which is what makes their fixed sizes safe rather than hopeful. */
static void kc_copy(char *dst, size_t capacity, const char *src)
{
    size_t length;
    if (capacity == 0) return;
    if (src == NULL) { dst[0] = '\0'; return; }
    length = strlen(src);
    if (length >= capacity) length = capacity - 1;
    memcpy(dst, src, length);
    dst[length] = '\0';
}

/* Append `name` to a comma separated list already in `dst`, or drop it when there is no room. */
static void kc_append_name(char *dst, size_t capacity, const char *name)
{
    size_t used = strlen(dst);
    if (used != 0) {
        if (used + 3 >= capacity) return;
        dst[used] = ',';
        dst[used + 1] = ' ';
        dst[used + 2] = '\0';
        used += 2;
    }
    kc_copy(dst + used, capacity - used, name);
}

/* The warning the diagnostic bypass prints, exactly once per process because its only caller runs
 * inside the pthread_once body.
 *
 * It names the exact mismatch, the expected identity and the found identity, which is condition 2 of
 * plan section 15.6 question 3. stderr and not stdout, and one fputs of one pre-formatted buffer
 * rather than a sequence of prints, so a concurrent writer cannot interleave inside the message.
 *
 * This is the ONLY place the C layer writes to a stream. scripts/symbol-audit.sh asserts that, both
 * as an allowlist entry with this reason and as a source level check that no other unit mentions
 * stderr, so "the library does not print" stays true everywhere except the one path the plan
 * requires to be loud. */
static void kc_warn_bypassed(const kc_ffmpeg_report *report)
{
    char found[KC_TEXT_PATH];
    char line[KC_TEXT_SENTENCE * 2];
    int index;

    found[0] = '\0';
    for (index = 0; index < KC_FFMPEG_LIBRARY_COUNT; index++) {
        char one[KC_TEXT_NAME * 2];
        if (report->verdict[index] == KC_VERDICT_OK) continue;
        snprintf(
            one, sizeof one,
            "%s expected %d.%d.%d found %d.%d.%d (%s)",
            KC_LIBRARY_NAMES[index],
            report->header_major[index], report->header_minor[index], report->header_micro[index],
            report->runtime_major[index], report->runtime_minor[index], report->runtime_micro[index],
            kc_verdict_name(report->verdict[index]));
        kc_append_name(found, sizeof found, one);
    }
    if (found[0] == '\0') kc_copy(found, sizeof found, "none reported");

    snprintf(
        line, sizeof line,
        "warning: [KiteFFmpeg] the FFmpeg identity gate REJECTED this runtime, and %s=%s downgraded\n"
        "  the rejection to this warning. THIS IS NOT A SUPPORTED CONFIGURATION: the headers this\n"
        "  library was compiled against do not describe the runtime it is linked to, so struct field\n"
        "  offsets may be wrong and a wrong offset corrupts memory instead of failing.\n"
        "  mismatch: %s\n"
        "  built for: FFmpeg %s, %s flavour, provisioned from %s\n"
        "  found: FFmpeg %s, licence \"%s\"\n"
        "  the fact that the gate was bypassed is recorded in the identity report, so it travels with\n"
        "  any bug report taken from this process.\n",
        KC_BYPASS_ENV, KC_BYPASS_VALUE,
        found,
        report->build_ffmpeg_ref, report->build_license_flavour, report->build_provisioning_dir,
        report->runtime_version_info, report->runtime_license);
    fputs(line, stderr);
}

/* The gate. Runs exactly once per process, under pthread_once. */
static void kc_run_gate(void)
{
    kc_ffmpeg_report *report = &kc_gate_report;
    const char *baseline;
    const char *bypass;
    int status = KC_STATUS_OK;
    int saw_major_mismatch = 0;
    int saw_runtime_older = 0;
    int index;

    report->abi_major = KITECODEC_C_ABI_MAJOR;
    report->abi_minor = KITECODEC_C_ABI_MINOR;

    kc_copy(report->build_ffmpeg_ref, sizeof report->build_ffmpeg_ref, KC_BUILD_FFMPEG_REF);
    kc_copy(report->build_license_flavour, sizeof report->build_license_flavour, KC_BUILD_FFMPEG_LICENSE);
    kc_copy(report->build_provisioning_dir, sizeof report->build_provisioning_dir, KC_BUILD_FFMPEG_DIR);
    kc_copy(report->runtime_version_info, sizeof report->runtime_version_info, av_version_info());
    /* Register item B1-21. The build declares one licence flavour above and the runtime answers with
     * another; both strings are in the report so the contradiction is visible in every rejection and
     * every diagnostic dump rather than waiting to be discovered. */
    kc_copy(report->runtime_license, sizeof report->runtime_license, avutil_license());

    for (index = 0; index < KC_FFMPEG_LIBRARY_COUNT; index++) {
        unsigned header = KC_HEADER_VERSION[index];
        unsigned runtime = KC_RUNTIME_VERSION[index]();
        int header_major = kc_version_major(header);
        int header_minor = kc_version_minor(header);
        int header_micro = kc_version_micro(header);
        int runtime_major = kc_version_major(runtime);
        int runtime_minor = kc_version_minor(runtime);
        int runtime_micro = kc_version_micro(runtime);
        int verdict = KC_VERDICT_OK;

        report->header_major[index] = header_major;
        report->header_minor[index] = header_minor;
        report->header_micro[index] = header_micro;
        report->runtime_major[index] = runtime_major;
        report->runtime_minor[index] = runtime_minor;
        report->runtime_micro[index] = runtime_micro;

        if (header_major != runtime_major) {
            verdict = KC_VERDICT_MAJOR_MISMATCH;
            saw_major_mismatch = 1;
        } else if (runtime_minor < header_minor) {
            verdict = KC_VERDICT_RUNTIME_OLDER;
            saw_runtime_older = 1;
        } else if (runtime_minor == header_minor && runtime_micro < header_micro) {
            /* Micro is only comparable within one minor, because FFmpeg restarts it at every minor
             * bump. A runtime one minor ahead with a lower micro is newer, not older. */
            verdict = KC_VERDICT_MICRO_OLDER;
        }
        report->verdict[index] = verdict;
    }

    /* The mixed install check. Version numbers cannot see one: six libraries from two builds can
     * report six agreeing version triples and still be from different configure runs.
     *
     * libavutil is the baseline, so index 0 always compares equal to itself. When libavutil is the
     * odd library out the other five all disagree, which names five libraries instead of one; that is
     * still a correct report of a mixed install, and picking a different baseline would only move
     * which library the arithmetic favours. */
    baseline = KC_RUNTIME_CONFIGURATION[KC_LIB_AVUTIL]();
    report->configuration_agrees = 1;
    for (index = 0; index < KC_FFMPEG_LIBRARY_COUNT; index++) {
        const char *configuration = KC_RUNTIME_CONFIGURATION[index]();
        if (baseline != NULL && configuration != NULL && strcmp(baseline, configuration) == 0) continue;
        report->configuration_agrees = 0;
        report->configuration_disagreed_count++;
        kc_append_name(
            report->configuration_disagreed, sizeof report->configuration_disagreed,
            KC_LIBRARY_NAMES[index]);
        if (report->verdict[index] == KC_VERDICT_OK) {
            report->verdict[index] = KC_VERDICT_CONFIGURATION_DISAGREES;
        }
    }

    /* Strongest finding wins, so the status names the reason a human should act on first. */
    if (saw_major_mismatch) {
        status = KC_STATUS_MAJOR_MISMATCH;
    } else if (saw_runtime_older) {
        status = KC_STATUS_RUNTIME_OLDER;
    } else if (!report->configuration_agrees) {
        status = KC_STATUS_CONFIGURATION_MISMATCH;
    }

    snprintf(
        report->provisioning, sizeof report->provisioning,
        "KiteFFmpeg was compiled against FFmpeg %s headers provisioned from %s, %s flavour; the linked "
        "runtime reports %s with licence \"%s\". Either link the FFmpeg build the headers came from, "
        "or rebuild KiteFFmpeg against the runtime you have. For diagnosis only, setting %s=%s "
        "downgrades this rejection to a warning printed once; that is not a supported configuration "
        "and the report records that it was used.",
        report->build_ffmpeg_ref, report->build_provisioning_dir, report->build_license_flavour,
        report->runtime_version_info, report->runtime_license,
        KC_BYPASS_ENV, KC_BYPASS_VALUE);

    report->status = status;
    report->bypassed = 0;
    if (status == KC_STATUS_OK) return;

    bypass = getenv(KC_BYPASS_ENV);
    if (bypass == NULL || strcmp(bypass, KC_BYPASS_VALUE) != 0) return;
    /* Opt in, taken. Keep the original verdict where a diagnostic can find it, hand the caller an
     * accepting status, and say so out loud exactly once. */
    report->bypassed = status;
    report->status = KC_STATUS_OK;
    kc_warn_bypassed(report);
}

KC_API int kc_init(void)
{
    pthread_once(&kc_gate_once, kc_run_gate);
    return kc_gate_report.status;
}

KC_API void kc_ffmpeg_report_get(kc_ffmpeg_report *out)
{
    pthread_once(&kc_gate_once, kc_run_gate);
    if (out != NULL) *out = kc_gate_report;
}

KC_API uint32_t kc_abi_version(void)
{
    return ((uint32_t)KITECODEC_C_ABI_MAJOR << 16) | ((uint32_t)KITECODEC_C_ABI_MINOR << 8);
}

KC_API const char *kc_ffmpeg_library_name(int index)
{
    if (index < 0 || index >= KC_FFMPEG_LIBRARY_COUNT) return "";
    return KC_LIBRARY_NAMES[index];
}

KC_API const char *kc_verdict_name(int verdict)
{
    switch (verdict) {
        case KC_VERDICT_OK: return "ok";
        case KC_VERDICT_MAJOR_MISMATCH: return "major mismatch";
        case KC_VERDICT_RUNTIME_OLDER: return "runtime older than headers";
        case KC_VERDICT_MICRO_OLDER: return "micro older than headers";
        case KC_VERDICT_CONFIGURATION_DISAGREES: return "configuration disagrees";
        default: return "unknown";
    }
}

KC_API const char *kc_ffmpeg_configuration(void)
{
    const char *configuration;
    pthread_once(&kc_gate_once, kc_run_gate);
    configuration = avcodec_configuration();
    return configuration != NULL ? configuration : "";
}

/* S1.c.1. The JavaVM handoff for the JNI bridge. The Android arm includes libavcodec/jni.h only
 * inside this guard, so a host FFmpeg built without --enable-jni still links this archive. */
#ifdef __ANDROID__
#include <libavcodec/jni.h>
#endif

KC_API int kc_jvm_attach(void *java_vm)
{
    if (java_vm == NULL) return KC_JVM_BAD_ARGUMENT;
#ifdef __ANDROID__
    /* The VM is process identity too: never hand it to FFmpeg until the archive's frozen header
     * identity has accepted the linked runtime. Calling kc_init(), rather than merely running the
     * once body, keeps the status decision in the gate's one public path (including its explicit
     * diagnostic-bypass semantics). */
    if (kc_init() != KC_STATUS_OK) return KC_JVM_FFMPEG_REFUSED;
    if (av_jni_set_java_vm(java_vm, NULL) < 0) return KC_JVM_FFMPEG_REFUSED;
    return KC_JVM_OK;
#else
    return KC_JVM_UNSUPPORTED;
#endif
}

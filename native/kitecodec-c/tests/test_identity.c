/* The FFmpeg header versus runtime identity gate, one case per verdict.
 *
 * Register item B1-02, plan section 15.2 sub-phase B1.6 and section 15.3's suite table. A gate that
 * has never fired is level 8 evidence in the terms of plan section 2, which is to say it is a sentence
 * in a document. This suite is what makes it level 2: a deterministic differential on the exact
 * contract, with a doctored expectation on one side and the real runtime on the other.
 *
 * HERMETIC. It needs no second FFmpeg install and no network. src/kitecodec_abi.c is compiled several
 * more times, once per shim include tree under tests/fake_headers/, and each shim renames that copy's
 * seven exported symbols so all of them link into this one binary. The source compiled is byte for byte
 * the shipped one: there is no test-only branch inside the production file, which is what keeps the
 * experiment about the gate rather than about a test hook.
 *
 * The order of the cases below is load bearing and not cosmetic. Each copy of the gate runs once per
 * process under pthread_once, so a copy's verdict is fixed by the first call. Case 1 must therefore
 * run while KITECODEC_FFMPEG_ABI_BYPASS is unset, and the bypass cases must be the first users of the
 * separate `bypass` copy after it is set.
 */

#include "harness.h"
#include "kitecodec_abi.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

/* The doctored copies. Each name is what tests/fake_headers/<case>/kitecodec_ffmpeg_versions.h renamed
 * the corresponding kc_ symbol to; see tests/fake_headers/kc_rename.h. */
extern int kc_major_mismatch_init(void);
extern void kc_major_mismatch_report_get(kc_ffmpeg_report *out);

extern int kc_runtime_older_init(void);
extern void kc_runtime_older_report_get(kc_ffmpeg_report *out);

extern int kc_micro_older_init(void);
extern void kc_micro_older_report_get(kc_ffmpeg_report *out);

extern int kc_configuration_mismatch_init(void);
extern void kc_configuration_mismatch_report_get(kc_ffmpeg_report *out);

extern int kc_bypass_init(void);
extern void kc_bypass_report_get(kc_ffmpeg_report *out);

/* kc_rename.h Android-preprocesses every doctored copy of the byte-identical production source.
 * The major-mismatch copy is already rejected by case 1, while the micro-older copy is already
 * accepted by case 3. Both resolve FFmpeg's VM setter to this probe, making attach order observable
 * without a test branch in src/kitecodec_abi.c. */
extern int kc_major_mismatch_jvm_attach(void *java_vm);
extern int kc_micro_older_jvm_attach(void *java_vm);

static int kc_android_jni_set_calls;
static void *kc_android_jni_last_vm;
static int kc_android_jni_set_result;

int av_jni_set_java_vm(void *java_vm, void *log_ctx)
{
    KC_EQ_PTR(log_ctx, NULL);
    kc_android_jni_set_calls++;
    kc_android_jni_last_vm = java_vm;
    return kc_android_jni_set_result;
}

/* The doctored configure line case 5 hands libavfilter. Deliberately a string no real FFmpeg configure
 * run would produce, so a false pass cannot come from the two strings accidentally agreeing. */
const char *kc_fake_avfilter_configuration(void)
{
    return "--prefix=/somewhere/else --enable-nothing-real";
}

#define KC_BYPASS_ENV "KITECODEC_FFMPEG_ABI_BYPASS"

/* Every library's verdict, so a case can say "exactly this one library is unhappy". */
static int count_verdicts(const kc_ffmpeg_report *report, int verdict)
{
    int index;
    int found = 0;
    for (index = 0; index < KC_FFMPEG_LIBRARY_COUNT; index++) {
        if (report->verdict[index] == verdict) found++;
    }
    return found;
}

/* Runs `call` with stderr captured into a buffer, and returns what it wrote.
 *
 * The bypass warning is required by plan section 15.6 question 3 to name the exact mismatch with both
 * identities and to appear once per process. Asserting that from the outside means reading what was
 * actually written, not trusting that a call site exists. stderr is unbuffered, so a plain fd swap is
 * enough and no fflush dance is needed before the redirect. */
static void capture_stderr(int (*call)(void), int *out_status, char *buffer, size_t capacity)
{
    char path[] = "/tmp/kc_identity_stderr_XXXXXX";
    int fd;
    int saved;
    FILE *sink;
    long length;

    buffer[0] = '\0';
    fd = mkstemp(path);
    KC_CHECKF(fd >= 0, "mkstemp(%s) failed", path);
    sink = fdopen(fd, "w+");
    KC_NOT_NULL(sink);

    saved = dup(STDERR_FILENO);
    KC_CHECKF(saved >= 0, "dup(stderr) failed");
    (void)dup2(fileno(sink), STDERR_FILENO);

    *out_status = call();

    fflush(stderr);
    (void)dup2(saved, STDERR_FILENO);
    (void)close(saved);

    fflush(sink);
    (void)fseek(sink, 0, SEEK_END);
    length = ftell(sink);
    if (length < 0) length = 0;
    if ((size_t)length >= capacity) length = (long)capacity - 1;
    (void)fseek(sink, 0, SEEK_SET);
    if (length > 0) {
        size_t read_bytes = fread(buffer, 1, (size_t)length, sink);
        buffer[read_bytes] = '\0';
    }
    (void)fclose(sink);
    (void)unlink(path);
}

#define KC_CONTAINS(haystack, needle) \
    do { \
        if (strstr((haystack), (needle)) == NULL) \
            KC_FAIL("expected the captured warning to contain \"%s\"", (needle)); \
    } while (0)

int main(void)
{
    kc_ffmpeg_report report;
    int status;
    int index;
    char captured[8192];

    kc_suite_begin("test_identity");

    /* ----------------------------------------------------------------------------------------
     * Case 1. avutil header major one below the runtime's: hard reject, no override.
     *
     * Runs FIRST and with KITECODEC_FFMPEG_ABI_BYPASS unset, which is also the assertion that the
     * escape hatch is not a silent default (plan section 15.4 under B1.6).
     * ---------------------------------------------------------------------------------------- */
    kc_case("major mismatch rejects, and the bypass is not a silent default");
    KC_CHECKF(getenv(KC_BYPASS_ENV) == NULL, "%s must be unset when this suite starts", KC_BYPASS_ENV);
    status = kc_major_mismatch_init();
    KC_EQ_INT(status, KC_STATUS_MAJOR_MISMATCH);
    kc_major_mismatch_report_get(&report);
    KC_EQ_INT(report.status, KC_STATUS_MAJOR_MISMATCH);
    KC_EQ_INT(report.bypassed, 0);
    KC_EQ_INT(report.verdict[KC_LIB_AVUTIL], KC_VERDICT_MAJOR_MISMATCH);
    KC_EQ_INT(count_verdicts(&report, KC_VERDICT_MAJOR_MISMATCH), 1);
    /* Both numbers present, and the doctored one really is one major behind. */
    KC_EQ_INT(report.header_major[KC_LIB_AVUTIL], report.runtime_major[KC_LIB_AVUTIL] - 1);
    KC_CHECK(report.runtime_major[KC_LIB_AVUTIL] > 0);
    KC_CHECK(report.header_major[KC_LIB_AVUTIL] > 0);
    /* The provisioning sentence has to be actionable, so it must exist, and it must not be cut short.
     * Measured at 483 bytes when the field was 512, so a provisioning directory thirty characters longer
     * than this machine's would have truncated the actionable half away, and a sentence that stops
     * mid-instruction is worse than a short one because a reader cannot tell that it stopped. That is
     * what KC_TEXT_SENTENCE is for, and this is what stops it going back to being tight. */
    KC_CHECK(report.provisioning[0] != '\0');
    KC_CHECKF(
        strlen(report.provisioning) < sizeof report.provisioning - 1,
        "the provisioning sentence fills its field exactly (%zu of %zu), so it is truncated",
        strlen(report.provisioning), sizeof report.provisioning);
    kc_detail("provisioning sentence %zu of %zu bytes",
              strlen(report.provisioning), sizeof report.provisioning);
    /* Extended at the interlude: the line above only proves THIS machine's sentence fits,
     * and at KC_TEXT_SENTENCE 1024 the worst case did not: with the build defines compiled at
     * their declared capacities the sentence measured 1011 bytes while the two runtime-supplied
     * fields were still 101 bytes below their own caps, so a long av_version_info() plus a long
     * provisioning directory dropped the tail, which is the part that records the bypass. The
     * sentence embeds exactly five variable fields, each exactly once, so the worst case is this
     * instance plus the headroom every embedded field still has to its capacity. Assert that
     * arithmetic bound, machine-independently. */
    {
        size_t worst = strlen(report.provisioning)
            + ((sizeof report.build_ffmpeg_ref - 1) - strlen(report.build_ffmpeg_ref))
            + ((sizeof report.build_provisioning_dir - 1) - strlen(report.build_provisioning_dir))
            + ((sizeof report.build_license_flavour - 1) - strlen(report.build_license_flavour))
            + ((sizeof report.runtime_version_info - 1) - strlen(report.runtime_version_info))
            + ((sizeof report.runtime_license - 1) - strlen(report.runtime_license));
        KC_CHECKF(
            worst < sizeof report.provisioning - 1,
            "with every embedded field at its declared capacity the sentence would be %zu bytes "
            "against a %zu byte field, so a long enough runtime would truncate it",
            worst, sizeof report.provisioning);
        kc_detail("worst-case provisioning sentence %zu of %zu bytes", worst, sizeof report.provisioning);
    }
    kc_detail("libavutil headers %d.%d.%d runtime %d.%d.%d verdict %s",
              report.header_major[KC_LIB_AVUTIL], report.header_minor[KC_LIB_AVUTIL],
              report.header_micro[KC_LIB_AVUTIL],
              report.runtime_major[KC_LIB_AVUTIL], report.runtime_minor[KC_LIB_AVUTIL],
              report.runtime_micro[KC_LIB_AVUTIL],
              kc_verdict_name(report.verdict[KC_LIB_AVUTIL]));

    kc_case("major mismatch leaves the other five libraries ok");
    kc_major_mismatch_report_get(&report);
    for (index = 0; index < KC_FFMPEG_LIBRARY_COUNT; index++) {
        if (index == KC_LIB_AVUTIL) continue;
        KC_CHECKF(report.verdict[index] == KC_VERDICT_OK,
                  "%s should be ok, is %s", kc_ffmpeg_library_name(index),
                  kc_verdict_name(report.verdict[index]));
    }
    kc_detail("one rejecting library, five ok");

    /* ----------------------------------------------------------------------------------------
     * Case 2. avcodec header minor above the runtime's: the runtime is older than the headers.
     * ---------------------------------------------------------------------------------------- */
    kc_case("runtime minor below header minor rejects");
    status = kc_runtime_older_init();
    KC_EQ_INT(status, KC_STATUS_RUNTIME_OLDER);
    kc_runtime_older_report_get(&report);
    KC_EQ_INT(report.verdict[KC_LIB_AVCODEC], KC_VERDICT_RUNTIME_OLDER);
    KC_EQ_INT(count_verdicts(&report, KC_VERDICT_RUNTIME_OLDER), 1);
    KC_EQ_INT(report.header_major[KC_LIB_AVCODEC], report.runtime_major[KC_LIB_AVCODEC]);
    KC_EQ_INT(report.header_minor[KC_LIB_AVCODEC], report.runtime_minor[KC_LIB_AVCODEC] + 1);
    KC_EQ_INT(report.bypassed, 0);
    kc_detail("libavcodec headers %d.%d.%d runtime %d.%d.%d",
              report.header_major[KC_LIB_AVCODEC], report.header_minor[KC_LIB_AVCODEC],
              report.header_micro[KC_LIB_AVCODEC],
              report.runtime_major[KC_LIB_AVCODEC], report.runtime_minor[KC_LIB_AVCODEC],
              report.runtime_micro[KC_LIB_AVCODEC]);

    /* ----------------------------------------------------------------------------------------
     * Case 3. avformat header micro above the runtime's: reported, never fatal.
     * ---------------------------------------------------------------------------------------- */
    kc_case("micro older is reported and accepted");
    status = kc_micro_older_init();
    KC_EQ_INT(status, KC_STATUS_OK);
    kc_micro_older_report_get(&report);
    KC_EQ_INT(report.status, KC_STATUS_OK);
    KC_EQ_INT(report.verdict[KC_LIB_AVFORMAT], KC_VERDICT_MICRO_OLDER);
    KC_EQ_INT(count_verdicts(&report, KC_VERDICT_MICRO_OLDER), 1);
    KC_EQ_INT(report.header_minor[KC_LIB_AVFORMAT], report.runtime_minor[KC_LIB_AVFORMAT]);
    KC_EQ_INT(report.header_micro[KC_LIB_AVFORMAT], report.runtime_micro[KC_LIB_AVFORMAT] + 1);
    KC_EQ_INT(report.bypassed, 0);
    kc_detail("libavformat headers %d.%d.%d runtime %d.%d.%d, status %d",
              report.header_major[KC_LIB_AVFORMAT], report.header_minor[KC_LIB_AVFORMAT],
              report.header_micro[KC_LIB_AVFORMAT],
              report.runtime_major[KC_LIB_AVFORMAT], report.runtime_minor[KC_LIB_AVFORMAT],
              report.runtime_micro[KC_LIB_AVFORMAT], report.status);

    /* ----------------------------------------------------------------------------------------
     * Case 4. The true build. This is the case that would fail if the gate were too strict, which
     * is the failure mode plan section 15.4 says would make a false rejection our outage.
     * ---------------------------------------------------------------------------------------- */
    kc_case("the real build accepts, with all six verdicts ok");
    status = kc_init();
    KC_EQ_INT(status, KC_STATUS_OK);
    kc_ffmpeg_report_get(&report);
    KC_EQ_INT(report.status, KC_STATUS_OK);
    KC_EQ_INT(report.bypassed, 0);
    KC_EQ_INT(count_verdicts(&report, KC_VERDICT_OK), KC_FFMPEG_LIBRARY_COUNT);
    KC_EQ_INT(report.configuration_agrees, 1);
    KC_EQ_INT(report.configuration_disagreed_count, 0);
    for (index = 0; index < KC_FFMPEG_LIBRARY_COUNT; index++) {
        KC_EQ_INT(report.header_major[index], report.runtime_major[index]);
        KC_EQ_INT(report.header_minor[index], report.runtime_minor[index]);
        KC_EQ_INT(report.header_micro[index], report.runtime_micro[index]);
    }
    kc_detail("avutil %d.%d.%d avcodec %d.%d.%d avformat %d.%d.%d",
              report.runtime_major[KC_LIB_AVUTIL], report.runtime_minor[KC_LIB_AVUTIL],
              report.runtime_micro[KC_LIB_AVUTIL],
              report.runtime_major[KC_LIB_AVCODEC], report.runtime_minor[KC_LIB_AVCODEC],
              report.runtime_micro[KC_LIB_AVCODEC],
              report.runtime_major[KC_LIB_AVFORMAT], report.runtime_minor[KC_LIB_AVFORMAT],
              report.runtime_micro[KC_LIB_AVFORMAT]);

    /* ----------------------------------------------------------------------------------------
     * Case 5. One library's configure line disagrees: a mixed install, which the version numbers
     * cannot see at all.
     * ---------------------------------------------------------------------------------------- */
    kc_case("a disagreeing configure line rejects and names the library");
    status = kc_configuration_mismatch_init();
    KC_EQ_INT(status, KC_STATUS_CONFIGURATION_MISMATCH);
    kc_configuration_mismatch_report_get(&report);
    KC_EQ_INT(report.configuration_agrees, 0);
    KC_EQ_INT(report.configuration_disagreed_count, 1);
    KC_EQ_STR(report.configuration_disagreed, "libavfilter");
    KC_EQ_INT(report.verdict[KC_LIB_AVFILTER], KC_VERDICT_CONFIGURATION_DISAGREES);
    /* Every version triple still agrees, which is the point of the case. */
    for (index = 0; index < KC_FFMPEG_LIBRARY_COUNT; index++) {
        KC_EQ_INT(report.header_major[index], report.runtime_major[index]);
        KC_EQ_INT(report.header_minor[index], report.runtime_minor[index]);
    }
    kc_detail("disagreed: %s", report.configuration_disagreed);

    /* ----------------------------------------------------------------------------------------
     * Register item B1-21. Both licence fields populated, in every report, so the contradiction
     * between the declared build flavour and the linked runtime's licence is always visible.
     * ---------------------------------------------------------------------------------------- */
    kc_case("the report carries both the runtime licence and the build flavour");
    kc_ffmpeg_report_get(&report);
    KC_CHECKF(report.runtime_license[0] != '\0', "runtime_license is empty");
    KC_CHECKF(report.build_license_flavour[0] != '\0', "build_license_flavour is empty");
    KC_CHECKF(report.build_ffmpeg_ref[0] != '\0', "build_ffmpeg_ref is empty");
    KC_CHECKF(report.build_provisioning_dir[0] != '\0', "build_provisioning_dir is empty");
    KC_CHECKF(report.runtime_version_info[0] != '\0', "runtime_version_info is empty");
    kc_detail("built for %s/%s from %s, runtime %s licence \"%s\"",
              report.build_ffmpeg_ref, report.build_license_flavour, report.build_provisioning_dir,
              report.runtime_version_info, report.runtime_license);

    kc_case("the report carries this library's own C ABI version");
    KC_EQ_INT(report.abi_major, KITECODEC_C_ABI_MAJOR);
    KC_EQ_INT(report.abi_minor, KITECODEC_C_ABI_MINOR);
    KC_EQ_INT((int)kc_abi_version(),
              (int)(((uint32_t)KITECODEC_C_ABI_MAJOR << 16) | ((uint32_t)KITECODEC_C_ABI_MINOR << 8)));
    kc_detail("abi %d.%d packed 0x%06x", report.abi_major, report.abi_minor, kc_abi_version());

    kc_case("the six library names are the accessor's, in the report's own index order");
    KC_EQ_STR(kc_ffmpeg_library_name(KC_LIB_AVUTIL), "libavutil");
    KC_EQ_STR(kc_ffmpeg_library_name(KC_LIB_AVCODEC), "libavcodec");
    KC_EQ_STR(kc_ffmpeg_library_name(KC_LIB_AVFORMAT), "libavformat");
    KC_EQ_STR(kc_ffmpeg_library_name(KC_LIB_AVFILTER), "libavfilter");
    KC_EQ_STR(kc_ffmpeg_library_name(KC_LIB_SWSCALE), "libswscale");
    KC_EQ_STR(kc_ffmpeg_library_name(KC_LIB_SWRESAMPLE), "libswresample");
    /* Out of range must be an empty string and never NULL: Kotlin reads it with toKString(). */
    KC_EQ_STR(kc_ffmpeg_library_name(-1), "");
    KC_EQ_STR(kc_ffmpeg_library_name(KC_FFMPEG_LIBRARY_COUNT), "");
    kc_detail("six names plus two out-of-range guards");

    kc_case("every verdict has a name, and an unknown value does not return NULL");
    KC_EQ_STR(kc_verdict_name(KC_VERDICT_OK), "ok");
    KC_EQ_STR(kc_verdict_name(KC_VERDICT_MAJOR_MISMATCH), "major mismatch");
    KC_EQ_STR(kc_verdict_name(KC_VERDICT_RUNTIME_OLDER), "runtime older than headers");
    KC_EQ_STR(kc_verdict_name(KC_VERDICT_MICRO_OLDER), "micro older than headers");
    KC_EQ_STR(kc_verdict_name(KC_VERDICT_CONFIGURATION_DISAGREES), "configuration disagrees");
    KC_EQ_STR(kc_verdict_name(4242), "unknown");
    kc_detail("five verdicts plus the unknown guard");

    kc_case("the configuration accessor answers, and it is the same string libavutil reports");
    KC_NOT_NULL(kc_ffmpeg_configuration());
    KC_CHECK(strlen(kc_ffmpeg_configuration()) > 0);
    kc_detail("%zu bytes of configure line", strlen(kc_ffmpeg_configuration()));

    kc_case("kc_ffmpeg_report_get tolerates NULL and still runs the gate");
    kc_ffmpeg_report_get(NULL);
    KC_EQ_INT(kc_init(), KC_STATUS_OK);
    kc_detail("no crash, status unchanged");

    /* ----------------------------------------------------------------------------------------
     * The diagnostic bypass. Three conditions from plan section 15.6 question 3, each asserted:
     * opt-in only (case 1 above, with the variable unset), a warning naming the exact mismatch and
     * both identities exactly once per process, and the use recorded in the report.
     *
     * From here on the environment variable is SET, so no case below may rely on a copy of the gate
     * that has not run yet unless it wants the bypass applied.
     * ---------------------------------------------------------------------------------------- */
    kc_case("with the bypass set, a rejection becomes an accepting status and a recorded fact");
    KC_EQ_INT(setenv(KC_BYPASS_ENV, "1", 1), 0);
    capture_stderr(kc_bypass_init, &status, captured, sizeof captured);
    KC_EQ_INT(status, KC_STATUS_OK);
    kc_bypass_report_get(&report);
    KC_EQ_INT(report.status, KC_STATUS_OK);
    KC_EQ_INT(report.bypassed, KC_STATUS_MAJOR_MISMATCH);
    kc_detail("status %d, bypassed %d, %zu bytes on stderr",
              report.status, report.bypassed, strlen(captured));

    kc_case("the bypass warning names the mismatch, the expected identity and the found identity");
    KC_CHECK(strlen(captured) > 0);
    KC_CONTAINS(captured, KC_BYPASS_ENV);
    KC_CONTAINS(captured, "NOT A SUPPORTED CONFIGURATION");
    KC_CONTAINS(captured, "libavutil expected ");
    KC_CONTAINS(captured, " found ");
    KC_CONTAINS(captured, "major mismatch");
    KC_CONTAINS(captured, "built for: FFmpeg ");
    KC_CONTAINS(captured, "recorded in the identity report");
    kc_note("captured warning, first line: %.*s", (int)strcspn(captured, "\n"), captured);
    kc_detail("all seven required fragments present");

    kc_case("the bypass warning is printed once per process and not once per call");
    capture_stderr(kc_bypass_init, &status, captured, sizeof captured);
    KC_EQ_INT(status, KC_STATUS_OK);
    KC_EQ_SIZE(strlen(captured), 0);
    kc_detail("second call wrote 0 bytes to stderr");

    kc_case("a copy whose gate already ran is not retroactively bypassed");
    /* The variable is set now, but the major_mismatch copy ran before it was. pthread_once means its
     * verdict is fixed, which is the property that makes the gate cheap on every later entry point. */
    KC_EQ_INT(kc_major_mismatch_init(), KC_STATUS_MAJOR_MISMATCH);
    kc_major_mismatch_report_get(&report);
    KC_EQ_INT(report.bypassed, 0);
    kc_detail("still rejecting, still not marked bypassed");

    KC_EQ_INT(unsetenv(KC_BYPASS_ENV), 0);

    /* S1.c.1. The production object is a host build, while the doctored byte-identical copies take
     * the Android arm. That gives this one suite both halves without adding a test branch to the
     * shipped source. The sentinel is a stack address; no arm may dereference it. */
    kc_case("kc_jvm_attach refuses NULL with KC_JVM_BAD_ARGUMENT");
    KC_EQ_INT(kc_jvm_attach(NULL), KC_JVM_BAD_ARGUMENT);

    kc_case("kc_jvm_attach gates Android before FFmpeg and remains unsupported on the host");
    {
        int sentinel = 0;
        KC_EQ_INT(kc_jvm_attach(&sentinel), KC_JVM_UNSUPPORTED);
        KC_EQ_INT(sentinel, 0);

        kc_android_jni_set_calls = 0;
        kc_android_jni_last_vm = NULL;
        kc_android_jni_set_result = 0;
        KC_EQ_INT(kc_major_mismatch_jvm_attach(&sentinel), KC_JVM_FFMPEG_REFUSED);
        KC_EQ_INT(kc_android_jni_set_calls, 0);
        KC_EQ_PTR(kc_android_jni_last_vm, NULL);

        /* Restore an accepting identity in a separate pthread_once domain. If the setter probe
         * itself were disconnected, this control would leave the call count at zero and fail. */
        KC_EQ_INT(kc_micro_older_jvm_attach(&sentinel), KC_JVM_OK);
        KC_EQ_INT(kc_android_jni_set_calls, 1);
        KC_EQ_PTR(kc_android_jni_last_vm, &sentinel);

        kc_android_jni_set_result = -1;
        KC_EQ_INT(kc_micro_older_jvm_attach(&sentinel), KC_JVM_FFMPEG_REFUSED);
        KC_EQ_INT(kc_android_jni_set_calls, 2);
        KC_EQ_PTR(kc_android_jni_last_vm, &sentinel);
        kc_android_jni_set_result = 0;
        KC_EQ_INT(sentinel, 0);
    }

    return kc_suite_end();
}

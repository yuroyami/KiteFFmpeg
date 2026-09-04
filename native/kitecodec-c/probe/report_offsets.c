/* Emits the byte layout of `kc_ffmpeg_report` for the web binding.
 *
 * JavaScript cannot read a C struct: it sees the codec's memory as a flat buffer and needs an
 * offset for every field. Those offsets are the COMPILER's to state, not a human's to count, and
 * getting one wrong reads a neighbouring field rather than failing, so the numbers are generated
 * here and checked by `scripts/wasm-report-offsets.sh` rather than written by hand.
 */
#include <stddef.h>
#include <stdio.h>

#include "kitecodec_abi.h"

#define OFF(field) printf("  \"%s\": %zu,\n", #field, offsetof(kc_ffmpeg_report, field))

int main(void) {
    printf("{\n");
    OFF(status);
    OFF(bypassed);
    OFF(abi_major);
    OFF(abi_minor);
    OFF(header_major);
    OFF(header_minor);
    OFF(header_micro);
    OFF(runtime_major);
    OFF(runtime_minor);
    OFF(runtime_micro);
    OFF(verdict);
    OFF(configuration_agrees);
    OFF(configuration_disagreed_count);
    OFF(configuration_disagreed);
    OFF(build_ffmpeg_ref);
    OFF(build_license_flavour);
    OFF(build_provisioning_dir);
    OFF(runtime_version_info);
    OFF(runtime_license);
    OFF(provisioning);
    printf("  \"LIBRARY_COUNT\": %d,\n", KC_FFMPEG_LIBRARY_COUNT);
    printf("  \"TEXT_REF\": %d,\n", KC_TEXT_REF);
    printf("  \"TEXT_NAME\": %d,\n", KC_TEXT_NAME);
    printf("  \"TEXT_LIST\": %d,\n", KC_TEXT_LIST);
    printf("  \"TEXT_PATH\": %d,\n", KC_TEXT_PATH);
    printf("  \"TEXT_SENTENCE\": %d,\n", KC_TEXT_SENTENCE);
    printf("  \"sizeof\": %zu\n", sizeof(kc_ffmpeg_report));
    printf("}\n");
    return 0;
}

/* Emits the byte layout of `kc_io_opener` for the web binding.
 *
 * The web backend builds this struct in the codec module's memory and hands its address to
 * ffkmp_fmt_open_input_io2, so it writes every field by offset. As with report_offsets.c, those
 * offsets are the compiler's to state, and `scripts/wasm-report-offsets.sh` checks them.
 */
#include <stddef.h>
#include <stdio.h>

#include "kitecodec_helpers.h"

#define OFF(field) printf("  \"%s\": %zu,\n", #field, offsetof(kc_io_opener, field))

int main(void) {
    printf("{\n");
    OFF(opaque);
    OFF(open_fn);
    OFF(read_fn);
    OFF(seek_fn);
    OFF(close_fn);
    OFF(location_fn);
    printf("  \"sizeof\": %zu\n", sizeof(kc_io_opener));
    printf("}\n");
    return 0;
}

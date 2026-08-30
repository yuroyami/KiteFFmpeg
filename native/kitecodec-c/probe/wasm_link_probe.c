/* Proves the wasm codec archive LINKS and RUNS, not merely that it compiled (KPKMP.md 17.14 X-03).
 *
 * The five filter names are the point. `helpers_filter.c` looks each of them up by name through
 * `avfilter_get_by_name`, and the web spike's FFmpeg recipe disabled avfilter entirely because its
 * own harness never opened a graph. A build without them links cleanly and returns 0 from every one
 * of these, which is a failure that would otherwise surface only when a user applied a filter.
 */
#include <stdio.h>

#include "kc_handles.h"
#include "kitecodec_abi.h"
#include "kitecodec_helpers.h"

int main(void) {
    printf("configuration=%.32s\n", kc_ffmpeg_configuration());
    static const char *const names[] = { "abuffer", "abuffersink", "anull", "buffer", "buffersink" };
    int missing = 0;
    for (int i = 0; i < 5; i++) {
        const int present = ffkmp_filter_exists(names[i]);
        printf("filter %s = %d\n", names[i], present);
        if (!present) missing = 1;
    }
    if (missing) {
        fprintf(stderr, "FAIL: the web FFmpeg is missing a filter helpers_filter.c needs\n");
        return 1;
    }
    /* The handle table, shared with the JNI adapter rather than copied.
       Three properties, in increasing order of what they cost to get wrong. */
    int dummy = 0;
    const int64_t token = kj_handle_put(KJ_KIND_FRAME, &dummy);
    if (token == 0) { fprintf(stderr, "FAIL: could not mint a handle\n"); return 1; }
    if (kj_handle_peek(token, KJ_KIND_FRAME) != &dummy) {
        fprintf(stderr, "FAIL: a live token did not resolve\n");
        return 1;
    }
    if (kj_handle_peek(token, KJ_KIND_PACKET) != NULL) {
        fprintf(stderr, "FAIL: a wrong-kind lookup resolved\n");
        return 1;
    }
    kj_handle_release(token, KJ_KIND_FRAME);
    if (kj_handle_peek(token, KJ_KIND_FRAME) != NULL) {
        fprintf(stderr, "FAIL: a closed token still resolves\n");
        return 1;
    }

    /* The one that needs the GENERATION, and the only one that actually exercises it.
       The two assertions above pass even with the generation removed, because a closed slot has a
       NULL pointer and that alone refuses them. The dangerous case is a freed slot REUSED by a new
       object: the old token then names a live slot holding different memory, and only the
       generation tells them apart. Reuse is not automatic, because the free-slot scan moves
       forward; it happens after the scan wraps. The table grows in 1024-slot chunks, so filling
       the first chunk and then freeing its first slot makes the next mint land exactly there. */
    enum { CHUNK = 1024 };
    static int cells[CHUNK];
    static int64_t tokens[CHUNK];
    for (int i = 0; i < CHUNK; i++) {
        tokens[i] = kj_handle_put(KJ_KIND_FRAME, &cells[i]);
        if (tokens[i] == 0) { fprintf(stderr, "FAIL: mint %d returned 0\n", i); return 1; }
    }
    kj_handle_release(tokens[0], KJ_KIND_FRAME);
    int other = 0;
    const int64_t reused = kj_handle_put(KJ_KIND_FRAME, &other);
    if (reused == 0) { fprintf(stderr, "FAIL: could not mint into the freed slot\n"); return 1; }
    if ((reused & (KJ_MAX_SLOTS - 1)) != (tokens[0] & (KJ_MAX_SLOTS - 1))) {
        fprintf(stderr, "FAIL: the mint did not reuse the freed slot, so this proves nothing\n");
        return 1;
    }
    if (kj_handle_peek(tokens[0], KJ_KIND_FRAME) != NULL) {
        fprintf(stderr, "FAIL: a token for a FREED object resolved after its slot was reused\n");
        return 1;
    }
    if (kj_handle_peek(reused, KJ_KIND_FRAME) != &other) {
        fprintf(stderr, "FAIL: the reusing token did not resolve\n");
        return 1;
    }
    for (int i = 1; i < CHUNK; i++) kj_handle_release(tokens[i], KJ_KIND_FRAME);
    kj_handle_release(reused, KJ_KIND_FRAME);
    if (kj_handle_live_count() != 0) {
        fprintf(stderr, "FAIL: live count is %lld after releasing everything\n",
                (long long)kj_handle_live_count());
        return 1;
    }
    printf("handles: wrong kind refused, closed refused, REUSED slot refuses the old token\n");

    printf("OK: the codec library links, runs and finds every filter it names\n");
    return 0;
}

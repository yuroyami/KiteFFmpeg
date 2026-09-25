/* Fuzz target: ffkmp_fmt_set_metadata, through av_dict_set.
 *
 * Entry point, and why this one. av_dict_set is a different parser from
 * av_opt_set and a different hazard. It does not look the key up in a table: it stores the key and
 * the value in a growing AVDictionary, which means every input mutates state that the next input
 * sees, and the dictionary itself is what does the string handling. Three properties are worth
 * exercising and none of them exists in the option targets:
 *
 *   1. Keys accumulate. The same context takes many keys in a row here, so the dictionary grows and
 *      its realloc path runs, which is where a size arithmetic mistake would live.
 *   2. A duplicate key replaces rather than appends, and the replacement frees the old value. This
 *      target sets every key twice on purpose, so the free path runs on every input.
 *   3. A NULL value DELETES the entry. That is av_dict_set's documented behaviour and it is the one
 *      branch that frees a key. An input with no newline in it has a NULL value, per kc_fuzz.h, so
 *      the corpus reaches this branch through its no-newline seeds.
 *
 * The readback is not decoration. Every key that was set is walked back out with ffkmp_dict_get and
 * ffkmp_dict_entry_key, which is the same iteration Kotlin uses, and each returned pointer is read.
 * Under ASan that turns a dictionary entry whose key was freed while still linked into a
 * use-after-free report on the spot instead of a wrong value nobody looks at.
 *
 * The context is a muxer context and is freed at the end of every input, which is also what frees
 * the dictionary. So the accumulation is per input rather than across inputs: libFuzzer needs each
 * call to be independent, or a crash is not reproducible from the one file it saved.
 */

#include "kc_fuzz.h"

#include <libavformat/avformat.h>
#include <libavutil/dict.h>
#include <libavutil/error.h>

#include <stdlib.h>

#define FUZZ_OUTPUT_PATH "kc_fuzz_never_opened.out"

/* Where the bytes read out of the dictionary go. Volatile so the compiler cannot decide the reads
 * are dead and delete them: a read that -O1 removed would be a check that silently stopped
 * checking, which is worse than no check because the target would still look green. */
static volatile char byte_sink;

/* Walks the whole metadata dictionary and reads every key and value it finds. The values are
 * thrown away; what is being tested is that reading them is safe. */
static void read_back_everything(AVFormatContext *ctx) {
    AVDictionary *dict = ffkmp_fmt_metadata(ctx);
    AVDictionaryEntry *entry = NULL;
    int seen = 0;
    while ((entry = ffkmp_dict_get(dict, entry)) != NULL) {
        const char *k = ffkmp_dict_entry_key(entry);
        const char *v = ffkmp_dict_entry_value(entry);
        /* A live entry always has a key. A value can be an empty string, which is legitimate. */
        if (k == NULL) abort();
        byte_sink = k[0];
        if (v != NULL) byte_sink = v[0];
        /* A dictionary this target built holds a handful of entries, so a walk that does not
         * terminate is a corrupted list rather than a big one. Bound it and abort, which is a
         * finding, rather than hanging until libFuzzer's timeout calls it something vaguer. */
        if (++seen > 4096) abort();
    }
}

int LLVMFuzzerTestOneInput(const uint8_t *data, size_t size) {
    kc_fuzz_quiet();

    char *key = NULL;
    char *value = NULL;
    if (kc_fuzz_split(data, size, &key, &value) != 0) return 0;

    /* Both guards this helper does have, asserted on every input. */
    if (ffkmp_fmt_set_metadata(NULL, key, value) != AVERROR(EINVAL)) abort();

    AVFormatContext *ctx = NULL;
    if (ffkmp_fmt_alloc_output2(&ctx, FUZZ_OUTPUT_PATH, "matroska") == 0 && ctx != NULL) {
        if (ffkmp_fmt_set_metadata(ctx, NULL, value) != AVERROR(EINVAL)) abort();

        /* A tag that is already there, so the fuzzed key can collide with a real one. */
        (void)ffkmp_fmt_set_metadata(ctx, "title", "kc-fuzz");

        /* First set, then the same key again: the second call takes av_dict_set's replace path and
         * frees the first value. */
        (void)ffkmp_fmt_set_metadata(ctx, key, value);
        (void)ffkmp_fmt_set_metadata(ctx, key, value);

        read_back_everything(ctx);

        /* And once more with an explicit NULL value, which is av_dict_set's delete path. When the
         * input carried no newline the value was already NULL and this repeats the same branch,
         * which costs nothing; when it carried one, this is the only call that reaches it. */
        (void)ffkmp_fmt_set_metadata(ctx, key, NULL);

        read_back_everything(ctx);

        ffkmp_fmt_free_output(&ctx);
        if (ctx != NULL) abort();
    }

    kc_fuzz_free(key);
    kc_fuzz_free(value);
    return 0;
}

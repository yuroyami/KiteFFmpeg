/* The corpus replay driver: main() for a fuzz target on a machine with no libFuzzer.
 *
 * Coverage-guided fuzzing cannot run on this host at all: the runtime
 * archive libclang_rt.fuzzer_osx.a is absent from Apple clang 17 and from konan's LLVM 21, and
 * Homebrew LLVM is not installed, so -fsanitize=fuzzer fails at the link. The plan's answer is
 * two drivers over one body. This is the second one, and what it earns is smaller and honest: it
 * replays a committed corpus under ASan and UBSan as an ordinary regression test. It discovers
 * nothing. It only refuses to forget.
 *
 * Usage:  <target>_replay <file> [file ...]
 *
 * One line per file, the path first so a sanitizer report that follows names its own input, and
 * the line is flushed BEFORE the call rather than after. That ordering is the whole point of the
 * driver's output: a finding aborts the process inside LLVMFuzzerTestOneInput, so a line printed
 * afterwards would never appear and the crashing input would be the one nobody could name.
 *
 * Exit codes:
 *   0  every file was read and replayed, no finding
 *   1  a file could not be read, or was not a regular file, or no file was replayed at all
 *   2  wrong usage
 *   anything else, or a signal: the sanitizer or the crash, which is a finding
 */

#include "kc_fuzz.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>

/* Reads a whole file. On success returns 0 and sets *out_data and *out_size; a zero length file
 * gives a one byte allocation and a size of 0, because an empty input is a legitimate test case
 * and must not be confused with a read failure. */
static int read_whole_file(const char *path, uint8_t **out_data, size_t *out_size) {
    *out_data = NULL;
    *out_size = 0;

    struct stat info;
    if (stat(path, &info) != 0) {
        fprintf(stderr, "replay: cannot stat %s\n", path);
        return -1;
    }
    if (!S_ISREG(info.st_mode)) {
        fprintf(stderr, "replay: not a regular file: %s\n", path);
        return -1;
    }

    FILE *file = fopen(path, "rb");
    if (file == NULL) {
        fprintf(stderr, "replay: cannot open %s\n", path);
        return -1;
    }

    size_t size = (size_t)info.st_size;
    uint8_t *data = (uint8_t *)malloc(size + 1);
    if (data == NULL) {
        fprintf(stderr, "replay: out of memory for %zu bytes from %s\n", size, path);
        fclose(file);
        return -1;
    }

    size_t got = (size > 0) ? fread(data, 1, size, file) : 0;
    fclose(file);
    if (got != size) {
        fprintf(stderr, "replay: short read on %s, wanted %zu got %zu\n", path, size, got);
        free(data);
        return -1;
    }

    *out_data = data;
    *out_size = size;
    return 0;
}

/* The last path component, for a readable line when the corpus is given as absolute paths. */
static const char *basename_of(const char *path) {
    const char *slash = strrchr(path, '/');
    return (slash != NULL) ? slash + 1 : path;
}

int main(int argc, char **argv) {
    if (argc < 2) {
        fprintf(stderr, "usage: %s <corpus-file> [corpus-file ...]\n", argv[0]);
        return 2;
    }

    int replayed = 0;
    int unreadable = 0;
    unsigned long long total_bytes = 0;

    for (int i = 1; i < argc; i++) {
        uint8_t *data = NULL;
        size_t size = 0;
        if (read_whole_file(argv[i], &data, &size) != 0) {
            unreadable++;
            continue;
        }

        printf("  run  %-40s %9zu bytes\n", basename_of(argv[i]), size);
        fflush(stdout);

        (void)LLVMFuzzerTestOneInput(data, size);

        free(data);
        replayed++;
        total_bytes += (unsigned long long)size;
    }

    printf("replay: %d files, %llu bytes, %d unreadable\n", replayed, total_bytes, unreadable);
    fflush(stdout);

    if (unreadable > 0) return 1;
    /* Zero files replayed is a failure and not a pass. A corpus directory that got emptied, or a
     * glob that matched nothing, must not read as green. */
    if (replayed == 0) return 1;
    return 0;
}

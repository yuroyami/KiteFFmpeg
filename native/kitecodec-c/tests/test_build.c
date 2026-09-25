/* ffkmp_component_names: the refusals, the two-call sizing, and names a real build must list. */

#include "harness.h"

#include "kitecodec_helpers.h"

#include <stdlib.h>
#include <string.h>

#include <libavutil/error.h>

/* True when list, newline separated, holds name as a whole entry. */
static int lists(const char *list, const char *name)
{
    size_t n = strlen(name);
    const char *at = list;
    while ((at = strstr(at, name)) != NULL) {
        int starts = at == list || at[-1] == '\n';
        int ends = at[n] == '\0' || at[n] == '\n';
        if (starts && ends) return 1;
        at += n;
    }
    return 0;
}

/* The whole list of kind, sized by a first call; the caller frees it. */
static char *names(int kind)
{
    int needed = ffkmp_component_names(kind, NULL, 0);
    char *buf;
    KC_CHECKF(needed > 0, "kind %d listed nothing (%d)", kind, needed);
    buf = malloc((size_t)needed + 1);
    KC_NOT_NULL(buf);
    KC_EQ_INT(ffkmp_component_names(kind, buf, needed + 1), needed);
    KC_EQ_INT((int)strlen(buf), needed);
    return buf;
}

static void case_refusals(void)
{
    char small[4];
    kc_case("an unknown kind, a negative cap and a NULL buffer with room are refused");
    KC_EQ_INT(ffkmp_component_names(99, small, (int)sizeof(small)), AVERROR(EINVAL));
    KC_EQ_INT(ffkmp_component_names(KC_COMPONENT_DECODERS, small, -1), AVERROR(EINVAL));
    KC_EQ_INT(ffkmp_component_names(KC_COMPONENT_DECODERS, NULL, 4), AVERROR(EINVAL));
}

static void case_a_short_buffer_is_ended_and_sized(void)
{
    char small[8];
    int needed;
    kc_case("a short buffer is still NUL-ended and the answer is the full length");
    memset(small, 'x', sizeof(small));
    needed = ffkmp_component_names(KC_COMPONENT_DECODERS, small, (int)sizeof(small));
    KC_CHECKF(needed > (int)sizeof(small), "the decoder list fit in %d bytes (%d)", (int)sizeof(small), needed);
    KC_EQ_INT(small[sizeof(small) - 1], '\0');
}

static void case_every_family_lists_what_a_build_has(void)
{
    struct row { int kind; const char *name; };
    const struct row rows[] = {
        { KC_COMPONENT_DECODERS, "h264" },
        { KC_COMPONENT_DECODERS, "aac" },
        { KC_COMPONENT_ENCODERS, "aac" },
        { KC_COMPONENT_DEMUXERS, "wav" },
        { KC_COMPONENT_MUXERS, "wav" },
        { KC_COMPONENT_FILTERS, "aresample" },
        { KC_COMPONENT_INPUT_PROTOCOLS, "file" },
        { KC_COMPONENT_BITSTREAM_FILTERS, "null" },
    };
    size_t i;
    for (i = 0; i < sizeof(rows) / sizeof(rows[0]); i++) {
        char *list = names(rows[i].kind);
        kc_case("kind %d lists %s", rows[i].kind, rows[i].name);
        KC_CHECKF(lists(list, rows[i].name), "kind %d does not list %s", rows[i].kind, rows[i].name);
        free(list);
    }
}

int main(void)
{
    kc_suite_begin("test_build");

    case_refusals();
    case_a_short_buffer_is_ended_and_sized();
    case_every_family_lists_what_a_build_has();

    return kc_suite_end();
}

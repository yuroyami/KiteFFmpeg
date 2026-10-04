/*
 * The libass chain's glyph fallback through loaded fonts (#152), tested against a built chain.
 *
 * libass is set up the way a build with no system font provider runs it, as on Android, Linux and
 * the web: no provider, fonts added from memory, and a default family. Every style names a family
 * that no font has, like the "MS Gothic" or "Arial" a script carries to a device without them.
 *
 * The four fonts come from make-test-fonts.py, and each draws its characters as plain shapes of a
 * known area, so the ink a line leaves tells which font drew it. Each check compares a line with
 * the same line under a style that names the expected font outright, and with the line the wrong
 * font would have drawn, so a pass means the expected font drew it and nothing else could have.
 *
 *   test_glyph_fallback <fonts dir>
 */
#include <ass/ass.h>

#include <math.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static int failures;
static int verbose;

static void message(int level, const char *fmt, va_list args, void *data)
{
    (void) data;
    if (!verbose || level > 6)
        return;
    fprintf(stderr, "  libass: ");
    vfprintf(stderr, fmt, args);
    fprintf(stderr, "\n");
}

static char *read_file(const char *dir, const char *name, size_t *size)
{
    char path[4096];
    snprintf(path, sizeof(path), "%s/%s", dir, name);
    FILE *f = fopen(path, "rb");
    if (!f) {
        fprintf(stderr, "cannot open %s\n", path);
        exit(2);
    }
    fseek(f, 0, SEEK_END);
    long length = ftell(f);
    fseek(f, 0, SEEK_SET);
    char *data = malloc((size_t) length);
    if (!data || fread(data, 1, (size_t) length, f) != (size_t) length) {
        fprintf(stderr, "cannot read %s\n", path);
        exit(2);
    }
    fclose(f);
    *size = (size_t) length;
    return data;
}

typedef struct {
    ASS_Library *library;
    ASS_Renderer *renderer;
} Setup;

/* A library with the named fonts added from memory in the given order, no font provider, and
 * "Kite Test Latin" as the default family, as a player without a system provider configures it. */
static Setup setup(const char *dir, const char *const *fonts, int count)
{
    Setup s;
    s.library = ass_library_init();
    if (!s.library) {
        fprintf(stderr, "ass_library_init failed\n");
        exit(2);
    }
    ass_set_message_cb(s.library, message, NULL);
    for (int i = 0; i < count; i++) {
        size_t size;
        char *data = read_file(dir, fonts[i], &size);
        ass_add_font(s.library, fonts[i], data, (int) size);
        free(data);
    }
    s.renderer = ass_renderer_init(s.library);
    if (!s.renderer) {
        fprintf(stderr, "ass_renderer_init failed\n");
        exit(2);
    }
    ass_set_frame_size(s.renderer, 1280, 720);
    ass_set_fonts(s.renderer, NULL, "Kite Test Latin", ASS_FONTPROVIDER_NONE, NULL, 1);
    return s;
}

static void teardown(Setup s)
{
    ass_renderer_done(s.renderer);
    ass_library_done(s.library);
}

/* The total coverage of one line: the sum of every alpha value libass drew for it. The style has
 * no outline and no shadow, so the glyphs' own fill is all there is. */
static double ink(Setup s, const char *style, const char *text)
{
    char script[4096];
    snprintf(script, sizeof(script),
             "[Script Info]\n"
             "ScriptType: v4.00+\n"
             "PlayResX: 1280\n"
             "PlayResY: 720\n"
             "WrapStyle: 2\n"
             "\n"
             "[V4+ Styles]\n"
             "Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, "
             "BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, "
             "BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\n"
             "Style: Default,%s,60,&H00FFFFFF,&H00FFFFFF,&H00000000,&H00000000,%s,0,0,0,100,100,"
             "0,0,1,0,0,5,10,10,10,1\n"
             "\n"
             "[Events]\n"
             "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n"
             "Dialogue: 0,0:00:00.00,0:00:10.00,Default,,0,0,0,,%s\n",
             style[0] == '*' ? style + 1 : style, style[0] == '*' ? "-1" : "0", text);
    ASS_Track *track = ass_read_memory(s.library, script, strlen(script), NULL);
    if (!track) {
        fprintf(stderr, "ass_read_memory failed\n");
        exit(2);
    }
    int change = 0;
    double sum = 0;
    for (ASS_Image *img = ass_render_frame(s.renderer, track, 500, &change); img; img = img->next) {
        for (int y = 0; y < img->h; y++)
            for (int x = 0; x < img->w; x++)
                sum += img->bitmap[y * img->stride + x];
    }
    ass_free_track(track);
    return sum;
}

static int close_to(double a, double b)
{
    return fabs(a - b) <= 0.01 * fmax(a, b);
}

static void expect(const char *what, double got, double want, double wrong)
{
    int ok = got > 0 && close_to(got, want) && !close_to(got, wrong);
    printf("%s %s: ink %.0f, expected %.0f, the wrong font gives %.0f\n",
           ok ? "PASS" : "FAIL", what, got, want, wrong);
    if (!ok)
        failures++;
}

/* Families that no font here has. A leading '*' makes the style bold. Each check takes its own,
 * because libass keeps the faces a font gained for one line and tries them first for the next, so a
 * shared family would answer a later check from an earlier one's fallback. */
#define MISSING "MS Gothic"
#define MISSING_BOLD "*MS Gothic"
#define MISSING_MIXED "Arial Unicode MS"
#define MISSING_STAR "Segoe UI Symbol"

int main(int argc, char **argv)
{
    if (argc < 2) {
        fprintf(stderr, "usage: %s <fonts dir>\n", argv[0]);
        return 2;
    }
    verbose = getenv("KITE_ASS_TEST_VERBOSE") != NULL;
    const char *dir = argv[1];
    const char *line = "日本語の字幕";

    /* The CJK font is loaded before the Latin one, so a fallback that ran for every character, or
     * that replaced the default family, would show as Latin letters drawn from the CJK font. */
    const char *const cjk_first[] = {
        "kite-test-cjk-regular.ttf", "kite-test-cjk-bold.ttf", "kite-test-latin.ttf", "kite-test-star.ttf",
    };
    Setup a = setup(dir, cjk_first, 4);

    double cjk_regular = ink(a, "Kite Test CJK", line);
    double cjk_bold = ink(a, "*Kite Test CJK", line);
    /* The fonts themselves: the bold face's squares hold 0.75 of the regular one's ink. Without
     * this the comparisons below could pass on two references that drew the same thing. */
    if (!(cjk_regular > 0 && fabs(cjk_bold / cjk_regular - 0.75) < 0.05)) {
        printf("FAIL the test fonts did not draw as designed: regular %.0f, bold %.0f\n", cjk_regular, cjk_bold);
        return 1;
    }

    /* Without the fallback the default family's hollow .notdef box draws for each character, as
     * it still does for six characters that no font here has. */
    double boxes = ink(a, "Kite Test Latin", "一二三四五六");
    expect("a CJK line in a missing font draws from the loaded CJK font",
           ink(a, MISSING, line), cjk_regular, boxes);

    /* The fallback names the family and the style picks the face, so a bold line takes the bold
     * face that has the characters, not the regular face loaded first, made bold by libass. */
    expect("a bold CJK line in a missing font draws from the bold face of the CJK family",
           ink(a, MISSING_BOLD, line), cjk_bold, cjk_regular);

    /* Latin letters are in the default family, so they come from it even though the CJK font,
     * loaded first, has them too; only the characters it lacks fall back. */
    const char *mixed = "AbAb日本語";
    expect("a mixed line keeps its Latin letters in the default family",
           ink(a, MISSING_MIXED, mixed),
           ink(a, MISSING_MIXED, "{\\fnKite Test Latin}AbAb{\\fnKite Test CJK}日本語"),
           ink(a, MISSING_MIXED, "{\\fnKite Test CJK}AbAb日本語"));

    /* Loading order decides between two fonts that both have a character. */
    double star_from_cjk = ink(a, "Kite Test CJK", "★");
    double star_from_star = ink(a, "Kite Test Star", "★");
    expect("a character two fonts have comes from the one loaded first",
           ink(a, MISSING_STAR, "★"), star_from_cjk, star_from_star);
    teardown(a);

    const char *const star_first[] = {
        "kite-test-star.ttf", "kite-test-cjk-regular.ttf", "kite-test-cjk-bold.ttf", "kite-test-latin.ttf",
    };
    Setup b = setup(dir, star_first, 4);
    expect("loaded first, the other font gives the character instead",
           ink(b, MISSING_STAR, "★"), ink(b, "Kite Test Star", "★"), ink(b, "Kite Test CJK", "★"));
    teardown(b);

    if (failures) {
        printf("%d check(s) failed\n", failures);
        return 1;
    }
    printf("all checks passed\n");
    return 0;
}

#!/usr/bin/env bash
#
# Build the host test binaries for the extracted FFmpeg helper layer.
#
# There is no make, no cmake and no ninja here, and that is deliberate:
# cmake is not installed on the proving machine, and GNU make starts a comment at an
# unescaped '#' while both repositories live under a path containing '#Kite'. Driving clang
# directly is the only form that is both available and safe under this path, and it is proven
# to work here.
#
# Usage:  ./scripts/build-host.sh <variant>
#         variant is one of: plain asan tsan
#
# Variants, per plan section 15.3. ASan and TSan are mutually exclusive, which is why there
# are three of them rather than one:
#
#   plain  -O2, no runtime instrumentation. The compile-fidelity and correctness variant.
#          This is also the only variant in which the allocation interposer works, so it is
#          the variant that carries the leak evidence (LeakSanitizer is not supported on
#          macOS arm64).
#   asan   -fsanitize=address,undefined -fno-omit-frame-pointer -O1. Catches the
#          out-of-bounds class that the identity gate of B1.6 prevents.
#   tsan   -fsanitize=thread -O1. Keeps the threaded cases honest.
#
# Environment:
#   KC_CC             compiler to use, default /usr/bin/clang
#   KC_AR             archiver to use, default /usr/bin/ar
#   KC_FFMPEG_PREFIX  when set, FFmpeg include and library flags are derived from it instead
#                     of from pkg-config. Expects <prefix>/include and <prefix>/lib.
#   KC_BUILD_REF      the FFmpeg ref this build claims to target, default n8.0. Reaches the
#   KC_BUILD_LICENSE  identity report of src/kitecodec_abi.c as -D defines, the same three the
#                     shipped archive gets from CompileKiteFFmpegCTask.buildDefines. They are
#                     reported and never compared, so a host build that leaves them at their
#                     defaults still tests the gate; what they must not be is absent, because
#                     then the report would say "unknown" and test_identity would fail on the
#                     case that asserts both licence fields are populated.
#
# Outputs, all under build/<variant>/ which is gitignored:
#   lib/libkitecodec_helpers_host.a   the extracted helper layer plus the identity gate
#   lib/libkc_interpose_alloc.dylib   the allocation interposer
#   bin/test_*                        one binary per test suite
#
set -euo pipefail

VARIANT="${1:-}"
case "$VARIANT" in
    plain|asan|tsan) ;;
    "") echo "build-host.sh: usage: $0 <plain|asan|tsan>" >&2; exit 2 ;;
    *)  echo "build-host.sh: unknown variant '$VARIANT', expected plain, asan or tsan" >&2
        exit 2 ;;
esac

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"

CC="${KC_CC:-/usr/bin/clang}"
AR="${KC_AR:-/usr/bin/ar}"
[ -x "$CC" ] || { echo "build-host.sh: no compiler at $CC" >&2; exit 1; }
[ -x "$AR" ] || { echo "build-host.sh: no archiver at $AR" >&2; exit 1; }

# The six FFmpeg libraries the helper layer needs, in link order.
FFMPEG_LIBS="libavformat libavcodec libavfilter libavutil libswscale libswresample"

if [ -n "${KC_FFMPEG_PREFIX:-}" ]; then
    [ -d "$KC_FFMPEG_PREFIX/include" ] || {
        echo "build-host.sh: KC_FFMPEG_PREFIX=$KC_FFMPEG_PREFIX has no include directory" >&2
        exit 1
    }
    FF_CFLAGS="-I$KC_FFMPEG_PREFIX/include"
    FF_LDFLAGS="-L$KC_FFMPEG_PREFIX/lib"
    FF_LIBS="-lavformat -lavcodec -lavfilter -lavutil -lswscale -lswresample"
    FF_ORIGIN="KC_FFMPEG_PREFIX=$KC_FFMPEG_PREFIX"
else
    command -v pkg-config >/dev/null 2>&1 || {
        echo "build-host.sh: pkg-config not found and KC_FFMPEG_PREFIX is not set" >&2
        exit 1
    }
    pkg-config --exists $FFMPEG_LIBS || {
        echo "build-host.sh: pkg-config cannot find all of: $FFMPEG_LIBS" >&2
        exit 1
    }
    # pkg-config output is used exactly as it comes. It was measured warning-clean under
    # -Wall -Wextra -Werror against FFmpeg 8.0 here, so there is no reason to rewrite its -I
    # into -isystem: doing that would silence a real signal from a future FFmpeg for free.
    FF_CFLAGS="$(pkg-config --cflags $FFMPEG_LIBS)"
    FF_LDFLAGS=""
    FF_LIBS="$(pkg-config --libs $FFMPEG_LIBS)"
    FF_ORIGIN="pkg-config ($(pkg-config --modversion libavcodec) libavcodec)"
fi

BASE_FLAGS="-std=c11 -Wall -Wextra -Werror -Werror=vla -g"
case "$VARIANT" in
    plain) VARIANT_FLAGS="-O2" ;;
    asan)  VARIANT_FLAGS="-fsanitize=address,undefined -fno-omit-frame-pointer -O1" ;;
    tsan)  VARIANT_FLAGS="-fsanitize=thread -O1" ;;
esac

# What the artifact was built for, carried into the identity report of src/kitecodec_abi.c. The
# shipped archive gets the same three from kiteffmpeg-core/build.gradle.kts through
# CompileKiteFFmpegCTask.buildDefines; here they describe the host tree the tests link against.
KC_BUILD_REF="${KC_BUILD_REF:-n8.0}"
KC_BUILD_LICENSE="${KC_BUILD_LICENSE:-lgpl}"
if [ -n "${KC_FFMPEG_PREFIX:-}" ]; then
    KC_BUILD_DIR="$KC_FFMPEG_PREFIX/lib"
else
    KC_BUILD_DIR="$(pkg-config --variable=libdir libavutil)"
fi
BUILD_DEFINES=(
    "-DKC_BUILD_FFMPEG_REF=\"$KC_BUILD_REF\""
    "-DKC_BUILD_FFMPEG_LICENSE=\"$KC_BUILD_LICENSE\""
    "-DKC_BUILD_FFMPEG_DIR=\"$KC_BUILD_DIR\""
)

OUT="$ROOT/build/$VARIANT"
OBJ="$OUT/obj"
LIB="$OUT/lib"
BIN="$OUT/bin"
rm -rf "$OUT"
mkdir -p "$OBJ" "$LIB" "$BIN"

HELPER_LIB="$LIB/libkitecodec_helpers_host.a"
INTERPOSE_LIB="$LIB/libkc_interpose_alloc.dylib"

# The seven suites of plan section 15.3. Keep this list and run-c-tests.sh in agreement.
TESTS="test_ownership test_buffers test_rescale test_strerror_thread test_convert test_identity test_args test_append"

# The doctored copies of the identity gate, one directory per case under tests/fake_headers/. Each is
# src/kitecodec_abi.c compiled again with that directory FIRST on the include path, so its shim
# replaces include/kitecodec_ffmpeg_versions.h and doctors what the gate believes its headers said.
# They link into test_identity only. See tests/fake_headers/kc_rename.h for why they do not collide.
IDENTITY_CASES="major_mismatch runtime_older micro_older configuration_mismatch bypass"

echo "build-host.sh: variant $VARIANT"
echo "  compiler   $CC ($("$CC" --version | head -1))"
echo "  archiver   $AR"
echo "  ffmpeg     $FF_ORIGIN"
echo "  flags      $BASE_FLAGS $VARIANT_FLAGS"
echo "  built for  $KC_BUILD_REF ($KC_BUILD_LICENSE) from $KC_BUILD_DIR"
echo "  output     $OUT"

compile() {
    # compile <source> <object> [extra flags...]
    local source="$1" object="$2"
    shift 2
    echo "  cc  $(basename "$source")"
    # shellcheck disable=SC2086
    # kitecodec-jni is on the include path for ONE reason: test_append covers kj_append.h, the
    # JNI layer's bounded string builder. That header carries no jni.h, so it compiles
    # here, and the JNI tree has no C test rig of its own to put the suite in.
    "$CC" $BASE_FLAGS $VARIANT_FLAGS "${BUILD_DEFINES[@]}" $FF_CFLAGS \
        -I "$ROOT/include" -I "$ROOT/tests" -I "$ROOT/../kitecodec-jni" \
        "$@" -c "$source" -o "$object"
}

# 1. The extracted helper layer, one translation unit per subsystem since B1.4. Every unit
#    includes the generated header, so this compile is the proof that every emitted declaration
#    matches its definition. -Werror makes a mismatch a hard failure rather than a warning
#    nobody reads.
#
#    Separate compilation is also the mechanical proof of the internal-locality property that
#    plan section 15.2 B1.4 step 1 asks for. The four trailing-underscore helpers are `static`.
#    Calling one from another unit would be an implicit declaration there, and defining one in a
#    unit that never calls it would be an unused function, and under -Wall -Wextra -Werror both
#    are hard errors. So a green build here says the four are in the same unit as their callers;
#    it is not an argument, it is the linker's and the compiler's answer.
#
#    -fvisibility=hidden is added for the helper units only, matching CompileKiteFFmpegCTask's
#    flag set, so the host archive carries the same exported set as the shipped one and
#    scripts/symbol-audit.sh means the same thing whichever archive it is pointed at. It does not
#    affect static linking, so the test binaries still resolve every helper they call.
HELPER_SOURCES="$(find "$ROOT/src" -maxdepth 1 -name '*.c' | sort)"
[ -n "$HELPER_SOURCES" ] || {
    echo "build-host.sh: no .c sources under $ROOT/src" >&2
    exit 1
}
HELPER_OBJECTS=""
for source in $HELPER_SOURCES; do
    object="$OBJ/$(basename "${source%.c}").o"
    compile "$source" "$object" -fvisibility=hidden
    HELPER_OBJECTS="$HELPER_OBJECTS $object"
done
rm -f "$HELPER_LIB"
# shellcheck disable=SC2086
"$AR" rcs "$HELPER_LIB" $HELPER_OBJECTS
echo "  ar  $(basename "$HELPER_LIB") ($(echo $HELPER_SOURCES | wc -w | tr -d ' ') units)"

# 2. The allocation interposer. It has to be a dynamic library: a Mach-O __DATA,__interpose
#    section is only honoured by dyld when it arrives in a loaded image, and linking the same
#    object straight into the executable was measured to count exactly zero.
echo "  cc  interpose_alloc.c (dylib)"
# shellcheck disable=SC2086
"$CC" $BASE_FLAGS $VARIANT_FLAGS -dynamiclib \
    -install_name "@rpath/$(basename "$INTERPOSE_LIB")" \
    "$ROOT/tests/interpose_alloc.c" -o "$INTERPOSE_LIB"

# 3. The harness.
compile "$ROOT/tests/harness.c" "$OBJ/harness.o"

# 4. The doctored copies of the identity gate, for tests/test_identity.c.
#
#    The include order is the whole trick and it is fragile in exactly one way: the shim directory has
#    to come BEFORE -I "$ROOT/include", because a quoted #include searches the -I list in command line
#    order and the first hit wins. Put it after and the real header would be found, every case would
#    read the true version macros, and all five would pass while testing nothing. So this is a separate
#    invocation rather than a call to compile(), which appends its extra flags at the end.
#
#    The SOURCE is the shipped src/kitecodec_abi.c, unmodified. Nothing about the production file knows
#    these copies exist.
IDENTITY_OBJECTS=""
if [ -f "$ROOT/tests/test_identity.c" ]; then
    for case_name in $IDENTITY_CASES; do
        shim="$ROOT/tests/fake_headers/$case_name"
        [ -f "$shim/kitecodec_ffmpeg_versions.h" ] || {
            echo "build-host.sh: no shim at $shim/kitecodec_ffmpeg_versions.h" >&2
            exit 1
        }
        object="$OBJ/kitecodec_abi_$case_name.o"
        echo "  cc  kitecodec_abi.c (doctored: $case_name)"
        # shellcheck disable=SC2086
        "$CC" $BASE_FLAGS $VARIANT_FLAGS "${BUILD_DEFINES[@]}" \
            -I "$shim" $FF_CFLAGS -I "$ROOT/include" -I "$ROOT/tests" \
            -fvisibility=hidden -c "$ROOT/src/kitecodec_abi.c" -o "$object"
        IDENTITY_OBJECTS="$IDENTITY_OBJECTS $object"
    done
fi

# 5. One binary per suite. Every binary links the interposer, so the harness can report
#    whether allocation accounting is live without any test having to care which variant it
#    is running under.
for test in $TESTS; do
    source="$ROOT/tests/$test.c"
    [ -f "$source" ] || {
        echo "build-host.sh: missing test source $source" >&2
        exit 1
    }
    compile "$source" "$OBJ/$test.o"
    extra=""
    [ "$test" = "test_identity" ] && extra="$IDENTITY_OBJECTS"
    echo "  ld  $test"
    # shellcheck disable=SC2086
    "$CC" $BASE_FLAGS $VARIANT_FLAGS -o "$BIN/$test" \
        "$OBJ/$test.o" "$OBJ/harness.o" $extra "$HELPER_LIB" "$INTERPOSE_LIB" \
        -Wl,-rpath,"$LIB" $FF_LDFLAGS $FF_LIBS -lpthread
done

echo "build-host.sh: variant $VARIANT built $(echo $TESTS | wc -w | tr -d ' ') binaries in $BIN"

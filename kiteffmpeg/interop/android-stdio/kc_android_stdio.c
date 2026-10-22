/*
 * Linked only into the Android native klibs, never into the JNI library.
 *
 * Kotlin/Native links Android binaries against the libc of API 21. FFmpeg and the helper layer are built
 * for API 24, where `stdout` and `stderr` are variables of libc (API 23 added them). The API 21 libc has
 * only the array `__sF`, so this file defines the two variables the way bionic does: they point into it.
 * The NDK headers hide the layout of FILE, but bionic keeps its size fixed for this array:
 * 152 bytes on 64-bit and 84 bytes on 32-bit.
 */
#include <stdio.h>

#undef stdout
#undef stderr

#if defined(__LP64__)
#define KC_BIONIC_FILE_SIZE 152
#else
#define KC_BIONIC_FILE_SIZE 84
#endif

extern unsigned char kc_bionic_sF[] __asm__("__sF");

FILE *stdout = (FILE *) (kc_bionic_sF + KC_BIONIC_FILE_SIZE);
FILE *stderr = (FILE *) (kc_bionic_sF + 2 * KC_BIONIC_FILE_SIZE);

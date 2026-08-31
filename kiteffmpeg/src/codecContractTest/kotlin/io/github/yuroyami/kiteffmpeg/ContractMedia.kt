package io.github.yuroyami.kiteffmpeg

/** A 0.6 second, 16x16 MPEG-4 + 8 kHz mono AAC MP4 used by every contract arm. */
internal object ContractMedia {
    const val sha256: String = "faaf3d1a56d29905869884ebebe7566998b377a3e9408186f1ec4aecd103592a"

    val bytes: ByteArray by lazy {
        decodeBase64(
            DATA.filterNot { it.isWhitespace() },
        ).also { decoded ->
            check(decoded.size == 4_017) { "Contract fixture size changed: ${decoded.size}" }
            check(sha256Hex(decoded) == sha256) { "Contract fixture digest changed" }
        }
    }

    private val DATA: String = """
AAAAHGZ0eXBpc29tAAACAGlzb21pc28ybXA0MQAABd1tb292AAAAbG12aGQAAAAAAAAAAAAAAAAAAAPoAAACWAABAAABAAAAAAAA
AAAAAAAAAQAAAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAADAAACk3Ry
YWsAAABcdGtoZAAAAAMAAAAAAAAAAAAAAAEAAAAAAAACWAAAAAAAAAAAAAAAAAAAAAAAAQAAAAAAAAAAAAAAAAAAAAEAAAAAAAAA
AAAAAAAAAEAAAAAAEAAAABAAAAAAACRlZHRzAAAAHGVsc3QAAAAAAAAAAQAAAlgAAAAAAAEAAAAAAgttZGlhAAAAIG1kaGQAAAAA
AAAAAAAAAAAAACgAAAAYAFXEAAAAAAAtaGRscgAAAAAAAAAAdmlkZQAAAAAAAAAAAAAAAFZpZGVvSGFuZGxlcgAAAAG2bWluZgAA
ABR2bWhkAAAAAQAAAAAAAAAAAAAAJGRpbmYAAAAcZHJlZgAAAAAAAAABAAAADHVybCAAAAABAAABdnN0YmwAAADqc3RzZAAAAAAA
AAABAAAA2m1wNHYAAAAAAAAAAQAAAAAAAAAAAAAAAAAAAAAAEAAQAEgAAABIAAAAAAAAAAETTGF2YzYyLjExLjEwMCBtcGVnNAAA
AAAAAAAAAAAAAAAY//8AAABgZXNkcwAAAAADgICATwABAASAgIBBIBEAAAAAAw1AAAARcgWAgIAvAAABsAEAAAG1iRMAAAEAAAAB
IADEjYgALQCEAhRjAAABskxhdmM2Mi4xMS4xMDAGgICAAQIAAAAQcGFzcAAAAAEAAAABAAAAFGJ0cnQAAAAAAAMNQAAAEXIAAAAY
c3R0cwAAAAAAAAABAAAAAwAACAAAAAAUc3RzcwAAAAAAAAABAAAAAQAAABxzdHNjAAAAAAAAAAEAAAABAAAAAQAAAAEAAAAgc3Rz
egAAAAAAAAAAAAAAAwAAAUEAAAAHAAAABwAAABxzdGNvAAAAAAAAAAMAAAggAAALtAAADl8AAAJ1dHJhawAAAFx0a2hkAAAAAwAA
AAAAAAAAAAAAAgAAAAAAAAJYAAAAAAAAAAAAAAABAQAAAAABAAAAAAAAAAAAAAAAAAAAAQAAAAAAAAAAAAAAAAAAQAAAAAAAAAAA
AAAAAAAAJGVkdHMAAAAcZWxzdAAAAAAAAAABAAACWAAABAAAAQAAAAAB7W1kaWEAAAAgbWRoZAAAAAAAAAAAAAAAAAAAH0AAABbA
VcQAAAAAAC1oZGxyAAAAAAAAAABzb3VuAAAAAAAAAAAAAAAAU291bmRIYW5kbGVyAAAAAZhtaW5mAAAAEHNtaGQAAAAAAAAAAAAA
ACRkaW5mAAAAHGRyZWYAAAAAAAAAAQAAAAx1cmwgAAAAAQAAAVxzdGJsAAAAfnN0c2QAAAAAAAAAAQAAAG5tcDRhAAAAAAAAAAEA
AAAAAAAAAAABABAAAAAAH0AAAAAAADZlc2RzAAAAAAOAgIAlAAIABICAgBdAFQAAAAAAXcAAAFu7BYCAgAUViFblAAaAgIABAgAA
ABRidHJ0AAAAAAAAXcAAAFu7AAAAIHN0dHMAAAAAAAAAAgAAAAUAAAQAAAAAAQAAAsAAAAA0c3RzYwAAAAAAAAADAAAAAQAAAAEA
AAABAAAAAgAAAAIAAAABAAAABAAAAAEAAAABAAAALHN0c3oAAAAAAAAAAAAAAAYAAAIXAAABQgAAAREAAAFkAAABQAAAAUsAAAAg
c3RjbwAAAAAAAAAEAAAGCQAACWEAAAu7AAAOZgAAABpzZ3BkAQAAAHJvbGwAAAACAAAAAf//AAAAHHNiZ3AAAAAAcm9sbAAAAAEA
AAAGAAAAAQAAAGF1ZHRhAAAAWW1ldGEAAAAAAAAAIWhkbHIAAAAAAAAAAG1kaXJhcHBsAAAAAAAAAAAAAAAALGlsc3QAAAAkqXRv
bwAAABxkYXRhAAAAAQAAAABMYXZmNjIuMy4xMDAAAAAIZnJlZQAACbBtZGF03gIATGF2YzYyLjExLjEwMAACJKhbqUj7Cm84ym98
StZxupW5J2iIiRJ//5H5L87+e/O/nvyP9L+L/m/i/5v8vdXavdXavdXaujtG7G2bsbZv+b+z/a/8buB/+/avzv3r77+2/q/nvxP6
6xQf5rtBgQMehu4ZEI8CORGaxhEQAJECRQAkZ12yiQJtYYgnqeDXflpNizPFqGLQ8e6UUEr/TUwrFB+2okH3L2XtbknZ2tdVZp0N
3T11zTyVt3Z2ydHa7lWu47G3Kw3LvvXug9e4Xht526s1qs1qs1qs0z6/Pr8+vz6qfX59fn1+fX5SuUrlq5auJKJKJKJKJKJKJKJK
JKJKJKJKJKJKJKJKJKJKJKJKllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllll
lllllllllllllllllllllllllllllllllllllllllllllllllllllllllAwMDAwMDAwMDAwMDAwMDAyHRuSk+i8lIdE5KT6Nywhz
zkRPoXJSHQOSE+hcnIdA5MT6Bych0DlBPn3JyHQOUk+eclIdA5QT5/yohz3lRPnvKyHPOVE+ecsIc65WT53ywhznlhPnHKiHN+SE
+hcwIc65cT51y4hznl5PnHLyHOOYE+b8vIc35gT5ty4hzjl5PnnNyHNOZE+bc0Ic05mT5rzTgAAAAbMAEAcAAAG2EYE2C4awGg8A
OHwkgdKqnTwfeUA3WVZeXqy/7CZIXD4eFqtKn/xZhIq/9Rn27ij39cqDDsHgYGcG0FBicSwcXjyCT8DwfUHAdTDuBBhemzRCojNB
8EGCSOWM+Dc1IBsQIH5YCJS146Bm2lfkwMAYEONg3B8PAeB/vS5OEG+xlgQk4QfAphGTp2qsIAKdatt8Ls1R5O20OcPnxJLhJ0GE
PQYSQYtEEEKlgIAPA/08LmIOmwOtAypoEJsIMH3SwGHAOBB74FPIDgQBBBlsHd8dwgA8BBNgyoeJBG1PS5kej4fD38HoMOEiqj8e
iM3B94SGgcXD/cEcQ5KX5YP04/4s0IQ9nWXB4CgBqnhcAYPUw+YLgRUoMwP4NlKatUuEdUAfrIGx9ftCUO4oxm+SNeStFoMH6RmK
HAEOntnhci7qQddRlDRG6nbx8/0/z9dcdXq0nt/45+334a61d3Wv+f/6b+n+jXFzVur/0//pP+P+ZxxqSa1wsuikDgMujknEQhF+
c1FFBqKKBQaiiiJY5C5HYXjj/9czL84WcHEHQH2hG7EcAlMtDS5TZLUad4UtPjc5DzjrOaM0Z8cmPFplnnLPMs8u9ns2UaL7ERZs
I+W5btk6F0bX2NkUWwpfkLxbF7SLwkI4XWEi8ZI6LeksPzEjueMEtZ0A1AayQom/lic7Ubu1q7bLQa7feXHX1RT7BHahHKlqzxvG
LXrxa9417mV6eeeeedSp51TzqnnnnVOrYeeeeeQkKpSh2XbHRtT66rla0Uq9jSZSq4CJSFnmI7/hhLc8sFRN3LVci1BTrFqbU30Y
G1MSJAwMDVySlDq9g7U2ns69nv71eQ8A4vQsZCodjgaJYiFUJnVv/tnFPX9P+dNVmr8zF+d8TEkp73Xx86FK/Xf+JEAP7Par9w77
dnYJIIiQQc1SFAekf9JEAPcsnx8GBMsWrVSSgakrNisVio4fI/uad0j3D+ByaAiEazHlARFAA417V7V7J0biv8da9SytttsaqaOG
jU1NTNJMrXX/K0UsFBTThQUFOCgoK/zr/lfGmgX/i/FWQoKCljQUFcUFBSxoKCuDY9d+LMBO7u4gbM2MyYu9eEjuB0444swE7voV
mIKCmoQUFVSCgpdsIKqIK+9NWgoKRsCgprtQUFVF86FLtBVcpM5zB4F4D7q5p0XAANALhYMkQiJ4ABQNMPWAEgQCAA2gocAAAAG2
U8CNANo0KRZKHBZfYpFYWcerub7//z///Opu9yXWE3xXetc7+F4670AQgQge7BhAhAhCjUPOARkUEUAQgZQ4wowqZKmedVr9eocN
hcyocNmO7fb+OpW+tW9Nd+yYozpbO5WMAceWs856w2wqhBzNw3dWicO6Tv2+7F4Hanl6eXpiyYpoHokiyYskqZFIKkUkUkUkUl89
kSKSKQRI3bKSKSKSKRvihEEQRXWOHvuGNXV1e7wq6urq60NK6urq68N+Fo1dBfXAXXE/2uvV1dXTjQAvX2AFwAX3SgLej4gN3wHC
AK5oBr+aAHJsBf3fysAX7P9a2gNX6hgAnzcAK/iPLSBu/VvxnYBf0H/4MAAAAB/YRwAAAAGXHzgAAAAAyjOg14AAAAOSXQPAAAAA
GQs0AAA7/mAL8L5aQK9E/OcQF+EoBn+n+BAOb9NAABkI5VXAAAAAins0AAAADvwL/AAAABlrqPCgAOABBDQsdFsjMSCV+1VJf/x/
3mVL3OpvrfXMiEggH7zMv/PrvtD/w2DlcNnh4MQALuTxvAwY6yAD95tVuWkaAs1bpoQnbhMFDtuWwjCZN/SYiEr+VsuOaoy3+fvL
IArEGjw+zgohy8sd0PnE7LnFsPyJxdNBOlvlns6PlOeY3TtnKWSLK+U55jdOCiUIqhCGoxB5yxklkpZTrmN05qJQiqUT2ZjdOeax
CKpQhqGU55rdk1ko+kwHiKLYAPSMB4hG2AD0jB8QjbMD0jB8QjbMD0jB8QjbMD4GD4iNswPgInxEZYwPgInxEZYwHwET4iOkYHwU
T4iOkYHwUT4iOkYHwUWxEdJgfBRPgEdJg/BRbAI6TAfBRbAI6TAfBRbMD6TB8RRbMD6TB8RRbMD6TB8RRbMD7GD4i1swPsYPiLWz
A+Bg/wAAAbZVgI0BDjQsdLaBKZX6Ky6+fxlzL3OpvqTnqc9TlBCC0QERi4oycPAh7n/49c9pf+V5/wfSbg/pcS79/igMlTtqSmbF
sVLpsUlakNlTtqSybF2Ws32dQ1d2lR4Lv3UeB/w/3e+mG3xWa+i1XJZUbkVIzZyxjQGyoVq4ZKg2JI2KVyHSxYDTMWDmyO4MhOLM
DieKVyHiz4DTMWDsyO5shOFMxYPTilch4tXgNMxYDTjFch4pW4MxOJsDueKVyLTMWA04xhIeKVyLjMTizA4myO4YzFgNMxYO2KVy
HjNXgNMxYSNilfNmz5efRkd5s2d/n0ZHebNnf59GR3mzZ3+fRkdmzZ34NGR2bNnfg0ZHZm878EOR2ZvO/BDkdmb934Ifh2Zv3fgh
zOzN6H4IczszejJBDmzzN6MkEObOdvRkTDmz5W9GTdDmzvt6MjsObO/w
"""
}

private fun decodeBase64(value: String): ByteArray {
    require(value.length % 4 == 0) { "Invalid base64 length" }
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    val output = ByteArray(value.length / 4 * 3 - value.takeLast(2).count { it == '=' })
    var source = 0
    var target = 0
    while (source < value.length) {
        var bits = 0
        var valid = 0
        repeat(4) {
            val ch = value[source++]
            bits = bits shl 6
            if (ch != '=') {
                val digit = alphabet.indexOf(ch)
                require(digit >= 0) { "Invalid base64 character" }
                bits = bits or digit
                valid++
            }
        }
        repeat(valid - 1) { index ->
            output[target++] = (bits ushr (16 - index * 8)).toByte()
        }
    }
    check(target == output.size)
    return output
}

internal fun sha256Hex(bytes: ByteArray): String {
    val constants = intArrayOf(
        0x428a2f98, 0x71374491, 0xb5c0fbcf.toInt(), 0xe9b5dba5.toInt(), 0x3956c25b, 0x59f111f1, 0x923f82a4.toInt(), 0xab1c5ed5.toInt(),
        0xd807aa98.toInt(), 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe.toInt(), 0x9bdc06a7.toInt(), 0xc19bf174.toInt(),
        0xe49b69c1.toInt(), 0xefbe4786.toInt(), 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152.toInt(), 0xa831c66d.toInt(), 0xb00327c8.toInt(), 0xbf597fc7.toInt(), 0xc6e00bf3.toInt(), 0xd5a79147.toInt(), 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e.toInt(), 0x92722c85.toInt(),
        0xa2bfe8a1.toInt(), 0xa81a664b.toInt(), 0xc24b8b70.toInt(), 0xc76c51a3.toInt(), 0xd192e819.toInt(), 0xd6990624.toInt(), 0xf40e3585.toInt(), 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814.toInt(), 0x8cc70208.toInt(), 0x90befffa.toInt(), 0xa4506ceb.toInt(), 0xbef9a3f7.toInt(), 0xc67178f2.toInt(),
    )
    val bitLength = bytes.size.toLong() * 8L
    val paddedSize = ((bytes.size + 9 + 63) / 64) * 64
    val padded = ByteArray(paddedSize)
    bytes.copyInto(padded)
    padded[bytes.size] = 0x80.toByte()
    for (index in 0 until 8) padded[padded.lastIndex - index] = (bitLength ushr (index * 8)).toByte()

    val hash = intArrayOf(
        0x6a09e667, 0xbb67ae85.toInt(), 0x3c6ef372, 0xa54ff53a.toInt(),
        0x510e527f, 0x9b05688c.toInt(), 0x1f83d9ab, 0x5be0cd19,
    )
    val words = IntArray(64)
    for (offset in padded.indices step 64) {
        for (index in 0 until 16) {
            val base = offset + index * 4
            words[index] = ((padded[base].toInt() and 0xff) shl 24) or
                ((padded[base + 1].toInt() and 0xff) shl 16) or
                ((padded[base + 2].toInt() and 0xff) shl 8) or
                (padded[base + 3].toInt() and 0xff)
        }
        for (index in 16 until 64) {
            val a = words[index - 15]
            val b = words[index - 2]
            val s0 = a.rotateRight(7) xor a.rotateRight(18) xor (a ushr 3)
            val s1 = b.rotateRight(17) xor b.rotateRight(19) xor (b ushr 10)
            words[index] = words[index - 16] + s0 + words[index - 7] + s1
        }
        var a = hash[0]; var b = hash[1]; var c = hash[2]; var d = hash[3]
        var e = hash[4]; var f = hash[5]; var g = hash[6]; var h = hash[7]
        for (index in 0 until 64) {
            val sum1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
            val choice = (e and f) xor (e.inv() and g)
            val temp1 = h + sum1 + choice + constants[index] + words[index]
            val sum0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
            val majority = (a and b) xor (a and c) xor (b and c)
            val temp2 = sum0 + majority
            h = g; g = f; f = e; e = d + temp1
            d = c; c = b; b = a; a = temp1 + temp2
        }
        hash[0] += a; hash[1] += b; hash[2] += c; hash[3] += d
        hash[4] += e; hash[5] += f; hash[6] += g; hash[7] += h
    }
    return hash.joinToString("") { value -> value.toUInt().toString(16).padStart(8, '0') }
}

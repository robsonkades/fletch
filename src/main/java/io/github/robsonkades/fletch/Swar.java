/*
 * Copyright 2026 Robson Kades
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.robsonkades.fletch;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/**
 * SWAR (SIMD-within-a-register) byte-scanning primitives for the plan engine.
 *
 * <p>All word tricks used by the engine are confined to this class. The scans
 * process eight bytes per iteration using an unaligned little-endian
 * {@code long} view over the buffer — portable JDK 9+ behavior, no incubator
 * modules, measured at multi-GB/s on commodity hardware.
 *
 * <p>The zero-byte detection identity used throughout:
 * {@code (w - 0x0101…) & ~w & 0x8080…} has the high bit set in every byte of
 * {@code w} that is zero, so XOR-ing the word with a repeated search pattern
 * first turns "find byte" into "find zero".
 */
final class Swar {

    private static final VarHandle LONGS = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    private static final long ONES = 0x0101010101010101L;
    private static final long HIGHS = 0x8080808080808080L;

    private Swar() {}

    /**
     * Returns the index of the first occurrence of {@code target} in
     * {@code b[from, to)}, or {@code -1} when absent.
     */
    static int memchr(final byte[] b, int from, final int to, final int target) {
        final long pat = ONES * (target & 0xFF);
        for (; from + 8 <= to; from += 8) {
            final long w = (long) LONGS.get(b, from) ^ pat;
            final long m = (w - ONES) & ~w & HIGHS;
            if (m != 0) return from + (Long.numberOfTrailingZeros(m) >>> 3);
        }
        for (; from < to; from++) {
            if (b[from] == target) return from;
        }
        return -1;
    }

    /**
     * Returns the index of the first occurrence of {@code t1} or {@code t2}
     * in {@code b[from, to)}, or {@code -1} when neither is present.
     */
    static int memchr2(final byte[] b, int from, final int to, final int t1, final int t2) {
        final long p1 = ONES * (t1 & 0xFF);
        final long p2 = ONES * (t2 & 0xFF);
        for (; from + 8 <= to; from += 8) {
            final long w = (long) LONGS.get(b, from);
            final long x1 = w ^ p1;
            final long x2 = w ^ p2;
            final long m = (((x1 - ONES) & ~x1) | ((x2 - ONES) & ~x2)) & HIGHS;
            if (m != 0) return from + (Long.numberOfTrailingZeros(m) >>> 3);
        }
        for (; from < to; from++) {
            if (b[from] == t1 || b[from] == t2) return from;
        }
        return -1;
    }

    /**
     * 64-bit hash of a tag or attribute name. Mixes the length with up to the
     * first sixteen bytes, which fully fingerprints every realistic XML name.
     * Name and attribute selection reverify the actual bytes, so a collision
     * there only costs a wasted compare; end-tag well-formedness checks trust
     * the hash alone, so two names agreeing on length and first sixteen bytes
     * (or colliding outright) are not reported as mismatched.
     */
    static long hash(final byte[] b, final int off, final int len) {
        long w = 0;
        final int k = Math.min(len, 8);
        for (int j = 0; j < k; j++) {
            w |= (b[off + j] & 0xFFL) << (j << 3);
        }
        long h = (len * 0x9E3779B97F4A7C15L) ^ w;
        if (len > 8) {
            long w2 = 0;
            final int k2 = Math.min(len - 8, 8);
            for (int j = 0; j < k2; j++) {
                w2 |= (b[off + 8 + j] & 0xFFL) << (j << 3);
            }
            h ^= w2 * 0xC2B2AE3D27D4EB4FL;
        }
        return h;
    }
}

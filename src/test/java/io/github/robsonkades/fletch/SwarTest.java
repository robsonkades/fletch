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

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Locks the word-at-a-time tricks in {@link Swar} against their scalar
 * fallbacks. The failure mode being guarded: the fast paths read whole words
 * that extend past the requested range (masked afterwards), so a masking bug
 * shows up only as a disagreement with the byte-loop path taken at the very
 * end of an array — positions the engine reaches on every window refill.
 */
class SwarTest {

    /**
     * The same name bytes must hash identically whether they sit mid-array
     * (word loads, trailing garbage masked off) or flush against the array
     * end (byte-loop fallback) — end-tag verification compares hashes taken
     * at both kinds of position.
     */
    @Test
    void hashAgreesBetweenWordAndByteTailPaths() {
        for (int len = 1; len <= 20; len++) {
            final byte[] name = new byte[len];
            for (int i = 0; i < len; i++) {
                name[i] = (byte) ('a' + (i * 7 + len) % 26);
            }

            final byte[] mid = new byte[len + 32];
            Arrays.fill(mid, (byte) '>');
            System.arraycopy(name, 0, mid, 4, len);

            final byte[] tight = new byte[len + 2];
            Arrays.fill(tight, (byte) '<');
            System.arraycopy(name, 0, tight, 2, len);

            final long fast = Swar.hash(mid, 4, len);
            final long slow = Swar.hash(tight, 2, len);
            assertEquals(slow, fast, "len " + len);
        }
    }

    /** Bytes after the name must never leak into the hash. */
    @Test
    void hashIgnoresTrailingGarbage() {
        final byte[] name = "infNFe".getBytes(StandardCharsets.UTF_8);
        final byte[] one = new byte[name.length + 24];
        final byte[] two = new byte[name.length + 24];
        Arrays.fill(one, (byte) 0x00);
        Arrays.fill(two, (byte) 0xFF);
        System.arraycopy(name, 0, one, 0, name.length);
        System.arraycopy(name, 0, two, 0, name.length);

        assertEquals(Swar.hash(one, 0, name.length), Swar.hash(two, 0, name.length));
    }

    /**
     * Sweeps every {@code [from, to)} window of a buffer whose bytes include
     * the targets, garbage past {@code to}, and array-edge positions, and
     * compares the whole memchr family against a naive scalar reference.
     * A masking bug in the sub-word tail shows up as a hit reported from
     * the garbage region; an edge bug as a disagreement near the array end.
     */
    @Test
    void memchrFamilyAgreesWithNaiveReferenceOnEveryWindow() {
        final byte[] b = new byte[41];
        for (int i = 0; i < b.length; i++) {
            b[i] = switch (i % 7) {
                case 0 -> (byte) '<';
                case 2 -> (byte) '&';
                case 4 -> (byte) '\r';
                case 5 -> (byte) 0xC3;   // multibyte UTF-8 lead: exercises sign handling
                default -> (byte) ('a' + i % 3);
            };
        }
        for (int from = 0; from <= b.length; from++) {
            for (int to = from; to <= b.length; to++) {
                assertEquals(naive(b, from, to, '<', -1, -1),
                        Swar.memchr(b, from, to, '<'), "memchr [" + from + "," + to + ")");
                assertEquals(naive(b, from, to, '<', '&', -1),
                        Swar.memchr2(b, from, to, '<', '&'), "memchr2 [" + from + "," + to + ")");
                assertEquals(naive(b, from, to, '<', '&', '\r'),
                        Swar.memchr3(b, from, to, '<', '&', '\r'), "memchr3 [" + from + "," + to + ")");
            }
        }
    }

    private static int naive(final byte[] b, final int from, final int to,
                             final int t1, final int t2, final int t3) {
        for (int i = from; i < to; i++) {
            if (b[i] == t1 || (t2 >= 0 && b[i] == t2) || (t3 >= 0 && b[i] == t3)) return i;
        }
        return -1;
    }

    /** Same-prefix names of different lengths must not collide via masking. */
    @Test
    void hashSeparatesPrefixesByLength() {
        final byte[] b = "prodprodprodprod".getBytes(StandardCharsets.UTF_8);
        final long[] seen = new long[16];
        for (int len = 1; len <= 16; len++) {
            seen[len - 1] = Swar.hash(b, 0, len);
            for (int prior = 0; prior < len - 1; prior++) {
                if (seen[prior] == seen[len - 1]) {
                    throw new AssertionError("hash collision between len " + (prior + 1) + " and " + len);
                }
            }
        }
    }
}

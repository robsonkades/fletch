/*
 * Copyright 2026 Robson Kades
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.robsonkades.fletch;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ByteScannerNameTest {
    private final XmlCursorEngine scanner = new XmlCursorEngine();

    @Test
    void everyByteHasTheSameBoundaryMeaningAtEveryPosition() {
        final byte[] bytes = new byte[48];
        for (int value = 0; value < 256; value++) {
            for (int position = 0; position < 24; position++) {
                Arrays.fill(bytes, (byte) 'a');
                bytes[position + 3] = (byte) value;
                for (int end = 3; end <= 35; end++) {
                    assertEquals(scalar(bytes, 3, end), scan(bytes, 3, end),
                            "value=" + value + ", position=" + position + ", end=" + end);
                }
            }
        }
    }

    @Test
    void adjacentBytesDoNotChangeTheFirstBoundary() {
        final byte[] bytes = new byte[24];
        for (int pair = 0; pair < 65536; pair++) {
            Arrays.fill(bytes, (byte) 0xFF);
            bytes[7] = (byte) pair;
            bytes[8] = (byte) (pair >>> 8);
            assertEquals(scalar(bytes, 1, 20), scan(bytes, 1, 20), "offset 1, pair=" + pair);
            assertEquals(scalar(bytes, 0, 20), scan(bytes, 0, 20), "offset 0, pair=" + pair);
        }
    }

    @Test
    void randomSpansMatchTheScalarReferenceIncludingArrayEdges() {
        final Random random = new Random(20260914);
        for (int trial = 0; trial < 20000; trial++) {
            final byte[] bytes = new byte[random.nextInt(129)];
            random.nextBytes(bytes);
            final int from = random.nextInt(bytes.length + 1);
            final int to = from + random.nextInt(bytes.length - from + 1);
            assertEquals(scalar(bytes, from, to), scan(bytes, from, to),
                    "trial=" + trial + ", range=[" + from + "," + to + "), length=" + bytes.length);
        }
    }

    private int scan(final byte[] bytes, final int from, final int to) {
        scanner.b = bytes;
        scanner.n = to;
        return scanner.nameEnd(from);
    }

    private static int scalar(final byte[] bytes, int from, final int to) {
        while (from < to) {
            final int c = bytes[from] & 0xFF;
            if (c == '>' || c == '/' || c == '=' || c <= ' ') break;
            from++;
        }
        return from;
    }
}

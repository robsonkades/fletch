/*
 * Copyright 2026 Robson Kades
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.robsonkades.fletch;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Utf8Test {
    @Test
    void asciiWordScanAgreesWithScalarAcrossBytesOffsetsAndTails() {
        final byte[] a = new byte[40];
        for (int c = 0; c <= 255; c++) {
            for (int pos = 0; pos < a.length; pos++) {
                Arrays.fill(a, (byte) 'a');
                a[pos] = (byte) c;
                for (int start = 0; start <= pos; start++) {
                    for (int end = pos; end <= a.length; end++) {
                        int expected = start;
                        while (expected < end && (a[expected] & 0xFF) >= 0x20
                                && (a[expected] & 0xFF) < 0x80 && a[expected] != ']') expected++;
                        assertEquals(expected, Swar.asciiEnd(a, start, end));
                    }
                }
            }
        }
    }

    @Test
    void randomByteSpansAgreeWithTheStrictJdkDecoderAndXmlCharacterRules() {
        final Random random = new Random(736291);
        for (int trial = 0; trial < 10000; trial++) {
            final byte[] a = new byte[2 + random.nextInt(32)];
            random.nextBytes(a);
            final boolean expected = reference(a, 1, a.length - 1);
            assertEquals(expected, Utf8.invalidXml(a, 1, a.length - 1, true) < 0, "trial " + trial);
        }
    }

    @Test
    void unicodeBoundariesMatchXml10AcrossBothValidationModes() {
        for (int cp : new int[] {0, 8, 9, 10, 13, 31, 32, 127, 128, 0x7FF, 0x800,
                0xD7FF, 0xD800, 0xDFFF, 0xE000, 0xFFFD, 0xFFFE, 0xFFFF, 0x10000, 0x10FFFF}) {
            final byte[] a = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8);
            // Unpaired UTF-16 surrogates are replaced by String.getBytes; test their UTF-8 encoding explicitly.
            final byte[] encoded = cp >= 0xD800 && cp <= 0xDFFF
                    ? new byte[] {(byte) (0xE0 | cp >> 12), (byte) (0x80 | (cp >> 6 & 63)), (byte) (0x80 | (cp & 63))} : a;
            final boolean expected = xmlChar(cp);
            assertEquals(expected, Utf8.invalidXml(encoded, 0, encoded.length, false) < 0, "code point " + cp);
            assertEquals(expected, Utf8.invalidXml(encoded, 0, encoded.length, true) < 0, "code point " + cp);
        }
    }

    private static boolean reference(final byte[] a, final int start, final int end) {
        try {
            final String decoded = StandardCharsets.UTF_8.newDecoder()
                    .decode(ByteBuffer.wrap(a, start, end - start)).toString();
            return !decoded.contains("]]>") && decoded.codePoints().allMatch(Utf8Test::xmlChar);
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    private static boolean xmlChar(final int cp) {
        return cp == 9 || cp == 10 || cp == 13 || (cp >= 0x20 && cp <= 0xD7FF)
                || (cp >= 0xE000 && cp <= 0xFFFD) || (cp >= 0x10000 && cp <= 0x10FFFF);
    }
}

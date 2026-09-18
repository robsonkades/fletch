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

/** Strict UTF-8 and XML 1.0 character validation over an existing byte span. */
final class Utf8 {
    private Utf8() {}

    /** Returns the first invalid byte, or -1. Character data also forbids a literal {@code ]]>}. */
    static int invalidXml(final byte[] a, final int start, final int end, final boolean characterData) {
        int i = start;
        while (i < end) {
            i = Swar.asciiEnd(a, i, end);
            if (i == end) return -1;
            final int c = a[i] & 0xFF;
            if (c < 0x80) {
                if (c < 0x20 && c != '\t' && c != '\n' && c != '\r') return i;
                if (characterData && c == ']' && i + 2 < end && a[i + 1] == ']' && a[i + 2] == '>') return i;
                i++;
                continue;
            }
            final int count = c >= 0xC2 && c <= 0xDF ? 2
                    : c >= 0xE0 && c <= 0xEF ? 3
                    : c >= 0xF0 && c <= 0xF4 ? 4 : 0;
            if (count == 0 || end - i < count) return i;
            int cp = c & (0x7F >> count);
            for (int j = 1; j < count; j++) {
                final int next = a[i + j] & 0xFF;
                if ((next & 0xC0) != 0x80) return i + j;
                cp = (cp << 6) | (next & 0x3F);
            }
            if ((count == 3 && cp < 0x800) || (count == 4 && cp < 0x10000)
                    || (cp >= 0xD800 && cp <= 0xDFFF) || cp == 0xFFFE || cp == 0xFFFF || cp > 0x10FFFF) {
                return i;
            }
            i += count;
        }
        return -1;
    }
}

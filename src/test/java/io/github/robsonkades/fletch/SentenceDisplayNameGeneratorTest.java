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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the suite-wide display-name wiring: {@code junit-platform.properties}
 * must be on the classpath and register {@link SentenceDisplayNameGenerator},
 * or every report silently falls back to raw method names.
 */
class SentenceDisplayNameGeneratorTest {

    @Test
    void turnsCamelCaseMethodNamesIntoLowercaseSentences(TestInfo testInfo) {
        assertEquals("turns camel case method names into lowercase sentences",
                testInfo.getDisplayName());
    }

    @Test
    void keepsDigitsAttachedToTheirWord(TestInfo testInfo) {
        assertEquals("keeps digits attached to their word", testInfo.getDisplayName());
        assertEquals("latin1 auto detected from iso8859 declarations",
                new SentenceDisplayNameGenerator().generateDisplayNameForMethod(java.util.List.of(),
                        getClass(), Sample.class.getDeclaredMethods()[0]));
    }

    @Test
    @DisplayName("explicit @DisplayName annotations still win")
    void explicitAnnotationsTakePrecedence(TestInfo testInfo) {
        assertEquals("explicit @DisplayName annotations still win", testInfo.getDisplayName());
    }

    private interface Sample {
        void latin1AutoDetectedFromIso8859Declarations();
    }
}

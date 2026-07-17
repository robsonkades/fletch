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

import org.junit.jupiter.api.DisplayNameGenerator;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;

/**
 * Renders camelCase test method names as lowercase sentences in reports
 * ({@code absentElementYieldsNull} → {@code absent element yields null}).
 * Registered for the whole suite via {@code junit-platform.properties};
 * explicit {@code @DisplayName} annotations still take precedence.
 */
public class SentenceDisplayNameGenerator extends DisplayNameGenerator.Standard {

    // Standard implements only this 3-arg overload — the 2-arg one is never
    // consulted once it is present, so this is the one to override.
    @Override
    public String generateDisplayNameForMethod(final List<Class<?>> enclosingInstanceTypes,
                                               final Class<?> testClass, final Method testMethod) {
        return testMethod.getName()
                .replaceAll("([a-z0-9])([A-Z])", "$1 $2")
                .toLowerCase(Locale.ROOT);
    }
}

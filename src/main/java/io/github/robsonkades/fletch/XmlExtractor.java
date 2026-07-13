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

/**
 * Declarative, composable extractor for a scoped XML element.
 *
 * <p>An extractor receives an {@link XmlCursor} positioned at the
 * start tag of the element to read. It may call {@code child},
 * {@code children}, {@code value}, {@code firstOf} and {@code attribute} on
 * the cursor in any combination and in any order. The framework guarantees the
 * cursor is drained back to the element boundary after the extractor returns,
 * so callers never manage depth bookkeeping and an extractor may read any
 * subset of an element's content.
 *
 * <p>Extractors are stateless lambdas — define them as {@code static final}
 * constants and reuse them freely across threads. They compose naturally:
 *
 * <pre>{@code
 * record Address(String city, String zip) {}
 * record Customer(String name, Address address) {}
 *
 * static final XmlExtractor<Address> ADDRESS = a -> new Address(
 *         a.value("city", String.class),
 *         a.value("zip", String.class));
 *
 * static final XmlExtractor<Customer> CUSTOMER = c -> new Customer(
 *         c.value("name", String.class),
 *         c.child("address", ADDRESS));
 * }</pre>
 *
 * @param <T> the type this extractor produces
 */
@FunctionalInterface
public interface XmlExtractor<T> {

    /**
     * Extracts a value from the element the cursor is positioned at.
     *
     * @param cursor cursor positioned at the element's start tag
     * @return the extracted value; {@code null} is a legal result
     */
    T extract(XmlCursor cursor);
}

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
 * Receives one extracted value and stores it on the caller's draft object.
 *
 * <p>Bindings are registered per path on an {@link XmlMapping.Builder} and are
 * invoked by the engine each time the path produces a non-empty value, in
 * document order. A binding must consume the {@link XmlValue} immediately —
 * the value is a reusable view that becomes invalid when the binding returns.
 *
 * <p>Bindings are stateless lambdas; define them inline in the mapping builder
 * and share the compiled mapping freely across threads.
 *
 * @param <D> the draft type the mapping accumulates into
 */
@FunctionalInterface
public interface XmlBinding<D> {

    /**
     * Stores {@code value} on {@code draft}.
     *
     * @param draft the draft object being filled for the current document
     *              (or the current group instance for group-scoped bindings)
     * @param value the extracted value; valid only during this call
     */
    void bind(D draft, XmlValue value);
}

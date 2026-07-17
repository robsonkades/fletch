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
/**
 * Fletch — fast, declarative XML extraction over a single streaming pass.
 *
 * <p>The {@link io.github.robsonkades.fletch.Xml} facade offers two extraction
 * styles over the same engine — a pull-style cursor and a push-style mapping:
 * <ul>
 *   <li>{@link io.github.robsonkades.fletch.Xml} — static entry points that
 *       parse a {@code String}, {@code byte[]} or {@code InputStream};</li>
 *   <li>{@link io.github.robsonkades.fletch.XmlExtractor} — a lambda that maps
 *       one XML element to a typed value;</li>
 *   <li>{@link io.github.robsonkades.fletch.XmlCursor} — the order-tolerant
 *       navigation surface handed to extractors;</li>
 *   <li>{@link io.github.robsonkades.fletch.XmlMapping} — a compiled, declarative
 *       mapping that binds wanted paths into a draft (build via
 *       {@link io.github.robsonkades.fletch.Xml#mapping});</li>
 *   <li>{@link io.github.robsonkades.fletch.XmlBinding} and
 *       {@link io.github.robsonkades.fletch.XmlValue} — the per-path binding
 *       and the lazily-decoded value it receives;</li>
 *   <li>{@link io.github.robsonkades.fletch.XmlException} — the single
 *       unchecked exception type for every failure mode.</li>
 * </ul>
 *
 * <p>Typical usage:
 *
 * <pre>{@code
 * record Item(String sku, Integer qty) {}
 * record Order(String id, List<Item> items) {}
 *
 * static final XmlExtractor<Item> ITEM = i -> new Item(
 *         i.value("sku", String.class),
 *         i.value("qty", Integer.class));
 *
 * static final XmlExtractor<Order> ORDER = o -> new Order(
 *         o.attribute("id", String.class),
 *         o.children("item", ITEM));
 *
 * Order order = Xml.extract(inputStream, doc -> doc.child("order", ORDER));
 * }</pre>
 *
 * <p>Design constraints worth knowing before use: extraction is a
 * <em>single streaming pass</em> (reads tolerate children in any order,
 * buffering only what must be revisited), parsing is not namespace-aware
 * (elements match by raw tag name), and DTDs / external entities are disabled
 * (no XXE surface). See {@link io.github.robsonkades.fletch.XmlCursor} for the
 * detailed contract.
 */
package io.github.robsonkades.fletch;

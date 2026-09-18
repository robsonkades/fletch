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

/**
 * Immutable resource limits for one extraction, safe to share across threads.
 * Pass them to {@link Xml#extract(byte[], XmlLimits, XmlMapping)} or the
 * corresponding cursor, string or stream overload.
 *
 * <pre>{@code
 * XmlLimits limits = XmlLimits.builder()
 *         .maxInputBytes(8 * 1024 * 1024)
 *         .maxDepth(64)
 *         .maxElements(100_000)
 *         .maxNameBytes(256)
 *         .maxAttributesPerElement(32)
 *         .maxTextBytes(1024 * 1024)
 *         .build();
 * Book book = Xml.extract(bytes, limits, BOOK_MAPPING);
 * }</pre>
 *
 * <p>Limits apply to the input and structure actually consumed by extraction;
 * they do not turn selective extraction into whole-document validation. Depth,
 * element counts, start-tag names and attributes include skipped subtrees.
 * Selected text includes nested text and CDATA; ignored text is not measured.
 * A cursor replay does not count an element twice. Limit violations throw
 * {@link XmlException} before the offending value or element reaches a callback.
 * Earlier callbacks may already have run; extraction is not transactional.
 *
 * <p>The defaults preserve the existing resource ceilings: 16 MiB per text or
 * attribute value and 1,024 attributes per element, with no additional input,
 * depth, element-count or name-length limit. Independent implementation ceilings
 * still apply: buffered documents must fit a Java array, and streaming token
 * windows and copied open-name scratch are capped at 16 MiB. These limits do not
 * bound allocations made by caller callbacks or guarantee a maximum heap size.
 */
public final class XmlLimits {
    private static final XmlLimits DEFAULTS = new Builder().build();

    final long maxInputBytes;
    final int maxDepth;
    final long maxElements;
    final int maxNameBytes;
    final int maxAttributesPerElement;
    final int maxTextBytes;

    private XmlLimits(final Builder builder) {
        maxInputBytes = builder.maxInputBytes;
        maxDepth = builder.maxDepth;
        maxElements = builder.maxElements;
        maxNameBytes = builder.maxNameBytes;
        maxAttributesPerElement = builder.maxAttributesPerElement;
        maxTextBytes = builder.maxTextBytes;
    }

    /**
     * Returns the existing extraction ceilings without additional limits.
     * @return the shared, immutable default limits
     */
    public static XmlLimits defaults() { return DEFAULTS; }

    /**
     * Starts a resource limit configuration.
     * @return a fresh builder initialized with the default limits
     */
    public static Builder builder() { return new Builder(); }

    /**
     * Returns the maximum original input size, in bytes, including a BOM or
     * encoding declaration. Strings are measured as their UTF-8 encoding before
     * allocating that encoding. Legacy byte encodings are measured before
     * transcoding; their UTF-8 representation can be larger.
     *
     * <p>Arrays and strings are checked in full, even with early exit. Streams
     * are limited as they are read, including read-ahead. If parsing needs more
     * input at the limit, one extra byte may be consumed to distinguish EOF from
     * oversized input. Early exit need not read or check the remainder. Cursor
     * streams and legacy-encoded mapping streams are always read in full.
     *
     * @return the input byte limit; {@link Long#MAX_VALUE} adds no limit
     */
    public long maxInputBytes() { return maxInputBytes; }

    /**
     * Returns the depth limit, counting the document element as depth 1.
     * @return maximum element depth
     */
    public int maxDepth() { return maxDepth; }

    /**
     * Returns the element count limit, including empty elements and skipped subtrees.
     * @return maximum distinct start tags scanned
     */
    public long maxElements() { return maxElements; }

    /**
     * Returns the maximum UTF-8 byte length of an element or attribute name,
     * including any prefix. Start tags are checked throughout scanned content;
     * end tags in skipped subtrees are also measured when this limit is set.
     * Processing-instruction targets and declaration pseudo-attributes are excluded.
     *
     * @return the name byte limit
     */
    public int maxNameBytes() { return maxNameBytes; }

    /**
     * Returns the attribute count limit, including namespace declarations.
     * @return maximum attributes per scanned start tag
     */
    public int maxAttributesPerElement() { return maxAttributesPerElement; }

    /**
     * Returns the maximum UTF-8 content bytes per selected text value or per
     * attribute in any scanned start tag. Bytes are counted before entity
     * decoding, whitespace normalization and trimming. Text and CDATA runs
     * within a selected value count together; markup itself does not count.
     *
     * @return the content byte limit
     */
    public int maxTextBytes() { return maxTextBytes; }

    /** Mutable configuration builder; use one thread while constructing limits. */
    public static final class Builder {
        private long maxInputBytes = Long.MAX_VALUE;
        private int maxDepth = Integer.MAX_VALUE;
        private long maxElements = Long.MAX_VALUE;
        private int maxNameBytes = Integer.MAX_VALUE;
        private int maxAttributesPerElement = 1024;
        private int maxTextBytes = 16 * 1024 * 1024;

        private Builder() {}

        /**
         * Sets the original input byte limit. See {@link XmlLimits#maxInputBytes()}.
         * @param value positive maximum; {@link Long#MAX_VALUE} adds no limit
         * @return this builder
         * @throws IllegalArgumentException if value is not positive
         */
        public Builder maxInputBytes(final long value) {
            if (value <= 0) throw new IllegalArgumentException("maxInputBytes must be positive");
            maxInputBytes = value;
            return this;
        }

        /**
         * Sets the element depth limit, including skipped and self-closing elements.
         * @param value positive maximum; the document element has depth 1
         * @return this builder
         * @throws IllegalArgumentException if value is not positive
         */
        public Builder maxDepth(final int value) {
            if (value <= 0) throw new IllegalArgumentException("maxDepth must be positive");
            maxDepth = value;
            return this;
        }

        /**
         * Sets the distinct scanned element limit. Cursor replays are counted once.
         * @param value positive maximum; {@link Long#MAX_VALUE} adds no limit
         * @return this builder
         * @throws IllegalArgumentException if value is not positive
         */
        public Builder maxElements(final long value) {
            if (value <= 0) throw new IllegalArgumentException("maxElements must be positive");
            maxElements = value;
            return this;
        }

        /**
         * Sets the element and attribute name limit, measured in UTF-8 bytes.
         * @param value positive maximum
         * @return this builder
         * @throws IllegalArgumentException if value is not positive
         */
        public Builder maxNameBytes(final int value) {
            if (value <= 0) throw new IllegalArgumentException("maxNameBytes must be positive");
            maxNameBytes = value;
            return this;
        }

        /**
         * Sets the attribute count limit. Zero permits only attribute-free elements.
         * @param value maximum from 0 through the implementation ceiling of 1,024
         * @return this builder
         * @throws IllegalArgumentException if value is outside that range
         */
        public Builder maxAttributesPerElement(final int value) {
            if (value < 0 || value > 1024) {
                throw new IllegalArgumentException("maxAttributesPerElement must be between 0 and 1024");
            }
            maxAttributesPerElement = value;
            return this;
        }

        /**
         * Sets the selected text and scanned attribute content limit.
         * @param value maximum from 0 through the implementation ceiling of 16 MiB
         * @return this builder
         * @throws IllegalArgumentException if value is outside that range
         */
        public Builder maxTextBytes(final int value) {
            if (value < 0 || value > 16 * 1024 * 1024) {
                throw new IllegalArgumentException("maxTextBytes must be between 0 and 16777216");
            }
            maxTextBytes = value;
            return this;
        }

        /**
         * Captures this builder's configuration.
         * @return a new immutable snapshot, unaffected by later changes to this builder
         */
        public XmlLimits build() { return new XmlLimits(this); }
    }
}

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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Runtime support for the experimental build-time mapping generator.
 *
 * <p>Generated classes specialize element and attribute lookup only. Parsing,
 * validation, callbacks, conversions, limits and resource ownership retain the
 * {@link XmlMapping} contract. This type is a code-generator protocol, not an
 * application extension point. Use the generator shipped with the core version
 * in use and regenerate sources when upgrading either artifact.
 *
 * <p>A generated program must be immutable and safe to share between workers.
 * Matchers must compare complete names, including bytes beyond the hash prefix,
 * and return only the state/field IDs belonging to the validated mapping layout.
 * Custom implementations are trusted code, just like user-supplied bindings.
 */
public abstract class XmlMappingCode {
    private final String fingerprint;

    /**
     * Initializes the generated program with its expected layout fingerprint.
     * @param fingerprint SHA-256 layout fingerprint produced by the matching generator
     */
    protected XmlMappingCode(final String fingerprint) {
        this.fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
    }

    /**
     * Attaches this program to an independently built mapping with the same layout.
     *
     * <p>The result has a fresh engine pool and retains the definition's callbacks
     * and immutable tables. The supplied mapping is unchanged. This setup operation
     * hashes the layout once; no layout hashing or source compilation occurs per document.
     * Callback captures are deliberately not part of the fingerprint: they retain
     * the semantics and thread-safety requirements of the supplied definition.
     *
     * @param <T> mapping result type
     * @param definition mapping built using the original DSL
     * @return an immutable mapping using this generated lookup program
     * @throws NullPointerException if definition is null
     * @throws IllegalArgumentException if the layout differs; regenerate the sources
     */
    public final <T> XmlMapping<T> bind(final XmlMapping<T> definition) {
        Objects.requireNonNull(definition, "definition");
        if (!fingerprint.equals(fingerprint(definition))) {
            throw new IllegalArgumentException("Generated mapping layout differs; regenerate the code");
        }
        return new XmlMapping<>(definition, this);
    }

    /**
     * Finds an element transition, comparing the complete name on a hash hit.
     * @param state current mapping state
     * @param hash name hash computed by the scanner
     * @param input scanner buffer; must not be mutated or retained
     * @param offset name offset
     * @param length name length in bytes
     * @return destination state, or -1 when the name is not selected
     */
    protected abstract int transition(int state, long hash, byte[] input, int offset, int length);

    /**
     * Finds an attribute binding, comparing the complete name on a hash hit.
     * @param state mapping state of the owning element
     * @param hash name hash computed by the scanner
     * @param input scanner buffer; must not be mutated or retained
     * @param offset name offset
     * @param length name length in bytes
     * @return binding ID, or -1 when the attribute is not selected
     */
    protected abstract int attribute(int state, long hash, byte[] input, int offset, int length);

    static String fingerprint(final XmlMapping<?> mapping) {
        final MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException failure) { throw new AssertionError(failure); }
        digest.update("fletch-lookup-v1".getBytes(StandardCharsets.US_ASCII));
        ints(digest, mapping.transBase);
        ints(digest, mapping.transCount);
        longs(digest, mapping.transHash);
        ints(digest, mapping.transTarget);
        ints(digest, mapping.transNameOff);
        ints(digest, mapping.transNameLen);
        ints(digest, mapping.attrBase);
        ints(digest, mapping.attrCount);
        longs(digest, mapping.attrHash);
        ints(digest, mapping.attrField);
        ints(digest, mapping.attrNameOff);
        ints(digest, mapping.attrNameLen);
        ints(digest, mapping.stateNameOff);
        ints(digest, mapping.stateNameLen);
        ints(digest, mapping.stateText);
        ints(digest, mapping.stateGroup);
        number(digest, mapping.bindings.length);
        number(digest, mapping.blob.length);
        digest.update(mapping.blob);
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void ints(final MessageDigest digest, final int[] values) {
        number(digest, values.length);
        for (final int value : values) number(digest, value);
    }

    private static void longs(final MessageDigest digest, final long[] values) {
        number(digest, values.length);
        for (final long value : values) {
            number(digest, (int) (value >>> 32));
            number(digest, (int) value);
        }
    }

    private static void number(final MessageDigest digest, final int value) {
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }
}

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

import java.io.InputStream;

/**
 * A reusable, thread-confined session for one compiled {@link XmlMapping}.
 * Obtain it from {@link XmlMapping#openSession()} or
 * {@link XmlMapping#openSession(XmlLimits)}.
 *
 * <pre>{@code
 * try (var session = mapping.openSession(limits)) {
 *     for (byte[] document : documents) {
 *         results.add(session.extract(document));
 *     }
 * }
 * }</pre>
 *
 * <p>The thread that opens the session must perform every extraction and close
 * it. Calls from another thread fail with {@link IllegalStateException}, even
 * when idle or externally synchronized. Callbacks run synchronously on the owner
 * thread; recursive extraction and closing during extraction are also rejected.
 * Separate sessions can use the same mapping concurrently if its callbacks and
 * draft suppliers support that use. No session operation waits for another
 * session; stream reads and caller callbacks may block.
 *
 * <p>Every extraction uses a fresh draft and the limits chosen at creation.
 * Success and failure release references to the input and drafts; the session
 * remains usable after parsing, I/O, conversion or callback failures. Callback
 * side effects are not rolled back. Scratch buffers are reused, with outlier
 * buffers trimmed as in pooled extraction. The bounded cache used by
 * {@link XmlValue#asCanonical()} may retain decoded values between documents.
 * This cleanup does not erase the contents of scratch buffers.
 *
 * <p>{@link #close()} releases the session's reference to its engine, including
 * scratch buffers and caches, without closing any caller-owned input stream.
 * Close on the owner thread is idempotent; extraction after close is rejected.
 * No native resource is owned and no engine is returned to the shared pool.
 *
 * <p>Parsing, encodings, selective validation and early exit have the same
 * semantics as {@link Xml#extract(byte[], XmlLimits, XmlMapping)}. Sessions
 * avoid pool access per document; they do not guarantee zero allocation or a
 * throughput improvement for every workload.
 *
 * @param <T> the mapping's result type
 */
public final class XmlMappingSession<T> implements AutoCloseable {
    private final Thread owner = Thread.currentThread();
    private final XmlLimits limits;
    private XmlMappingEngine<T> engine;
    private boolean extracting;

    XmlMappingSession(final XmlMapping<T> mapping, final XmlLimits limits) {
        this.limits = limits;
        this.engine = mapping.newEngine();
    }

    /**
     * Extracts from encoded XML bytes. The full array length is checked against
     * the input budget before parsing; UTF-8 is scanned without copying.
     *
     * @param xml encoded XML document; must not be mutated during extraction
     * @return the finisher's result, possibly null
     * @throws IllegalStateException if called from another thread, after close,
     *                               or recursively during extraction
     * @throws NullPointerException if xml is null and the session is available
     * @throws XmlException if a limit is exceeded or scanned content is malformed
     */
    public T extract(final byte[] xml) {
        final XmlMappingEngine<T> current = begin();
        try {
            return current.extract(xml);
        } finally {
            end(current);
        }
    }

    /**
     * Extracts from decoded XML text. Input size is measured as UTF-8 bytes
     * before allocating the encoded array; an encoding declaration is ignored.
     *
     * @param xml XML document text
     * @return the finisher's result, possibly null
     * @throws IllegalStateException if called from another thread, after close,
     *                               or recursively during extraction
     * @throws NullPointerException if xml is null and the session is available
     * @throws XmlException if a limit is exceeded or scanned content is malformed
     */
    public T extract(final String xml) {
        final XmlMappingEngine<T> current = begin();
        try {
            return current.extract(xml);
        } finally {
            end(current);
        }
    }

    /**
     * Extracts synchronously from a borrowed stream, which is never closed here.
     * UTF-8 and US-ASCII use a sliding window; ISO-8859-1 and UTF-16 are buffered
     * and transcoded. Early exit can leave unread content, and read-ahead can
     * consume bytes past the last selected value. The stream position is not a
     * document-framing contract. See {@link XmlLimits#maxInputBytes()}.
     *
     * @param xml encoded XML stream owned by the caller
     * @return the finisher's result, possibly null
     * @throws IllegalStateException if called from another thread, after close,
     *                               or recursively during extraction
     * @throws NullPointerException if xml is null and the session is available
     * @throws XmlException if a limit is exceeded, scanned content is malformed,
     *                      or reading fails; I/O failures preserve their cause
     */
    public T extract(final InputStream xml) {
        final XmlMappingEngine<T> current = begin();
        try {
            return current.extract(xml);
        } finally {
            end(current);
        }
    }

    /**
     * Releases the engine and makes this session unusable for extraction.
     * Repeated close calls on the owner thread have no effect.
     *
     * @throws IllegalStateException if called from another thread or during extraction
     */
    @Override
    public void close() {
        checkOwner();
        if (extracting) throw new IllegalStateException("Cannot close a session during extraction");
        engine = null;
    }

    private XmlMappingEngine<T> begin() {
        checkOwner();
        final XmlMappingEngine<T> current = engine;
        if (current == null) throw new IllegalStateException("Session is closed");
        if (extracting) throw new IllegalStateException("Session is already extracting");
        extracting = true;
        current.limits = limits;
        return current;
    }

    private void end(final XmlMappingEngine<T> current) {
        try {
            current.trimForReuse();
        } finally {
            extracting = false;
        }
    }

    private void checkOwner() {
        // Check the immutable owner before touching any mutable session state.
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException("Session must be used and closed by its creating thread");
        }
    }
}

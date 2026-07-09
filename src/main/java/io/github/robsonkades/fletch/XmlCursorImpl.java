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

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Predicate;

/**
 * Order-tolerant {@link XmlCursor} backed by a Woodstox {@link XMLStreamReader}.
 *
 * <h2>Order tolerance with a streaming fast path</h2>
 * <p>Extractor calls may request the direct children of an element in any order,
 * regardless of the order they appear in the document. The cursor stays a single
 * forward pass over the stream and only buffers what it must:
 * <ul>
 *   <li>When a request matches the next child in the stream (the common case
 *       when reads follow document order), it is served straight from the reader
 *       — no buffering, flat memory, as fast as a plain forward-only scan.</li>
 *   <li>When a request targets a child that appears later than the current
 *       position, the direct children scanned past on the way are captured into
 *       a small per-scope {@link #pending} buffer as raw event subtrees, so a
 *       later request for one of them is served from the buffer instead of the
 *       (unrewindable) stream.</li>
 * </ul>
 * In the worst case (reading a whole scope in reverse) the current scope is fully
 * buffered; the document as a whole is never materialised up front.
 *
 * <h2>Scope model</h2>
 * <p>An instance reads the content of one element (its <em>scope</em>). The
 * element's {@code START_ELEMENT} has already been consumed when the instance is
 * created, so {@link EventSource#depth() depth} equals the element's depth and
 * its direct children live at {@code depth + 1}. The scope ends at the element's
 * {@code END_ELEMENT}. Attributes of the element are snapshotted at construction,
 * so {@link #attribute} works before or after any child navigation.
 *
 * <p>Nested extraction reuses the same machinery: a fast-path {@link #child}
 * shares the reader with a sub-cursor and drains whatever the sub-extractor left
 * unread; a buffered child replays its captured subtree through a
 * {@link BufferedEventSource}. Either way the parent scope is always left at the
 * correct position for the next sibling scan.
 */
final class XmlCursorImpl implements XmlCursor {

    private static final String[] NO_ATTRS = new String[0];

    /**
     * Sentinel returned by {@link #takeChild} when the match is live in the stream.
     */
    private static final Captured STREAM_HIT = new Captured(null, null);

    private final EventSource src;

    /**
     * Depth (in {@link #src}) of the element whose content this cursor reads.
     */
    private final int scopeDepth;

    private final String currentName;
    private final String[] attrNames;
    private final String[] attrValues;

    /**
     * Direct children read from {@link #src} but not yet consumed by the
     * extractor, in document order. Lazily allocated: stays {@code null} while
     * reads follow document order, so the fast path allocates nothing.
     */
    private List<Captured> pending;

    /**
     * True once the scope's own {@code END_ELEMENT} has been consumed from {@link #src}.
     */
    private boolean scopeClosed;

    XmlCursorImpl(final XMLStreamReader reader) {
        this(new StreamEventSource(reader));
    }

    private XmlCursorImpl(final EventSource src) {
        this.src = src;
        this.scopeDepth = src.depth();
        if (src.eventType() == XMLStreamConstants.START_ELEMENT) {
            this.currentName = src.localName();
            final int n = src.attributeCount();
            if (n == 0) {
                this.attrNames = NO_ATTRS;
                this.attrValues = NO_ATTRS;
            } else {
                this.attrNames = new String[n];
                this.attrValues = new String[n];
                for (int i = 0; i < n; i++) {
                    this.attrNames[i] = src.attributeLocalName(i);
                    this.attrValues[i] = src.attributeValue(i);
                }
            }
        } else {
            // Root cursor: positioned before the document element, no name/attributes.
            this.currentName = null;
            this.attrNames = NO_ATTRS;
            this.attrValues = NO_ATTRS;
        }
    }

    // -------------------------------------------------------------------------
    // XmlCursor interface
    // -------------------------------------------------------------------------

    private static Event startEventFrom(final EventSource s) {
        final int n = s.attributeCount();
        if (n == 0) {
            return new Event(XMLStreamConstants.START_ELEMENT, s.localName(), NO_ATTRS, NO_ATTRS, null, false);
        }
        final String[] an = new String[n];
        final String[] av = new String[n];
        for (int i = 0; i < n; i++) {
            an[i] = s.attributeLocalName(i);
            av[i] = s.attributeValue(i);
        }
        return new Event(XMLStreamConstants.START_ELEMENT, s.localName(), an, av, null, false);
    }

    private static XmlCursorImpl cursorOver(final Captured c) {
        final BufferedEventSource bes = new BufferedEventSource(c.subtree());
        bes.next(); // position at the captured START_ELEMENT
        return new XmlCursorImpl(bes);
    }

    private static String bufferedText(final Captured c) throws XMLStreamException {
        final BufferedEventSource bes = new BufferedEventSource(c.subtree());
        bes.next();
        return readText(bes);
    }

    /**
     * Reads the full text content of the element {@code s} is positioned at
     * (including CDATA and text nested in child elements) until its matching
     * {@code END_ELEMENT}. The single-chunk case — the vast majority of leaf
     * elements — returns the chunk directly without a {@code StringBuilder}.
     */
    private static String readText(final EventSource s) throws XMLStreamException {
        final int entryDepth = s.depth();
        String first = null;
        StringBuilder sb = null;

        while (s.hasNext()) {
            final int event = s.next();
            if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) {
                if (!s.isWhiteSpace()) {
                    final String chunk = s.text();
                    if (sb != null) {
                        sb.append(chunk);
                    } else if (first == null) {
                        first = chunk;
                    } else {
                        sb = new StringBuilder(first.length() + chunk.length() + 16);
                        sb.append(first).append(chunk);
                        first = null;
                    }
                }
            } else if (event == XMLStreamConstants.END_ELEMENT && s.depth() == entryDepth - 1) {
                break;
            }
        }

        if (sb != null) return sb.toString().strip();
        return first == null ? "" : fastTrim(first);
    }

    /**
     * Trims without allocating when no trimming is needed — returns the same
     * {@code String} instance when the content has no leading/trailing whitespace.
     */
    private static String fastTrim(final String s) {
        final int len = s.length();
        if (len == 0) return s;
        int start = 0, end = len;
        while (start < end && s.charAt(start) <= ' ') start++;
        while (end > start && s.charAt(end - 1) <= ' ') end--;
        if (start == 0 && end == len) return s;
        return start == end ? "" : s.substring(start, end);
    }

    /**
     * Linear scan over a small varargs array — JIT-inlineable for 2-3 names.
     */
    private static boolean matchesAny(final String localName, final String[] names) {
        for (final String name : names) {
            if (name.equals(localName)) return true;
        }
        return false;
    }

    @Override
    public <T> T child(final String name, final XmlExtractor<T> extractor) {
        try {
            final Captured hit = takeChild(n -> n.equals(name));
            if (hit == null) return null;
            if (hit == STREAM_HIT) {
                final T result = extractor.extract(new XmlCursorImpl(src));
                drainToDepth(scopeDepth); // consume the child's remaining content and its END
                return result;
            }
            return extractor.extract(cursorOver(hit));
        } catch (XMLStreamException e) {
            throw new XmlException("Error navigating to child element: " + name, e);
        }
    }

    // -------------------------------------------------------------------------
    // Child location: buffer first, then stream (capturing non-matches)
    // -------------------------------------------------------------------------

    @Override
    public <T> List<T> children(final String name, final XmlExtractor<T> extractor) {
        try {
            final List<T> results = new ArrayList<>();
            if (pending != null) {
                for (final Iterator<Captured> it = pending.iterator(); it.hasNext(); ) {
                    final Captured c = it.next();
                    if (name.equals(c.name())) {
                        it.remove();
                        results.add(extractor.extract(cursorOver(c)));
                    }
                }
            }
            while (!scopeClosed && src.hasNext()) {
                final int event = src.next();
                if (event == XMLStreamConstants.START_ELEMENT && src.depth() == scopeDepth + 1) {
                    if (name.equals(src.localName())) {
                        final T result = extractor.extract(new XmlCursorImpl(src));
                        drainToDepth(scopeDepth);
                        results.add(result);
                    } else {
                        addPending(captureSubtree());
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT && src.depth() < scopeDepth) {
                    scopeClosed = true;
                }
            }
            return results;
        } catch (XMLStreamException e) {
            throw new XmlException("Error collecting child elements: " + name, e);
        }
    }

    @Override
    public <T> T value(final String name, final Class<T> type) {
        try {
            final Captured hit = takeChild(n -> n.equals(name));
            if (hit == null) return null;
            final String text = hit == STREAM_HIT ? readText(src) : bufferedText(hit);
            return TypeConverter.convert(text, type);
        } catch (XMLStreamException e) {
            throw new XmlException("Error reading value: " + name, e);
        }
    }

    @Override
    public <T> T firstOf(final Class<T> type, final String... names) {
        try {
            final Captured hit = takeChild(n -> matchesAny(n, names));
            if (hit == null) return null;
            final String text = hit == STREAM_HIT ? readText(src) : bufferedText(hit);
            return TypeConverter.convert(text, type);
        } catch (XMLStreamException e) {
            throw new XmlException("Error scanning firstOf alternatives", e);
        }
    }

    @Override
    public <T> T attribute(final String name, final Class<T> type) {
        for (int i = 0; i < attrNames.length; i++) {
            if (attrNames[i].equals(name)) {
                return TypeConverter.convert(attrValues[i], type);
            }
        }
        return null;
    }

    @Override
    public String name() {
        return currentName;
    }

    @Override
    public void skip() {
        if (pending != null) pending.clear();
        scopeClosed = true;
    }

    /**
     * Locates the next direct child whose name satisfies {@code matcher}.
     * Returns {@link #STREAM_HIT} when the match is live in the stream (cursor
     * left at its {@code START_ELEMENT}), a {@link Captured} subtree when the
     * match was previously buffered, or {@code null} on a miss.
     */
    private Captured takeChild(final Predicate<String> matcher) throws XMLStreamException {
        if (pending != null) {
            for (final Iterator<Captured> it = pending.iterator(); it.hasNext(); ) {
                final Captured c = it.next();
                if (matcher.test(c.name())) {
                    it.remove();
                    return c;
                }
            }
        }
        while (!scopeClosed && src.hasNext()) {
            final int event = src.next();
            if (event == XMLStreamConstants.START_ELEMENT && src.depth() == scopeDepth + 1) {
                if (matcher.test(src.localName())) return STREAM_HIT;
                addPending(captureSubtree());
            } else if (event == XMLStreamConstants.END_ELEMENT && src.depth() < scopeDepth) {
                scopeClosed = true;
            }
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Text assembly
    // -------------------------------------------------------------------------

    private void addPending(final Captured c) {
        if (pending == null) pending = new ArrayList<>(4);
        pending.add(c);
    }

    /**
     * Captures the subtree of the direct child the cursor is positioned at
     * ({@code START_ELEMENT} already read) as a replayable event array, leaving
     * {@link #src} just past the child's {@code END_ELEMENT}.
     */
    private Captured captureSubtree() throws XMLStreamException {
        final int startDepth = src.depth();
        final String name = src.localName();
        final List<Event> events = new ArrayList<>();
        events.add(startEventFrom(src));
        while (true) {
            final int event = src.next();
            switch (event) {
                case XMLStreamConstants.START_ELEMENT -> events.add(startEventFrom(src));
                case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA ->
                        events.add(new Event(event, null, null, null, src.text(), src.isWhiteSpace()));
                case XMLStreamConstants.END_ELEMENT -> {
                    events.add(Event.END);
                    if (src.depth() == startDepth - 1) {
                        return new Captured(name, events.toArray(new Event[0]));
                    }
                }
                default -> { /* comments, PIs and the like carry no extractable content */ }
            }
        }
    }

    private void drainToDepth(final int targetDepth) throws XMLStreamException {
        while (src.depth() > targetDepth && src.hasNext()) {
            src.next();
        }
    }

    // -------------------------------------------------------------------------
    // Event source abstraction: live stream vs. replay of a captured subtree
    // -------------------------------------------------------------------------

    /**
     * Minimal forward event source used by the cursor; depth-aware.
     */
    private interface EventSource {
        int next() throws XMLStreamException;

        boolean hasNext() throws XMLStreamException;

        int depth();

        int eventType();

        String localName();

        boolean isWhiteSpace();

        String text();

        int attributeCount();

        String attributeLocalName(int i);

        String attributeValue(int i);
    }

    /**
     * {@link EventSource} over a live Woodstox reader — zero copy.
     */
    private static final class StreamEventSource implements EventSource {
        private final XMLStreamReader reader;
        private int depth;

        StreamEventSource(final XMLStreamReader reader) {
            this.reader = reader;
        }

        @Override
        public int next() throws XMLStreamException {
            final int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) depth++;
            else if (event == XMLStreamConstants.END_ELEMENT) depth--;
            return event;
        }

        @Override
        public boolean hasNext() throws XMLStreamException {
            return reader.hasNext();
        }

        @Override
        public int depth() {
            return depth;
        }

        @Override
        public int eventType() {
            return reader.getEventType();
        }

        @Override
        public String localName() {
            return reader.getLocalName();
        }

        @Override
        public boolean isWhiteSpace() {
            return reader.isWhiteSpace();
        }

        @Override
        public String text() {
            return reader.getText();
        }

        @Override
        public int attributeCount() {
            return reader.getAttributeCount();
        }

        @Override
        public String attributeLocalName(final int i) {
            return reader.getAttributeLocalName(i);
        }

        @Override
        public String attributeValue(final int i) {
            return reader.getAttributeValue(i);
        }
    }

    /**
     * {@link EventSource} replaying a captured subtree.
     */
    private static final class BufferedEventSource implements EventSource {

        private final Event[] events;
        private int index = -1;
        private int depth;

        BufferedEventSource(final Event[] events) {
            this.events = events;
        }

        @Override
        public int next() {
            final Event e = events[++index];
            if (e.type() == XMLStreamConstants.START_ELEMENT) depth++;
            else if (e.type() == XMLStreamConstants.END_ELEMENT) depth--;
            return e.type();
        }

        @Override
        public boolean hasNext() {
            return index + 1 < events.length;
        }

        @Override
        public int depth() {
            return depth;
        }

        @Override
        public int eventType() {
            return index < 0 ? XMLStreamConstants.START_DOCUMENT : events[index].type();
        }

        @Override
        public String localName() {
            return events[index].name();
        }

        @Override
        public boolean isWhiteSpace() {
            return events[index].whitespace();
        }

        @Override
        public String text() {
            return events[index].text();
        }

        @Override
        public int attributeCount() {
            return events[index].attrNames().length;
        }

        @Override
        public String attributeLocalName(final int i) {
            return events[index].attrNames()[i];
        }

        @Override
        public String attributeValue(final int i) {
            return events[index].attrValues()[i];
        }
    }

    /**
     * A captured parser event. {@code START} carries name/attributes; text events carry {@code text}.
     */
    private record Event(int type, String name, String[] attrNames, String[] attrValues, String text, boolean whitespace) {
        static final Event END = new Event(XMLStreamConstants.END_ELEMENT, null, null, null, null, false);
    }

    /**
     * A direct child captured as a replayable subtree, keyed by its tag name.
     */
    private record Captured(String name, Event[] subtree) {}
}

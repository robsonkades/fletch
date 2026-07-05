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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Depth-tracking, forward-only {@link XmlCursor} backed by a Woodstox
 * {@link XMLStreamReader}.
 *
 * <h2>Depth invariant</h2>
 * <p>A global {@code depth} counter is incremented on every
 * {@code START_ELEMENT} and decremented on every {@code END_ELEMENT} via
 * {@link #nextEvent()}. Every public API method that reads events does so
 * exclusively through {@code nextEvent()}, keeping the counter consistent
 * across nested calls.
 *
 * <h2>Scope safety</h2>
 * <p>After each {@link #child} / {@link #children} call the framework drains
 * any remaining events inside the targeted element via
 * {@link #drainToDepth(int)}. The extractor is therefore free to read any
 * subset of an element's children — the cursor is always left at the correct
 * depth for the next sibling scan.
 *
 * <p>When a scan reaches the end of the enclosing container without a match
 * (a <em>miss</em>), the container's {@code END_ELEMENT} has necessarily been
 * consumed. The cursor then marks the scope as {@link #exhausted}: every
 * subsequent request in the same extractor reports absence ({@code null} /
 * empty list) instead of silently scanning — and corrupting — the parent
 * scope.
 *
 * <h2>Out-of-order detection (debug mode)</h2>
 * <p>Requests must follow document order; asking for an element the cursor
 * has already consumed cannot be answered by a forward-only parser and
 * surfaces as {@code null}. In debug mode the cursor records the direct-child
 * names it consumes per scope and converts that silent {@code null} into an
 * {@link XmlException} naming the out-of-order element. Debug mode is enabled
 * by running with assertions ({@code -ea}, the Surefire default) or with
 * {@code -Dfletch.xml.debug=true}. The flag is a {@code static final} read
 * once at class load, so HotSpot constant-folds every {@code if (DEBUG)}
 * branch away in production.
 *
 * <h2>JIT notes</h2>
 * <ul>
 *   <li>In practice only one concrete type ({@code XmlCursorImpl}) implements
 *       {@link XmlCursor}, so all interface call sites become monomorphic and
 *       are inlined by HotSpot after warm-up.</li>
 *   <li>{@link #nextEvent()} is a single-increment/decrement branch —
 *       predictable and cheap.</li>
 *   <li>No {@code HashMap}, no reflection, no {@code Optional} in the hot
 *       path; the only production-mode addition per public call is one
 *       predictable {@code exhausted} branch.</li>
 * </ul>
 */
final class XmlCursorImpl implements XmlCursor {

    private static final boolean DEBUG;

    static {
        boolean enabled = Boolean.getBoolean("fletch.xml.debug");
        // Intentional assignment inside assert: running with -ea flips debug on.
        assert enabled = true;
        DEBUG = enabled;
    }

    private final XMLStreamReader reader;

    /**
     * Absolute element nesting depth.
     * 0 = before any element; 1 = inside root element; etc.
     */
    private int depth = 0;

    /**
     * True when the enclosing scope's {@code END_ELEMENT} has been consumed
     * (miss, {@link #children} sweep, or {@link #skip}). While set, navigation
     * requests answer "absent" instead of leaking into the parent scope.
     */
    private boolean exhausted = false;

    /** Sibling depth of the most recently exhausted scope (debug bookkeeping). */
    private int exhaustedSiblingDepth = -1;

    /** Debug only: direct-child names already consumed, keyed by sibling depth. */
    private final Map<Integer, Set<String>> seenSiblings;

    XmlCursorImpl(final XMLStreamReader reader) {
        this.reader = reader;
        this.seenSiblings = DEBUG ? new HashMap<>() : null;
    }

    // -------------------------------------------------------------------------
    // Core event loop — ALL reads go through here
    // -------------------------------------------------------------------------

    /** Advances the reader by one event and keeps {@link #depth} consistent. */
    private int nextEvent() throws XMLStreamException {
        final int event = reader.next();
        if (event == XMLStreamConstants.START_ELEMENT) depth++;
        else if (event == XMLStreamConstants.END_ELEMENT) depth--;
        return event;
    }

    /**
     * Consumes events until {@code depth == targetDepth}.
     * Idempotent: does nothing when already at or below target.
     */
    private void drainToDepth(final int targetDepth) throws XMLStreamException {
        while (depth > targetDepth && reader.hasNext()) {
            nextEvent();
        }
    }

    // -------------------------------------------------------------------------
    // XmlCursor interface
    // -------------------------------------------------------------------------

    @Override
    public <T> T child(final String name, final XmlExtractor<T> extractor) {
        if (exhausted) return absent(name);
        try {
            final int containerDepth = depth;
            final int siblingDepth = containerDepth + 1;
            final String misordered = DEBUG && wasSeen(siblingDepth, name) ? name : null;

            while (reader.hasNext()) {
                final int event = nextEvent();

                if (event == XMLStreamConstants.START_ELEMENT) {
                    if (depth == siblingDepth) {
                        final String localName = reader.getLocalName();
                        if (DEBUG) recordSibling(siblingDepth, localName);
                        if (name.equals(localName)) {
                            if (DEBUG) pruneDeeperThan(siblingDepth);
                            final T result = extractor.extract(this);
                            // Drain any content the extractor left unconsumed
                            drainToDepth(containerDepth);
                            exhausted = false; // container still open for later siblings
                            return result;
                        }
                        // Skip non-matching direct sibling and its subtree
                        drainToDepth(containerDepth);
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT && depth < containerDepth) {
                    // Consumed the container's own END_ELEMENT — target absent
                    closeScope(siblingDepth, misordered);
                    return null;
                }
            }
            closeScope(siblingDepth, misordered);
            return null;
        } catch (XMLStreamException e) {
            throw new XmlException("Error navigating to child element: " + name, e);
        }
    }

    @Override
    public <T> List<T> children(final String name, final XmlExtractor<T> extractor) {
        if (exhausted) {
            absent(name); // debug-mode order check; production: scope is simply spent
            return new ArrayList<>();
        }
        try {
            final int containerDepth = depth;
            final int siblingDepth = containerDepth + 1;
            final String misordered = DEBUG && wasSeen(siblingDepth, name) ? name : null;
            final List<T> results = new ArrayList<>();

            while (reader.hasNext()) {
                final int event = nextEvent();

                if (event == XMLStreamConstants.START_ELEMENT) {
                    if (depth == siblingDepth) {
                        final String localName = reader.getLocalName();
                        if (DEBUG) recordSibling(siblingDepth, localName);
                        if (name.equals(localName)) {
                            if (DEBUG) pruneDeeperThan(siblingDepth);
                            exhausted = false; // fresh scope for this element's extractor
                            final T result = extractor.extract(this);
                            drainToDepth(containerDepth);
                            exhausted = false; // container still open, keep scanning
                            results.add(result);
                        } else {
                            drainToDepth(containerDepth);
                        }
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT && depth < containerDepth) {
                    break; // Consumed the container's END_ELEMENT
                }
            }
            closeScope(siblingDepth, misordered);
            return results;
        } catch (XMLStreamException e) {
            throw new XmlException("Error collecting child elements: " + name, e);
        }
    }

    @Override
    public <T> T value(final String name, final Class<T> type) {
        return child(name, ignored -> readTextAs(type));
    }

    @Override
    public <T> T firstOf(final Class<T> type, final String... names) {
        if (exhausted) return absentAny(names);
        try {
            final int containerDepth = depth;
            final int siblingDepth = containerDepth + 1;
            final String misordered = DEBUG ? firstSeen(siblingDepth, names) : null;

            while (reader.hasNext()) {
                final int event = nextEvent();

                if (event == XMLStreamConstants.START_ELEMENT) {
                    if (depth == siblingDepth) {
                        final String localName = reader.getLocalName();
                        if (DEBUG) recordSibling(siblingDepth, localName);
                        if (matchesAny(localName, names)) {
                            // readTextAs consumes END_ELEMENT, depth = containerDepth
                            return readTextAs(type);
                        }
                        drainToDepth(containerDepth);
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT && depth < containerDepth) {
                    closeScope(siblingDepth, misordered);
                    return null;
                }
            }
            closeScope(siblingDepth, misordered);
            return null;
        } catch (XMLStreamException e) {
            throw new XmlException("Error scanning firstOf alternatives", e);
        }
    }

    @Override
    public <T> T attribute(final String name, final Class<T> type) {
        if (exhausted) {
            if (DEBUG) {
                throw new XmlException("attribute(\"" + name + "\") called after the element's scope "
                        + "was consumed — read attributes before navigating to children");
            }
            return null;
        }
        try {
            final String value = reader.getAttributeValue(null, name);
            if (value == null) return null;
            return TypeConverter.convert(value, type);
        } catch (IllegalStateException e) {
            throw new XmlException("attribute(\"" + name + "\") requires the cursor at START_ELEMENT — "
                    + "read attributes before navigating to children", e);
        }
    }

    @Override
    public String name() {
        return reader.getLocalName();
    }

    @Override
    public void skip() {
        try {
            final int siblingDepth = depth + 1; // children scope of the skipped element
            drainToDepth(depth - 1);
            exhausted = true;
            exhaustedSiblingDepth = siblingDepth;
        } catch (XMLStreamException e) {
            throw new XmlException("Error skipping element", e);
        }
    }

    // -------------------------------------------------------------------------
    // Scope bookkeeping
    // -------------------------------------------------------------------------

    /**
     * Marks the enclosing scope as fully consumed. In debug mode, converts a
     * miss for an element that was in fact present — but already consumed —
     * into an explicit ordering error instead of a silent {@code null}.
     */
    private void closeScope(final int siblingDepth, final String misorderedName) {
        exhausted = true;
        exhaustedSiblingDepth = siblingDepth;
        if (misorderedName != null) throw orderViolation(misorderedName);
    }

    /** Answers a request made after the scope was consumed. */
    private <T> T absent(final String name) {
        if (DEBUG && wasSeen(exhaustedSiblingDepth, name)) throw orderViolation(name);
        return null;
    }

    private <T> T absentAny(final String[] names) {
        if (DEBUG) {
            final String violated = firstSeen(exhaustedSiblingDepth, names);
            if (violated != null) throw orderViolation(violated);
        }
        return null;
    }

    private static XmlException orderViolation(final String name) {
        return new XmlException("Out-of-order read: element '" + name + "' exists in this scope, "
                + "but the cursor already consumed it while serving an earlier request. Reorder the "
                + "extractor calls to follow document order. (Detection is active only in debug mode: "
                + "-ea or -Dfletch.xml.debug=true; in production the result would be null.)");
    }

    // -------------------------------------------------------------------------
    // Debug-mode sibling tracking (dead code in production — DEBUG is folded)
    // -------------------------------------------------------------------------

    private boolean wasSeen(final int siblingDepth, final String name) {
        final Set<String> seen = seenSiblings.get(siblingDepth);
        return seen != null && seen.contains(name);
    }

    private String firstSeen(final int siblingDepth, final String[] names) {
        final Set<String> seen = seenSiblings.get(siblingDepth);
        if (seen == null) return null;
        for (final String name : names) {
            if (seen.contains(name)) return name;
        }
        return null;
    }

    private void recordSibling(final int siblingDepth, final String localName) {
        seenSiblings.computeIfAbsent(siblingDepth, k -> new HashSet<>()).add(localName);
    }

    /** A new element scope is opening: drop stale sets from previously consumed subtrees. */
    private void pruneDeeperThan(final int siblingDepth) {
        seenSiblings.keySet().removeIf(d -> d > siblingDepth);
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private <T> T readTextAs(final Class<T> type) {
        try {
            return TypeConverter.convert(readText(), type);
        } catch (XMLStreamException e) {
            throw new XmlException("Error reading text value", e);
        }
    }

    /**
     * Reads the full text content of the current element (including CDATA and
     * content nested inside child elements) until its matching
     * {@code END_ELEMENT}.
     *
     * <p><b>Fast path</b>: a single {@code CHARACTERS} event — the overwhelming
     * majority of leaf elements — returns {@code reader.getText()} directly
     * without a {@code StringBuilder} allocation. Multi-chunk paths use
     * {@code getTextCharacters()} to append char arrays without creating
     * intermediate {@code String} objects per chunk.
     */
    private String readText() throws XMLStreamException {
        final int entryDepth = depth; // depth of the element whose text we read
        String first = null;
        StringBuilder sb = null;

        while (reader.hasNext()) {
            final int event = nextEvent();

            if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) {
                if (!reader.isWhiteSpace()) {
                    if (sb != null) {
                        sb.append(reader.getTextCharacters(),
                                reader.getTextStart(),
                                reader.getTextLength());
                    } else if (first == null) {
                        first = reader.getText(); // single-chunk fast path
                    } else {
                        // Second chunk: materialise StringBuilder once
                        sb = new StringBuilder(first.length() + reader.getTextLength() + 16);
                        sb.append(first);
                        sb.append(reader.getTextCharacters(),
                                reader.getTextStart(),
                                reader.getTextLength());
                        first = null;
                    }
                }
            } else if (event == XMLStreamConstants.END_ELEMENT && depth == entryDepth - 1) {
                break; // exited the element
            }
            // START_ELEMENT (nested mixed-content): depth increments, we continue
            // END_ELEMENT (nested): depth stays >= entryDepth, we continue
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

    /** Linear scan over a small varargs array — JIT-inlineable for 2-3 names. */
    private static boolean matchesAny(final String localName, final String[] names) {
        for (final String name : names) {
            if (name.equals(localName)) return true;
        }
        return false;
    }
}

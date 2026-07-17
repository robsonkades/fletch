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

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A compiled, immutable XML extraction mapping — the declarative, push-style
 * face of the byte-level engine.
 *
 * <p>A mapping is the declarative, push-style counterpart to the
 * {@link XmlCursor} contract: both run on the same byte-level engine and are
 * driven through the {@link Xml} facade. Build one with {@link Xml#mapping} and
 * run it with {@link Xml#extract(byte[], XmlMapping)}; a compiled mapping is a pure,
 * immutable recipe, exactly as an {@link XmlExtractor} is for the cursor style.
 *
 * <p>A mapping declares every wanted value up front as a path; {@code build}
 * compiles the path set into a flattened matching automaton. Per document, a
 * single fused pass over the raw bytes fills each declared slot as the
 * document supplies it, so <em>declaration order is irrelevant by
 * construction</em> — no buffering, no replay, no per-event objects. Subtrees
 * that cannot contain a declared path are skipped at scan speed without being
 * parsed.
 *
 * <pre>{@code
 * record Book(String title, long year) {}
 *
 * static final class Draft { String title; long year; }
 *
 * static final XmlMapping<Book> BOOK = Xml.mapping(Draft::new)
 *         .text("/book/title", (d, v) -> d.title = v.asString())
 *         .text("/book/year",  (d, v) -> d.year = v.asLong())
 *         .build(d -> new Book(d.title, d.year));
 *
 * Book book = Xml.extract(bytes, BOOK);
 * }</pre>
 *
 * <h2>Paths</h2>
 * <p>A path is a chain of raw tag names: {@code /order/customer/name}. As in
 * {@link XmlCursor}, matching is not namespace-aware — documents with a
 * default namespace match by plain local name, prefixed elements include the
 * prefix ({@code /soap:Envelope/soap:Body}). An attribute is addressed with
 * {@code @}: {@code /order@id}. There are no wildcards or predicates; every
 * construct compiles to a constant-time table transition.
 *
 * <h2>Bindings</h2>
 * <p>Each path carries an {@link XmlBinding} that stores the decoded
 * {@link XmlValue} on a mutable <em>draft</em> object supplied per document;
 * {@code build}'s finisher maps the completed draft to the result. Bindings
 * fire once per occurrence, in document order, and only for non-empty values
 * (absent or blank content leaves the draft untouched — the same
 * {@code null}-for-absent convention as the cursor API). Repeated leaf values
 * can therefore be collected by binding into a list.
 *
 * <h2>Repeating structures</h2>
 * <p>{@link Builder#group group} declares a repeating element with its own
 * draft: the engine creates one group draft per occurrence, fills the group's
 * relative paths into it, and commits it to the parent draft when the element
 * closes. Groups nest.
 *
 * <h2>Choices and early exit</h2>
 * <p>{@link Builder#firstOf firstOf} binds whichever of several alternative
 * paths appears first ({@code xsd:choice}). {@link Builder#required required}
 * marks paths that complete the extraction: once all required slots are
 * bound, the engine stops reading the document.
 *
 * <h2>Thread safety and reuse</h2>
 * <p>A compiled mapping is immutable — define it as a {@code static final}
 * constant and share it across threads. Running it through
 * {@link Xml#extract(byte[], XmlMapping)} draws a reusable engine from an
 * internal pool, so steady-state extraction allocates only the caller's draft
 * and result objects.
 *
 * <h2>Semantics and limits</h2>
 * <p>The engine reads UTF-8 (and US-ASCII) natively and transcodes ISO-8859-1
 * and UTF-16 documents once on entry. DTDs are rejected ({@code <!DOCTYPE}
 * fails fast — no XXE surface), only the five predefined entities and
 * character references are recognised, and a mapping supports at most 64 bound
 * fields. Structural errors surface as {@link XmlException}; value conversion
 * errors propagate from the {@link XmlValue} accessors.
 *
 * @param <T> the result type produced by the mapping's finisher
 */
public final class XmlMapping<T> {

    // ------------------------------------------------------------------ compiled tables
    // States are the nodes of the path trie, numbered breadth-first from the
    // synthetic document state 0. Per-state transition slices index the
    // flattened transition arrays; matches verify name bytes against `blob`.

    final Supplier<Object> rootDraft;
    final Function<Object, T> finisher;
    final XmlBinding<Object>[] bindings;
    final long onceMask;
    final long requiredMask;
    final boolean strictSkip;

    final int[] transBase;
    final int[] transCount;
    final long[] transHash;
    final int[] transTarget;
    final int[] transNameOff;
    final int[] transNameLen;

    final long[] stateTag;
    final int[] stateText;
    final int[] stateGroup;

    final int[] attrBase;
    final int[] attrCount;
    final long[] attrHash;
    final int[] attrField;
    final int[] attrNameOff;
    final int[] attrNameLen;

    final byte[] blob;

    final Supplier<Object>[] groupDraft;
    final BiConsumer<Object, Object>[] groupCommit;
    final long[] groupReset;

    private final AtomicReferenceArray<XmlMappingEngine<T>> pool = new AtomicReferenceArray<>(8);

    /**
     * Starts a mapping definition.
     *
     * @param <D>           the draft type accumulated while reading a document
     * @param draftSupplier creates one fresh draft per extracted document
     * @return a builder to declare paths on
     */
    static <D> Builder<D> builder(final Supplier<D> draftSupplier) {
        Objects.requireNonNull(draftSupplier, "draftSupplier");
        return new Builder<>(draftSupplier);
    }

    /**
     * Creates a new reusable engine bound to this mapping.
     *
     * <p>An engine owns the scratch state (stacks and buffers) and amortises it
     * across documents: steady-state extraction allocates only the caller's
     * draft and result objects. Engines are <em>not</em> thread-safe — hold one
     * per worker.
     *
     * @return a fresh engine
     */
    XmlMappingEngine<T> newEngine() {
        return new XmlMappingEngine<>(this);
    }

    /**
     * Extracts from a raw XML document using a pooled engine. Encoding is
     * detected from the byte-order mark or the XML declaration.
     *
     * @param xml the encoded document
     * @return the finisher's result
     * @throws XmlException if the document is malformed
     */
    T extract(final byte[] xml) {
        final XmlMappingEngine<T> s = take();
        try {
            return s.extract(xml);
        } finally {
            release(s);
        }
    }

    /**
     * Extracts from an XML string using a pooled engine.
     *
     * @param xml the document text
     * @return the finisher's result
     * @throws XmlException if the document is malformed
     */
    T extract(final String xml) {
        final XmlMappingEngine<T> s = take();
        try {
            return s.extract(xml);
        } finally {
            release(s);
        }
    }

    /**
     * Extracts from a stream using a pooled engine, sliding a window over it
     * rather than buffering it whole — except for ISO-8859-1 and UTF-16, which
     * are drained and transcoded first. The stream is not closed.
     *
     * @param xml the stream to read; consumed but not closed
     * @return the finisher's result
     * @throws XmlException if the document is malformed or reading fails
     */
    T extract(final InputStream xml) {
        final XmlMappingEngine<T> s = take();
        try {
            return s.extract(xml);
        } finally {
            release(s);
        }
    }

    private XmlMappingEngine<T> take() {
        final int slot = (int) Thread.currentThread().getId() & 7;
        final XmlMappingEngine<T> s = pool.getAndSet(slot, null);
        return s != null ? s : new XmlMappingEngine<>(this);
    }

    private void release(final XmlMappingEngine<T> s) {
        pool.set((int) Thread.currentThread().getId() & 7, s);
    }

    /**
     * Resolves the transition from {@code state} on the element or attribute
     * name at {@code b[off, off+len)}. Hash hits verify the actual bytes, so
     * a collision can never misroute. Returns the target state or -1.
     */
    int transition(final int state, final long h, final byte[] b, final int off, final int len) {
        int k = transBase[state];
        final int end = k + transCount[state];
        for (; k < end; k++) {
            if (transHash[k] == h && transNameLen[k] == len
                    && java.util.Arrays.equals(blob, transNameOff[k], transNameOff[k] + len, b, off, off + len)) {
                return transTarget[k];
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------ builder

    /**
     * Declares the paths of an {@link XmlMapping}. Obtain via
     * {@link Xml#mapping}; finish with {@link #build}.
     *
     * @param <D> the draft type bindings write into
     */
    public static final class Builder<D> {

        private final Spec spec;

        private Builder(final Supplier<D> draft) {
            this.spec = new Spec(draft);
        }

        /**
         * Binds the text content of the element at {@code path}. The binding
         * fires for every non-empty occurrence, in document order.
         *
         * @param path    absolute element path, e.g. {@code /order/total}
         * @param binding stores the value on the draft
         * @return this builder
         */
        public Builder<D> text(final String path, final XmlBinding<D> binding) {
            spec.addText(Spec.elemSteps(path, true), -1, path, binding);
            return this;
        }

        /**
         * Binds an attribute value. The path names the element and the
         * attribute after {@code @}, e.g. {@code /order@id}.
         *
         * @param path    absolute attribute path
         * @param binding stores the value on the draft
         * @return this builder
         */
        public Builder<D> attr(final String path, final XmlBinding<D> binding) {
            spec.addAttr(path, true, null, -1, binding);
            return this;
        }

        /**
         * Binds whichever of the alternative element paths appears first in
         * the document ({@code xsd:choice} groups such as
         * {@code CPF | CNPJ | idEstrangeiro}). Later alternatives are
         * ignored once one has bound.
         *
         * @param binding stores the winning value on the draft
         * @param paths   two or more absolute element paths
         * @return this builder
         */
        public Builder<D> firstOf(final XmlBinding<D> binding, final String... paths) {
            spec.addFirstOf(paths, true, null, -1, binding);
            return this;
        }

        /**
         * Declares a repeating element with its own per-occurrence draft.
         * Paths declared on the returned group builder are relative to
         * {@code path}; when each occurrence closes, {@code commit} receives
         * the parent draft and the completed group draft.
         *
         * @param <G>    the group draft type
         * @param path   absolute path of the repeating element
         * @param draft  creates one draft per occurrence
         * @param commit appends the completed occurrence to the parent draft
         * @return a builder scoped to the group
         */
        public <G> GroupBuilder<G, Builder<D>> group(final String path, final Supplier<G> draft,
                                                     final BiConsumer<D, G> commit) {
            final int idx = spec.addGroup(Spec.elemSteps(path, true), path, -1, draft, commit);
            return new GroupBuilder<>(spec, this, idx, Spec.elemSteps(path, true));
        }

        /**
         * Marks an already-declared path as required. Once every required
         * path has bound a value, the engine stops reading the document —
         * fields that would bind later stay untouched. Paths inside groups
         * cannot be required.
         *
         * @param path the exact path string used when declaring the binding
         * @return this builder
         */
        public Builder<D> required(final String path) {
            spec.require(path);
            return this;
        }

        /**
         * Verifies end tags inside skipped subtrees instead of only counting
         * them, trading throughput for well-formedness coverage.
         *
         * <p>Subtrees no declared path selects are crossed at memchr speed:
         * tags are counted so the mapping resumes at the right place, but
         * their names are never read, so {@code <a></b>} inside an unselected
         * region passes unnoticed. This turns those regions into full end-tag
         * checks. The cost scales with how much of the document goes
         * unselected: on the bundled NF-e fixture it measures ~18% less
         * throughput. Elements the mapping does select are always verified,
         * with or without this.
         *
         * <p>End tags are matched by name hash, as everywhere else in the
         * engine — two names agreeing on length and first sixteen bytes are
         * not reported as mismatched.
         *
         * @return this builder
         */
        public Builder<D> strictSkip() {
            spec.strictSkip = true;
            return this;
        }

        /**
         * Compiles the declared paths into an immutable mapping.
         *
         * @param <T>      the result type
         * @param finisher maps the completed draft to the result
         * @return the compiled mapping
         * @throws XmlException if the declaration set is inconsistent
         */
        public <T> XmlMapping<T> build(final Function<D, T> finisher) {
            Objects.requireNonNull(finisher, "finisher");
            return new XmlMapping<>(spec, finisher);
        }
    }

    /**
     * Declares paths inside a repeating {@code group}, relative to the group
     * element. {@link #endGroup()} returns to the enclosing builder.
     *
     * @param <G> the group draft type
     * @param <P> the enclosing builder type
     */
    public static final class GroupBuilder<G, P> {

        private final Spec spec;
        private final P parent;
        private final int idx;
        private final String[] base;

        private GroupBuilder(final Spec spec, final P parent, final int idx, final String[] base) {
            this.spec = spec;
            this.parent = parent;
            this.idx = idx;
            this.base = base;
        }

        /**
         * Binds the text content of a child element, relative to the group
         * element (e.g. {@code prod/cProd}).
         *
         * @param path    group-relative element path
         * @param binding stores the value on the group draft
         * @return this group builder
         */
        public GroupBuilder<G, P> text(final String path, final XmlBinding<G> binding) {
            spec.addText(Spec.concat(base, Spec.elemSteps(path, false)), idx, path, binding);
            return this;
        }

        /**
         * Binds an attribute, relative to the group element: {@code @nItem}
         * addresses the group element itself, {@code prod@ean} a descendant.
         *
         * @param path    group-relative attribute path
         * @param binding stores the value on the group draft
         * @return this group builder
         */
        public GroupBuilder<G, P> attr(final String path, final XmlBinding<G> binding) {
            spec.addAttr(path, false, base, idx, binding);
            return this;
        }

        /**
         * Binds whichever of the alternative group-relative paths appears
         * first within each occurrence of the group.
         *
         * @param binding stores the winning value on the group draft
         * @param paths   two or more group-relative element paths
         * @return this group builder
         */
        public GroupBuilder<G, P> firstOf(final XmlBinding<G> binding, final String... paths) {
            spec.addFirstOf(paths, false, base, idx, binding);
            return this;
        }

        /**
         * Declares a nested repeating element inside this group.
         *
         * @param <G2>   the nested group draft type
         * @param path   group-relative path of the repeating element
         * @param draft  creates one draft per occurrence
         * @param commit appends each completed occurrence to this group's draft
         * @return a builder scoped to the nested group
         */
        public <G2> GroupBuilder<G2, GroupBuilder<G, P>> group(final String path, final Supplier<G2> draft,
                                                               final BiConsumer<G, G2> commit) {
            final String[] steps = Spec.concat(base, Spec.elemSteps(path, false));
            final int nested = spec.addGroup(steps, path, idx, draft, commit);
            return new GroupBuilder<>(spec, this, nested, steps);
        }

        /**
         * Closes this group scope.
         *
         * @return the enclosing builder
         */
        public P endGroup() {
            return parent;
        }
    }

    // ------------------------------------------------------------------ declaration model

    private static final class Spec {

        private static final String[] NO_STEPS = new String[0];

        final Supplier<?> rootDraft;
        final List<Entry> entries = new ArrayList<>();
        final List<Group> groups = new ArrayList<>();
        final List<XmlBinding<?>> fields = new ArrayList<>();
        final List<Integer> fieldGroup = new ArrayList<>();
        final Map<String, Integer> fieldByPath = new HashMap<>();
        long onceMask;
        long requiredMask;
        boolean strictSkip;

        Spec(final Supplier<?> rootDraft) {
            this.rootDraft = rootDraft;
        }

        int newField(final XmlBinding<?> binding, final int group) {
            Objects.requireNonNull(binding, "binding");
            if (fields.size() == 64) {
                throw new XmlException("A mapping supports at most 64 bound fields");
            }
            fields.add(binding);
            fieldGroup.add(group);
            return fields.size() - 1;
        }

        void addText(final String[] steps, final int group, final String path, final XmlBinding<?> binding) {
            final int f = newField(binding, group);
            entries.add(new Entry(steps, null, f, group, display(steps, null)));
            fieldByPath.putIfAbsent(display(steps, null), f);
        }

        void addAttr(final String path, final boolean absolute, final String[] base, final int group,
                     final XmlBinding<?> binding) {
            final int at = path == null ? -1 : path.lastIndexOf('@');
            if (at < 0) {
                throw new XmlException("Attribute path must contain '@': " + path);
            }
            final String name = path.substring(at + 1);
            validateStep(name, path);
            final String elem = path.substring(0, at);
            String[] steps;
            if (absolute) {
                steps = elemSteps(elem, true);
            } else {
                steps = elem.isEmpty() ? NO_STEPS : elemSteps(elem, false);
                steps = concat(base, steps);
            }
            if (steps.length == 0) {
                throw new XmlException("Attribute path must name an element: " + path);
            }
            final int f = newField(binding, group);
            entries.add(new Entry(steps, name, f, group, display(steps, name)));
            fieldByPath.putIfAbsent(display(steps, name), f);
        }

        void addFirstOf(final String[] paths, final boolean absolute, final String[] base, final int group,
                        final XmlBinding<?> binding) {
            if (paths == null || paths.length < 2) {
                throw new XmlException("firstOf requires at least two alternative paths");
            }
            final int f = newField(binding, group);
            onceMask |= 1L << f;
            for (final String path : paths) {
                String[] steps = elemSteps(path, absolute);
                if (!absolute) {
                    steps = concat(base, steps);
                }
                entries.add(new Entry(steps, null, f, group, display(steps, null)));
                fieldByPath.putIfAbsent(display(steps, null), f);
            }
        }

        int addGroup(final String[] steps, final String path, final int parent, final Supplier<?> draft,
                     final BiConsumer<?, ?> commit) {
            Objects.requireNonNull(draft, "draft");
            Objects.requireNonNull(commit, "commit");
            for (final Group g : groups) {
                if (java.util.Arrays.equals(g.steps, steps)) {
                    throw new XmlException("Duplicate group path: " + path);
                }
            }
            groups.add(new Group(steps, parent, draft, commit));
            return groups.size() - 1;
        }

        void require(final String path) {
            final Integer f = fieldByPath.get(path);
            if (f == null) {
                throw new XmlException("required() must reference a declared path: " + path);
            }
            if (fieldGroup.get(f) != -1) {
                throw new XmlException("Paths inside groups cannot be required: " + path);
            }
            requiredMask |= 1L << f;
        }

        static String display(final String[] steps, final String attr) {
            final StringBuilder sb = new StringBuilder();
            for (final String s : steps) {
                sb.append('/').append(s);
            }
            if (attr != null) {
                sb.append('@').append(attr);
            }
            return sb.toString();
        }

        static String[] elemSteps(final String path, final boolean absolute) {
            if (path == null) {
                throw new XmlException("Path must not be null");
            }
            String p = path;
            if (absolute) {
                if (!p.startsWith("/")) {
                    throw new XmlException("Path must start with '/': " + path);
                }
                p = p.substring(1);
            } else if (p.startsWith("/")) {
                throw new XmlException("Group-relative path must not start with '/': " + path);
            }
            if (p.isEmpty()) {
                throw new XmlException("Path must name an element: " + path);
            }
            final String[] steps = p.split("/", -1);
            for (final String step : steps) {
                validateStep(step, path);
            }
            return steps;
        }

        static void validateStep(final String step, final String path) {
            if (step.isEmpty()) {
                throw new XmlException("Empty step in path: " + path);
            }
            for (int i = 0; i < step.length(); i++) {
                final char c = step.charAt(i);
                if (c <= ' ' || c == '<' || c == '>' || c == '@' || c == '&' || c == '"' || c == '\'' || c == '=') {
                    throw new XmlException("Invalid character '" + c + "' in path: " + path);
                }
            }
        }

        static String[] concat(final String[] base, final String[] rel) {
            if (rel.length == 0) return base;
            final String[] all = new String[base.length + rel.length];
            System.arraycopy(base, 0, all, 0, base.length);
            System.arraycopy(rel, 0, all, base.length, rel.length);
            return all;
        }
    }

    private record Entry(String[] steps, String attr, int field, int group, String display) {}

    private record Group(String[] steps, int parent, Supplier<?> draft, BiConsumer<?, ?> commit) {}

    // ------------------------------------------------------------------ compilation

    private static final class Node {
        final String name;
        final Map<String, Node> children = new LinkedHashMap<>();
        final List<String> attrNames = new ArrayList<>();
        final List<Integer> attrFields = new ArrayList<>();
        int text = -1;
        int group = -1;
        int id = -1;

        Node(final String name) {
            this.name = name;
        }
    }

    @SuppressWarnings("unchecked")
    private XmlMapping(final Spec spec, final Function<?, T> finish) {
        this.rootDraft = (Supplier<Object>) spec.rootDraft;
        this.finisher = (Function<Object, T>) finish;
        this.bindings = spec.fields.toArray(new XmlBinding[0]);
        this.onceMask = spec.onceMask;
        this.requiredMask = spec.requiredMask;
        this.strictSkip = spec.strictSkip;

        // Build the path trie: groups first so entry insertion can see them.
        final Node root = new Node(null);
        final Node[] groupNodes = new Node[spec.groups.size()];
        for (int g = 0; g < spec.groups.size(); g++) {
            final Node nd = insert(root, spec.groups.get(g).steps());
            if (nd.group != -1) {
                throw new XmlException("Duplicate group path: " + Spec.display(spec.groups.get(g).steps(), null));
            }
            nd.group = g;
            groupNodes[g] = nd;
        }
        for (final Entry en : spec.entries) {
            final Node nd = insert(root, en.steps());
            if (en.attr() != null) {
                if (nd.attrNames.contains(en.attr())) {
                    throw new XmlException("Duplicate attribute target: " + en.display());
                }
                nd.attrNames.add(en.attr());
                nd.attrFields.add(en.field());
            } else {
                if (nd.text != -1) {
                    throw new XmlException("Duplicate text target: " + en.display());
                }
                if (nd.group != -1) {
                    throw new XmlException("Text target on a group element: " + en.display());
                }
                nd.text = en.field();
            }
        }

        // A text target consumes its whole element, so it cannot also be a
        // container the mapping descends into.
        for (final Entry en : spec.entries) {
            if (en.attr() == null) {
                final Node nd = walk(root, en.steps());
                if (!nd.children.isEmpty()) {
                    throw new XmlException("Text target must be a leaf path: " + en.display());
                }
            }
        }

        // Every path must cross exactly its owning chain of groups: a foreign
        // path through a group subtree would bind against the wrong draft.
        for (final Entry en : spec.entries) {
            checkGroupCrossing(root, en.steps(), spec, en.group(), en.display());
        }
        for (int g = 0; g < spec.groups.size(); g++) {
            final Group grp = spec.groups.get(g);
            final String[] outer = java.util.Arrays.copyOf(grp.steps(), grp.steps().length - 1);
            checkGroupCrossing(root, outer, spec, grp.parent(), Spec.display(grp.steps(), null));
        }

        // Number states breadth-first and flatten.
        final List<Node> nodes = new ArrayList<>();
        root.id = 0;
        nodes.add(root);
        for (int idx = 0; idx < nodes.size(); idx++) {
            for (final Node child : nodes.get(idx).children.values()) {
                child.id = nodes.size();
                nodes.add(child);
            }
        }
        final int n = nodes.size();
        transBase = new int[n];
        transCount = new int[n];
        stateTag = new long[n];
        stateText = new int[n];
        stateGroup = new int[n];
        attrBase = new int[n];
        attrCount = new int[n];

        final ByteArrayOutputStream blobOut = new ByteArrayOutputStream();
        final List<Long> tHash = new ArrayList<>();
        final List<Integer> tTarget = new ArrayList<>();
        final List<Integer> tOff = new ArrayList<>();
        final List<Integer> tLen = new ArrayList<>();
        final List<Long> aHash = new ArrayList<>();
        final List<Integer> aField = new ArrayList<>();
        final List<Integer> aOff = new ArrayList<>();
        final List<Integer> aLen = new ArrayList<>();

        for (final Node nd : nodes) {
            final int s = nd.id;
            stateText[s] = nd.text;
            stateGroup[s] = nd.group;
            stateTag[s] = nd.name == null ? 0 : hashOf(nd.name);
            transBase[s] = tHash.size();
            transCount[s] = nd.children.size();
            for (final Node child : nd.children.values()) {
                final byte[] nb = child.name.getBytes(StandardCharsets.UTF_8);
                tHash.add(Swar.hash(nb, 0, nb.length));
                tTarget.add(child.id);
                tOff.add(blobOut.size());
                tLen.add(nb.length);
                blobOut.writeBytes(nb);
            }
            attrBase[s] = aHash.size();
            attrCount[s] = nd.attrNames.size();
            for (int k = 0; k < nd.attrNames.size(); k++) {
                final byte[] nb = nd.attrNames.get(k).getBytes(StandardCharsets.UTF_8);
                aHash.add(Swar.hash(nb, 0, nb.length));
                aField.add(nd.attrFields.get(k));
                aOff.add(blobOut.size());
                aLen.add(nb.length);
                blobOut.writeBytes(nb);
            }
        }
        transHash = toLongs(tHash);
        transTarget = toInts(tTarget);
        transNameOff = toInts(tOff);
        transNameLen = toInts(tLen);
        attrHash = toLongs(aHash);
        attrField = toInts(aField);
        attrNameOff = toInts(aOff);
        attrNameLen = toInts(aLen);
        blob = blobOut.toByteArray();

        groupDraft = new Supplier[spec.groups.size()];
        groupCommit = new BiConsumer[spec.groups.size()];
        groupReset = new long[spec.groups.size()];
        for (int g = 0; g < spec.groups.size(); g++) {
            groupDraft[g] = (Supplier<Object>) spec.groups.get(g).draft();
            groupCommit[g] = (BiConsumer<Object, Object>) spec.groups.get(g).commit();
        }
        for (final Entry en : spec.entries) {
            for (int g = en.group(); g >= 0; g = spec.groups.get(g).parent()) {
                groupReset[g] |= 1L << en.field();
            }
        }
    }

    private static long hashOf(final String name) {
        final byte[] nb = name.getBytes(StandardCharsets.UTF_8);
        return Swar.hash(nb, 0, nb.length);
    }

    private static Node insert(final Node root, final String[] steps) {
        Node cur = root;
        for (final String step : steps) {
            cur = cur.children.computeIfAbsent(step, Node::new);
        }
        return cur;
    }

    private static Node walk(final Node root, final String[] steps) {
        Node cur = root;
        for (final String step : steps) {
            cur = cur.children.get(step);
        }
        return cur;
    }

    private static void checkGroupCrossing(final Node root, final String[] steps, final Spec spec,
                                           final int owner, final String display) {
        final List<Integer> crossed = new ArrayList<>();
        Node cur = root;
        for (final String step : steps) {
            cur = cur.children.get(step);
            if (cur.group != -1) {
                crossed.add(cur.group);
            }
        }
        final List<Integer> expected = new ArrayList<>();
        for (int g = owner; g >= 0; g = spec.groups.get(g).parent()) {
            expected.add(0, g);
        }
        if (!crossed.equals(expected)) {
            throw new XmlException("Path crosses a group it does not belong to: " + display
                    + " (declare it on that group's builder)");
        }
    }

    private static long[] toLongs(final List<Long> list) {
        final long[] a = new long[list.size()];
        for (int i = 0; i < a.length; i++) a[i] = list.get(i);
        return a;
    }

    private static int[] toInts(final List<Integer> list) {
        final int[] a = new int[list.size()];
        for (int i = 0; i < a.length; i++) a[i] = list.get(i);
        return a;
    }
}
